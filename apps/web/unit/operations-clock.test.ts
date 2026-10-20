import assert from 'node:assert/strict';
import { test } from 'node:test';
import { serverElapsed, waitingLabel } from '../src/lib/operations-clock.ts';

const clock = { serverTime: '2026-10-20T12:00:00Z', receivedAt: 5_000 };
test('uses the server anchor and monotonic elapsed time without the notebook wall clock', () => {
  assert.equal(serverElapsed(clock, 6_500), Date.parse(clock.serverTime) + 1_500);
  assert.equal(waitingLabel('2026-10-20T11:59:30Z', clock, 6_500), 'Aguardando há 31 s');
});
test('future timestamps and suspended clocks never show negative waiting', () => {
  assert.equal(waitingLabel('2026-10-20T12:00:10Z', clock, 3_000), 'Aguardando há 0 s');
});
test('shows full minutes after the exact one minute edge', () => {
  assert.equal(waitingLabel('2026-10-20T11:59:00Z', clock, 5_000), 'Aguardando há 1 min');
});
test('rejects an invalid origin instead of publishing a misleading duration', () => {
  assert.equal(waitingLabel('invalid', clock, 6_000), 'Tempo de espera indisponível');
});
