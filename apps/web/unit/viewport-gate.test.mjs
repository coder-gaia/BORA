import assert from 'node:assert/strict';
import { test } from 'node:test';
import { ViewportGate } from '../src/lib/viewport-gate.ts';
const viewport = (west) => ({ west, east: west + 0.1, south: -24, north: -23, scope: 'PUBLIC' });
function setup() {
  let now = 0,
    sequence = 0;
  const timers = new Map(),
    sent = [];
  const gate = new ViewportGate((value) => sent.push({ at: now, value }), {
    now: () => now,
    schedule: (callback, delay) => {
      const id = ++sequence;
      timers.set(id, { at: now + delay, callback });
      return id;
    },
    cancel: (id) => timers.delete(id),
  });
  function advance(target) {
    while (true) {
      const next = [...timers].sort((a, b) => a[1].at - b[1].at)[0];
      if (!next || next[1].at > target) break;
      timers.delete(next[0]);
      now = next[1].at;
      next[1].callback();
    }
    now = target;
  }
  return { gate, sent, advance };
}

test('a delayed initial subscription cannot accumulate extra viewport sends before the first MAP', () => {
  const { gate, sent, advance } = setup();
  gate.start(viewport(-46.8));
  gate.update(viewport(-46.7));
  advance(500);
  gate.update(viewport(-46.6));
  advance(5000);
  assert.equal(sent.length, 1, 'no local timeout may bypass the initial server-progress barrier');
  gate.progress();
  advance(5300);
  gate.update(viewport(-46.5));
  advance(5499);
  assert.equal(sent.length, 1);
  advance(5500);
  assert.deepEqual(
    sent.map((s) => [s.at, s.value.west]),
    [
      [0, -46.8],
      [5500, -46.5],
    ],
  );
});

test('resize bursts keep only the latest viewport and wait 500ms after observable server progress', () => {
  const { gate, sent, advance } = setup();
  gate.start(viewport(-46.8));
  advance(100);
  gate.progress();
  gate.update(viewport(-46.7));
  advance(300);
  gate.update(viewport(-46.6));
  advance(599);
  assert.equal(sent.length, 1);
  advance(600);
  assert.equal(sent[1].value.west, -46.6);
  gate.update(viewport(-46.5));
  advance(5000);
  assert.equal(sent.length, 2, 'later sends also require new MAP progress');
  gate.progress();
  advance(5500);
  assert.equal(sent[2].value.west, -46.5);
});

test('compressed inbound MAP bursts renew the safety margin instead of releasing a queued burst', () => {
  const { gate, sent, advance } = setup();
  gate.start(viewport(-46.8));
  gate.update(viewport(-46.7));
  advance(1000);
  gate.progress();
  advance(1005);
  gate.progress();
  advance(1010);
  gate.progress();
  advance(1509);
  assert.equal(sent.length, 1);
  advance(1510);
  assert.equal(sent.length, 2);
  assert.equal(sent[1].at, 1510);
});

test('duplicate viewports do not send redundant frames and stop cancels pending work', () => {
  const { gate, sent, advance } = setup();
  gate.start(viewport(-46.8));
  gate.progress();
  gate.update(viewport(-46.8));
  advance(1000);
  assert.equal(sent.length, 1);
  gate.update(viewport(-46.7));
  gate.stop();
  advance(10000);
  assert.equal(sent.length, 1);
});

test('a new connection resets the initial MAP barrier and sends its current viewport immediately', () => {
  const { gate, sent, advance } = setup();
  gate.start(viewport(-46.8));
  gate.progress();
  gate.update(viewport(-46.7));
  gate.stop();
  advance(2000);
  gate.start(viewport(-46.5));
  gate.update(viewport(-46.4));
  advance(10000);
  assert.deepEqual(
    sent.map((s) => [s.at, s.value.west]),
    [
      [0, -46.8],
      [2000, -46.5],
    ],
  );
  gate.progress();
  advance(10500);
  assert.equal(sent[2].value.west, -46.4);
});
