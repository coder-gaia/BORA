import { test } from 'node:test';
import assert from 'node:assert/strict';
import { RouteMotion, offerWindow } from '../src/lib/route-motion.ts';

test('segue duas ruas em vez de cortar a esquina', () => {
  const route = new RouteMotion(
    [
      { latitude: -23.56, longitude: -46.65 },
      { latitude: -23.55, longitude: -46.65 },
      { latitude: -23.55, longitude: -46.64 },
    ],
    10_000,
  );
  const early = route.pointAt(2_000);
  const late = route.pointAt(8_000);
  assert.equal(early.longitude, -46.65);
  assert.equal(late.latitude, -23.55);
  assert.ok(early.latitude > -23.56 && early.latitude < -23.55);
  assert.ok(late.longitude > -46.65 && late.longitude < -46.64);
});

test('cópia da geometria, extremos e segmentos repetidos', () => {
  const source = [
    { latitude: -23.56, longitude: -46.65 },
    { latitude: -23.56, longitude: -46.65 },
    { latitude: -23.55, longitude: -46.65 },
  ];
  const route = new RouteMotion(source, 10_000);
  source[0]!.latitude = 0;
  assert.equal(route.pointAt(-1).latitude, -23.56);
  assert.equal(route.pointAt(5_000).latitude, -23.555);
  assert.equal(route.pointAt(20_000).latitude, -23.55);
  const single = new RouteMotion([{ latitude: 0, longitude: 0 }], 0);
  assert.deepEqual(single.pointAt(0), { latitude: 0, longitude: 0 });
});

test('recusa rota inválida sem produzir GPS falso', () => {
  assert.throws(() => new RouteMotion([], 10));
  assert.throws(() => new RouteMotion([{ latitude: NaN, longitude: 0 }], 10));
  assert.throws(() => new RouteMotion([{ latitude: 91, longitude: 0 }], 10));
  assert.throws(() => new RouteMotion([{ latitude: 0, longitude: 0 }], -1));
  assert.throws(() => new RouteMotion([{ latitude: 0, longitude: 0 }], Infinity));
});

test('contagem usa relógio remoto e avanço monotônico', () => {
  const result = offerWindow(
    '2026-10-20T12:00:00Z',
    '2026-10-20T12:00:30Z',
    '2026-10-20T12:00:05Z',
    5_000,
    8_250,
  );
  assert.equal(result.remainingMillis, 21_750);
  assert.equal(result.seconds, 22);
  assert.equal(result.fraction, 0.725);
  assert.deepEqual(
    offerWindow(
      '2026-10-20T12:00:00Z',
      '2026-10-20T12:00:30Z',
      '2026-10-20T12:00:05Z',
      5_000,
      40_000,
    ),
    { remainingMillis: 0, seconds: 0, fraction: 0 },
  );
});

test('prazo inválido fecha ações e janela iniciada no futuro fica limitada', () => {
  assert.deepEqual(offerWindow('invalid', 'invalid', 'invalid', 0, 1), {
    remainingMillis: 0,
    seconds: 0,
    fraction: 0,
  });
  assert.equal(
    offerWindow('2026-10-20T12:00:00Z', '2026-10-20T12:00:30Z', '2026-10-20T11:59:59Z', 0, 0)
      .fraction,
    1,
  );
});
