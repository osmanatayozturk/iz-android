-- Metadata only. Live coordinates MUST NOT be inserted or logged by any function.
create table public.iz_groups (
 id uuid primary key default gen_random_uuid(), host_id uuid not null references auth.users on delete cascade,
 stops jsonb not null, created_at timestamptz not null default now(), expires_at timestamptz not null default now()+interval '24 hours',
 invite_code text unique not null default upper(substr(replace(gen_random_uuid()::text,'-',''),1,8)),
 invite_expires_at timestamptz not null default now()+interval '15 minutes', ended boolean not null default false
);
create table public.iz_group_members (
 group_id uuid references public.iz_groups on delete cascade, user_id uuid references auth.users on delete cascade,
 name text not null check(length(name) between 1 and 40), status text not null check(status in ('pending','approved','removed')),
 consent boolean not null default false, consent_until timestamptz, inbox uuid not null default gen_random_uuid(),
 last_publish_at timestamptz, joined_at timestamptz not null default now(), primary key(group_id,user_id)
);
create table public.iz_group_limits (user_id uuid primary key references auth.users on delete cascade, window_at timestamptz not null default now(), requests int not null default 0);
alter table public.iz_groups enable row level security;
alter table public.iz_group_members enable row level security;
alter table public.iz_group_limits enable row level security;
revoke all on public.iz_groups, public.iz_group_members, public.iz_group_limits from anon, authenticated;

-- Committed separately before actions, so a denied invite cannot roll back its quota.
create function public.iz_group_rate_limit(p_user uuid) returns boolean
language plpgsql security definer set search_path=public,pg_temp as $$
declare count_requests int;
begin
 insert into iz_group_limits(user_id,requests) values(p_user,1) on conflict(user_id) do update set
 requests=case when iz_group_limits.window_at < now()-interval '1 minute' then 1 else least(iz_group_limits.requests+1,1000) end,
 window_at=case when iz_group_limits.window_at < now()-interval '1 minute' then now() else iz_group_limits.window_at end
 returning requests into count_requests;
 return count_requests<=40;
