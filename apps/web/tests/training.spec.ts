import { expect, test, type Page } from '@playwright/test';
import { chooseRole } from './demo';
import type { components } from '../src/generated/api';

async function logoutAndConfirm(page: Page) {
  const logout = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      new URL(response.url()).pathname === '/api/v1/auth/logout',
  );
  await page.getByRole('button', { name: 'Sair', exact: true }).click();
  expect((await logout).status()).toBe(204);
  await expect(page.getByRole('button', { name: 'Sou entregador', exact: true })).toHaveAttribute(
    'aria-pressed',
    'false',
  );
  await expect
    .poll(() =>
      page.evaluate(() =>
        Object.keys(sessionStorage).filter((key) => key.startsWith('bora:training-intent:')),
      ),
    )
    .toEqual([]);
}

test('treina uma entrega com direção explícita, pausa e confirmações manuais', async ({ page }) => {
  test.setTimeout(240_000);
  const errors: string[] = [];
  const gps: number[] = [];
  const requests: string[] = [];
  page.on('pageerror', (error) => errors.push(error.message));
  page.on('request', (request) => {
    const path = new URL(request.url()).pathname;
    requests.push(path);
    if (request.method() === 'POST' && path === '/api/v1/courier/positions') gps.push(Date.now());
  });
  await page.goto('/entregador');
  await chooseRole(page, 'Sou entregador');
  await page.getByLabel('Latitude da sua posição', { exact: true }).fill('-23.5799');
  await page.getByLabel('Longitude da sua posição', { exact: true }).fill('-46.6671');
  await page.getByRole('button', { name: 'Ficar online', exact: true }).click();
  const creation = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      new URL(response.url()).pathname === '/api/v1/courier/training-order',
  );
  await page.getByRole('button', { name: 'Criar pedido de treino', exact: true }).click();
  const response = await creation;
  expect(response.status()).toBe(201);
  const receipt = (await response.json()) as components['schemas']['TrainingOrderReceipt'];
  expect(receipt.confirmationCode).toMatch(/^\d{6}$/);
  await expect(page.getByText('Código deste treino', { exact: true })).toBeVisible();
  expect(requests).not.toContain(`/api/v1/orders/${receipt.orderId}`);
  await expect(page.getByRole('button', { name: 'Aceitar oferta', exact: true })).toBeEnabled();
  await page.getByRole('button', { name: 'Aceitar oferta', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Entrega em andamento' })).toBeVisible();
  const before = gps.length;
  await page.getByRole('button', { name: 'Dirigir até a loja', exact: true }).click();
  await expect.poll(() => gps.length).toBeGreaterThan(before);
  await page.getByRole('button', { name: 'Pausar direção', exact: true }).click();
  const paused = gps.length;
  await page.waitForTimeout(2_500);
  expect(gps.length).toBe(paused);
  const reconcile = page.getByRole('button', { name: 'Confirmar posição enviada', exact: true });
  if (await reconcile.isVisible()) await reconcile.click();
  await expect(page.locator('.position-receipt')).toHaveText('Posição confirmada pelo servidor.');
  await page.getByRole('button', { name: 'Retomar direção', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Confirmar coleta', exact: true })).toBeEnabled({
    timeout: 90_000,
  });
  await page.getByRole('button', { name: 'Confirmar coleta', exact: true }).click();
  await page.getByRole('button', { name: 'Dirigir até o cliente', exact: true }).click();
  await expect(page.getByText('Ponto final enviado.', { exact: false })).toBeVisible({
    timeout: 120_000,
  });
  await expect(page.locator('.position-receipt')).toHaveText('Posição confirmada pelo servidor.');
  const wrong = String((Number(receipt.confirmationCode) + 1) % 1_000_000).padStart(6, '0');
  await page.getByLabel('Código de confirmação', { exact: true }).fill(wrong);
  await page.getByRole('button', { name: 'Confirmar entrega', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('incorreto');
  await page.getByLabel('Código de confirmação', { exact: true }).fill(receipt.confirmationCode);
  await page.getByRole('button', { name: 'Confirmar entrega', exact: true }).click();
  await expect(page.getByText('Entrega concluída.', { exact: false })).toBeVisible();
  const stored = await page.evaluate(() =>
    Array.from({ length: sessionStorage.length }, (_, index) =>
      sessionStorage.getItem(sessionStorage.key(index)!),
    ).join(' '),
  );
  expect(stored).not.toContain(receipt.confirmationCode);
  await logoutAndConfirm(page);
  expect(errors).toEqual([]);
});

test('limpa a intenção de treino após o logout HTTP e a confirmação da sessão anônima', async ({
  page,
}) => {
  await page.goto('/entregador');
  await chooseRole(page, 'Sou entregador');
  await page.getByLabel('Latitude da sua posição', { exact: true }).fill('-23.5799');
  await page.getByLabel('Longitude da sua posição', { exact: true }).fill('-46.6671');
  await page.getByRole('button', { name: 'Ficar online', exact: true }).click();
  await page.getByRole('button', { name: 'Criar pedido de treino', exact: true }).click();
  await expect(page.getByText('Código deste treino', { exact: true })).toBeVisible();
  expect(
    await page.evaluate(() =>
      Object.keys(sessionStorage).filter((key) => key.startsWith('bora:training-intent:')),
    ),
  ).toHaveLength(1);
  await logoutAndConfirm(page);
});
