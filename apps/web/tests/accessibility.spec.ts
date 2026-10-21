import { expect, test, type Page } from '@playwright/test';
import type { components } from '../src/generated/api';
import { auditAccessibility } from './accessibility';
import { chooseRole } from './demo';

// Retain explicit audit evidence without recording authenticated session cookies.
test.use({ trace: 'off' });

type Order = components['schemas']['OrderView'];

for (const width of [320, 390, 1440])
  test(`axe nas telas principais com API e WebSocket reais em ${width}px`, async ({
    page,
    browser,
    baseURL,
  }, testInfo) => {
    test.setTimeout(240_000);
    const origin = new URL(baseURL!);
    expect(['localhost', '127.0.0.1']).toContain(origin.hostname);
    expect(origin.protocol).toBe('http:');
    testInfo.annotations.push({
      type: 'integration',
      description: 'Compose local real; sem interceptar API, autenticação, mapa ou WebSocket.',
    });
    await page.setViewportSize({ width, height: 900 });
    const errors: string[] = [];
    function observe(target: Page) {
      target.on('pageerror', (error) => errors.push(error.message));
      target.on('console', (message) => {
        if (message.type() === 'error') errors.push(message.text());
      });
    }
    observe(page);
    let created: Order | null = null;
    try {
      await page.goto('/');
      await expect(page.getByRole('heading', { name: /Cada entrega começa/ })).toBeVisible();
      await expect(page.locator('.preview-map canvas.public-map-couriers')).toBeVisible();
      await auditAccessibility(page, testInfo, 'home');
      await page.goto('/loja');
      await expect(page.getByRole('button', { name: 'Sou loja', exact: true })).toBeEnabled();
      await auditAccessibility(page, testInfo, 'loja-anonima');
      await chooseRole(page, 'Sou loja');
      await expect(page.locator('.connection-status')).toHaveAttribute(
        'data-connection',
        'connected',
      );
      await expect(page.getByRole('button', { name: 'Criar pedido', exact: true })).toBeEnabled();
      await auditAccessibility(page, testInfo, 'loja-criacao');
      const creation = page.waitForResponse(
        (response) =>
          response.request().method() === 'POST' &&
          new URL(response.url()).pathname === '/api/v1/orders',
      );
      await page.getByRole('button', { name: 'Criar pedido', exact: true }).click();
      const response = await creation;
      expect(response.status()).toBe(201);
      created = (await response.json()) as Order;
      await expect(page.getByRole('heading', { name: 'Pedido criado', exact: true })).toBeVisible();
      await auditAccessibility(page, testInfo, 'loja-criado');
      await page.getByRole('button', { name: 'Acompanhar pedido', exact: false }).click();
      await expect(page.getByRole('heading', { name: 'Detalhe do pedido' })).toBeFocused();
      await auditAccessibility(page, testInfo, 'loja-detalhe');
      const anonymous = await browser.newContext({
        baseURL,
        viewport: { width, height: 900 },
        locale: 'pt-BR',
      });
      try {
        const tracking = await anonymous.newPage();
        observe(tracking);
        const me = await anonymous.request.get('/api/v1/auth/me');
        expect((await me.json()) as unknown).toMatchObject({ authenticated: false });
        await tracking.goto(`/rastreio/${created.publicCode}`);
        await expect(
          tracking.getByRole('heading', { name: 'Cada etapa, no seu tempo.' }),
        ).toBeVisible();
        await expect(tracking.getByRole('status')).toHaveText('Procurando entregador');
        await expect(tracking.getByText('Atualizações ao vivo.', { exact: true })).toBeVisible();
        await auditAccessibility(tracking, testInfo, 'rastreio-anonimo');
      } finally {
        await anonymous.close();
      }
      // Its own context keeps the courier available for this training, without a race
      // against the store's still-searching order in the other context.
      const courierContext = await browser.newContext({
        baseURL,
        viewport: { width, height: 900 },
        locale: 'pt-BR',
      });
      try {
        const courier = await courierContext.newPage();
        observe(courier);
        await courier.goto('/entregador');
        await chooseRole(courier, 'Sou entregador');
        await expect(courier.locator('.connection-status')).toHaveAttribute(
          'data-connection',
          'connected',
        );
        await expect(
          courier.getByRole('heading', { name: 'Seu trabalho como entregador' }),
        ).toBeVisible();
        await courier.getByLabel('Latitude da sua posição', { exact: true }).fill('-23.5799');
        await courier.getByLabel('Longitude da sua posição', { exact: true }).fill('-46.6671');
        await courier.getByRole('button', { name: 'Ficar online', exact: true }).click();
        await auditAccessibility(courier, testInfo, 'entregador-disponivel');
        await courier.getByRole('button', { name: 'Criar pedido de treino', exact: true }).click();
        await expect(
          courier.getByRole('button', { name: 'Aceitar oferta', exact: true }),
        ).toBeEnabled();
        await auditAccessibility(courier, testInfo, 'entregador-oferta');
        await courier.getByRole('button', { name: 'Aceitar oferta', exact: true }).click();
        await expect(
          courier.getByRole('button', { name: 'Dirigir até a loja', exact: true }),
        ).toBeEnabled();
        await auditAccessibility(courier, testInfo, 'entregador-em-entrega');
        await courier.getByRole('button', { name: 'Encerrar treino', exact: true }).click();
        await expect(courier.getByText('Estado: Cancelado.', { exact: false })).toBeVisible();
      } finally {
        await courierContext.close();
      }
      await page.goto('/operacao');
      await chooseRole(page, 'Operação');
      await expect(page.locator('.connection-status')).toHaveAttribute(
        'data-connection',
        'connected',
      );
      await expect(page.locator('.operations-indicators > div')).toHaveCount(6);
      await auditAccessibility(page, testInfo, 'operacao-visao-geral');
      await page.getByRole('button', { name: 'Ver pedidos', exact: true }).click();
      await page.getByRole('combobox', { name: 'Mostrar', exact: true }).selectOption('OWN');
      await page.getByLabel('Código do pedido', { exact: true }).fill(created.publicCode);
      await page.getByRole('button', { name: 'Buscar pedido', exact: true }).click();
      const row = page.locator(`.order-list-button[data-order-id="${created.id}"]`);
      await expect(row).toBeVisible();
      await auditAccessibility(page, testInfo, 'operacao-pedidos');
      await row.click();
      await page.getByRole('button', { name: 'Cancelar pedido', exact: true }).click();
      await expect(page.getByRole('heading', { name: 'Cancelar este pedido?' })).toBeFocused();
      await auditAccessibility(page, testInfo, 'operacao-confirmacao');
      await page.getByRole('button', { name: 'Confirmar cancelamento', exact: true }).click();
      await expect(page.locator('.order-detail .order-status')).toHaveText('Cancelado');
      expect(errors).toEqual([]);
    } finally {
      // Only this test's order is canceled, via its authenticated context. No SQL or
      // global cleanup occurs. The regular expiry covers a test stopped before login.
      if (created) {
        const current = await page.request.get(`/api/v1/orders/${created.id}`);
        if (current.ok()) {
          const order = (await current.json()) as Order;
          if (['SEARCHING', 'ASSIGNED', 'AT_STORE'].includes(order.status)) {
            const csrf = (await (await page.request.get('/api/v1/auth/csrf')).json()) as {
              headerName: string;
              token: string;
            };
            await page.request.post(`/api/v1/orders/${created.id}/cancel`, {
              data: { expectedVersion: order.version },
              headers: { [csrf.headerName]: csrf.token },
            });
          }
        }
      }
      await testInfo.attach('browser-errors', {
        body: JSON.stringify(errors, null, 2),
        contentType: 'application/json',
      });
    }
  });
