import type { Page, WebSocketRoute } from '@playwright/test';
import type { components } from '../src/generated/api';

type Schemas = components['schemas'];
type Summary = Schemas['OrderSummary'];
type Order = Schemas['OrderView'];
type Tracking = Schemas['TrackingSnapshot'];
type Metrics = Schemas['OperationsMetrics'];
type Origin = 'OWN' | 'PUBLIC';
const epoch = Date.parse('2026-10-20T13:00:00Z');
const at = (offsetSeconds: number) => new Date(epoch + offsetSeconds * 1_000).toISOString();
const id = (value: number) => `00000000-0000-4000-8000-${String(value).padStart(12, '0')}`;
const ownStore: Schemas['StoreView'] = {
  id: id(1),
  name: 'Minha loja do contexto',
  location: { latitude: -23.5614, longitude: -46.6559 },
  serviceAreaId: id(2),
};
const publicStore: Schemas['StoreView'] = { ...ownStore, id: id(3), name: 'Cozinha Bora Paulista' };
const user: Schemas['DemoUserView'] = {
  id: id(4),
  role: 'OPERATIONS',
  displayName: 'Operação do teste visual',
  expiresAt: at(7_200),
  storeId: null,
  courierId: null,
};
const statuses = [
  'SEARCHING',
  'SEARCHING',
  'SEARCHING',
  'ASSIGNED',
  'PICKED_UP',
  'DELIVERED',
] as const;
const created = [-300, -180, -1_200, -1_500, -1_800, -2_100];
const waits = [-300, -180, -60];
const deadlines = [120, -60, 600, 240, -120, -300];
const origins: Origin[] = ['OWN', 'PUBLIC', 'OWN', 'PUBLIC', 'PUBLIC', 'OWN'];
const summaries: Summary[] = statuses.map((status, index) => ({
  id: id(10 + index),
  publicCode: `AbCd_-${String(index).padStart(2, '0')}${'Xy'.repeat(12)}`,
  store: origins[index] === 'OWN' ? ownStore : publicStore,
  dropoff: { latitude: -23.5504, longitude: -46.6333 },
  status,
  courierId: status === 'ASSIGNED' || status === 'PICKED_UP' ? id(100 + index) : null,
  createdAt: at(created[index]!),
  slaDeadline: at(deadlines[index]!),
  searchingSince: status === 'SEARCHING' ? at(waits[index]!) : null,
  version: index === 2 ? 2 : status === 'SEARCHING' ? 0 : index === 3 ? 1 : index === 4 ? 3 : 4,
}));
function order(summary: Summary): Order {
  const created: Schemas['OrderEventView'] = {
    id: 1,
    type: 'ORDER_CREATED',
    fromStatus: null,
    toStatus: 'SEARCHING',
    occurredAt: summary.createdAt,
    orderVersion: 0,
  };
  const timeline = [created];
  if (summary.id === id(12))
    timeline.push(
      {
        id: 2,
        type: 'ORDER_ASSIGNED',
        fromStatus: 'SEARCHING',
        toStatus: 'ASSIGNED',
        occurredAt: at(-900),
        orderVersion: 1,
      },
      {
        id: 3,
        type: 'ORDER_REASSIGNED',
        fromStatus: 'ASSIGNED',
        toStatus: 'SEARCHING',
        occurredAt: summary.searchingSince!,
        orderVersion: 2,
      },
    );
  else if (summary.status !== 'SEARCHING') {
    timeline.push({
      id: 2,
      type: 'ORDER_ASSIGNED',
      fromStatus: 'SEARCHING',
      toStatus: 'ASSIGNED',
      occurredAt: at(-840),
      orderVersion: 1,
    });
    if (summary.status === 'PICKED_UP' || summary.status === 'DELIVERED')
      timeline.push(
        {
          id: 3,
          type: 'ORDER_AT_STORE',
          fromStatus: 'ASSIGNED',
          toStatus: 'AT_STORE',
          occurredAt: at(-600),
          orderVersion: 2,
        },
        {
          id: 4,
          type: 'ORDER_PICKED_UP',
          fromStatus: 'AT_STORE',
          toStatus: 'PICKED_UP',
          occurredAt: at(-300),
          orderVersion: 3,
        },
      );
    if (summary.status === 'DELIVERED')
      timeline.push({
        id: 5,
        type: 'ORDER_DELIVERED',
        fromStatus: 'PICKED_UP',
        toStatus: 'DELIVERED',
        occurredAt: at(-60),
        orderVersion: 4,
      });
  }
  return {
    id: summary.id,
    publicCode: summary.publicCode,
    store: summary.store,
    dropoff: summary.dropoff,
    status: summary.status,
    courierId: summary.courierId,
    createdAt: summary.createdAt,
    slaDeadline: summary.slaDeadline,
    version: summary.version,
    confirmationCode: null,
    timeline,
  };
}

