import { execFile } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { promisify } from 'node:util';
import { expect, test, type APIRequestContext } from '@playwright/test';
import { chooseRole, openOrder } from './demo';
import type { components } from '../src/generated/api';

const execute = promisify(execFile);
const root = fileURLToPath(new URL('../../../', import.meta.url));
const project = process.env.BORA_CLUSTER_PROJECT || 'bora-cluster';
const compose = [
  'compose',
  '--env-file',
  `${root}.env`,
  '-f',
  `${root}infra/compose/compose.cluster.yml`,
  '-p',
  project,
];

async function service(command: 'start' | 'stop', name: 'api1' | 'api2') {
  const args = command === 'stop' ? ['stop', '--timeout', '5', name] : ['start', name];
  await execute('docker', [...compose, ...args], { cwd: root, timeout: 40_000 });
}

async function ready(request: APIRequestContext, name: 'api1' | 'api2') {
  const origin =
    name === 'api1'
      ? process.env.BORA_CLUSTER_API1_URL || 'http://127.0.0.1:18081'
      : process.env.BORA_CLUSTER_API2_URL || 'http://127.0.0.1:18082';
  expect(['localhost', '127.0.0.1']).toContain(new URL(origin).hostname);
  await expect
    .poll(
      async () => {
        try {
          const response = await request.get(`${origin}/actuator/health/readiness`, {
            timeout: 2_000,
          });
          return response.status();
        } catch {
          return 0;
        }
      },
      { timeout: 60_000, intervals: [500, 1_000] },
    )
    .toBe(200);
}

test('reconecta nas duas instâncias sem perder sessão ou snapshot', async ({
  page,
  request,
  baseURL,
}, testInfo) => {
  test.skip(process.env.BORA_TEST_CLUSTER !== 'true', 'Ensaio explícito do cluster Docker local.');
  test.setTimeout(180_000);
  // Validate before the first stop and before entering cleanup that restarts services.
  expect(['bora-cluster', 'bora-sim-soak']).toContain(project);
  expect(['localhost', '127.0.0.1']).toContain(new URL(baseURL!).hostname);
  const sockets: { closed: boolean; connected: boolean; private: boolean }[] = [];
  const errors: string[] = [];
  const failedResponses: { path: string; status: number }[] = [];
  let snapshots = 0;
  let privateId = '';
  let privateSnapshots = 0;
  const privateFrames: string[] = [];
  page.on('pageerror', (error) => errors.push(error.message));
  page.on('response', (response) => {
    const path = new URL(response.url()).pathname;
    if (path.startsWith('/api/') && response.status() >= 500) {
      failedResponses.push({ path, status: response.status() });
    }
  });
  page.on('websocket', (socket) => {
    if (new URL(socket.url()).pathname !== '/ws') return;
    const state = { closed: false, connected: false, private: false };
    socket.on('framesent', ({ payload }) => {
      if (String(payload).includes('destination:/user/queue/events')) state.private = true;
    });
    sockets.push(state);
    socket.on('close', () => {
      state.closed = true;
    });
    socket.on('framereceived', ({ payload }) => {
      if (String(payload).startsWith('CONNECTED')) state.connected = true;
      if (privateId && String(payload).includes(`"id":"${privateId}"`))
        privateFrames.push(String(payload));
    });
  });
  page.on('request', (value) => {
    if (value.method() !== 'GET') return;
    const path = new URL(value.url()).pathname;
    if (path === '/api/v1/auth/me') snapshots++;
    if (privateId && path === `/api/v1/orders/${privateId}`) privateSnapshots++;
  });
  const recoveries: { stopped: string; snapshots: number; connections: number }[] = [];
  try {
    // Só api1 está disponível para o primeiro socket. Depois, cada parada força a outra instância.
    await service('stop', 'api2');
    await ready(request, 'api1');
    await page.goto('/');
    await chooseRole(page, 'Sou loja');
    await expect(page.locator('.connection-status')).toHaveAttribute(
      'data-connection',
      'connected',
    );
    const original = (await (await page.request.get('/api/v1/auth/me')).json()) as {
      user: { id: string; role: string };
    };
    const csrfResponse = await page.request.get('/api/v1/auth/csrf');
    const csrf = (await csrfResponse.json()) as { headerName: string; token: string };
    const creation = await page.request.post('/api/v1/orders', {
      data: { dropoff: { latitude: -23.5504, longitude: -46.6333 } },
      headers: { [csrf.headerName]: csrf.token, 'Idempotency-Key': crypto.randomUUID() },
    });
    expect(creation.status()).toBe(201);
    const order = (await creation.json()) as components['schemas']['OrderView'];
    privateId = order.id;
    await openOrder(page, order.id);
    await expect(page.locator('.confirmation-code')).toHaveText(order.confirmationCode!);
    await expect.poll(() => privateFrames.length).toBeGreaterThan(0);
    await service('start', 'api2');
    await ready(request, 'api2');
    for (const stopped of ['api1', 'api2'] as const) {
      const previous = sockets
        .filter((socket) => socket.private && !socket.closed && socket.connected)
        .at(-1);
      expect(previous).toBeDefined();
      const snapshotCount = snapshots;
      const privateSnapshotCount = privateSnapshots;
      const privateFrameCount = privateFrames.length;
      const connectedCount = sockets.filter((socket) => socket.private && socket.connected).length;
      await service('stop', stopped);
      await expect.poll(() => previous!.closed, { timeout: 15_000 }).toBe(true);
      await expect
        .poll(() => sockets.filter((socket) => socket.private && socket.connected).length, {
          timeout: 20_000,
        })
        .toBeGreaterThan(connectedCount);
      await expect(page.locator('.connection-status')).toHaveAttribute(
        'data-connection',
        'connected',
      );
      await expect.poll(() => snapshots, { timeout: 10_000 }).toBeGreaterThan(snapshotCount);
      await expect
        .poll(() => privateSnapshots, { timeout: 10_000 })
        .toBeGreaterThan(privateSnapshotCount);
      await expect
        .poll(() => privateFrames.length, { timeout: 10_000 })
        .toBeGreaterThan(privateFrameCount);
      const current = (await (await page.request.get('/api/v1/auth/me')).json()) as typeof original;
      expect(current.user.id).toBe(original.user.id);
      expect(current.user.role).toBe(original.user.role);
      await expect(page.getByRole('heading', { name: 'Detalhe do pedido' })).toBeVisible();
      await expect(page.locator('.confirmation-code')).toHaveText(order.confirmationCode!);
      await expect(page.getByRole('alert')).toHaveCount(0);
      recoveries.push({
        stopped,
        snapshots,
        connections: sockets.filter((socket) => socket.private && socket.connected).length,
      });
      await service('start', stopped);
      await ready(request, stopped);
    }
    expect(errors).toEqual([]);
    expect(failedResponses).toEqual([]);
    await testInfo.attach('recovery.json', {
      body: JSON.stringify(
        {
          recoveries,
          errors,
          failedResponses,
          privateSnapshots,
          privateFrames: privateFrames.length,
        },
        null,
        2,
      ),
      contentType: 'application/json',
    });
  } finally {
    // Nunca deixar a segunda instância parada se uma asserção falhar.
    // Start existing containers so additional Compose overrides stay intact.
    await execute('docker', [...compose, 'start', 'api1', 'api2'], {
      cwd: root,
      timeout: 60_000,
    });
  }
});
