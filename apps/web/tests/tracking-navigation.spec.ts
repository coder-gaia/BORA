import { expect, test, type WebSocketRoute } from '@playwright/test';
import type { components } from '../src/generated/api';

type Snapshot = components['schemas']['TrackingSnapshot'];
const code = 'AbCd_-'.repeat(5) + 'Xy';
const createdAt = '2026-10-20T12:00:00Z';
const initial: Snapshot = {
  status: 'ASSIGNED',
  version: 1,
  storeName: 'Loja do rastreio',
  pickup: { latitude: -23.5614, longitude: -46.6559 },
  dropoff: { latitude: -23.5504, longitude: -46.6333 },
  courierPosition: { latitude: -23.562, longitude: -46.656 },
  courierPositionUpdatedAt: '2026-10-20T12:01:00Z',
  createdAt,
  timeline: [
    {
      type: 'ORDER_CREATED',
      fromStatus: null,
      toStatus: 'SEARCHING',
      occurredAt: createdAt,
      orderVersion: 0,
    },
    {
      type: 'ORDER_ASSIGNED',
      fromStatus: 'SEARCHING',
      toStatus: 'ASSIGNED',
      occurredAt: '2026-10-20T12:01:00Z',
      orderVersion: 1,
    },
  ],
  route: {
    destination: 'PICKUP',
    geometry: [
      { latitude: -23.562, longitude: -46.656 },
      { latitude: -23.5614, longitude: -46.6559 },
    ],
    distanceMeters: 80,
    durationMillis: 30_000,
    simulatedDurationMillis: 10_000,
    calculatedAt: '2026-10-20T12:01:00Z',
  },
  etaSeconds: 10,
  serverTime: '2026-10-20T12:01:00Z',
};

test('abre e recarrega rastreio anônimo com código base64url, atualiza pelo socket e não revive quadros antigos', async ({
  page,
}) => {
  const errors: string[] = [];
  let logins = 0;
  let current = initial;
  let socket: WebSocketRoute | undefined;
  let subscription = '';
  let messageId = 0;
  function send(value: Snapshot) {
    socket?.send(
      `MESSAGE\nsubscription:${subscription}\nmessage-id:${++messageId}\ndestination:/topic/tracking/${code}\ncontent-type:application/json\n\n${JSON.stringify(value)}\0`,
    );
  }
  page.on('pageerror', (error) => errors.push(error.message));
  page.on('console', (message) => {
    if (message.type() === 'error' && /hydration|hydrating|Minified React/i.test(message.text()))
      errors.push(message.text());
  });
  page.on('request', (request) => {
    if (new URL(request.url()).pathname === '/api/v1/auth/demo') logins++;
  });
  await page.route(`**/api/v1/tracking/${code}`, (route) => route.fulfill({ json: current }));
  await page.route('**/api/v1/auth/csrf', (route) =>
    route.fulfill({ json: { headerName: 'X-CSRF-TOKEN', token: 'tracking-fixture' } }),
  );
  await page.route('https://tiles.openfreemap.org/**', (route) =>
    route.fulfill({ status: 503, body: '' }),
  );
  await page.routeWebSocket('**/ws', (route) => {
    socket = route;
    route.onMessage((raw) => {
      const message = String(raw);
      if (message.startsWith('CONNECT\n'))
        route.send('CONNECTED\nversion:1.2\nheart-beat:0,0\n\n\0');
      if (message.startsWith('SUBSCRIBE\n')) {
        subscription = /\nid:([^\n]+)/.exec(message)?.[1] ?? '';
        send(current);
      }
    });
  });
  await page.goto(`/rastreio/${code}`);
  await expect(page.getByRole('heading', { name: 'Cada etapa, no seu tempo.' })).toBeVisible();
  await expect(page.locator('#tracking-content').getByRole('status')).toHaveText(
    'Entregador a caminho da loja',
  );
  const pickup = page.getByRole('definition').filter({ hasText: 'Loja do rastreio' });
  await expect(pickup).toContainText('Loja do rastreio');
  await expect(pickup).toContainText('-23.5614, -46.6559');
  await expect(page.getByText('Código de confirmação', { exact: true })).toHaveCount(0);
  await expect(page.getByText('O mapa não carregou.', { exact: false })).toBeVisible();
  await expect.poll(() => subscription).not.toBe('');
  const pickedUp: Snapshot = {
    ...initial,
    status: 'PICKED_UP',
    version: 3,
    serverTime: '2026-10-20T12:04:00Z',
    courierPosition: { latitude: -23.554, longitude: -46.643 },
    courierPositionUpdatedAt: '2026-10-20T12:04:00Z',
    route: { ...initial.route!, destination: 'DROPOFF', calculatedAt: '2026-10-20T12:04:00Z' },
    timeline: [
      ...initial.timeline,
      {
        type: 'ORDER_AT_STORE',
        fromStatus: 'ASSIGNED',
        toStatus: 'AT_STORE',
        occurredAt: '2026-10-20T12:02:00Z',
        orderVersion: 2,
      },
      {
        type: 'ORDER_PICKED_UP',
        fromStatus: 'AT_STORE',
        toStatus: 'PICKED_UP',
        occurredAt: '2026-10-20T12:03:00Z',
        orderVersion: 3,
      },
    ],
  };
  current = pickedUp;
  send(pickedUp);
  await expect(page.locator('#tracking-content').getByRole('status')).toHaveText(
    'A caminho do cliente',
  );
  send(initial);
  await expect(page.locator('#tracking-content').getByRole('status')).toHaveText(
    'A caminho do cliente',
  );
  await page.reload();
  await expect(page.locator('#tracking-content').getByRole('status')).toHaveText(
    'A caminho do cliente',
  );
  expect(logins).toBe(0);
  expect(errors).toEqual([]);
  await page.getByRole('link', { name: 'Conhecer o Bora' }).click();
  await expect(page.getByRole('heading', { name: /Cada entrega/ })).toBeVisible();
  await page.goBack();
  await expect(page.locator('#tracking-content').getByRole('status')).toHaveText(
    'A caminho do cliente',
  );
  expect(errors).toEqual([]);
});

