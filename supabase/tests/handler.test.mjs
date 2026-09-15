import test from 'node:test';
import assert from 'node:assert/strict';
let handler;
const denoStub={env:{get:name=>name==='SUPABASE_URL'?'https://example.supabase.co':'test-server-secret'},serve:value=>{handler=value;}};
if(globalThis.Deno)Object.assign(globalThis.Deno,denoStub); else globalThis.Deno=denoStub;
await import('../functions/group-api/index.ts');
const user='00000000-0000-4000-8000-000000000001';
const req=(body={},token='device-token')=>new Request('https://example.test/group-api',{method:'POST',headers:token?{Authorization:'Bearer '+token}:{},body:JSON.stringify(body)});
const json=(body,status=200)=>Response.json(body,{status});
test('handler rejects missing or forged authorization before touching database',async()=>{
 let calls=0; globalThis.fetch=async()=>{calls++;return json({},401);};
 assert.equal((await handler(req({action:'status'},null))).status,401); assert.equal(calls,0);
 assert.equal((await handler(req({action:'status'}))).status,401); assert.equal(calls,1);
});
test('handler refuses non-anonymous identities',async()=>{
 globalThis.fetch=async()=>json({id:user,is_anonymous:false});
 assert.equal((await handler(req({action:'status'}))).status,403);
});
test('quota reservation commits separately before an invalid command',async()=>{
 const calls=[]; globalThis.fetch=async(url,options)=>{calls.push(url); if(url.endsWith('/user')) return json({id:user,is_anonymous:true}); return json(true);};
 const response=await handler(req({action:'magic'})); assert.equal(response.status,400);
 assert.equal((await response.json()).error,'invalid_action'); assert.equal(calls.length,2); assert.ok(calls[1].endsWith('/iz_group_rate_limit'));
});
test('uses verified user identity and hides unexpected provider errors',async()=>{
 const bodies=[]; globalThis.fetch=async(url,options)=>{if(url.endsWith('/user'))return json({id:user,is_anonymous:true}); bodies.push(JSON.parse(options.body)); if(url.endsWith('/iz_group_rate_limit'))return json(true); return json({message:'upstream credentials and private database detail'},500);};
 const result=await handler(req({action:'status',p_user:'spoof',user_id:'spoof'}));
 assert.equal(bodies[1].p_user,user); assert.deepEqual(await result.json(),{ok:false,error:'request_failed'});
});
test('relay performs fresh recipient authorization and emits only a private server broadcast',async()=>{
 const calls=[]; globalThis.fetch=async(url,options)=>{
  calls.push({url,body:options.body?JSON.parse(options.body):null});
  if(url.endsWith('/user'))return json({id:user,is_anonymous:true});
  if(url.endsWith('/iz_group_rate_limit'))return json(true);
  if(url.endsWith('/iz_group_publish'))return json([{user_id:'peer',inbox:'inbox'}]);
  if(url.endsWith('/iz_group_can_receive'))return json(true);
  if(url.endsWith('/api/broadcast'))return json({});
  throw Error('Unexpected external request');
 };
 const result=await handler(req({action:'publish',group_id:'group',latitude:0,longitude:0,accuracy:1,speed:2,recorded_at:Date.now()}));
 assert.deepEqual(await result.json(),{ok:true,delivered:1});
 assert.equal(calls.at(-2).body.p_recipient,'peer');
 assert.equal(calls.at(-1).body.messages[0].private,true);
 assert.equal(calls.at(-1).body.messages[0].topic,'iz:inbox:inbox');
});

