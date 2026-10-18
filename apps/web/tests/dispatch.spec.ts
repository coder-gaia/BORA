import { chooseRole } from './demo';
import { expect, test, type APIRequestContext, type Page } from '@playwright/test';
import type { components } from '../src/generated/api';

type Order = components['schemas']['OrderView'];
type Inbox = components['schemas']['CourierOfferInbox'];
type Point = components['schemas']['GeoPoint'];

async function post(request: APIRequestContext, path: string, data?: unknown) {
  const csrf = await request.get('/api/v1/auth/csrf');
  const token = (await csrf.json()) as { headerName: string; token: string };
  return request.post(path, { data, headers: { [token.headerName]: token.token } });
}

async function cancelOrder(request: APIRequestContext, id: string) {
  const response = await request.get(`/api/v1/orders/${id}`);
  const current = (await response.json()) as Order;
  expect(
    (
      await post(request, `/api/v1/orders/${id}/cancel`, { expectedVersion: current.version })
    ).status(),
  ).toBe(200);
}

async function createOrder(page: Page) {
  await chooseRole(page, 'Sou loja');
  await expect(page.getByRole('button', { name: 'Criar pedido', exact: true })).toBeEnabled();
  const response = page.waitForResponse(
    (value) =>
      value.request().method() === 'POST' && new URL(value.url()).pathname === '/api/v1/orders',
  );
  await page.getByRole('button', { name: 'Criar pedido', exact: true }).click();
  const created = await response;
  expect(created.status()).toBe(201);
  return (await created.json()) as Order;
}

async function becomeAvailable(page: Page, position: Point) {
  await chooseRole(page, 'Sou entregador');
  await page.getByLabel('Latitude da sua posição', { exact: true }).fill(String(position.latitude));
  await page
    .getByLabel('Longitude da sua posição', { exact: true })
    .fill(String(position.longitude));
  await page.getByRole('button', { name: 'Ficar online', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Aceitar oferta', exact: true })).toBeEnabled({
    timeout: 15_000,
  });
  const response = await page.request.get('/api/v1/courier/offer');
  expect(response.status()).toBe(200);
  const inbox = (await response.json()) as Inbox;
  expect(inbox.offer).not.toBeNull();
  return inbox.offer!;
}

async function sendPosition(page: Page, position: Point) {
  await page.getByLabel('Latitude da sua posição', { exact: true }).fill(String(position.latitude));
  await page
    .getByLabel('Longitude da sua posição', { exact: true })
    .fill(String(position.longitude));
  const submission = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      new URL(response.url()).pathname === '/api/v1/courier/positions',
  );
  await page.getByRole('button', { name: 'Enviar posição', exact: true }).click();
  expect((await submission).status()).toBe(202);
  await expect(page.locator('.position-receipt')).toHaveText('Posição confirmada pelo servidor.', {
    timeout: 10_000,
  });
}

