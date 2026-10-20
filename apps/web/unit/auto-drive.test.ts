import assert from 'node:assert/strict';
import test from 'node:test';
import {
  AutoDrive,
  pointForConfirmation,
  type DriveIdentity,
  type DriveReceipt,
  type DriveRoute,
} from '../src/lib/auto-drive.ts';

class TestClock {
  time = 0;
  nextId = 0;
  tasks = new Map<number, { at: number; run: () => void }>();
  now = () => this.time;
  later = (run: () => void, delay: number) => {
    const id = ++this.nextId;
    this.tasks.set(id, { at: this.time + delay, run });
    return id;
  };
  cancel = (id: unknown) => {
    this.tasks.delete(id as number);
  };
  advance(milliseconds: number) {
    this.time += milliseconds;
    for (const [id, task] of [...this.tasks])
      if (task.at <= this.time) {
        this.tasks.delete(id);
        task.run();
      }
  }
}
const flush = async () => {
  for (let i = 0; i < 6; i++) await Promise.resolve();
};
function pending<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}
const identity: DriveIdentity = { orderId: 'order-a', destination: 'PICKUP' };
const route: DriveRoute = {
  ...identity,
  geometry: [
    { latitude: -23.56, longitude: -46.65 },
    { latitude: -23.55, longitude: -46.65 },
    { latitude: -23.55, longitude: -46.64 },
  ],
  simulatedDurationMillis: 4_000,
};

test('envia apenas uma requisição por vez, sem intervalo acumulado durante HTTP lento', async () => {
  const clock = new TestClock();
  const held = pending<DriveReceipt>();
  let calls = 0;
  const actor = new AutoDrive({
    identity: () => identity,
    route: async () => route,
    send: async () => {
      calls++;
      return held.promise;
    },
    state() {},
    confirmedReceipt() {},
    clock,
  });
  await actor.start();
  clock.advance(0);
  await flush();
  clock.advance(10_000);
  await flush();
  assert.equal(calls, 1);
  assert.equal(clock.tasks.size, 0);
  actor.pause();
  held.resolve({ accepted: 1 });
  await flush();
  assert.equal(clock.tasks.size, 0);
});

test('resposta de rota atrasada após troca de pedido não envia GPS nem publica mapa antigo', async () => {
  const clock = new TestClock();
  const held = pending<DriveRoute>();
  let active: DriveIdentity | null = identity;
  let calls = 0;
  let paints = 0;
  const actor = new AutoDrive({
    identity: () => active,
    route: () => held.promise,
    acceptedRoute() {
      paints++;
    },
    send: async () => {
      calls++;
      return { accepted: 1 };
    },
    state() {},
    confirmedReceipt() {},
    clock,
  });
  const started = actor.start();
  active = { orderId: 'order-b', destination: 'PICKUP' };
  actor.checkIdentity();
  held.resolve(route);
  await started;
  clock.advance(10_000);
  await flush();
  assert.equal(calls, 0);
  assert.equal(paints, 0);
});

test('pausa ou teardown aborta a solicitação e ignora seu retorno tardio', async () => {
  for (const stop of ['pause', 'dispose'] as const) {
    const clock = new TestClock();
    const held = pending<DriveReceipt>();
    let signal: AbortSignal | undefined;
    let receipts = 0;
    const actor = new AutoDrive({
      identity: () => identity,
      route: async () => route,
      send: async (_, requestSignal) => {
        signal = requestSignal;
        return held.promise;
      },
      state() {},
      confirmedReceipt() {
        receipts++;
      },
      clock,
    });
    await actor.start();
    clock.advance(0);
    await flush();
    actor[stop]();
    assert.equal(signal!.aborted, true);
    held.resolve({ accepted: 1 });
    await flush();
    assert.equal(receipts, 0);
    assert.equal(clock.tasks.size, 0);
  }
});

test('duração da API não recebe outro fator de velocidade e chegada aguarda confirmação real', async () => {
  const clock = new TestClock();
  const points: { latitude: number; longitude: number }[] = [];
  const states: string[] = [];
  const actor = new AutoDrive({
    identity: () => identity,
    route: async () => route,
    send: async (point) => {
      points.push(point);
      return { accepted: 1 };
    },
    state(value) {
      states.push(value);
    },
    confirmedReceipt() {},
    clock,
  });
  await actor.start();
  clock.advance(0);
  await flush();
  clock.advance(2_000);
  await flush();
  assert.notDeepEqual(points.at(-1), route.geometry.at(-1));
  clock.advance(2_000);
  await flush();
  assert.deepEqual(points.at(-1), route.geometry.at(-1));
  assert.equal(states.at(-1), 'awaiting-confirmation');
  assert.equal(clock.tasks.size, 0);
});