const bodyLimit = 16_384;
function allowSmallCommands() {
 const calls=[];
 globalThis.fetch=async(url,options)=>{
  calls.push(url);
  if(url.endsWith('/user'))return json({id:user,is_anonymous:true});
  if(url.endsWith('/iz_group_rate_limit'))return json(true);
  if(url.endsWith('/iz_group_command'))return json({ok:true});
  throw Error('Unexpected external request');
 };
 return calls;
}
function streamedRequest(chunks, {length, cancelError=false, cancelPending=false, readError=false}={}) {
 let reads=0, cancelled=false;
 const stream=new ReadableStream({
  pull(controller) {
   if(readError) { controller.error(Error('private input failure')); return; }
   if(reads===chunks.length)controller.close();
   else controller.enqueue(chunks[reads++]);
  },
  cancel() {
   cancelled=true;
   if(cancelError)return Promise.reject(Error('private cancellation failure'));
   if(cancelPending)return new Promise(()=>{});
  }
 },{highWaterMark:0});
 const headers={Authorization:'Bearer device-token'};
 if(length!==undefined)headers['Content-Length']=length;
 const request=new Request('https://example.test/group-api',{method:'POST',headers,body:stream,duplex:'half'});
 return {request,stream,get reads(){return reads;},get cancelled(){return cancelled;}};
}
const bytes=text=>new TextEncoder().encode(text);
const statusOfSize=size=>bytes('{"action":"status"}'+' '.repeat(size-19));
async function assertTooLarge(input,calls) {
 const response=await handler(input.request);
 assert.equal(response.status,400);
 assert.deepEqual(await response.json(),{ok:false,error:'request_failed'});
 assert.equal(input.cancelled,true);
 assert.equal(input.stream.locked,false);
 assert.equal(calls.length,2);
 assert.ok(calls[1].endsWith('/iz_group_rate_limit'));
}
test('body limit accepts exactly 16384 bytes and rejects one additional byte',async()=>{
 let calls=allowSmallCommands();
 const exact=streamedRequest([statusOfSize(bodyLimit)]);
 assert.equal((await handler(exact.request)).status,200);
 assert.ok(calls.at(-1).endsWith('/iz_group_command'));
 calls=allowSmallCommands();
 await assertTooLarge(streamedRequest([statusOfSize(bodyLimit+1)]),calls);
});
test('body limit counts UTF-8 bytes with missing, small or invalid length headers',async()=>{
 const payload=bytes(JSON.stringify({action:'status',padding:'ü'.repeat(8200)}));
 assert.ok(payload.length>bodyLimit);
 for(const length of [undefined,'1','-1','nonnumeric']) {
  const calls=allowSmallCommands();
  await assertTooLarge(streamedRequest([payload],{length}),calls);
 }
});
test('body limit cancels a 1 MiB chunked stream before consuming its remainder',async()=>{
 const calls=allowSmallCommands();
 const input=streamedRequest(Array.from({length:256},()=>new Uint8Array(4096).fill(32)));
 await assertTooLarge(input,calls);
 assert.equal(input.reads,5);
});
test('body limit rejects an oversized declared length without reading its stream',async()=>{
 const calls=allowSmallCommands();
 const input=streamedRequest([bytes('{"action":"status"}')],{length:'16385'});
 await assertTooLarge(input,calls);
 assert.equal(input.reads,0);
});
test('body limit preserves safe errors when stream cancellation rejects or never settles',async()=>{
 for(const options of [{cancelError:true},{cancelPending:true}]) {
  const calls=allowSmallCommands();
  const input=streamedRequest([statusOfSize(bodyLimit+1)],options);
  let timer;
  try {
   await Promise.race([
    assertTooLarge(input,calls),
    new Promise((_,reject)=>{timer=setTimeout(()=>reject(Error('Oversized response waited for cancellation')),1000);})
   ]);
  } finally {clearTimeout(timer);}
 }
});
test('small UTF-8 JSON split between bytes and BOM remains valid',async()=>{
 const calls=allowSmallCommands();
 const data=bytes('\uFEFF'+JSON.stringify({action:'status',padding:'İz şüş🌍'}));
 const input=streamedRequest(Array.from(data,value=>new Uint8Array([value])));
 const response=await handler(input.request);
 assert.equal(response.status,200);
 assert.deepEqual(await response.json(),{ok:true});
 assert.ok(calls.at(-1).endsWith('/iz_group_command'));
});
test('small invalid JSON and failed body reads preserve safe errors',async()=>{
 for(const input of [streamedRequest([bytes('{')]),streamedRequest([],{readError:true})]) {
  const calls=allowSmallCommands();
  const response=await handler(input.request);
  assert.equal(response.status,400);
  assert.deepEqual(await response.json(),{ok:false,error:'request_failed'});
  assert.equal(calls.length,2);
 }
});