test('revalida o rastreio conectado e limpa os dados quando o link expira', async ({ page }) => {
  await page.clock.install();
  // Keep the prerendered home visible while the first two mounting frames are delayed.
  await page.addInitScript(() => {
    const frame = window.requestAnimationFrame.bind(window);
    let mountingFrames = 2;
    window.requestAnimationFrame = (callback) =>
      mountingFrames-- > 0
        ? frame((time) => window.setTimeout(() => callback(time), 150))
        : frame(callback);
  });
  let expired = false;
  let reads = 0;
  let subscribed = false;
  await page.route(`**/api/v1/tracking/${code}`, (route) => {
    reads++;
    return expired
      ? route.fulfill({ status: 404, json: { detail: 'Rastreio não encontrado.' } })
      : route.fulfill({ json: initial });
  });
  await page.route('**/api/v1/auth/csrf', (route) =>
    route.fulfill({ json: { headerName: 'X-CSRF-TOKEN', token: 'fixture' } }),
  );
  await page.route('https://tiles.openfreemap.org/**', (route) =>
    route.fulfill({ status: 503, body: '' }),
  );
  await page.routeWebSocket('**/ws', (socket) =>
    socket.onMessage((raw) => {
      const message = String(raw);
      if (message.startsWith('CONNECT\n'))
        socket.send('CONNECTED\nversion:1.2\nheart-beat:0,0\n\n\0');
      if (message.startsWith('SUBSCRIBE\n')) subscribed = true;
    }),
  );
  await page.goto(`/rastreio/${code}`);
  await expect(page.locator('#tracking-content').getByRole('status')).toHaveText(
    'Entregador a caminho da loja',
  );
  await expect(page.getByRole('definition').filter({ hasText: 'Loja do rastreio' })).toBeVisible();
  await expect.poll(() => subscribed).toBe(true);
  const before = reads;
  expired = true;
  await page.clock.fastForward(31_000);
  await expect(page.getByRole('alert')).toContainText('Este link de rastreio não está disponível');
  expect(reads).toBeGreaterThan(before);
  await expect(page.getByRole('definition').filter({ hasText: 'Loja do rastreio' })).toHaveCount(0);
  await expect(page.getByRole('region', { name: 'Linha do tempo', exact: true })).toHaveCount(0);
});
