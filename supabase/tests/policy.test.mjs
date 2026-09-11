import test from 'node:test';
import assert from 'node:assert/strict';
import { relayLocation, validateAction } from '../functions/group-api/policy.mjs';
const fix = { group_id: 'ride', latitude: 41, longitude: 29, accuracy: 10, speed: 2, recorded_at: 100000 };
const dependencies = (overrides = {}) => ({ now: () => 100000, authorize: async () => [{ user_id: 'peer', inbox: 'private-inbox' }], recheck: async () => true, broadcast: async () => {}, ...overrides });
test('reject invalid, stale and future fixes before touching server authorization', async () => {
  for (const invalid of [{ latitude: 91 }, { longitude: NaN }, { accuracy: 201 }, { recorded_at: 69999 }, { recorded_at: 106000 }]) {
    let touched = false;
    await assert.rejects(() => relayLocation({ ...fix, ...invalid }, 'self', dependencies({ authorize: async () => { touched = true; return []; } })));
    assert.equal(touched, false);
  }
});
test('revocation after authorization prevents that recipient from receiving', async () => {
  const sent = [];
  const result = await relayLocation(fix, 'self', dependencies({ recheck: async () => false, broadcast: async (...x) => sent.push(x) }));
  assert.equal(result.delivered, 0); assert.deepEqual(sent, []);
});
test('relay contains only current fix and server timestamp, no caller-controlled sender or inbox', async () => {
  const sent = [];
  await relayLocation({ ...fix, user_id: 'spoof', inbox: 'public' }, 'self', dependencies({ broadcast: async (...x) => sent.push(x) }));
  assert.equal(sent[0][0], 'private-inbox');
  assert.deepEqual(sent[0][1], { group_id:'ride', user_id:'self', latitude:41, longitude:29, accuracy:10, speed:2, recorded_at:100000, sent_at:100000 });
});
test('denied publisher never broadcasts', async () => {
  let sent = false;
  await assert.rejects(() => relayLocation(fix, 'self', dependencies({ authorize: async () => { throw new Error('consent_required'); }, broadcast: async () => { sent = true; } })));
  assert.equal(sent, false);
});
test('reject unknown actions and invalid common routes', () => {
  assert.throws(() => validateAction({ action:'magic' }));
  assert.throws(() => validateAction({ action:'create', name:'Ali', stops:[] }));
  assert.throws(() => validateAction({ action:'create', name:'Ali', stops:[{label:'x',latitude:100,longitude:0}] }));
  assert.equal(validateAction({ action:'join', name:'Ali', code:'AB12CD34' }).action, 'join');
});
