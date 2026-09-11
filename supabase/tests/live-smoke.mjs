// Run only against the dedicated test/group project. Uses synthetic coordinates and three anonymous identities.
// Environment: GROUP_SUPABASE_URL and GROUP_SUPABASE_KEY (publishable or anon key, NEVER service role).
import assert from 'node:assert/strict';
const url=process.env.GROUP_SUPABASE_URL?.replace(/\/$/,''); const key=process.env.GROUP_SUPABASE_KEY;
if(!url || !key) { console.error('Set GROUP_SUPABASE_URL and GROUP_SUPABASE_KEY to run the real-provider test.'); process.exit(2); }
async function identity() { const r=await fetch(url+'/auth/v1/signup',{method:'POST',headers:{apikey:key,'Content-Type':'application/json'},body:'{}'}); const s=await r.json(); assert.ok(r.ok && s.access_token,'Anonymous sign-in must be enabled'); return s; }
async function command(s,action,args={}) { const r=await fetch(url+'/functions/v1/group-api',{method:'POST',headers:{apikey:key,Authorization:'Bearer '+s.access_token,'Content-Type':'application/json'},body:JSON.stringify({action,...args})}); return {status:r.status,body:await r.json()}; }
async function socket(s,inbox) {
 const ws=new WebSocket(url.replace('https://','wss://')+'/realtime/v1/websocket?apikey='+encodeURIComponent(key)+'&vsn=1.0.0'); const messages=[];
 await new Promise((resolve,reject)=>{ const timeout=setTimeout(()=>reject(Error('Realtime join timeout')),15000); ws.onerror=()=>reject(Error('Realtime connection failed')); ws.onopen=()=>ws.send(JSON.stringify({topic:'realtime:iz:inbox:'+inbox,event:'phx_join',ref:'1',join_ref:'1',payload:{access_token:s.access_token,config:{private:true,broadcast:{self:false,ack:true},presence:{enabled:false}}}})); ws.onmessage=event=>{const data=JSON.parse(event.data); messages.push(data);if(data.event==='phx_reply' && data.ref==='1') {clearTimeout(timeout);data.payload.status==='ok'?resolve():reject(Error('Private join denied'));}}; });
 return {ws,messages};
}
const host=await identity(), peer=await identity(), stranger=await identity(); let group; let connection;
try {
 const create=await command(host,'create',{name:'SQL smoke host',stops:[{label:'Synthetic test destination',latitude:0,longitude:0}]}); assert.equal(create.body.ok,true); group=create.body.group;
 const join=await command(peer,'join',{name:'SQL smoke peer',code:group.invite_code}); assert.equal(join.body.group.self_status,'pending'); assert.equal(join.body.group.consent,false);
 const denied=await command(peer,'consent',{group_id:group.id,enabled:true}); assert.equal(denied.body.ok,false);
 await command(host,'approve',{group_id:group.id,user_id:peer.user.id});
 await command(host,'consent',{group_id:group.id,enabled:true});
 const consent=await command(peer,'consent',{group_id:group.id,enabled:true}); assert.equal(consent.body.group.consent,true);
 connection=await socket(peer,consent.body.group.inbox);
 const unauthorized=await command(stranger,'publish',{group_id:group.id,latitude:0,longitude:0,accuracy:1,speed:2,recorded_at:Date.now()}); assert.equal(unauthorized.body.ok,false);
 const sent=await command(host,'publish',{group_id:group.id,latitude:0,longitude:0,accuracy:1,speed:2,recorded_at:Date.now()}); assert.equal(sent.body.ok,true); assert.equal(sent.body.delivered,1);
 await new Promise(resolve=>setTimeout(resolve,1500)); assert.equal(connection.messages.filter(x=>x.event==='broadcast').length,1);
 const rls=await fetch(url+'/rest/v1/iz_groups?select=*',{headers:{apikey:key,Authorization:'Bearer '+peer.access_token}}); assert.ok(rls.status===401 || rls.status===403,'Raw group metadata must be denied');
 const broadcast=await fetch(url+'/realtime/v1/api/broadcast',{method:'POST',headers:{apikey:key,Authorization:'Bearer '+peer.access_token,'Content-Type':'application/json'},body:JSON.stringify({messages:[{topic:'iz:inbox:'+consent.body.group.inbox,event:'location',private:true,payload:{spoof:true}}]})}); assert.ok(!broadcast.ok,'Direct client broadcast must be rejected');
 await command(host,'remove',{group_id:group.id,user_id:peer.user.id});
 await new Promise(resolve=>setTimeout(resolve,11000));
 const after=await command(host,'publish',{group_id:group.id,latitude:0,longitude:0,accuracy:1,speed:2,recorded_at:Date.now()}); assert.equal(after.body.ok,true); assert.equal(after.body.delivered,0);
 await new Promise(resolve=>setTimeout(resolve,1500)); assert.equal(connection.messages.filter(x=>x.event==='broadcast').length,1,'Removed client receives no subsequent position on its old socket');
 console.log('PASS: anonymous identity, approval, consent, trusted private relay, direct-access denial and retained-socket revocation.');
} finally { connection?.ws.close(); if(group) await command(host,'end',{group_id:group.id}); }
