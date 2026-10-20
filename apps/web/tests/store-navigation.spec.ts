import { expect, test } from '@playwright/test';
import type { components } from '../src/generated/api';
import { chooseRole } from './demo';

type Order = components['schemas']['OrderView'];

test('abre pedidos por intenção e preserva busca, status, leitura e foco durante atualizações', async ({
  page,
}, testInfo) => {
  test.setTimeout(90_000);
  const errors: string[] = [];
  const listQueries: URL[] = [];
  let logins = 0;
  page.on('pageerror', (error) => errors.push(error.message));
  page.on('console', (message) => {
    if (message.type() === 'error' && /hydration|hydrating|Minified React/i.test(message.text()))
      errors.push(message.text());
  });
  page.on('request', (request) => {
    const url = new URL(request.url());
    if (url.pathname === '/api/v1/auth/demo') logins++;
    if (request.method() === 'GET' && url.pathname === '/api/v1/orders') listQueries.push(url);
  });
  await page.goto('/loja');
  await expect(page.getByRole('heading', { name: /Um destino.*Todo o caminho/ })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Sou loja', exact: true })).toBeEnabled();
  expect(logins).toBe(0);
  await chooseRole(page, 'Sou loja');
  await expect(page.getByRole('button', { name: 'Criar pedido', exact: true })).toBeEnabled();
  await expect(page.getByRole('heading', { name: 'Pedidos', exact: true })).toHaveCount(0);
  await expect(page.getByRole('combobox', { name: 'Status do pedido', exact: true })).toHaveCount(
    0,
  );
  expect(listQueries).toHaveLength(0);

  const creation = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      new URL(response.url()).pathname === '/api/v1/orders',
  );
  await page.getByRole('button', { name: 'Criar pedido', exact: true }).click();
  const response = await creation;
  expect(response.status()).toBe(201);
  const order = (await response.json()) as Order;
  await expect(page.getByRole('heading', { name: 'Pedido criado', exact: true })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Detalhe do pedido', exact: true })).toHaveCount(
    0,
  );
  await expect(page.getByRole('combobox', { name: 'Status do pedido', exact: true })).toHaveCount(
    0,
  );
  expect(listQueries).toHaveLength(0);
  const notice = await page.locator('.created-order-notice').boundingBox();
  const submit = await page
    .getByRole('button', { name: 'Criar pedido', exact: true })
    .boundingBox();
  expect(notice!.y - (submit!.y + submit!.height)).toBeLessThanOrEqual(32);

  await page.getByRole('button', { name: 'Ver pedidos', exact: true }).click();
  await expect(page.getByRole('combobox', { name: 'Mostrar', exact: true })).toHaveValue('OWN');
  const row = page.locator(`.order-list-button[data-order-id="${order.id}"]`);
  await expect(row).toBeVisible();
  expect(listQueries.at(-1)!.searchParams.get('scope')).toBe('OWN');
  await page
    .getByRole('combobox', { name: 'Status do pedido', exact: true })
    .selectOption('SEARCHING');
  await page.getByLabel('Código do pedido', { exact: true }).fill(order.publicCode);
  await page.getByRole('button', { name: 'Buscar pedido', exact: true }).click();
  await expect(row).toBeVisible();
  await expect(page.getByRole('button', { name: 'Atualizar', exact: true })).toBeEnabled();
  const filtered = listQueries.at(-1)!;
  expect(filtered.searchParams.get('status')).toBe('SEARCHING');
  expect(filtered.searchParams.get('code')).toBe(order.publicCode);
  const rowsBefore = await page.locator('.order-list-button').getAttribute('data-order-id');
  const queriesBefore = listQueries.length;
  const search = page.getByLabel('Código do pedido', { exact: true });
  await search.focus();
  const csrf = (await (await page.request.get('/api/v1/auth/csrf')).json()) as {
    headerName: string;
    token: string;
  };
  const canceled = await page.request.post(`/api/v1/orders/${order.id}/cancel`, {
    data: { expectedVersion: order.version },
    headers: { [csrf.headerName]: csrf.token },
  });
  expect(canceled.status()).toBe(200);
  await expect(page.getByText('Há atualizações disponíveis.', { exact: false })).toBeVisible();
  await expect(search).toBeFocused();
  expect(listQueries.length).toBe(queriesBefore);
  expect(await page.locator('.order-list-button').getAttribute('data-order-id')).toBe(rowsBefore);
  await expect(row).toContainText('Procurando entregador');
  await row.click();
  await expect(page.locator('.order-detail').getByRole('status')).toHaveText('Cancelado');
  await expect(page.getByRole('heading', { name: 'Detalhe do pedido' })).toBeFocused();
  await page.getByRole('button', { name: 'Voltar', exact: true }).click();
  await expect(search).toHaveValue(order.publicCode);
  await expect(page.getByRole('combobox', { name: 'Status do pedido', exact: true })).toHaveValue(
    'SEARCHING',
  );
  await expect(row).toBeFocused();
  await expect(row).toContainText('Procurando entregador');
  await page.getByRole('button', { name: 'Ver atualizações', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Nenhum pedido encontrado' })).toBeVisible();
  await page.getByRole('button', { name: 'Limpar filtros', exact: true }).click();
  await expect(row).toContainText('Cancelado');

  await page.reload();
  await expect(page.getByRole('button', { name: 'Sou loja', exact: true })).toHaveAttribute(
    'aria-pressed',
    'true',
  );
  await expect(page.getByRole('combobox', { name: 'Status do pedido', exact: true })).toHaveCount(
    0,
  );
  await page.getByRole('link', { name: 'Voltar à cidade' }).click();
  await expect(page.getByRole('heading', { name: /Cada entrega/ })).toBeVisible();
  await page.goBack();
  await expect(page.getByRole('heading', { name: 'Sua loja, suas entregas' })).toBeVisible();
  expect(logins).toBe(1);
  expect(errors).toEqual([]);
  await testInfo.attach('store-navigation.json', {
    body: JSON.stringify(
      { logins, listQueries: listQueries.map((url) => url.pathname + url.search), errors },
      null,
      2,
    ),
    contentType: 'application/json',
  });
});
