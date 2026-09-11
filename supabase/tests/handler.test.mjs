import test from 'node:test';
import assert from 'node:assert/strict';
let handler;
globalThis.Deno={env:{get:name=>name==='SUPABASE_URL'?'https://example.supabase.co':'test-server-secret'},serve:value=>{handler=value;}};
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