test('body limit accepts an exact-boundary UTF-8 request and preserves decoded arguments',async()=>{
 const calls=allowSmallCommands();
 const acceptedFetch=globalThis.fetch;
 let command;
 globalThis.fetch=async(url,options)=>{
  if(url.endsWith('/iz_group_command'))command=JSON.parse(options.body);
  return acceptedFetch(url,options);
 };
 const value=JSON.stringify({action:'status',padding:'ü'.repeat(8176)});
 const payload=bytes(value+' '.repeat(bodyLimit-bytes(value).length));
 assert.equal(payload.byteLength,16384);
 const input=streamedRequest([payload.subarray(0,200),payload.subarray(200)],{length:'1'});
 assert.equal((await handler(input.request)).status,200);
 assert.deepEqual(command,{p_user:user,p_action:'status',p_args:JSON.parse(value)});
 assert.equal(input.stream.locked,false);
 assert.equal(input.cancelled,false);
 assert.equal(calls.length,3);
});
test('small route creation preserves Unicode fields and validation',async()=>{
 const calls=allowSmallCommands();
 const acceptedFetch=globalThis.fetch;
 let command;
 globalThis.fetch=async(url,options)=>{
  if(url.endsWith('/iz_group_command'))command=JSON.parse(options.body);
  return acceptedFetch(url,options);
 };
 const body={action:'create',name:'İz 🌍',stops:[{label:'Şile',latitude:41,longitude:29,privateNote:'discard'}]};
 const input=streamedRequest([bytes(JSON.stringify(body))]);
 assert.equal((await handler(input.request)).status,200);
 assert.deepEqual(command,{p_user:user,p_action:'create',p_args:{action:'create',name:'İz 🌍',stops:[{label:'Şile',latitude:41,longitude:29}]}});
 assert.equal(calls.length,3);
});
test('empty body and failed stream reads preserve safe errors and release locks',async()=>{
 const calls=allowSmallCommands();
 const empty=new Request('https://example.test/group-api',{method:'POST',headers:{Authorization:'Bearer device-token'}});
 const response=await handler(empty);
 assert.equal(response.status,400);
 assert.deepEqual(await response.json(),{ok:false,error:'request_failed'});
 assert.equal(calls.length,2);
 const failed=streamedRequest([],{readError:true});
 const failedResponse=await handler(failed.request);
 assert.equal(failedResponse.status,400);
 assert.deepEqual(await failedResponse.json(),{ok:false,error:'request_failed'});
 assert.equal(failed.stream.locked,false);
 assert.equal(calls.length,4);
});

test('authorization and denied quota reject streamed bodies before reading or command dispatch',async()=>{
 const cases=[
  {kind:'missing',status:401,error:'unauthorized',calls:0},
  {kind:'forged',status:401,error:'unauthorized',calls:1},
  {kind:'nonanonymous',status:403,error:'anonymous_identity_required',calls:1},
  {kind:'quota',status:429,error:'rate_limited',calls:2},
 ];
 for(const item of cases) {
  const input=streamedRequest([new Uint8Array(1024*1024)]);
  if(item.kind==='missing')input.request.headers.delete('Authorization');
  const calls=[];
  globalThis.fetch=async(url)=>{
   calls.push(url);
   if(url.endsWith('/user'))return item.kind==='forged'
    ? json({},401) : json({id:user,is_anonymous:item.kind!=='nonanonymous'});
   if(url.endsWith('/iz_group_rate_limit'))return json(false);
   throw Error('Unexpected command or publish dispatch');
  };
  const response=await handler(input.request);
  assert.equal(response.status,item.status,item.kind);
  assert.deepEqual(await response.json(),{ok:false,error:item.error},item.kind);
  assert.equal(input.reads,0,item.kind);
  assert.equal(input.stream.locked,false,item.kind);
  assert.equal(calls.length,item.calls,item.kind);
  assert.equal(calls.some(url=>url.endsWith('/iz_group_command') || url.endsWith('/iz_group_publish')),false,item.kind);
 }
});

