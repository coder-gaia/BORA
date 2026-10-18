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