end $$;
revoke all on function public.iz_group_rate_limit(uuid) from public,anon,authenticated;
grant execute on function public.iz_group_rate_limit(uuid) to service_role;
create function public.iz_group_command(p_user uuid, p_action text, p_args jsonb) returns jsonb
language plpgsql security definer set search_path=public,pg_temp as $$
declare g iz_groups; m iz_group_members; gid uuid; target uuid; count_members int; result jsonb;
begin
 perform pg_advisory_xact_lock(hashtextextended(p_user::text,0));
 if p_action in ('create','join') and (length(trim(p_args->>'name')) not between 1 and 40 or p_args->>'name' is null) then raise exception 'invalid_name'; end if;
 if p_action='create' then
   if exists(select 1 from iz_group_members x join iz_groups y on y.id=x.group_id where x.user_id=p_user and x.status in ('pending','approved') and not y.ended and y.expires_at>now()) then raise exception 'already_in_group'; end if;
   if jsonb_typeof(p_args->'stops') <> 'array' or jsonb_array_length(p_args->'stops') not between 1 and 12 then raise exception 'invalid_route'; end if;
   insert into iz_groups(host_id,stops) values(p_user,p_args->'stops') returning * into g;
   insert into iz_group_members(group_id,user_id,name,status) values(g.id,p_user,trim(p_args->>'name'),'approved');
 elsif p_action='join' then
   select * into g from iz_groups where invite_code=p_args->>'code' and invite_expires_at>now() and expires_at>now() and not ended for update;
   if not found then raise exception 'invalid_invite'; end if;
   if exists(select 1 from iz_group_members x join iz_groups y on y.id=x.group_id where x.user_id=p_user and x.group_id<>g.id and x.status in ('pending','approved') and not y.ended and y.expires_at>now()) then raise exception 'already_in_group'; end if;
   if (select count(*) from iz_group_members where group_id=g.id and status<>'removed')>=10 and not exists(select 1 from iz_group_members where group_id=g.id and user_id=p_user and status<>'removed') then raise exception 'group_full'; end if;
   -- Removed members cannot rejoin with a previously learned code.
   if exists(select 1 from iz_group_members where group_id=g.id and user_id=p_user and status='removed') then raise exception 'removed'; end if;
   insert into iz_group_members(group_id,user_id,name,status) values(g.id,p_user,trim(p_args->>'name'),'pending') on conflict do nothing;
 else
   gid=nullif(p_args->>'group_id','')::uuid;
   if gid is null and p_action='status' then
     select x.group_id into gid from iz_group_members x join iz_groups y on y.id=x.group_id where x.user_id=p_user and x.status in ('pending','approved') and not y.ended and y.expires_at>now() order by x.joined_at desc limit 1;
     if gid is null then return jsonb_build_object('ok',true,'group',null); end if;
   end if;
   select * into g from iz_groups where id=gid for update;
   select * into m from iz_group_members where group_id=gid and user_id=p_user;
   if g.id is null or m.user_id is null or m.status='removed' or g.ended or g.expires_at<=now() then return jsonb_build_object('ok',true,'group',null); end if;
   if p_action in ('approve','remove','end','invite') and g.host_id<>p_user then raise exception 'host_required'; end if;
   if p_action='approve' then
     target=(p_args->>'user_id')::uuid;
     select count(*) into count_members from iz_group_members where group_id=gid and status='approved';
     if count_members>=10 then raise exception 'group_full'; end if;
     update iz_group_members set status='approved',consent=false,consent_until=null where group_id=gid and user_id=target and status='pending';
     if not found then raise exception 'invalid_member'; end if;
   elsif p_action='remove' then
     target=(p_args->>'user_id')::uuid;
     if target=g.host_id then raise exception 'cannot_remove_host'; end if;
     update iz_group_members set status='removed',consent=false,consent_until=null,inbox=gen_random_uuid() where group_id=gid and user_id=target;
   elsif p_action='end' or (p_action='leave' and p_user=g.host_id) then
     update iz_groups set ended=true,invite_expires_at=now() where id=gid;
     update iz_group_members set consent=false,consent_until=null,inbox=gen_random_uuid() where group_id=gid;
     return jsonb_build_object('ok',true,'group',null);
   elsif p_action='leave' then
     update iz_group_members set status='removed',consent=false,consent_until=null,inbox=gen_random_uuid() where group_id=gid and user_id=p_user;
     return jsonb_build_object('ok',true,'group',null);
   elsif p_action='consent' then
     if m.status<>'approved' then raise exception 'approval_required'; end if;
     update iz_group_members set consent=coalesce((p_args->>'enabled')::boolean,false),
       consent_until=case when (p_args->>'enabled')::boolean then now()+interval '60 seconds' else null end,
       inbox=gen_random_uuid() where group_id=gid and user_id=p_user;
   elsif p_action='invite' then
     update iz_groups set invite_code=upper(substr(replace(gen_random_uuid()::text,'-',''),1,8)),invite_expires_at=least(expires_at,now()+interval '15 minutes') where id=gid returning * into g;
   elsif p_action='status' then
     if coalesce((p_args->>'keepalive')::boolean,false) then
       update iz_group_members set consent_until=now()+interval '60 seconds' where group_id=gid and user_id=p_user and consent=true and consent_until>now();
     else
       update iz_group_members set consent=false,consent_until=null where group_id=gid and user_id=p_user and consent=true;
     end if;
   else raise exception 'invalid_action'; end if;
 end if;
 select * into m from iz_group_members where group_id=g.id and user_id=p_user;
 select jsonb_build_object('id',g.id,'host_id',g.host_id,'expires_at',extract(epoch from g.expires_at)*1000,
   'stops',case when m.status='approved' then g.stops else '[]'::jsonb end,
   'invite_code',case when g.host_id=p_user then g.invite_code else null end,
   'invite_expires_at',case when g.host_id=p_user then extract(epoch from g.invite_expires_at)*1000 else null end,
   'inbox',m.inbox,'self_status',m.status,'consent',m.consent and m.consent_until>now(),
   'members',coalesce((select jsonb_agg(jsonb_build_object('user_id',x.user_id,'name',x.name,'status',x.status,'consent',x.consent and x.consent_until>now())) from iz_group_members x where x.group_id=g.id and x.status<>'removed' and (m.status='approved' or x.user_id=p_user)),'[]'::jsonb)) into result;
 return jsonb_build_object('ok',true,'user_id',p_user,'group',result);
