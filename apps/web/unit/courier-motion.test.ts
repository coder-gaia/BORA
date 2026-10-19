import assert from 'node:assert/strict';
import test from 'node:test';
import { CourierMotion, parseFrame, type MapFrame } from '../src/lib/courier-motion.ts';
const frame = (time: number, longitude = -46.65): MapFrame => ({
  serverTime: new Date(time).toISOString(),
  points: [['courier-a', longitude, -23.55, 'AVAILABLE', 0]],
});
const longitude = (motion: CourierMotion, now: number) =>
  motion.sample(now, true).features[0].geometry.coordinates[0];
test('interpolates between received positions without extrapolating during a gap', () => {
  const motion = new CourierMotion();
  motion.receive(frame(1000), 0);
  motion.receive(frame(2000, -46.64), 1000);
  assert.ok(Math.abs(longitude(motion, 1500) - -46.645) < 1e-9);
  assert.equal(longitude(motion, 2000), -46.64);
  assert.equal(longitude(motion, 5000), -46.64);
  assert.equal(motion.isAnimating(5000), false);
});
test('a new snapshot starts at the current displayed position', () => {
  const motion = new CourierMotion();
  motion.receive(frame(1000), 0);
  motion.receive(frame(2000, -46.64), 1000);
  const before = longitude(motion, 1500);
  motion.receive(frame(3000, -46.63), 1500);
  assert.equal(longitude(motion, 1500), before);
  assert.ok(Math.abs(longitude(motion, 2000) - -46.6375) < 1e-9);
});
test('older frames cannot move a courier backwards and viewport removal removes stale points', () => {
  const motion = new CourierMotion();
  motion.receive(frame(3000, -46.63), 0);
  assert.equal(motion.receive(frame(2000), 1000), false);
  assert.equal(longitude(motion, 1000), -46.63);
  motion.receive({ serverTime: new Date(4000).toISOString(), points: [] }, 2000);
  assert.deepEqual(motion.sample(2000, true).features, []);
});
test('reduced motion samples the latest target immediately', () => {
  const motion = new CourierMotion();
  motion.receive(frame(1000), 0);
  motion.receive(frame(2000, -46.64), 1000);
  assert.equal(motion.sample(1000, false).features[0].geometry.coordinates[0], -46.64);
});
test('rejects invalid coordinates, duplicate ids, unsupported status and oversized snapshots', () => {
  assert.ok(parseFrame(frame(1000)));
  assert.equal(parseFrame({ serverTime: 'invalid', points: [] }), null);
  assert.equal(parseFrame({ ...frame(1000), points: [['a', Infinity, 0, 'AVAILABLE', 0]] }), null);
  assert.equal(parseFrame({ ...frame(1000), points: [['a', -46, -23, 'OFFLINE', 0]] }), null);
  assert.equal(
    parseFrame({ ...frame(1000), points: [...frame(1000).points, ...frame(1000).points] }),
    null,
  );
  assert.equal(
    parseFrame({
      ...frame(1000),
      points: Array.from({ length: 2001 }, (_, i) => [String(i), -46, -23, 'AVAILABLE', 0]),
    }),
    null,
  );
});
