import { chooseRole } from './demo';
import { expect, test } from '@playwright/test';

test('altera disponibilidade e confirma uma posição após a gravação em lote', async ({ page }) => {
  await page.clock.install();
  await page.goto('/');
  await chooseRole(page, 'Sou entregador');
  await expect(page.getByRole('heading', { name: 'Seu trabalho como entregador' })).toBeVisible();
  await expect(page.locator('.courier-status')).toHaveText('Offline');
  await page.getByRole('button', { name: 'Ficar online', exact: true }).click();
  await expect(page.locator('.courier-status')).toHaveText('Disponível');

  await page.getByLabel('Latitude da sua posição', { exact: true }).fill('-23.5504');
  await page.getByLabel('Longitude da sua posição', { exact: true }).fill('-46.6333');
  for (const skew of [-3_600_000, 3_600_000]) {
    await page.clock.setSystemTime(new Date(Date.now() + skew));
    const submission = page.waitForResponse(
      (response) =>
        new URL(response.url()).pathname === '/api/v1/courier/positions' &&
        response.request().method() === 'POST',
    );
    await page.getByRole('button', { name: 'Enviar posição', exact: true }).click();
    const response = await submission;
    expect(response.status()).toBe(202);
    const receipt = (await response.json()) as { accepted: number; latestRecordedAt: string };
    expect(receipt.accepted).toBe(1);
    await expect(page.locator('.position-receipt')).toHaveText('Posição confirmada pelo servidor.');
    const snapshot = await page.request.get('/api/v1/courier/me');
    const courier = (await snapshot.json()) as {
      location: { latitude: number; longitude: number };
      locationUpdatedAt: string;
    };
    expect(courier.location).toEqual({ latitude: -23.5504, longitude: -46.6333 });
    expect(Date.parse(courier.locationUpdatedAt)).toBeGreaterThanOrEqual(
      Date.parse(receipt.latestRecordedAt),
    );
  }

  await page.reload();
  await expect(page.getByLabel('Latitude da sua posição', { exact: true })).toHaveValue('-23.5504');
  await expect(page.getByLabel('Longitude da sua posição', { exact: true })).toHaveValue(
    '-46.6333',
  );
  await page.getByRole('button', { name: 'Ficar offline', exact: true }).click();
  await expect(page.locator('.courier-status')).toHaveText('Offline');
  await expect(page.getByRole('button', { name: 'Enviar posição', exact: true })).toBeDisabled();
});
