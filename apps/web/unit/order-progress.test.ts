import assert from 'node:assert/strict';
import test from 'node:test';
import type { OrderView } from '../src/lib/api.ts';
import { deliveryProgress, type OrderEvent } from '../src/lib/order-progress.ts';

const createdAt = '2026-10-19T12:00:00Z';
const confirmedAt = '2026-10-19T12:01:00Z';
const arrivalAt = '2026-10-19T12:03:00Z';
const restartAt = '2026-10-19T12:04:00Z';

function event(
  id: number,
  type: string,
  fromStatus: OrderEvent['fromStatus'],
  toStatus: OrderEvent['toStatus'],
  occurredAt = createdAt,
): OrderEvent {
  return { id, type, fromStatus, toStatus, occurredAt, orderVersion: id };
}

function order(status: OrderView['status'], timeline: OrderEvent[]): OrderView {
  return { id: 'fixture', status, createdAt, timeline } as OrderView;
}

const created = event(1, 'ORDER_CREATED', null, 'SEARCHING');
const assigned = event(2, 'ORDER_ASSIGNED', 'SEARCHING', 'ASSIGNED', confirmedAt);
const arrived = event(3, 'ORDER_AT_STORE', 'ASSIGNED', 'AT_STORE', arrivalAt);

test('ofertas e prazos não concluem uma etapa da entrega', () => {
  const result = deliveryProgress(
    order('SEARCHING', [
      created,
      event(2, 'OFFER_CREATED', 'SEARCHING', 'SEARCHING'),
      event(3, 'OFFER_EXPIRED', 'SEARCHING', 'SEARCHING'),
    ]),
  );
  assert.deepEqual(
    result.stages.map((stage) => stage.state),
    ['current', 'upcoming', 'upcoming', 'upcoming', 'upcoming'],
  );
  assert.equal(result.stages[1]!.occurredAt, undefined);
});

test('marcos exibem os horários persistidos e identificam a etapa atual', () => {
  const result = deliveryProgress(order('AT_STORE', [created, assigned, arrived]));
  assert.equal(result.current.key, 'AT_STORE');
  assert.equal(result.stages[1]!.state, 'completed');
  assert.equal(result.stages[1]!.occurredAt, confirmedAt);
  assert.equal(result.stages[2]!.state, 'current');
  assert.equal(result.stages[2]!.occurredAt, arrivalAt);
  assert.equal(result.stages[3]!.state, 'upcoming');
});

test('cancelar durante o trajeto não marca chegada, coleta ou entrega como concluídas', () => {
  const result = deliveryProgress(
    order('CANCELED', [
      created,
      assigned,
      event(3, 'ORDER_CANCELED', 'ASSIGNED', 'CANCELED', arrivalAt),
    ]),
  );
  assert.deepEqual(
    result.stages.map((stage) => stage.state),
    ['completed', 'interrupted', 'not-reached', 'not-reached', 'not-reached'],
  );
  assert.equal(result.cancellation!.occurredAt, arrivalAt);
});

test('redistribuir reinicia a busca e preserva os registros anteriores no histórico', () => {
  const result = deliveryProgress(
    order('SEARCHING', [
      created,
      assigned,
      arrived,
      event(4, 'ORDER_REASSIGNED', 'AT_STORE', 'SEARCHING', restartAt),
    ]),
  );
  assert.equal(result.attempt, 2);
  assert.equal(result.stages[0]!.occurredAt, restartAt);
  assert.equal(result.stages[1]!.state, 'upcoming');
  assert.equal(result.stages[1]!.occurredAt, undefined);
  assert.equal(result.stages[2]!.state, 'upcoming');
  assert.equal(result.events.length, 4);
});

test('o novo aceite após redistribuição não herda a chegada da tentativa anterior', () => {
  const acceptedAgainAt = '2026-10-19T12:05:00Z';
  const result = deliveryProgress(
    order('ASSIGNED', [
      created,
      assigned,
      arrived,
      event(4, 'ORDER_REASSIGNED', 'AT_STORE', 'SEARCHING', restartAt),
      event(5, 'ORDER_ASSIGNED', 'SEARCHING', 'ASSIGNED', acceptedAgainAt),
    ]),
  );
  assert.equal(result.stages[1]!.state, 'current');
  assert.equal(result.stages[1]!.occurredAt, acceptedAgainAt);
  assert.equal(result.stages[2]!.state, 'upcoming');
  assert.equal(result.stages[2]!.event, undefined);
});

test('somente uma entrega confirmada conclui todos os marcos', () => {
  const deliveredAt = '2026-10-19T12:10:00Z';
  const result = deliveryProgress(
    order('DELIVERED', [
      created,
      assigned,
      arrived,
      event(4, 'ORDER_PICKED_UP', 'AT_STORE', 'PICKED_UP', restartAt),
      event(5, 'ORDER_DELIVERED', 'PICKED_UP', 'DELIVERED', deliveredAt),
    ]),
  );
  assert.ok(result.stages.every((stage) => stage.state === 'completed'));
  assert.equal(result.stages[4]!.occurredAt, deliveredAt);
});

test('horários ausentes não são inventados', () => {
  const result = deliveryProgress(order('AT_STORE', [created, arrived]));
  assert.equal(result.stages[1]!.occurredAt, undefined);
  assert.equal(result.stages[2]!.occurredAt, arrivalAt);
});

test('a ordem dos IDs persistidos determina a tentativa, sem depender da ordenação recebida', () => {
  const result = deliveryProgress(
    order('SEARCHING', [
      event(4, 'ORDER_REASSIGNED', 'AT_STORE', 'SEARCHING', restartAt),
      arrived,
      assigned,
      created,
    ]),
  );
  assert.equal(result.attempt, 2);
  assert.equal(result.current.key, 'SEARCHING');
  assert.equal(result.stages[2]!.state, 'upcoming');
});
