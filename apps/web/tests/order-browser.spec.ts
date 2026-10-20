import { expect, test } from '@playwright/test';
import type { components } from '../src/generated/api';

type Summary = components['schemas']['OrderSummary'];
const snapshotAt = '2026-10-20T12:00:00Z';
const store: components['schemas']['StoreView'] = {
  id: '00000000-0000-4000-8000-000000000001',
  name: 'Loja da paginação',
  location: { latitude: -23.5614, longitude: -46.6559 },
  serviceAreaId: '00000000-0000-4000-8000-000000000002',
};
const items: Summary[] = Array.from({ length: 12 }, (_, index) => ({
  id: `00000000-0000-4000-8000-${String(index + 10).padStart(12, '0')}`,
  publicCode: `${'AbCd_-'.repeat(5)}${String(index).padStart(2, '0')}`,
  store,
  dropoff: { latitude: -23.5504, longitude: -46.6333 },
  status: index === 11 ? 'DELIVERED' : 'SEARCHING',
  createdAt: `2026-10-20T11:${String(59 - index).padStart(2, '0')}:00Z`,
  slaDeadline: '2026-10-20T13:00:00Z',
  version: 0,
}));

test('pagina com snapshot e busca no conjunto autorizado, preservando a página ao voltar do detalhe', async ({
  page,
}) => {
  const queries: URL[] = [];
  await page.route('**/api/v1/auth/me', (route) =>
    route.fulfill({
      json: {
        authenticated: true,
        user: {
          id: '00000000-0000-4000-8000-000000000003',
          role: 'STORE',
          displayName: 'Loja de teste',
          expiresAt: '2026-10-20T14:00:00Z',
          storeId: store.id,
        },
      },
    }),
  );
  await page.route('**/api/v1/auth/csrf', (route) =>
    route.fulfill({ json: { headerName: 'X-CSRF-TOKEN', token: 'fixture' } }),
  );
  await page.route('**/api/v1/stores/me', (route) => route.fulfill({ json: store }));
  await page.route('https://tiles.openfreemap.org/**', (route) =>
    route.fulfill({ status: 503, body: '' }),
  );
  await page.routeWebSocket('**/ws', (socket) =>
    socket.onMessage((raw) => {
      if (String(raw).startsWith('CONNECT\n'))
        socket.send('CONNECTED\nversion:1.2\nheart-beat:0,0\n\n\0');
    }),
  );
  await page.route('**/api/v1/orders?*', (route) => {
    const url = new URL(route.request().url());
    queries.push(url);
    const status = url.searchParams.get('status');
    const code = url.searchParams.get('code');
    const pageIndex = Number(url.searchParams.get('page'));
    const selected = items.filter(
      (item) => (!status || item.status === status) && (!code || item.publicCode === code),
    );
    return route.fulfill({
      json: {
        items: selected.slice(pageIndex * 10, (pageIndex + 1) * 10),
        page: pageIndex,
        size: 10,
        totalPages: Math.ceil(selected.length / 10),
        totalElements: selected.length,
        snapshotAt,
      },
    });
  });
  await page.route('**/api/v1/orders/*', (route) => {
    const item = items.find((item) => route.request().url().endsWith(item.id));
    return route.fulfill({
      json: {
        ...item,
        timeline: [
          {
            id: 1,
            type: 'ORDER_CREATED',
            fromStatus: null,
            toStatus: 'SEARCHING',
            occurredAt: item!.createdAt,
            orderVersion: 0,
          },
        ],
      },
    });
  });
  await page.route('**/api/v1/tracking/*', (route) =>
    route.fulfill({
      status: 404,
      json: { detail: 'Rastreio indisponível na fixture de paginação.' },
    }),
  );
  await page.goto('/loja');
  await expect(page.getByRole('button', { name: 'Ver pedidos', exact: true })).toBeVisible();
  expect(queries).toHaveLength(0);
  await page.getByRole('button', { name: 'Ver pedidos', exact: true }).click();
  await expect(page.locator('.order-list-button')).toHaveCount(10);
  expect(queries[0]!.searchParams.get('scope')).toBe('OWN');
  expect(queries[0]!.searchParams.has('snapshotAt')).toBe(false);
  await page.getByRole('button', { name: 'Próxima', exact: true }).click();
  await expect(page.locator('.pagination')).toContainText('Página 2 de 2');
  await expect(page.locator('.order-list-button')).toHaveCount(2);
  expect(queries.at(-1)!.searchParams.get('snapshotAt')).toBe(snapshotAt);
  const wanted = items[11]!;
  const row = page.locator(`.order-list-button[data-order-id="${wanted.id}"]`);
  await row.click();
  await expect(page.getByRole('heading', { name: 'Detalhe do pedido', exact: true })).toBeFocused();
  await page.getByRole('button', { name: 'Voltar', exact: true }).click();
  await expect(page.locator('.pagination')).toContainText('Página 2 de 2');
  await expect(row).toBeFocused();
  await page.getByLabel('Código do pedido', { exact: true }).fill(` ${wanted.publicCode} `);
  await page.getByRole('button', { name: 'Buscar pedido', exact: true }).click();
  await expect(page.locator('.order-list-button')).toHaveCount(1);
  await expect(row).toBeVisible();
  expect(queries.at(-1)!.searchParams.get('page')).toBe('0');
  expect(queries.at(-1)!.searchParams.get('code')).toBe(wanted.publicCode);
  expect(queries.at(-1)!.searchParams.has('snapshotAt')).toBe(false);
  await page
    .getByRole('combobox', { name: 'Status do pedido', exact: true })
    .selectOption('DELIVERED');
  await expect(row).toBeVisible();
  await expect.poll(() => queries.at(-1)!.searchParams.get('status')).toBe('DELIVERED');
  await page.getByLabel('Código do pedido', { exact: true }).fill(wanted.publicCode.toLowerCase());
  await page.getByRole('button', { name: 'Buscar pedido', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Nenhum pedido encontrado' })).toBeVisible();
});
