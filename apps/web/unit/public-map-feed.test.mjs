import assert from 'node:assert/strict';
import { after, before, test } from 'node:test';
import { createServer } from 'vite';

let server;
let PublicMapFeed;
before(async () => {
  server = await createServer({
    server: { middlewareMode: true, hmr: false, watch: null },
    optimizeDeps: { noDiscovery: true },
    appType: 'custom',
  });
  ({ PublicMapFeed } = await server.ssrLoadModule('/src/lib/public-map-feed.ts'));
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
function harness(t) {
  const previous = {
    window: globalThis.window,
    document: globalThis.document,
    fetch: globalThis.fetch,
  };
  let sequence = 0;
  const timers = new Map();
  const responses = [];
  const frames = [];
  globalThis.window = {
    setTimeout: (fn, delay) => {
      const id = ++sequence;
      timers.set(id, { fn, delay });
      return id;
    },
    clearTimeout: (id) => timers.delete(id),
    setInterval: () => ++sequence,
    clearInterval: () => {},
    addEventListener: () => {},
    removeEventListener: () => {},
  };
  globalThis.document = { visibilityState: 'visible' };
  globalThis.fetch = async (url, init) => {
    if (String(url).includes('/auth/csrf')) throw new Error('Socket intentionally unavailable');
    assert.match(String(url), /^\/api\/v1\/map\/snapshot\?/);
    const body = deferred();
    responses.push({ url, signal: init.signal, body });
    // Intentionally ignore cancellation in JSON parsing to exercise the late-response guard.
    return { ok: true, json: () => body.promise };
  };
  const feed = new PublicMapFeed(
    (value) => frames.push(value),
    () => {},
  );
  t.after(() => {
    feed.stop();
    Object.assign(globalThis, previous);
  });
  return {
    feed,
    frames,
    responses,
    runViewport: () => {
      const entry = [...timers].find(([, timer]) => timer.delay === 250);
      assert.ok(entry, 'latest HTTP viewport snapshot is debounced independently from STOMP');
      timers.delete(entry[0]);
      entry[1].fn();
    },
  };
}
const viewport = (west, east) => ({ west, east, south: -24, north: -23, scope: 'PUBLIC' });
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