const realTimeout = globalThis.setTimeout;
const realClearTimeout = globalThis.clearTimeout;
const flushBodyTasks = () => new Promise(resolve => realTimeout(resolve, 0));
function bodyClock() {
 const oldNow = Object.getOwnPropertyDescriptor(performance, 'now');
 const timers = new Map(); let now = 0, next = 0;
 Object.defineProperty(performance, 'now', {configurable:true,value:()=>now});
 globalThis.setTimeout=(fn,ms=0,...args)=>{const id=++next;timers.set(id,{at:now+ms,fn:()=>fn(...args)});return id;};
 globalThis.clearTimeout=id=>timers.delete(id);
 return {
  advance(ms, fire=true) {
   now+=ms;
   if(fire) for(const [id,timer] of [...timers].sort((a,b)=>a[1].at-b[1].at)) {
    if(timer.at<=now && timers.delete(id))timer.fn();
   }
  },
  get pending(){return timers.size;},
  restore(){
   globalThis.setTimeout=realTimeout;globalThis.clearTimeout=realClearTimeout;
   if(oldNow)Object.defineProperty(performance,'now',oldNow);else delete performance.now;
  }
 };
}
function controlledBody({initial=[],cancelMode='',emptyChunks=0,onPull=()=>{}}={}) {
 const abort=new AbortController(); let controller,reads=0,cancels=0;
 const queued=[...initial];
 const stream=new ReadableStream({
  start(value){controller=value;},
  pull(value){
   reads++;onPull();
   if(queued.length)value.enqueue(queued.shift());
   else if(emptyChunks-->0)value.enqueue(new Uint8Array());
  },
  cancel(){cancels++;if(cancelMode==='reject')return Promise.reject(Error('private cancel'));
   if(cancelMode==='pending')return new Promise(()=>{});}
 },{highWaterMark:0});
 const request=new Request('https://example.test/group-api',{method:'POST',
  headers:{Authorization:'Bearer device-token'},body:stream,duplex:'half',signal:abort.signal});
 const listeners=new Set();
 const add=request.signal.addEventListener.bind(request.signal);
 const remove=request.signal.removeEventListener.bind(request.signal);
 request.signal.addEventListener=(type,listener,options)=>{if(type==='abort')listeners.add(listener);return add(type,listener,options);};
 request.signal.removeEventListener=(type,listener,options)=>{if(type==='abort')listeners.delete(listener);return remove(type,listener,options);};
 return {request,stream,abort,
  push(value){try{controller.enqueue(value);return true;}catch{return false;}},
  close(){try{controller.close();}catch{}},
  fail(){try{controller.error(Error('private read'));}catch{}},
  get reads(){return reads;},get cancels(){return cancels;},get listeners(){return listeners.size;}
 };
}
function observeBody(input) {
 const state={response:null,promise:null};
 state.promise=handler(input.request).then(response=>{state.response=response;return response;});
 return state;
}
async function assertBodyStopped(input,state,calls,clock) {
 assert.ok(state.response,'body did not settle within its deadline/abort');
 assert.equal(state.response.status,400);
 assert.deepEqual(await state.response.json(),{ok:false,error:'request_failed'});
 assert.equal(input.stream.locked,false);assert.equal(input.listeners,0);
 if(clock)assert.equal(clock.pending,0,'timer leaked');
 assert.equal(calls.some(url=>url.endsWith('/iz_group_command')||url.endsWith('/iz_group_publish')),false);
}

test('body deadline releases 40 empty or full-limit EOF-less streams after five seconds',async()=>{
 const calls=allowSmallCommands();
 const inputs=Array.from({length:40},(_,i)=>controlledBody({initial:i%2?[statusOfSize(bodyLimit)]:[]}));
 const states=inputs.map(observeBody);let guard;
 try {
  await Promise.race([Promise.all(states.map(s=>s.promise)),new Promise((_,reject)=>{
   guard=realTimeout(()=>reject(Error('40 bodies exceeded five-second deadline plus scheduling allowance')),6500);
  })]);
  for(let i=0;i<inputs.length;i++){await assertBodyStopped(inputs[i],states[i],calls);assert.equal(inputs[i].cancels,1);}
  assert.equal(calls.length,80);
 } finally {
  realClearTimeout(guard);inputs.forEach(i=>i.close());await Promise.all(states.map(s=>s.promise));
 }
});

test('body deadline is absolute across trickles and blocks late EOF or command',async()=>{
 const clock=bodyClock(),calls=allowSmallCommands();
 const input=controlledBody({initial:[bytes('{"action":')]});const state=observeBody(input);
 try {
  await flushBodyTasks();clock.advance(3000);input.push(bytes('"status"'));await flushBodyTasks();
  clock.advance(1999);input.push(bytes('}'));await flushBodyTasks();assert.equal(state.response,null);
  clock.advance(1);await flushBodyTasks();await assertBodyStopped(input,state,calls,clock);
  assert.equal(input.push(bytes(' ')),false);input.close();await flushBodyTasks();assert.equal(calls.length,2);
 } finally {input.close();await state.promise;clock.restore();}
});

