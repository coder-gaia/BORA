import assert from 'node:assert/strict';
import { after, before, test } from 'node:test';
import { createServer } from 'vite';
import { fileURLToPath } from 'node:url';

let server;
let OperationsMapFeed;
let clients;
before(async () => {
  server = await createServer({
    configFile: false,
    root: fileURLToPath(new URL('..', import.meta.url)),
    resolve: { alias: { '@': fileURLToPath(new URL('../src', import.meta.url)) } },
    ssr: { noExternal: ['@stomp/stompjs'] },
    plugins: [
      {
        name: 'operations-feed-test-transport',
        enforce: 'pre',
        resolveId(id) {
          if (id === '@stomp/stompjs') return '\0operations-test-stomp';
        },
        load(id) {
          if (id !== '\0operations-test-stomp') return;
          return `export const clients=[];
          export class Client {
            active=false; connected=false;
            constructor(options) { this.options=options; clients.push(this); }
            activate() { this.active=true; }
            connect() { this.connected=true; this.options.onConnect(); }
            publish() {}
            subscribe(destination,callback) { this.receive=callback; return {unsubscribe(){}}; }
            async deactivate() { this.active=false; this.connected=false; }
            forceDisconnect() { this.connected=false; this.options.onWebSocketClose(); }
          }`;
        },
      },
    ],
    server: { middlewareMode: true, hmr: false, watch: null },
    optimizeDeps: { noDiscovery: true },
    appType: 'custom',
  });
  ({ OperationsMapFeed } = await server.ssrLoadModule('/src/lib/operations-map-feed.ts'));
  ({ clients } = await server.ssrLoadModule('\0operations-test-stomp'));
});
after(async () => {
  await server?.close();
});

function deferred() {
  let resolve;
  const promise = new Promise((done) => {
    resolve = done;
  });
  return { promise, resolve };
}
const flush = () => new Promise((done) => setImmediate(done));
function harness(t, { socket = false } = {}) {
  const previous = {
    window: globalThis.window,
    document: globalThis.document,
    fetch: globalThis.fetch,
  };
  let sequence = 0;
  const timers = new Map();
  const responses = [];
  const frames = [];
  let snapshotStatus = 200;
  let resets = 0;
  let unauthorized = 0;
  let displayed = null;
  let session = { user: { id: 'operations-user', role: 'OPERATIONS' } };
  const user = {
    id: 'operations-user',
    role: 'OPERATIONS',
    displayName: 'Operação visitante',
    storeId: null,
    courierId: null,
  };
  globalThis.window = {
    setTimeout: (fn, delay) => {
      const id = ++sequence;
      timers.set(id, { fn, delay });
      return id;
    },
    clearTimeout: (id) => timers.delete(id),
    setInterval: (fn, delay) => {
      const id = ++sequence;
      timers.set(id, { fn, delay, repeat: true });
      return id;
    },
    clearInterval: (id) => timers.delete(id),
    location: { href: 'http://localhost/' },
    addEventListener: () => {},
    removeEventListener: () => {},
  };
  globalThis.document = { visibilityState: 'visible' };
  globalThis.fetch = async (url, init) => {
    if (String(url).includes('/auth/me')) return { ok: true, json: async () => session };
    if (String(url).includes('/auth/csrf')) {
      if (socket)
        return {
          ok: true,
          json: async () => ({ token: 'test-token', headerName: 'X-CSRF-TOKEN' }),
        };
      throw new Error('Socket intentionally unavailable');
    }
    assert.match(String(url), /^\/api\/v1\/ops\/snapshot\?/);
    const body = deferred();
    responses.push({ url, signal: init.signal, body });
    // Intentionally ignore cancellation in JSON parsing to exercise the late-response guard.
    return { ok: snapshotStatus === 200, status: snapshotStatus, json: () => body.promise };
  };
  const feed = new OperationsMapFeed(
    user,
    (value) => {
      displayed = value;
      frames.push(value);
    },
    () => {},
    () => {
      resets++;
      displayed = null;
    },
    () => {
      unauthorized++;
    },
  );
  t.after(() => {
    feed.stop();
    Object.assign(globalThis, previous);
  });
  return {
    feed,
    frames,
    responses,
    displayed: () => displayed,
    runInterval: (delay) => {
      const entry = [...timers].find(([, timer]) => timer.delay === delay && timer.repeat);
      assert.ok(entry, `periodic reconciliation is installed at ${delay}ms`);
      entry[1].fn();
    },
    setSnapshotStatus: (status) => {
      snapshotStatus = status;
    },
    resetCount: () => resets,
    unauthorizedCount: () => unauthorized,
    setSession: (value) => {
      session = value;
    },
    runViewport: () => {
      const entry = [...timers].find(([, timer]) => timer.delay === 250);
      assert.ok(entry, 'latest HTTP viewport snapshot is debounced independently from STOMP');
      timers.delete(entry[0]);
      entry[1].fn();
    },
  };
}
const viewport = (west, east) => ({ west, east, south: -24, north: -23, scope: 'OPERATIONS' });
const frame = (id, longitude, time = 1000) => ({
  serverTime: new Date(time).toISOString(),
  points: [[id, longitude, -23.55, 'AVAILABLE', 0]],
});

test('the first REST snapshot is delivered immediately when the socket is unavailable', async (t) => {
  const { feed, frames, responses } = harness(t);
  feed.start();
  await flush();
  assert.equal(responses.length, 1);
  responses[0].body.resolve(frame('initial', -46.65));
  await flush();
  assert.equal(frames.length, 1);
  assert.equal(frames[0].points[0][0], 'initial');
});

