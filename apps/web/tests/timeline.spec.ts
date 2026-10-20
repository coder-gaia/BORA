import { expect, test, type Request } from '@playwright/test';
import type { components } from '../src/generated/api';
import { chooseRole } from './demo';

type Order = components['schemas']['OrderView'];

test('navega pelas etapas e alcança o registro do pedido pelo teclado sem nova consulta', async ({
  page,
}) => {
  await page.goto('/');
  await chooseRole(page, 'Sou loja');
  await expect(page.locator('.connection-status')).toHaveAttribute('data-connection', 'connected');
  const create = page.getByRole('button', { name: 'Criar pedido', exact: true });
  await expect(create).toBeEnabled();
  const creation = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      new URL(response.url()).pathname === '/api/v1/orders',
  );
  await create.click();
  const response = await creation;
  expect(response.status()).toBe(201);
  const created = (await response.json()) as Order;
  expect(created.status).toBe('SEARCHING');
  expect(Boolean(created.confirmationCode)).toBe(true);
  const createdEvent = created.timeline.find((event) => event.type === 'ORDER_CREATED');
  expect(createdEvent).toBeDefined();
  await page.getByRole('button', { name: 'Acompanhar pedido', exact: true }).click();

  const detail = page.getByRole('region', { name: 'Detalhe do pedido', exact: true });
  await expect(detail.getByRole('status')).toHaveText('Procurando entregador');
  const progress = detail.getByRole('region', { name: 'Linha do tempo', exact: true });
  const steps = progress.getByRole('list', { name: 'Etapas da entrega', exact: true });
  const history = progress.locator('details.delivery-history');
  const description = progress.locator('.delivery-step-description');
  const cancel = detail.getByRole('button', { name: 'Cancelar pedido', exact: true });
  await expect(steps.getByRole('listitem')).toHaveCount(5);
  await expect(history).toHaveJSProperty('open', false);
  await expect(cancel).toBeEnabled();

  // A conexão já está ativa e o snapshot do detalhe terminou. A janela abaixo contém
  // somente navegação local; os eventos e a reconciliação reais continuam habilitados.
  const detailQueries: string[] = [];
  const observeDetail = (request: Request) => {
    if (
      request.method() === 'GET' &&
      new URL(request.url()).pathname === `/api/v1/orders/${created.id}`
    ) {
      detailQueries.push(request.url());
    }
  };
  page.on('request', observeDetail);
  try {
    const delivered = steps.getByRole('button', { name: /^Entregue\b/ });
    await delivered.click();
    await expect(delivered).toHaveAttribute('aria-pressed', 'true');
    await expect(description.locator('strong')).toHaveText('Entregue');
    await expect(description).toContainText('A confirmação no destino concluirá a entrega.');
    await expect(description.locator('time')).toHaveCount(0);
    await expect(
      description.getByRole('button', { name: 'Ver registro no histórico', exact: true }),
    ).toHaveCount(0);
    await expect(history).toHaveJSProperty('open', false);

    const searching = steps.getByRole('button', { name: /^Busca por entregador\b/ });
    await searching.focus();
    await page.keyboard.press('Enter');
    await expect(searching).toHaveAttribute('aria-pressed', 'true');
    const historyLink = description.getByRole('button', {
      name: 'Ver registro no histórico',
      exact: true,
    });
    await historyLink.focus();
    await page.keyboard.press('Enter');
    await expect(history).toHaveJSProperty('open', true);
    const event = history.locator(`li[id$="-event-${createdEvent!.id}"]`);
    await expect(event).toBeFocused();
    await expect(event).toContainText('Pedido criado');
    await expect(event.locator('time')).toHaveAttribute('datetime', createdEvent!.occurredAt);
    expect(detailQueries).toEqual([]);
  } finally {
    page.off('request', observeDetail);
  }

  const cancellation = page.waitForResponse(
    (value) =>
      value.request().method() === 'POST' &&
      new URL(value.url()).pathname === `/api/v1/orders/${created.id}/cancel`,
  );
  await cancel.click();
  expect((await cancellation).status()).toBe(200);
  await expect(detail.getByRole('status')).toHaveText('Cancelado');
  await expect(steps.locator('li[data-state="interrupted"]')).toHaveCount(1);
  await expect(steps.locator('li[data-state="not-reached"]')).toHaveCount(4);
  await expect(steps.locator('li[data-state="completed"]')).toHaveCount(0);
  await expect(history).toHaveJSProperty('open', true);
  await expect(history.locator('.order-timeline')).toContainText('Pedido cancelado');
});
