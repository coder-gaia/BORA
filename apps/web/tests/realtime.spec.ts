import { expect, test, type APIRequestContext, type Page } from '@playwright/test';
import type { components } from '../src/generated/api';
import { chooseRole } from './demo';

type Order = components['schemas']['OrderView'];

async function post(request: APIRequestContext, path: string, data?: unknown, headers = {}) {
  const response = await request.get('/api/v1/auth/csrf');
  const csrf = (await response.json()) as { headerName: string; token: string };
  return request.post(path, { data, headers: { [csrf.headerName]: csrf.token, ...headers } });
}

async function createOutsideInterface(page: Page) {
  const response = await post(
    page.request,
    '/api/v1/orders',
    { dropoff: { latitude: -23.5504, longitude: -46.6333 } },
    { 'Idempotency-Key': crypto.randomUUID() },
  );
  expect(response.status()).toBe(201);
  return (await response.json()) as Order;
}

async function cancelOutsideInterface(page: Page, order: Order) {
  const response = await post(page.request, `/api/v1/orders/${order.id}/cancel`, {
    expectedVersion: order.version,
  });
  expect(response.status()).toBe(200);
}

test('recebe mudanças pelo WebSocket e recupera o snapshot após perder a rede', async ({
  page,
}) => {
  const errors: string[] = [];
  const messages: string[] = [];
  let listQueries = 0;
  page.on('pageerror', (error) => errors.push(error.message));
  page.on('websocket', (socket) => {
    if (new URL(socket.url()).pathname !== '/ws') return;
    socket.on('framereceived', ({ payload }) => messages.push(String(payload)));
  });
  page.on('request', (request) => {
    if (request.method() === 'GET' && new URL(request.url()).pathname === '/api/v1/orders') {
      listQueries++;
    }
  });
  await page.goto('/');
  await chooseRole(page, 'Sou loja');
  await expect(page.locator('.connection-status')).toHaveAttribute('data-connection', 'connected');
  const order = await createOutsideInterface(page);
  const button = page.locator(`.order-list-button[data-order-id="${order.id}"]`);
  await expect(button).toBeVisible({ timeout: 5_000 });
  await button.click();
  await expect(page.locator('.order-detail').getByRole('status')).toHaveText(
    'Procurando entregador',
  );

  // A mudança do detalhe precisa vir no frame; este endpoint não pode suprir o teste.
  await page.route(`**/api/v1/orders/${order.id}`, (route) => route.abort('connectionfailed'));
  await cancelOutsideInterface(page, order);
  await expect(page.locator('.order-detail').getByRole('status')).toHaveText('Cancelado', {
    timeout: 5_000,
  });
  await expect
    .poll(() => messages.some((value) => value.includes(`"id":"${order.id}"`)))
    .toBe(true);
  await page.unroute(`**/api/v1/orders/${order.id}`);
  // Com a conexão ativa, o intervalo antigo de dois/três segundos deixa de consultar o servidor.
  await page.waitForTimeout(500);
  const settledQueries = listQueries;
  await page.waitForTimeout(3_500);
  expect(listQueries).toBe(settledQueries);

  const missed = await createOutsideInterface(page);
  await page.locator(`.order-list-button[data-order-id="${missed.id}"]`).click();
  await expect(page.locator('.order-detail').getByRole('status')).toHaveText(
    'Procurando entregador',
  );
  await page.context().setOffline(true);
  await expect(page.locator('.connection-status')).toHaveAttribute('data-connection', 'offline');
  try {
    // APIRequestContext usa uma conexão HTTP independente da rede emulada do navegador.
    await cancelOutsideInterface(page, missed);
  } finally {
    await page.context().setOffline(false);
  }
  await expect(page.locator('.connection-status')).toHaveAttribute('data-connection', 'connected', {
    timeout: 15_000,
  });
  await expect(page.locator('.order-detail').getByRole('status')).toHaveText('Cancelado');
  expect(errors).toEqual([]);
});

test('troca de papel e encerra sockets e dados privados ao sair', async ({ page }) => {
  const sockets: { closed: boolean }[] = [];
  page.on('websocket', (socket) => {
    if (new URL(socket.url()).pathname !== '/ws') return;
    const observed = { closed: false };
    sockets.push(observed);
    socket.on('close', () => {
      observed.closed = true;
    });
  });
  await page.goto('/');
  await chooseRole(page, 'Sou loja');
  await expect(page.locator('.connection-status')).toHaveAttribute('data-connection', 'connected');
  const order = await createOutsideInterface(page);
  await page.locator(`.order-list-button[data-order-id="${order.id}"]`).click();
  await expect(page.locator('.confirmation-code')).toHaveText(order.confirmationCode!);
  const oldSockets = [...sockets];
  await chooseRole(page, 'Operação');
  await expect(page.locator('.connection-status')).toHaveAttribute('data-connection', 'connected');
  await expect.poll(() => oldSockets.every((socket) => socket.closed)).toBe(true);
  await page.locator(`.order-list-button[data-order-id="${order.id}"]`).click();
  await expect(page.locator('.order-detail').getByRole('status')).toHaveText(
    'Procurando entregador',
  );
  await expect(page.locator('.confirmation-code')).toHaveCount(0);
  expect(await page.locator('.demo-panel').textContent()).not.toContain(order.confirmationCode!);
  await cancelOutsideInterface(page, order);
  await expect(page.locator('.order-detail').getByRole('status')).toHaveText('Cancelado');
  await page.getByRole('button', { name: 'Sair', exact: true }).click();
  await expect(page.locator('.connection-status')).toHaveCount(0);
  await expect(page.locator('.order-list-button')).toHaveCount(0);
  await expect.poll(() => sockets.every((socket) => socket.closed)).toBe(true);
  await page.reload();
  await expect(page.locator('.order-list-button')).toHaveCount(0);
  await expect(page.locator('.confirmation-code')).toHaveCount(0);
});