test('a late response from viewport A cannot clear or replace newer viewport B', async (t) => {
  const { feed, frames, responses, runViewport } = harness(t);
  feed.setViewport(viewport(-46.8, -46.7));
  feed.start();
  await flush();
  feed.setViewport(viewport(-46.6, -46.5));
  assert.equal(responses[0].signal.aborted, true);
  runViewport();
  await flush();
  assert.equal(responses.length, 2);
  responses[1].body.resolve(frame('B', -46.55, 2000));
  await flush();
  responses[0].body.resolve(frame('A', -46.75, 3000));
  await flush();
  assert.equal(frames.length, 1);
  assert.equal(frames[0].points[0][0], 'B');
});

test('stopping the feed aborts HTTP and rejects a body that finishes after stop', async (t) => {
  const { feed, frames, responses } = harness(t);
  feed.start();
  await flush();
  feed.stop();
  assert.equal(responses[0].signal.aborted, true);
  responses[0].body.resolve(frame('stale', -46.65));
  await flush();
  assert.deepEqual(frames, []);
});

test('operations scope cannot be changed to PUBLIC by a caller', (t) => {
  const { feed } = harness(t);
  assert.throws(() => feed.setViewport({ ...viewport(-46.8, -46.5), scope: 'PUBLIC' }));
});

test('a different or revoked session is cleared before protected positions are requested', async (t) => {
  const { feed, responses, resetCount, unauthorizedCount, setSession } = harness(t);
  setSession({ user: { id: 'another-visitor', role: 'OPERATIONS' } });
  feed.start();
  await flush();
  assert.equal(responses.length, 0);
  assert.equal(resetCount(), 1);
  assert.equal(unauthorizedCount(), 1);
});

test('a body finishing after protected feed teardown cannot restore private couriers', async (t) => {
  const { feed, frames, responses } = harness(t);
  feed.start();
  await flush();
  assert.equal(responses.length, 1);
  feed.stop();
  responses[0].body.resolve(frame('private', -46.65));
  await flush();
  assert.deepEqual(frames, []);
});

test('a protected 403 clears positions and does not consume a late private body', async (t) => {
  const { feed, frames, responses, setSnapshotStatus, resetCount, unauthorizedCount } = harness(t);
  setSnapshotStatus(403);
  feed.start();
  await flush();
  assert.equal(resetCount(), 1);
  assert.equal(unauthorizedCount(), 1);
  responses[0].body.resolve(frame('private-denied', -46.65));
  await flush();
  assert.deepEqual(frames, []);
});

test('an older server frame cannot overwrite newer authorized positions', async (t) => {
  const { feed, frames, responses, runViewport } = harness(t);
  feed.start();
  await flush();
  responses[0].body.resolve(frame('newer', -46.65, 2_000));
  await flush();
  feed.setViewport(viewport(-46.8, -46.5));
  runViewport();
  await flush();
  responses[1].body.resolve(frame('older', -46.65, 1_000));
  await flush();
  assert.equal(frames.length, 1);
  assert.equal(frames[0].points[0][0], 'newer');
});

test('a delayed older REST frame cannot replace a newer socket frame within the same millisecond', async (t) => {
  const { feed, frames, responses, displayed } = harness(t, { socket: true });
  feed.start();
  await flush();
  const client = clients.at(-1);
  assert.ok(client, 'the isolated fake transport must be available');
  client.connect();
  const newer = { ...frame('newer-socket', -46.65), serverTime: '2026-10-20T14:00:00.123900Z' };
  const older = { ...frame('older-rest', -46.66), serverTime: '2026-10-20T14:00:00.123100Z' };
  assert.notEqual(newer.serverTime, older.serverTime);
  assert.equal(Date.parse(newer.serverTime), Date.parse(older.serverTime));
  client.receive({ body: JSON.stringify(newer) });
  assert.equal(displayed().points[0][0], 'newer-socket');
  responses[0].body.resolve(older);
  await flush();
  assert.equal(frames.length, 1);
  assert.equal(displayed().points[0][0], 'newer-socket');
});

test('a connected socket still reconciles authorization every 30s and revocation rejects an older private body', async (t) => {
  const {
    feed,
    frames,
    responses,
    displayed,
    runInterval,
    runViewport,
    setSnapshotStatus,
    resetCount,
    unauthorizedCount,
  } = harness(t, { socket: true });
  feed.start();
  await flush();
  const client = clients.at(-1);
  assert.ok(client, 'the fake transport was created without opening a network connection');
  client.connect();
  responses[0].body.resolve(frame('authorized', -46.65, 2_000));
  await flush();
  assert.equal(displayed().points[0][0], 'authorized');
  runInterval(5_000);
  await flush();
  assert.equal(
    responses.length,
    1,
    'the 5s fallback must stay idle while the transport is connected',
  );
  feed.setViewport(viewport(-46.8, -46.5));
  runViewport();
  await flush();
  assert.equal(responses.length, 2);
  setSnapshotStatus(403);
  runInterval(30_000);
  await flush();
  assert.equal(
    responses.length,
    3,
    'the 30s authorization check must run even with a connected socket',
  );
  assert.equal(responses[1].signal.aborted, true);
  assert.equal(resetCount(), 1);
  assert.equal(unauthorizedCount(), 1);
  assert.equal(displayed(), null);
  responses[1].body.resolve(frame('old-private', -46.65, 3_000));
  responses[2].body.resolve(frame('denied-private', -46.65, 4_000));
  client.receive({ body: JSON.stringify(frame('late-socket', -46.65, 5_000)) });
  await flush();
  assert.equal(frames.length, 1);
  assert.equal(displayed(), null);
});