end $$;

create function public.iz_group_publish(p_user uuid,p_group uuid,p_interval int) returns jsonb
language plpgsql security definer set search_path=public,pg_temp as $$
declare m iz_group_members; result jsonb;
begin
 perform 1 from iz_groups where id=p_group and not ended and expires_at>now() for update;
 if not found then raise exception 'group_expired'; end if;
 select * into m from iz_group_members where group_id=p_group and user_id=p_user for update;
 if m.user_id is null or m.status<>'approved' or not m.consent or m.consent_until<=now() then raise exception 'consent_required'; end if;
 if p_interval not in (10,30) then raise exception 'invalid_interval'; end if;
 if m.last_publish_at>now()-make_interval(secs=>p_interval) then raise exception 'rate_limited'; end if;
 update iz_group_members set last_publish_at=now() where group_id=p_group and user_id=p_user;
 select coalesce(jsonb_agg(jsonb_build_object('user_id',user_id,'inbox',inbox)),'[]'::jsonb) into result from iz_group_members where group_id=p_group and user_id<>p_user and status='approved' and consent and consent_until>now();
 return result;
end $$;
create function public.iz_group_can_receive(p_user uuid,p_group uuid,p_recipient uuid,p_inbox uuid) returns boolean
language sql security definer set search_path=public,pg_temp as $$
 select exists(select 1 from iz_groups g join iz_group_members s on s.group_id=g.id join iz_group_members r on r.group_id=g.id where g.id=p_group and not g.ended and g.expires_at>now() and s.user_id=p_user and s.status='approved' and s.consent and s.consent_until>now() and r.user_id=p_recipient and r.inbox=p_inbox and r.status='approved' and r.consent and r.consent_until>now());
$$;
create function public.iz_own_inbox(p_topic text) returns boolean
language sql security definer set search_path=public,pg_temp as $$
 select exists(select 1 from iz_group_members m join iz_groups g on g.id=m.group_id where m.user_id=auth.uid() and 'iz:inbox:'||m.inbox::text=p_topic and m.status='approved' and m.consent and m.consent_until>now() and not g.ended and g.expires_at>now());
$$;
-- SELECT permits receiving only; no INSERT policy means clients cannot broadcast.
create policy iz_private_receive on realtime.messages for select to authenticated using(extension='broadcast' and public.iz_own_inbox(realtime.topic()));
create policy iz_deny_client_broadcast on realtime.messages as restrictive for insert to authenticated with check(false);
create policy iz_restrict_private_receive on realtime.messages as restrictive for select to authenticated using(extension='broadcast' and public.iz_own_inbox(realtime.topic()));
revoke all on function public.iz_group_command(uuid,text,jsonb),public.iz_group_publish(uuid,uuid,int),public.iz_group_can_receive(uuid,uuid,uuid,uuid) from public,anon,authenticated;
grant execute on function public.iz_group_command(uuid,text,jsonb),public.iz_group_publish(uuid,uuid,int),public.iz_group_can_receive(uuid,uuid,uuid,uuid) to service_role;
revoke all on function public.iz_own_inbox(text) from public,anon;
grant execute on function public.iz_own_inbox(text) to authenticated;

create function public.iz_group_cleanup() returns void language plpgsql security definer set search_path=public,pg_temp as $$
begin
 delete from iz_groups where expires_at<now() or ended;
 delete from iz_group_limits where window_at<now()-interval '7 days';
 delete from auth.users u where u.is_anonymous=true and u.created_at<now()-interval '7 days' and coalesce(u.last_sign_in_at,u.created_at)<now()-interval '7 days' and not exists(select 1 from iz_group_members m where m.user_id=u.id) and not exists(select 1 from iz_group_limits l where l.user_id=u.id);
end $$;
revoke all on function public.iz_group_cleanup() from public,anon,authenticated;
grant execute on function public.iz_group_cleanup() to service_role;
-- Enable pg_cron (Free) and schedule this on the dedicated group project.
create extension if not exists pg_cron;
select cron.schedule('iz-group-cleanup','*/15 * * * *','select public.iz_group_cleanup()');