test('aceita uma oferta privada e conclui a entrega após conferir o código', async ({
  page,
  browser,
  baseURL,
}, testInfo) => {
  test.setTimeout(90_000);
  const errors: string[] = [];
  page.on('pageerror', (error) => errors.push(error.message));
  await page.goto('/');
  const order = await createOrder(page);
  expect(order.confirmationCode).toMatch(/^\d{6}$/);
  const offer = await becomeAvailable(page, order.store.location);
  expect(offer.orderId).toBe(order.id);
  expect(JSON.stringify(offer)).not.toMatch(/confirmationCode|confirmationHash|publicCode/);
  for (const width of [1440, 390, 320]) {
    await page.setViewportSize({ width, height: 1000 });
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(
      width,
    );
    await page.screenshot({ path: testInfo.outputPath(`offer-${width}.png`), fullPage: true });
  }
  await page.setViewportSize({ width: 1440, height: 1000 });

  const outsider = await browser.newContext({ baseURL });
  try {
    const other = await outsider.newPage();
    await other.goto('/');
    await chooseRole(other, 'Sou entregador');
    await expect(
      other.getByRole('heading', { name: 'Seu trabalho como entregador' }),
    ).toBeVisible();
    const own = await outsider.request.get('/api/v1/courier/offer');
    expect(((await own.json()) as Inbox).offer).toBeNull();
    expect((await post(outsider.request, `/api/v1/offers/${offer.id}/accept`)).status()).toBe(404);
  } finally {
    await outsider.close();
  }

  await page.getByRole('button', { name: 'Aceitar oferta', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Entrega em andamento' })).toBeVisible();
  await sendPosition(page, order.store.location);
  await expect(page.getByRole('button', { name: 'Confirmar coleta', exact: true })).toBeEnabled({
    timeout: 15_000,
  });
  await page.getByRole('button', { name: 'Confirmar coleta', exact: true }).click();
  await expect(page.getByLabel('Código de confirmação', { exact: true })).toBeVisible();
  await sendPosition(page, order.dropoff);
  const wrong = String((Number(order.confirmationCode) + 1) % 1_000_000).padStart(6, '0');
  await page.getByLabel('Código de confirmação', { exact: true }).fill(wrong);
  await page.getByRole('button', { name: 'Confirmar entrega', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('incorreto');
  await expect(page.getByRole('heading', { name: 'Entrega em andamento' })).toBeVisible();
  await page.getByLabel('Código de confirmação', { exact: true }).fill(order.confirmationCode!);
  await page.getByRole('button', { name: 'Confirmar entrega', exact: true }).click();
  await expect(
    page.getByText('Entrega concluída. Você está disponível novamente.', { exact: true }),
  ).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Entrega em andamento' })).toHaveCount(0);
  await chooseRole(page, 'Sou loja');
  await page.locator('.order-list-button').first().click();
  await expect(page.locator('.order-detail').getByRole('status')).toHaveText('Entregue');
  await expect(page.locator('.order-timeline')).toContainText('Oferta aceita');
  expect(errors).toEqual([]);
});

test('recusa uma oferta e deixa o prazo de outra encerrar sem nova oferta ao mesmo entregador', async ({
  page,
}) => {
  test.setTimeout(90_000);
  await page.clock.install({ time: new Date(Date.now() + 3_600_000) });
  await page.goto('/');
  const rejected = await createOrder(page);
  await becomeAvailable(page, rejected.store.location);
  await page.getByRole('button', { name: 'Recusar oferta', exact: true }).click();
  await expect(
    page.getByText('Oferta recusada. Você continua disponível para outros pedidos.', {
      exact: true,
    }),
  ).toBeVisible();
  await expect(page.getByRole('button', { name: 'Aceitar oferta', exact: true })).toHaveCount(0);
  const expired = await createOrder(page);
  await chooseRole(page, 'Sou entregador');
  await expect(page.getByRole('button', { name: 'Aceitar oferta', exact: true })).toBeEnabled({
    timeout: 15_000,
  });
  const response = await page.request.get('/api/v1/courier/offer');
  const inbox = (await response.json()) as Inbox;
  expect(inbox.offer?.orderId).toBe(expired.id);
  await expect(page.locator('.offer-countdown')).toContainText('Responda em até');
  await expect(page.getByRole('button', { name: 'Aceitar oferta', exact: true })).toHaveCount(0, {
    timeout: 40_000,
  });
  await expect(page.locator('.courier-status')).toHaveText('Disponível');
  const after = await page.request.get('/api/v1/courier/offer');
  expect(((await after.json()) as Inbox).offer).toBeNull();
  await chooseRole(page, 'Operação');
  const detail = await page.request.get(`/api/v1/orders/${expired.id}`);
  expect(
    ((await detail.json()) as Order).timeline.some((event) => event.type === 'OFFER_EXPIRED'),
  ).toBe(true);
  await cancelOrder(page.request, rejected.id);
  await cancelOrder(page.request, expired.id);
});

test('redistribui pela operação e libera o entregador anterior', async ({ page }) => {
  test.setTimeout(60_000);
  await page.goto('/');
  const order = await createOrder(page);
  await becomeAvailable(page, order.store.location);
  await page.getByRole('button', { name: 'Aceitar oferta', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Entrega em andamento' })).toBeVisible();
  await chooseRole(page, 'Operação');
  await page.locator('.order-list-button').first().click();
  await expect(
    page.getByRole('button', { name: 'Redistribuir pedido', exact: true }),
  ).toBeEnabled();
  const action = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      new URL(response.url()).pathname === `/api/v1/ops/orders/${order.id}/reassign`,
  );
  await page.getByRole('button', { name: 'Redistribuir pedido', exact: true }).click();
  expect((await action).status()).toBe(204);
  await expect(page.locator('.order-detail').getByRole('status')).toHaveText(
    'Procurando entregador',
  );
  await expect(page.locator('.order-timeline')).toContainText('Pedido redistribuído');
  await chooseRole(page, 'Sou entregador');
  await expect(page.locator('.courier-status')).toHaveText('Disponível');
  await expect(page.getByRole('heading', { name: 'Entrega em andamento' })).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Aceitar oferta', exact: true })).toHaveCount(0);
  await chooseRole(page, 'Operação');
  const current = await page.request.get(`/api/v1/orders/${order.id}`);
  const state = (await current.json()) as Order;
  expect(
    (
      await post(page.request, `/api/v1/orders/${order.id}/cancel`, {
        expectedVersion: state.version,
      })
    ).status(),
  ).toBe(200);
});