test('body deadline checks EOF against monotonic time even before timer callback',async()=>{
 for(const elapsed of [4999,5000]) {
  const clock=bodyClock(),calls=allowSmallCommands();
  const input=controlledBody({initial:[statusOfSize(bodyLimit)]});const state=observeBody(input);
  try {
   await flushBodyTasks();clock.advance(elapsed,false);input.close();await flushBodyTasks();
   if(elapsed===4999){assert.equal(state.response?.status,200);assert.equal(calls.length,3);}
   else await assertBodyStopped(input,state,calls,clock);
   assert.equal(input.stream.locked,false);assert.equal(input.listeners,0);assert.equal(clock.pending,0);
  } finally {input.close();await state.promise;clock.restore();}
 }
});

test('body client abort before read during read or racing EOF never dispatches',async()=>{
 for(const phase of ['before','during','eof']) {
  const clock=bodyClock(),calls=allowSmallCommands();
  const input=controlledBody({initial:[bytes('{"action":"status"}')]});
  if(phase==='before')input.abort.abort();
  const state=observeBody(input);
  try {
   await flushBodyTasks();
   if(phase==='eof')input.close();
   if(phase!=='before')input.abort.abort();
   await flushBodyTasks();await assertBodyStopped(input,state,calls,clock);
   assert.equal(calls.length,2);
  } finally {input.close();await state.promise;clock.restore();}
 }
});

test('body deadline never waits for rejected or pending cancellation and cleans resources',async()=>{
 for(const cancelMode of ['reject','pending']) {
  const clock=bodyClock(),calls=allowSmallCommands();
  const input=controlledBody({cancelMode});const state=observeBody(input);
  try {
   await flushBodyTasks();clock.advance(5000);await flushBodyTasks();
   await assertBodyStopped(input,state,calls,clock);assert.equal(input.cancels,1);
  } finally {input.close();await state.promise;clock.restore();}
 }
});

test('body deadline cannot be starved by immediately fulfilled empty reads',async()=>{
 const clock=bodyClock(),calls=allowSmallCommands();
 const input=controlledBody({emptyChunks:100,onPull:()=>clock.advance(100,false)});
 const state=observeBody(input);
 try {
  await flushBodyTasks();await assertBodyStopped(input,state,calls,clock);
  assert.ok(input.reads<=50);assert.equal(input.cancels,1);
 } finally {input.close();await state.promise;clock.restore();}
});

test('body reader yields bounded empty-read batches and clears pending yield on timeout',async()=>{
 const clock=bodyClock(),calls=allowSmallCommands();
 const input=controlledBody({emptyChunks:200});const state=observeBody(input);
 try {
  await flushBodyTasks();assert.ok(input.reads<=64,'empty reads monopolized the microtask queue');
  clock.advance(5000);await flushBodyTasks();await assertBodyStopped(input,state,calls,clock);
 } finally {input.close();await state.promise;clock.restore();}
});

test('body deadline starts only after authentication and quota resolve',async()=>{
 const clock=bodyClock(),calls=allowSmallCommands();const allowed=globalThis.fetch;let quota;
 globalThis.fetch=(url,options)=>url.endsWith('/iz_group_rate_limit')
  ? new Promise(resolve=>{quota=()=>{calls.push(url);resolve(json(true));};}):allowed(url,options);
 const input=controlledBody();const state=observeBody(input);
 try {
  await flushBodyTasks();clock.advance(10000);assert.equal(clock.pending,0);assert.equal(input.reads,0);
  quota();await flushBodyTasks();clock.advance(4999);await flushBodyTasks();assert.equal(state.response,null);
  clock.advance(1);await flushBodyTasks();await assertBodyStopped(input,state,calls,clock);
 } finally {if(quota)quota();input.close();await state.promise;clock.restore();}
});

test('body reader clears deadline and abort listener on success read failure and overflow',async()=>{
 for(const mode of ['success','read-error','overflow','declared']) {
  const clock=bodyClock(),calls=allowSmallCommands();
  const input=controlledBody({initial:mode==='overflow'?[statusOfSize(bodyLimit+1)]:[bytes('{"action":"status"}')]});
  if(mode==='declared')input.request.headers.set('Content-Length','16385');
  const state=observeBody(input);
  try {
   await flushBodyTasks();
   if(mode==='read-error')input.fail();
   if(mode==='success')input.close();
   await flushBodyTasks();
   if(mode==='success')assert.equal(state.response?.status,200);
   else await assertBodyStopped(input,state,calls,clock);
   assert.equal(input.listeners,0);assert.equal(clock.pending,0);assert.equal(input.stream.locked,false);
   clock.advance(5000);input.abort.abort();await flushBodyTasks();
   assert.equal(calls.length,mode==='success'?3:2);
  } finally {input.close();await state.promise;clock.restore();}
 }
});
