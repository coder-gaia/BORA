import { expect, test } from '@playwright/test';
import type { components } from '../src/generated/api';

type Order = components['schemas']['OrderView'];
const time = '2026-10-20T12:00:00Z';
const store = {
  id: '00000000-0000-4000-8000-000000000001',
  name: 'Loja da criação',
  location: { latitude: -23.5614, longitude: -46.6559 },
  serviceAreaId: '00000000-0000-4000-8000-000000000002',
};

test('uma criação antiga não apaga a chave nem a confirmação de uma nova tentativa após navegar', async ({
  page,
}) => {
  let releaseA!: () => void;
  let releaseB!: () => void;
  const pendingA = new Promise<void>((resolve) => {
    releaseA = resolve;
  });
  const pendingB = new Promise<void>((resolve) => {
    releaseB = resolve;
  });
  const keysA: string[] = [];
  const keysB: string[] = [];
  const committed = new Map<string, Order>();
  let commitsB = 0;
  await page.route('**/api/v1/auth/me', (route) =>
    route.fulfill({
      json: {
        authenticated: true,
        user: {
          id: '00000000-0000-4000-8000-000000000003',
          role: 'STORE',
          displayName: 'Loja de teste',
          expiresAt: '2026-10-20T14:00:00Z',
          storeId: store.id,
        },
      },
    }),
  );
  await page.route('**/api/v1/auth/csrf', (route) =>
    route.fulfill({ json: { headerName: 'X-CSRF-TOKEN', token: 'fixture' } }),
  );
  await page.route('**/api/v1/stores/me', (route) => route.fulfill({ json: store }));
  await page.route('https://tiles.openfreemap.org/**', (route) =>
    route.fulfill({ status: 503, body: '' }),
  );
  await page.routeWebSocket('**/ws', (socket) =>
    socket.onMessage((raw) => {
      if (String(raw).startsWith('CONNECT\n'))
        socket.send('CONNECTED\nversion:1.2\nheart-beat:0,0\n\n\0');
    }),
  );
  await page.route('**/api/v1/orders?*', (route) =>
    route.fulfill({
      json: { items: [], page: 0, size: 10, totalPages: 0, totalElements: 0, snapshotAt: time },
    }),
  );
  await page.route('**/api/v1/orders', async (route) => {
    if (route.request().method() !== 'POST') return route.continue();
    const key = route.request().headers()['idempotency-key']!;
    const body = route.request().postDataJSON() as {
      dropoff: { latitude: number; longitude: number };
    };
    const isB = body.dropoff.latitude === -23.5514;
    (isB ? keysB : keysA).push(key);
    if (!committed.has(key)) {
      if (isB) commitsB++;
      committed.set(key, {
        id: `00000000-0000-4000-8000-${String(committed.size + 10).padStart(12, '0')}`,
        publicCode: (isB ? 'B' : 'A').repeat(32),
        store,
        dropoff: body.dropoff,
        status: 'SEARCHING',
        createdAt: time,
        slaDeadline: time,
        version: 0,
        confirmationCode: '123456',
        timeline: [
          {
            id: committed.size + 1,
            type: 'ORDER_CREATED',
            fromStatus: null,
            toStatus: 'SEARCHING',
            occurredAt: time,
            orderVersion: 0,
          },
        ],
      });
    }
    if (!isB) {
      await pendingA;
      return route.fulfill({ status: 201, json: committed.get(key) });
    }
    if (keysB.length === 1) {
      await pendingB;
      return route.abort('connectionreset');
    }
    return route.fulfill({ status: 201, json: committed.get(key) });
  });
  await page.goto('/loja');
  await expect(page.getByRole('button', { name: 'Criar pedido', exact: true })).toBeEnabled();
  const completedA = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      response.request().postDataJSON().dropoff.latitude === -23.5504,
  );
  await page.getByRole('button', { name: 'Criar pedido', exact: true }).click();
  await expect.poll(() => keysA.length).toBe(1);
  await page.getByRole('button', { name: 'Ver pedidos', exact: true }).click();
  await page.getByRole('button', { name: 'Novo pedido', exact: true }).click();
  await page.getByLabel('Latitude', { exact: true }).fill('-23.5514');
  await page.getByRole('button', { name: 'Criar pedido', exact: true }).click();
  await expect.poll(() => keysB.length).toBe(1);
  releaseA();
  expect((await completedA).status()).toBe(201);
  await expect(page.getByRole('heading', { name: 'Pedido criado', exact: true })).toHaveCount(0);
  releaseB();
  await expect(page.getByRole('alert')).toContainText('conexão foi interrompida');
  await page.getByRole('button', { name: 'Criar pedido', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Pedido criado', exact: true })).toBeVisible();
  expect(keysB).toHaveLength(2);
  expect(keysB[0]).toBe(keysB[1]);
  expect(commitsB).toBe(1);
  await expect(page.locator('.created-order-notice .order-reference')).toHaveText('B'.repeat(32));
});
