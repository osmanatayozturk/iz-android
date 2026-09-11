import { relayLocation, validateAction } from './policy.mjs';
const url = Deno.env.get('SUPABASE_URL')!;
const serviceKey = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')!;
const headers = { apikey: serviceKey, Authorization: `Bearer ${serviceKey}`, 'Content-Type': 'application/json' };
async function rpc(name: string, args: unknown) {
  const res = await fetch(`${url}/rest/v1/rpc/${name}`, {method:'POST',headers,body:JSON.stringify(args)});
  if (!res.ok) { const error=await res.json().catch(()=>({})); throw Error(typeof error.message==='string' ? error.message : 'server_error'); }
  return await res.json();
}
Deno.serve(async (req) => {
  if (req.method !== 'POST') return Response.json({ok:false,error:'method_not_allowed'},{status:405});
  try {
    const authorization = req.headers.get('Authorization');
    if (!authorization?.startsWith('Bearer ')) return Response.json({ok:false,error:'unauthorized'},{status:401});
    const auth = await fetch(`${url}/auth/v1/user`,{headers:{apikey:serviceKey,Authorization:authorization}});
    if (!auth.ok) return Response.json({ok:false,error:'unauthorized'},{status:401});
    const user = await auth.json();
    if (typeof user.id!=='string' || user.is_anonymous!==true) return Response.json({ok:false,error:'anonymous_identity_required'},{status:403});
    if (!await rpc('iz_group_rate_limit',{p_user:user.id})) throw Error('rate_limited');
    if (Number(req.headers.get('content-length') ?? 0)>16384) throw Error('invalid_request');
    const raw=await req.text(); if (raw.length>16384) throw Error('invalid_request');
    const body=validateAction(JSON.parse(raw));
    if (body.action==='publish') {
      const result=await relayLocation(body,user.id,{
        now:()=>Date.now(),
        authorize:(id:string,group:string,interval:number)=>rpc('iz_group_publish',{p_user:id,p_group:group,p_interval:interval}),
        recheck:(id:string,group:string,recipient:string,inbox:string)=>rpc('iz_group_can_receive',{p_user:id,p_group:group,p_recipient:recipient,p_inbox:inbox}),
        broadcast:async(inbox:string,payload:unknown)=>{
          const response=await fetch(`${url}/realtime/v1/api/broadcast`,{method:'POST',headers,body:JSON.stringify({messages:[{topic:`iz:inbox:${inbox}`,event:'location',payload,private:true}]})});
          if (!response.ok) throw Error('relay_unavailable');
        }
      });
      return Response.json(result);
    }
    return Response.json(await rpc('iz_group_command',{p_user:user.id,p_action:body.action,p_args:body}));
  } catch(error) {
    // Never log request bodies, tokens, coordinates, or upstream diagnostics.
    const safe = ['rate_limited','invalid_action','invalid_name','invalid_code','invalid_route','invalid_fix','invalid_invite','group_full','already_in_group','host_required','approval_required','consent_required','group_expired','removed','invalid_member','relay_unavailable'];
    const message=error instanceof Error && safe.includes(error.message) ? error.message : 'request_failed';
    return Response.json({ok:false,error:message},{status:message==='rate_limited'?429:400});
  }
});

