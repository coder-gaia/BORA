import { chooseRole } from './demo';
import { expect, test } from '@playwright/test';

test('cria pedido privado, preserva o contexto na troca de papel e cancela pela operação', async ({
  page,
  browser,
  baseURL,
}) => {
  await page.goto('/');
  await chooseRole(page, 'Sou loja');
  await expect(page.getByRole('heading', { name: 'Novo pedido' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Criar pedido', exact: true })).toBeEnabled();

  await page.getByLabel('Latitude', { exact: true }).fill('-22.9068');
  await page.getByLabel('Longitude', { exact: true }).fill('-43.1729');
  await page.getByRole('button', { name: 'Criar pedido', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('área');
  await expect(page.getByRole('heading', { name: 'Detalhe do pedido' })).toHaveCount(0);

  await page.getByLabel('Latitude', { exact: true }).fill('-23.5614');
  await page.getByLabel('Longitude', { exact: true }).fill('-46.6559');
  const creation = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      new URL(response.url()).pathname === '/api/v1/orders',
  );
  await page.getByRole('button', { name: 'Criar pedido', exact: true }).click();
  const response = await creation;
  expect(response.status()).toBe(201);
  const created = (await response.json()) as { id: string };
  await expect(page.getByRole('heading', { name: 'Detalhe do pedido' })).toBeVisible();
  await expect(page.getByText('Código de confirmação', { exact: true })).toBeVisible();
  await expect(page.locator('.order-timeline li')).toHaveCount(1);

  await page.reload();
  await expect(page.getByRole('button', { name: 'Sou loja', exact: true })).toHaveAttribute(
    'aria-pressed',
    'true',
  );
  const other = await browser.newContext({ baseURL });
  try {
    const outsider = await other.newPage();
    await outsider.goto('/');
    await chooseRole(outsider, 'Operação');
    await expect(outsider.getByText('A sessão expira', { exact: false })).toBeVisible();
    const forbidden = await other.request.get(`/api/v1/orders/${created.id}`);
    expect(forbidden.status()).toBe(404);
  } finally {
    await other.close();
  }

  await chooseRole(page, 'Operação');
  await expect(page.getByRole('button', { name: 'Operação', exact: true })).toHaveAttribute(
    'aria-pressed',
    'true',
  );
  await page.locator('.order-list-button').first().click();
  await expect(page.getByRole('heading', { name: 'Detalhe do pedido' })).toBeVisible();
  await expect(page.getByText('Código de confirmação', { exact: true })).toHaveCount(0);
  await page.getByRole('button', { name: 'Cancelar pedido', exact: true }).click();
  await expect(page.locator('.order-detail').getByRole('status')).toHaveText('Cancelado');
  await expect(page.locator('.order-timeline li')).toHaveCount(2);
  await expect(page.getByRole('button', { name: 'Cancelar pedido', exact: true })).toHaveCount(0);

  await page.getByRole('button', { name: 'Sair', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Pedidos', exact: true })).toHaveCount(0);
  await expect(
    page.getByText('Entre como loja para criar seu primeiro pedido.', { exact: false }),
  ).toBeVisible();
});

test('repete a chave de idempotência quando a resposta da criação é perdida', async ({ page }) => {
  await page.goto('/');
  await chooseRole(page, 'Sou loja');
  await expect(page.getByRole('button', { name: 'Criar pedido', exact: true })).toBeEnabled();

  let originalId: string | undefined;
  const keys: string[] = [];
  await page.route('**/api/v1/orders', async (route) => {
    if (route.request().method() !== 'POST') return route.continue();
    keys.push(route.request().headers()['idempotency-key']!);
    if (keys.length === 1) {
      const response = await route.fetch();
      expect(response.status()).toBe(201);
      originalId = ((await response.json()) as { id: string }).id;
      await route.abort('connectionreset');
      return;
    }
    await route.continue();
  });
  await page.getByRole('button', { name: 'Criar pedido', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('conexão foi interrompida');
  const retry = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      new URL(response.url()).pathname === '/api/v1/orders',
  );
  await page.getByRole('button', { name: 'Criar pedido', exact: true }).click();
  const response = await retry;
  expect(response.status()).toBe(201);
  expect(((await response.json()) as { id: string }).id).toBe(originalId);
  expect(keys).toHaveLength(2);
  expect(keys[0]).toBeTruthy();
  expect(keys[0]).toBe(keys[1]);
  await expect(page.getByRole('heading', { name: 'Detalhe do pedido' })).toBeVisible();
  await page.getByRole('button', { name: 'Cancelar pedido', exact: true }).click();
  await expect(page.locator('.order-detail').getByRole('status')).toHaveText('Cancelado');
});
