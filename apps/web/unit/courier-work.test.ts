import assert from 'node:assert/strict';
import test from 'node:test';
import { reconcileCourierWork } from '../src/lib/courier-work.ts';
import type { CourierWorkView } from '../src/lib/api.ts';

function work(at: string, version = 3): CourierWorkView {
  return {
    courier: {
      id: 'courier-a',
      name: 'Entregador',
      status: 'BUSY',
      version,
      location: { latitude: -23.56, longitude: -46.65 },
      locationUpdatedAt: at,
    },
    order: {
      id: 'order-a',
      assignedAt: '2026-10-20T12:00:00Z',
      status: 'ASSIGNED',
      version: 1,
      confirmationRequired: true,
      store: {
        id: 'store',
        name: 'Loja',
        serviceAreaId: 'area',
        location: { latitude: -23.55, longitude: -46.65 },
      },
      dropoff: { latitude: -23.54, longitude: -46.64 },
    },
  };
}

test('consulta pré-flush atrasada não sobrescreve a posição confirmada por outra consulta', () => {
  const before = work('2026-10-20T11:59:59Z');
  const persisted = work('2026-10-20T12:00:01Z');
  persisted.courier.location = { latitude: -23.55, longitude: -46.65 };
  const current = reconcileCourierWork(persisted, before);
  assert.equal(reconcileCourierWork(before, current), persisted);
});

test('leitura antiga não restaura uma atribuição já liberada', () => {
  const assigned = work('2026-10-20T12:00:01Z');
  const released = work('2026-10-20T12:00:01Z', 4);
  released.order = null;
  released.courier.status = 'AVAILABLE';
  assert.equal(reconcileCourierWork(assigned, released), released);
  assert.equal(reconcileCourierWork(released, assigned), released);
});

test('a mesma geração de posição não regride a etapa persistida do pedido', () => {
  const assigned = work('2026-10-20T12:00:01Z');
  const atStore = work('2026-10-20T12:00:01Z');
  atStore.order = { ...atStore.order!, status: 'AT_STORE', version: 2 };
  assert.equal(reconcileCourierWork(assigned, atStore), atStore);
});