type SocketState = { route: WebSocketRoute; subscriptions: Map<string, string>; closed: boolean };
export async function operationsVisualFixture(page: Page) {
  const started = performance.now();
  const now = () => new Date(epoch + performance.now() - started).toISOString();
  const sockets: SocketState[] = [];
  const queries: URL[] = [];
  const unexpected: string[] = [];
  const viewports: { scope?: string }[] = [];
  const mutations: { path: string; body: unknown }[] = [];
  let logins = 0,
    messageId = 0;
  let denied = false;
  let sessionHeld = false;
  let releaseSession = () => {};
  let sessionGate = Promise.resolve();
  const points = Array.from({ length: 120 }, (_, index) => [
    id(100 + index),
    -46.645 + (index % 12) * 0.00004,
    -23.556 + Math.floor(index / 12) * 0.00004,
    index === 3 || index === 4 || index % 3 === 0
      ? 'BUSY'
      : index % 3 === 1
        ? 'OFFERED'
        : 'AVAILABLE',
    0,
  ]);
  const frame = () => ({ serverTime: now(), points });
  const metrics = (): Metrics => ({
    activeOrders: 5,
    searchingOrders: 3,
    searchingOverThreeMinutes: 2,
    overdueOrders: 2,
    dueWithinFiveMinutes: 2,
    deliveredLastHour: 1,
    serverTime: now(),
  });
  const tracking = (summary: Summary): Tracking => ({
    status: summary.status,
    version: summary.version,
    storeName: summary.store.name,
    pickup: summary.store.location,
    dropoff: summary.dropoff,
    courierPosition: summary.courierId ? { latitude: -23.556, longitude: -46.645 } : null,
    courierPositionUpdatedAt: summary.courierId ? now() : null,
    createdAt: summary.createdAt,
    timeline: order(summary).timeline.map((event) => ({
      type: event.type,
      fromStatus: event.fromStatus ?? null,
      toStatus: event.toStatus,
      occurredAt: event.occurredAt,
      orderVersion: event.orderVersion,
    })),
    route:
      summary.status === 'ASSIGNED'
        ? {
            destination: 'PICKUP',
            geometry: [{ latitude: -23.556, longitude: -46.645 }, summary.store.location],
            distanceMeters: 1_100,
            durationMillis: 90_000,
            simulatedDurationMillis: 30_000,
            calculatedAt: now(),
          }
        : null,
    etaSeconds: summary.status === 'ASSIGNED' ? 30 : null,
    serverTime: now(),
  });
  function send(socket: SocketState, subscription: string, destination: string, body: unknown) {
    if (socket.closed) return;
    socket.route.send(
      `MESSAGE\nsubscription:${subscription}\nmessage-id:${++messageId}\ndestination:${destination}\ncontent-type:application/json\n\n${JSON.stringify(body)}\0`,
    );
  }
  await page.routeWebSocket('**/ws', (route) => {
    const socket: SocketState = { route, subscriptions: new Map(), closed: false };
    sockets.push(socket);
    route.onClose(() => {
      socket.closed = true;
    });
    route.onMessage((raw) => {
      const value = String(raw);
      if (value.startsWith('CONNECT\n')) route.send('CONNECTED\nversion:1.2\nheart-beat:0,0\n\n\0');
      if (value.startsWith('SEND\n') && value.includes('destination:/app/viewport')) {
        const body = value.split('\n\n')[1]?.split('\0')[0] || '{}';
        viewports.push(JSON.parse(body) as { scope?: string });
      }
      if (value.startsWith('SUBSCRIBE\n')) {
        const subscription = /\nid:([^\n]+)/.exec(value)?.[1] ?? '';
        const destination = /\ndestination:([^\n]+)/.exec(value)?.[1] ?? '';
        socket.subscriptions.set(subscription, destination);
        if (destination === '/user/queue/map') send(socket, subscription, destination, frame());
        const matched = summaries.find((item) => destination === `/topic/orders/${item.id}`);
        if (matched) send(socket, subscription, destination, order(matched));
        const track = summaries.find(
          (item) => destination === `/topic/tracking/${item.publicCode}`,
        );
        if (track) send(socket, subscription, destination, tracking(track));
      }
    });
  });
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request();
    const url = new URL(request.url()),
      path = url.pathname;
    if (path === '/api/v1/auth/demo' || path === '/api/v1/auth/simulator') logins++;
    if (path === '/api/v1/auth/me') {
      if (sessionHeld) await sessionGate;
      return route.fulfill({
        json: denied ? { authenticated: false, user: null } : { authenticated: true, user },
      });
    }
    if (path === '/api/v1/auth/csrf')
      return route.fulfill({
        json: { headerName: 'X-CSRF-TOKEN', token: 'operations-visual-fixture' },
      });
    if (path === '/api/v1/ops/metrics' || path === '/api/v1/ops/snapshot') {
      if (denied)
        return route.fulfill({
          status: 403,
          contentType: 'application/problem+json',
          json: {
            type: 'about:blank',
            title: 'Forbidden',
            status: 403,
            code: 'DEMO_EXPIRED',
            detail: 'Sua demonstração expirou.',
          },
        });
      return route.fulfill({ json: path.endsWith('/metrics') ? metrics() : frame() });
    }
    if (path === '/api/v1/orders' && request.method() === 'GET') {
      queries.push(url);
      const scope = url.searchParams.get('scope') || 'ALL',
        status = url.searchParams.get('status');
      const code = url.searchParams.get('code'),
        sort = url.searchParams.get('sort');
      let items = summaries.filter(
        (item, index) =>
          (scope === 'ALL' || origins[index] === scope) &&
          (!status || status === item.status) &&
          (!code || code === item.publicCode),
      );
      if (sort === 'WAITING')
        items = [...items].sort(
          (a, b) => Date.parse(a.searchingSince!) - Date.parse(b.searchingSince!),
        );
      else items = [...items].sort((a, b) => Date.parse(b.createdAt) - Date.parse(a.createdAt));
      const pageIndex = Number(url.searchParams.get('page') || 0),
        size = Number(url.searchParams.get('size') || 10);
      return route.fulfill({
        json: {
          items: items.slice(pageIndex * size, (pageIndex + 1) * size),
          page: pageIndex,
          size,
          totalElements: items.length,
          totalPages: Math.ceil(items.length / size),
          snapshotAt: url.searchParams.get('snapshotAt') || now(),
        },
      });
    }
    const detail = summaries.find((item) => path === `/api/v1/orders/${item.id}`);
    if (detail && request.method() === 'GET') return route.fulfill({ json: order(detail) });
    const current = summaries.find((item) => path === `/api/v1/tracking/${item.publicCode}`);
    if (current) return route.fulfill({ json: tracking(current) });
    if (request.method() === 'POST' && (path.endsWith('/reassign') || path.endsWith('/cancel'))) {
      mutations.push({ path, body: request.postDataJSON() });
      return route.fulfill({
        status: 409,
        contentType: 'application/problem+json',
        json: {
          type: 'about:blank',
          title: 'Conflict',
          status: 409,
          code: 'ORDER_CONFLICT',
          detail: 'O pedido mudou. Reveja a etapa atual antes de agir novamente.',
        },
      });
    }
    unexpected.push(`${request.method()} ${path}`);
    return route.fulfill({
      status: 501,
      json: { detail: 'Unexpected API request in isolated visual fixture.' },
    });
  });
  return {
    queries,
    unexpected,
    viewports,
    mutations,
    get logins() {
      return logins;
    },
    assigned: summaries[3]!,
    waitingIds: [id(10), id(11), id(12)],
    publishOrdersChanged() {
      for (const socket of sockets)
        for (const [subscription, destination] of socket.subscriptions) {
          if (destination === '/user/queue/events')
            send(socket, subscription, destination, {
              eventId: id(900 + messageId),
              type: 'ORDERS_CHANGED',
            });
        }
    },
    revoke() {
      denied = true;
      sessionHeld = true;
      sessionGate = new Promise<void>((resolve) => {
        releaseSession = resolve;
      });
    },
    releaseRevokedSession() {
      sessionHeld = false;
      releaseSession();
    },
    get closedMapSockets() {
      return sockets.filter(
        (socket) => socket.closed && [...socket.subscriptions.values()].includes('/user/queue/map'),
      ).length;
    },
    summary: {
      serverAnchor: new Date(epoch).toISOString(),
      notebookAnchor: at(-7_200),
      points: points.length,
      metrics: [5, 3, 2, 2, 2, 1],
      mockAuthentication: true,
    },
  };
}
