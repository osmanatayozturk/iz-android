const finite = (x, min, max) => typeof x === 'number' && Number.isFinite(x) && x >= min && x <= max;
export function validateAction(input) {
  const allowed = ['create','join','status','approve','remove','leave','end','consent','invite','publish'];
  if (!input || !allowed.includes(input.action)) throw Error('invalid_action');
  if (['create','join'].includes(input.action) && (typeof input.name !== 'string' || !input.name.trim() || input.name.length > 40)) throw Error('invalid_name');
  if (input.action === 'join' && !/^[A-Z0-9]{8}$/.test(input.code ?? '')) throw Error('invalid_code');
  if (input.action === 'create' && (!Array.isArray(input.stops) || input.stops.length < 1 || input.stops.length > 12 || input.stops.some(s => typeof s.label !== 'string' || !s.label.trim() || s.label.length > 300 || !finite(s.latitude,-90,90) || !finite(s.longitude,-180,180)))) throw Error('invalid_route');
  if (input.action==='create') input={...input,stops:input.stops.map(({label,latitude,longitude})=>({label,latitude,longitude}))};
  return input;
}
export async function relayLocation(input, userId, deps) {
  const now = deps.now();
  if (!finite(input.latitude,-90,90) || !finite(input.longitude,-180,180) || !finite(input.accuracy,0,200) || !finite(input.speed,0,150) || !finite(input.recorded_at,now-30000,now+5000) || typeof input.group_id !== 'string') throw Error('invalid_fix');
  const recipients = await deps.authorize(userId,input.group_id,input.speed >= 1 ? 10 : 30);
  const payload = {group_id:input.group_id,user_id:userId,latitude:input.latitude,longitude:input.longitude,accuracy:input.accuracy,speed:input.speed,recorded_at:input.recorded_at,sent_at:now};
  let delivered = 0;
  for (const recipient of recipients) {
    if (await deps.recheck(userId,input.group_id,recipient.user_id,recipient.inbox)) {
      await deps.broadcast(recipient.inbox,payload); delivered++;
    }
  }
  return {ok:true,delivered};
}