test('pausa com envio em voo informa o ponto incerto para conciliação, sem aceitar o recibo atrasado', async () => {
  const clock = new TestClock();
  const held = pending<DriveReceipt>();
  const uncertain: unknown[] = [];
  let accepted = 0;
  const actor = new AutoDrive({
    identity: () => identity,
    route: async () => route,
    send: () => held.promise,
    uncertainPosition(point) {
      uncertain.push(point);
    },
    state() {},
    confirmedReceipt() {
      accepted++;
    },
    clock,
  });
  await actor.start();
  clock.advance(0);
  await flush();
  actor.pause();
  assert.deepEqual(uncertain, [route.geometry[0]]);
  held.resolve({ accepted: 1 });
  await flush();
  assert.equal(accepted, 0);
  assert.equal(clock.tasks.size, 0);
});

test('a confirmação de GPS exige o mesmo pedido e a mesma etapa', () => {
  const uncertain = { ...identity, point: route.geometry[1]! };
  assert.deepEqual(pointForConfirmation(uncertain, identity), uncertain.point);
  assert.equal(pointForConfirmation(uncertain, { ...identity, orderId: 'order-b' }), null);
  assert.equal(pointForConfirmation(uncertain, { ...identity, destination: 'DROPOFF' }), null);
  assert.equal(pointForConfirmation(uncertain, null), null);
});

test('confirma o ponto inicial e aguarda persistência antes de consultar a rota', async () => {
  const clock = new TestClock();
  const persisted = pending<void>();
  const calls: string[] = [];
  const actor = new AutoDrive({
    identity: () => identity,
    origin: async () => {
      calls.push('origin');
      return route.geometry[0]!;
    },
    send: async () => {
      calls.push('gps');
      return { accepted: 1, latestRecordedAt: '2026-10-20T12:00:00Z' };
    },
    persisted: () => persisted.promise,
    route: async () => {
      calls.push('route');
      return route;
    },
    state() {},
    confirmedReceipt() {},
    clock,
  });
  const started = actor.start();
  await flush();
  assert.deepEqual(calls, ['origin', 'gps']);
  clock.advance(10_000);
  await flush();
  assert.deepEqual(calls, ['origin', 'gps']);
  persisted.resolve();
  await started;
  assert.deepEqual(calls, ['origin', 'gps', 'route']);
  clock.advance(1_999);
  await flush();
  assert.equal(calls.filter((item) => item === 'gps').length, 1);
  clock.advance(1);
  await flush();
  assert.equal(calls.filter((item) => item === 'gps').length, 2);
  actor.dispose();
});

test('pausar durante a persistência do ponto inicial impede rota e movimento tardios', async () => {
  const clock = new TestClock();
  const persisted = pending<void>();
  let routes = 0;
  let gps = 0;
  const actor = new AutoDrive({
    identity: () => identity,
    origin: async () => route.geometry[0]!,
    send: async () => {
      gps++;
      return { accepted: 1 };
    },
    persisted: () => persisted.promise,
    route: async () => {
      routes++;
      return route;
    },
    state() {},
    confirmedReceipt() {},
    clock,
  });
  const started = actor.start();
  await flush();
  actor.pause();
  persisted.resolve();
  await started;
  clock.advance(20_000);
  await flush();
  assert.equal(gps, 1);
  assert.equal(routes, 0);
  assert.equal(clock.tasks.size, 0);
});

test('trocar pedido durante a consulta inicial não confirma a posição para a intenção antiga', async () => {
  const clock = new TestClock();
  const origin = pending<{ latitude: number; longitude: number }>();
  let active: DriveIdentity | null = identity;
  let gps = 0;
  const actor = new AutoDrive({
    identity: () => active,
    origin: () => origin.promise,
    send: async () => {
      gps++;
      return { accepted: 1 };
    },
    route: async () => route,
    state() {},
    confirmedReceipt() {},
    clock,
  });
  const started = actor.start();
  active = { ...identity, orderId: 'order-b' };
  actor.checkIdentity();
  origin.resolve(route.geometry[0]!);
  await started;
  assert.equal(gps, 0);
  assert.equal(clock.tasks.size, 0);
});
