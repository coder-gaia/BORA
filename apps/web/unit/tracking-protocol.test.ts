import assert from 'node:assert/strict';
import test from 'node:test';
import { newerTracking, parseTrackingSnapshot } from '../src/lib/tracking-protocol.ts';

const time = '2026-10-20T12:00:00Z';
const snapshot = {
  status: 'SEARCHING',
  version: 0,
  storeName: 'Loja',
  pickup: { latitude: -23.56, longitude: -46.65 },
  dropoff: { latitude: -23.55, longitude: -46.63 },
  courierPosition: null,
  courierPositionUpdatedAt: null,
  createdAt: time,
  timeline: [
    {
      type: 'ORDER_CREATED',
      fromStatus: null,
      toStatus: 'SEARCHING',
      occurredAt: time,
      orderVersion: 0,
    },
  ],
  route: null,
  etaSeconds: null,
  serverTime: time,
};

test('um quadro sem posição ou ETA ainda mantém os marcos públicos utilizáveis', () => {
  assert.deepEqual(parseTrackingSnapshot(snapshot), snapshot);
});

test('quadros incompletos ou coordenadas inválidas não substituem o último rastreio', () => {
  for (const change of [
    { timeline: null },
    { createdAt: 'invalid' },
    { courierPosition: { latitude: 91, longitude: -46 } },
    { etaSeconds: -1 },
    { route: { geometry: null } },
  ]) {
    assert.equal(parseTrackingSnapshot({ ...snapshot, ...change }), null);
  }
});

test('uma versão antiga nunca restaura uma etapa ou posição anterior após a reconexão', () => {
  const current = parseTrackingSnapshot({
    ...snapshot,
    version: 3,
    serverTime: '2026-10-20T12:04:00Z',
  })!;
  const delayed = parseTrackingSnapshot({
    ...snapshot,
    version: 2,
    serverTime: '2026-10-20T12:05:00Z',
  })!;
  assert.equal(newerTracking(delayed, current), false);
  assert.equal(newerTracking({ ...current, serverTime: time }, current), false);
  assert.equal(newerTracking({ ...current, serverTime: '2026-10-20T12:05:00Z' }, current), true);
});

test('a conclusão tardia de uma leitura não regride uma posição mais recente da mesma versão', () => {
  const current = parseTrackingSnapshot({
    ...snapshot,
    version: 1,
    courierPosition: snapshot.pickup,
    courierPositionUpdatedAt: '2026-10-20T12:02:00Z',
    serverTime: '2026-10-20T12:02:00Z',
  })!;
  const delayed = parseTrackingSnapshot({
    ...current,
    courierPositionUpdatedAt: '2026-10-20T12:01:00Z',
    serverTime: '2026-10-20T12:03:00Z',
  })!;
  assert.equal(newerTracking(delayed, current), false);
});
