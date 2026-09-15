import { relayLocation, validateAction } from './policy.mjs';
const url = Deno.env.get('SUPABASE_URL')!;
const serviceKey = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')!;
const headers = { apikey: serviceKey, Authorization: `Bearer ${serviceKey}`, 'Content-Type': 'application/json' };
async function rpc(name: string, args: unknown) {
  const res = await fetch(`${url}/rest/v1/rpc/${name}`, {method:'POST',headers,body:JSON.stringify(args)});
  if (!res.ok) { const error=await res.json().catch(()=>({})); throw Error(typeof error.message==='string' ? error.message : 'server_error'); }
  return await res.json();
}
const MAX_BODY_BYTES = 16_384;
const MAX_BODY_MILLIS = 5_000;
async function readRequestText(req: Request): Promise<string> {
  const deadline = performance.now() + MAX_BODY_MILLIS;
  const reader = req.body?.getReader();
  let stopped = false;
  let deadlineTimer: ReturnType<typeof setTimeout> | undefined;
  let yieldTimer: ReturnType<typeof setTimeout> | undefined;
  let resumeYield: (() => void) | undefined;
  let stop: (() => void) | undefined;
  const ensureActive = () => {
    if (stopped || req.signal.aborted || performance.now() >= deadline) throw Error('invalid_request');
  };
  try {
    ensureActive();
    // The header is only an early rejection hint; every actual chunk is counted.
    if (Number(req.headers.get('content-length') ?? 0) > MAX_BODY_BYTES) throw Error('invalid_request');
    if (!reader) return '';
    const interruption = new Promise<never>((_, reject) => {
      stop = () => { stopped = true; reject(Error('invalid_request')); };
      deadlineTimer = setTimeout(stop, Math.max(0, deadline - performance.now()));
      req.signal.addEventListener('abort', stop, { once: true });
    });
    const reading = (async () => {
      const bytes = new Uint8Array(MAX_BODY_BYTES);
      let size = 0, reads = 0;
      while (true) {
        ensureActive();
        const { done, value } = await reader.read();
        // Cancellation can settle a pending read as EOF; it must never permit decode.
        ensureActive();
        if (done) break;
        if (value.byteLength > MAX_BODY_BYTES - size) throw Error('invalid_request');
        bytes.set(value, size);
        size += value.byteLength;
        if (++reads % 64 === 0) {
          // Even immediately fulfilled empty reads must let timers and aborts run.
          await new Promise<void>(resolve => {
            resumeYield = resolve;
            yieldTimer = setTimeout(resolve, 0);
          });
          clearTimeout(yieldTimer);
          yieldTimer = undefined;
          resumeYield = undefined;
        }
      }
      ensureActive();
      const text = new TextDecoder().decode(bytes.subarray(0, size));
      ensureActive();
      return text;
    })();
    // One race bounds the reader task without accumulating handlers per chunk.
    const text = await Promise.race([reading, interruption]);
    ensureActive();
    return text;
  } catch (error) {
    stopped = true;
    // Cancellation must not hide the original error or delay the response.
    if (reader) void reader.cancel().catch(() => {});
    throw error;
  } finally {
    clearTimeout(deadlineTimer);
    clearTimeout(yieldTimer);
    resumeYield?.();
    if (stop) req.signal.removeEventListener('abort', stop);
    reader?.releaseLock();
  }
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
    const raw=await readRequestText(req);
    if (req.signal.aborted) throw Error('invalid_request');
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

