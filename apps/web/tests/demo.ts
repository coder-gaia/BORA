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
  await expect(page.getByRole('heading', { name: 'Pedidos', exact: true })).toBeVisible();
  await expect(page.getByText('Carregando pedidos...', { exact: true })).toHaveCount(0);
  const row = page.locator(`.order-list-button[data-order-id="${id}"]`);
  const next = page.getByRole('button', { name: 'Próxima', exact: true });
  if (!(await row.count())) {
    // Um POST externo pode terminar antes de a invalidação por WS atualizar a página.
    // Recarregue o snapshot atual antes de avançar, para não saltar o pedido recém-criado.
    const response = page.waitForResponse(
      (value) =>
        value.request().method() === 'GET' && new URL(value.url()).pathname === '/api/v1/orders',
    );
    await page.getByRole('button', { name: 'Atualizar', exact: true }).click();
    expect((await response).status()).toBe(200);
    await expect(page.getByRole('button', { name: 'Atualizar', exact: true })).toBeEnabled();
  }
  const deadline = Date.now() + 30_000;
  while (Date.now() < deadline) {
    if (await row.count()) {
      await row.click();
      return;
    }
    if (!(await next.count())) {
      await expect(row).toBeVisible();
      await row.click();
      return;
    }
    await expect(next).toBeEnabled();
    const label = await page.locator('.pagination').innerText();
    const currentPage = Number(/Página\s+(\d+)/.exec(label)?.[1]);
    expect(currentPage).toBeGreaterThan(0);
    const response = page.waitForResponse((value) => {
      const url = new URL(value.url());
      return (
        value.request().method() === 'GET' &&
        url.pathname === '/api/v1/orders' &&
        url.searchParams.get('page') === String(currentPage)
      );
    });
    await next.click();
    expect((await response).status()).toBe(200);
    await expect(page.locator('.pagination')).toContainText(`Página ${currentPage + 1} de`);
  }
  throw new Error('Não encontramos o pedido de teste na lista paginada.');
}
