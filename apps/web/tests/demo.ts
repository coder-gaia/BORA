import { expect, type Page } from '@playwright/test';

let nextLoginAt = 0;

export async function chooseRole(page: Page, label: string) {
  // A suíte compartilha o IP do proxy e respeita o limite real de dez entradas por minuto.
  const wait = nextLoginAt - Date.now();
  if (wait > 0) await page.waitForTimeout(wait);
  const response = page.waitForResponse(
    (value) =>
      value.request().method() === 'POST' && new URL(value.url()).pathname === '/api/v1/auth/demo',
  );
  const button = page.getByRole('button', { name: label, exact: true });
  await button.click();
  nextLoginAt = Date.now() + 6_100;
  expect((await response).status()).toBe(200);
  await expect(button).toHaveAttribute('aria-pressed', 'true');
}

export async function openOrder(page: Page, id: string) {
  const detail = page.locator('.order-detail');
  if (await detail.isVisible()) {
    if ((await detail.getAttribute('data-order-id')) === id) return;
    await detail.getByRole('button', { name: 'Voltar', exact: true }).click();
  }
  if (!(await page.getByRole('heading', { name: 'Pedidos', exact: true }).isVisible())) {
    await page.getByRole('button', { name: 'Ver pedidos', exact: true }).click();
  }
  await expect(page.getByRole('heading', { name: 'Pedidos', exact: true })).toBeVisible();
  await expect(page.getByText('Carregando pedidos...', { exact: true })).toHaveCount(0);
  const order = await page.request.get(`/api/v1/orders/${id}`);
  expect(order.status()).toBe(200);
  const reference = (await order.json()) as { publicCode: string };
  await page.getByRole('combobox', { name: 'Mostrar', exact: true }).selectOption('ALL');
  await page.getByRole('combobox', { name: 'Status do pedido', exact: true }).selectOption('');
  await page.getByLabel('Código do pedido', { exact: true }).fill(reference.publicCode);
  await page.getByRole('button', { name: 'Buscar pedido', exact: true }).click();
  const row = page.locator(`.order-list-button[data-order-id="${id}"]`);
  await expect(row).toBeVisible();
  await row.click();
}
