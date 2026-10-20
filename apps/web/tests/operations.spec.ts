import { expect, test } from '@playwright/test';
import { chooseRole, openOrder } from './demo';
import { LocalOperationsFixture } from './local-operations-fixture';

// Internal simulator authentication must not be recorded in request traces.
test.use({ trace: 'off' });

test('redistribui o mesmo pedido com liberação estável e aceite real de outro entregador', async ({
  page,
  baseURL,
}, testInfo) => {
  test.skip(
    process.env.BORA_TEST_OPS_FIXTURE !== 'true',
    'Explicit deterministic local fixture, never a public host.',
  );
  test.setTimeout(300_000);
  const fixture = await LocalOperationsFixture.start(baseURL!);
  try {
    await page.goto('/operacao');
    await chooseRole(page, 'Operação');
    await expect(
      page.getByRole('heading', { name: 'Visão geral da operação', exact: true }),
    ).toBeVisible();
    await expect(page.getByRole('combobox', { name: 'Status do pedido', exact: true })).toHaveCount(
      0,
    );
    await page.getByRole('button', { name: 'Ver pedidos', exact: true }).click();
    await openOrder(page, fixture.orderId);
    const detail = page.locator(`.order-detail[data-order-id="${fixture.orderId}"]`);
    await expect(detail).toBeVisible();
    await expect(detail.locator('.order-status').first()).toHaveAttribute(
      'data-status',
      'ASSIGNED',
    );
    await detail.getByRole('button', { name: 'Redistribuir pedido', exact: true }).click();
    await expect(
      detail.getByRole('heading', { name: 'Redistribuir este pedido?', exact: true }),
    ).toBeFocused();
    let attempts = 0;
    page.on('request', (request) => {
      if (
        request.method() === 'POST' &&
        new URL(request.url()).pathname === `/api/v1/ops/orders/${fixture.orderId}/reassign`
      )
        attempts++;
    });
    const result = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        new URL(response.url()).pathname === `/api/v1/ops/orders/${fixture.orderId}/reassign`,
    );
    await detail.getByRole('button', { name: 'Confirmar redistribuição', exact: true }).click();
    expect((await result).status()).toBe(204);
    // Existing orders are leased and this order excludes its accepted courier by history.
    // AVAILABLE therefore remains stable, without sampling a transient map frame.
    expect((await fixture.assertOldAvailable()).status).toBe('AVAILABLE');
    const searching = await fixture.order();
    expect(searching.status).toBe('SEARCHING');
    expect(searching.courierId).toBeNull();
    const redistributed = searching.timeline
      .filter((event) => event.type === 'ORDER_REASSIGNED')
      .sort((a, b) => a.orderVersion - b.orderVersion)
      .at(-1);
    expect(redistributed).toBeDefined();
    const assigned = await fixture.acceptWithSecondActor();
    expect(assigned.id).toBe(fixture.orderId);
    expect(assigned.status).toBe('ASSIGNED');
    expect(assigned.courierId).toBe(fixture.newCourierId);
    expect(assigned.courierId).not.toBe(fixture.oldCourierId);
    expect(
      assigned.timeline.some(
        (event) =>
          event.type === 'ORDER_ASSIGNED' && event.orderVersion > redistributed!.orderVersion,
      ),
    ).toBe(true);
    expect((await fixture.assertOldAvailable()).status).toBe('AVAILABLE');
    await expect(detail).toBeVisible();
    expect(attempts).toBe(1);
    fixture.assertHealthy();
  } finally {
    try {
      await fixture.close();
    } finally {
      await testInfo.attach('local-reassignment-fixture', {
        body: JSON.stringify(fixture.evidence, null, 2),
        contentType: 'application/json',
      });
    }
  }
});

test('um conflito preserva a intenção de busca sem repetir a redistribuição', async ({
  page,
  baseURL,
}, testInfo) => {
  test.skip(process.env.BORA_TEST_OPS_FIXTURE !== 'true', 'Explicit deterministic local fixture.');
  test.setTimeout(300_000);
  const fixture = await LocalOperationsFixture.start(baseURL!);
  try {
    await page.goto('/operacao');
    await chooseRole(page, 'Operação');
    await page.getByRole('button', { name: 'Ver pedidos', exact: true }).click();
    await page.getByRole('combobox', { name: 'Mostrar', exact: true }).selectOption('PUBLIC');
    await page
      .getByRole('combobox', { name: 'Status do pedido', exact: true })
      .selectOption('ASSIGNED');
    await page.getByLabel('Código do pedido', { exact: true }).fill(fixture.publicCode);
    await page.getByRole('button', { name: 'Buscar pedido', exact: true }).click();
    const row = page.locator(`.order-list-button[data-order-id="${fixture.orderId}"]`);
    await expect(row).toBeVisible();
    await row.click();
    const detail = page.locator(`.order-detail[data-order-id="${fixture.orderId}"]`);
    let attempts = 0;
    await page.route(`**/api/v1/ops/orders/${fixture.orderId}/reassign`, async (route) => {
      attempts++;
      await route.fulfill({
        status: 409,
        contentType: 'application/problem+json',
        body: JSON.stringify({
          type: 'about:blank',
          title: 'Conflict',
          status: 409,
          code: 'ORDER_CONFLICT',
          detail: 'O pedido mudou. Consulte a etapa atual antes de agir novamente.',
        }),
      });
    });
    await detail.getByRole('button', { name: 'Redistribuir pedido', exact: true }).click();
    await detail.getByRole('button', { name: 'Confirmar redistribuição', exact: true }).click();
    await expect(detail.getByRole('alert')).toContainText('O pedido mudou.');
    await expect(detail).toBeVisible();
    await page.waitForTimeout(1_000);
    expect(attempts).toBe(1);
    await detail.getByRole('button', { name: 'Voltar', exact: true }).click();
    await expect(page.getByRole('combobox', { name: 'Mostrar', exact: true })).toHaveValue(
      'PUBLIC',
    );
    await expect(page.getByRole('combobox', { name: 'Status do pedido', exact: true })).toHaveValue(
      'ASSIGNED',
    );
    await expect(page.getByLabel('Código do pedido', { exact: true })).toHaveValue(
      fixture.publicCode,
    );
    await expect(row).toBeFocused();
    expect((await fixture.order()).courierId).toBe(fixture.oldCourierId);
    fixture.assertHealthy();
  } finally {
    try {
      await fixture.close();
    } finally {
      await testInfo.attach('local-conflict-fixture', {
        body: JSON.stringify(fixture.evidence, null, 2),
        contentType: 'application/json',
      });
    }
  }
});
