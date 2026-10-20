import { expect, type Page } from '@playwright/test';
import type { components } from '../src/generated/api';
type Schemas = components['schemas'];
const ids = {
  user: '00000000-0000-4000-8000-000000000001',
  order: '00000000-0000-4000-8000-000000000002',
  offer: '00000000-0000-4000-8000-000000000003',
};
const start = { latitude: -23.5799, longitude: -46.6671 };
const store: Schemas['StoreView'] = {
  id: '00000000-0000-4000-8000-000000000004',
  name: 'Loja do treino',
  location: { latitude: -23.5614, longitude: -46.6559 },
  serviceAreaId: '00000000-0000-4000-8000-000000000005',
};

export async function trainingFixture(page: Page, offset = 0) {
  let elapsed = 0;
  const time = () => new Date(Date.now() + offset + elapsed).toISOString();
  const courier: Schemas['CourierView'] = {
    id: ids.user,
    name: 'Entregador do teste',
    status: 'AVAILABLE',
    location: start,
    locationUpdatedAt: time(),
    version: 1,
  };
  let order: Schemas['AssignedOrderView'] | null = null;
  let offer: Schemas['CourierOffer'] | null = null;
  let receipt: Schemas['TrainingOrderReceipt'] | null = null;
  const keys: string[] = [];
  const details: string[] = [];
  const subscriptions: string[] = [];
  const receipts = new Map<string, Schemas['TrainingOrderReceipt']>();
  const history = new Map<string, Schemas['AssignedOrderView']>();
  let currentKey = '';
  function rememberReceipt() {
    if (receipt) receipts.set(currentKey, receipt);
  }
  function rememberOrder() {
    if (order) history.set(order.id, { ...order });
  }
  function setOrderStatus(status: Schemas['AssignedOrderView']['status']) {
    if (!order) return;
    order = { ...order, status, version: order.version + 1 };
    receipt = { ...receipt!, status, version: order.version };
    rememberOrder();
    rememberReceipt();
  }
  let committed = 0;
  let loseResponse = false;
  let holdRoute = false;
  let releaseRoute: (() => void) | undefined;
  const heldRoutes: (() => void)[] = [];
  let holdGps = false;
  let releaseGps: (() => void) | undefined;
  let pendingPoint: { position: Schemas['GeoPoint']; recordedAt: string } | null = null;
  let autoPersist = true;
  let holdNextWork = false;
  let releaseWork: (() => void) | undefined;
  const gps: Schemas['GeoPoint'][] = [];
  await page.route('**/api/v1/auth/me', (route) =>
    route.fulfill({
      json: {
        authenticated: true,
        user: {
          id: ids.user,
          role: 'COURIER',
          displayName: 'Entregador do teste',
          expiresAt: new Date(Date.now() + 7_200_000).toISOString(),
        },
      },
    }),
  );
  await page.route('**/api/v1/auth/csrf', (route) =>
    route.fulfill({ json: { headerName: 'X-CSRF-TOKEN', token: 'fixture' } }),
  );
  await page.routeWebSocket('**/ws', (socket) =>
    socket.onMessage((raw) => {
      if (String(raw).startsWith('SUBSCRIBE\n') && String(raw).includes('/topic/orders/'))
        subscriptions.push(String(raw));
      if (String(raw).startsWith('CONNECT\n'))
        socket.send('CONNECTED\nversion:1.2\nheart-beat:0,0\n\n\0');
    }),
  );
  await page.route('https://tiles.openfreemap.org/**', (route) =>
    route.fulfill({ status: 503, body: '' }),
  );
  await page.route('**/api/v1/courier/work', async (route) => {
    const snapshot = structuredClone({ courier, order });
    if (holdNextWork) {
      holdNextWork = false;
      await new Promise<void>((resolve) => {
        releaseWork = resolve;
      });
    }
    return route.fulfill({ json: snapshot });
  });
  await page.route('**/api/v1/courier/offer', (route) =>
    route.fulfill({ json: { offer, serverTime: time() } }),
  );
  await page.route('**/api/v1/courier/training-order', async (route) => {
    currentKey = route.request().headers()['idempotency-key']!;
    keys.push(currentKey);
    receipt = receipts.get(currentKey) ?? null;
    if (!receipt) {
      const orderId = committed
        ? '00000000-0000-4000-8000-' + String(2 + committed * 10).padStart(12, '0')
        : ids.order;
      const offerId = committed
        ? '00000000-0000-4000-8000-' + String(3 + committed * 10).padStart(12, '0')
        : ids.offer;
      committed++;
      offer = {
        id: offerId,
        orderId,
        store,
        dropoff: { latitude: -23.5504, longitude: -46.6333 },
        etaToPickupSeconds: 12,
        createdAt: time(),
        expiresAt: new Date(Date.now() + offset + elapsed + 30_000).toISOString(),
      };
      receipt = {
        orderId,
        offerId,
        confirmationCode: String(824018 + committed),
        expiresAt: offer.expiresAt,
        status: 'SEARCHING',
        version: 0,
        serverTime: time(),
      };
      courier.status = 'OFFERED';
      rememberReceipt();
    }
    if (loseResponse) {
      loseResponse = false;
      return route.abort('connectionreset');
    }
    return route.fulfill({ status: 201, json: { ...receipt, serverTime: time() } });
  });
  await page.route('**/api/v1/offers/*/accept', async (route) => {
    order = {
      id: receipt!.orderId,
      store,
      dropoff: offer!.dropoff,
      status: 'ASSIGNED',
      version: 1,
      confirmationRequired: true,
      assignedAt: time(),
    };
    receipt = { ...receipt!, status: 'ASSIGNED', version: 1 };
    rememberOrder();
    rememberReceipt();
    offer = null;
    courier.status = 'BUSY';
    return route.fulfill({ status: 204 });
  });
  await page.route('**/api/v1/offers/*/reject', async (route) => {
    offer = null;
    courier.status = 'AVAILABLE';
    return route.fulfill({ status: 204 });
  });
  await page.route('**/api/v1/courier/training-order/*/cancel', async (route) => {
    expect(route.request().postDataJSON().expectedVersion).toBe(receipt!.version);
    receipt = { ...receipt!, status: 'CANCELED', version: receipt!.version + 1 };
    if (order) {
      order = { ...order, status: 'CANCELED', version: receipt.version };
      rememberOrder();
    }
    rememberReceipt();
    order = null;
    offer = null;
    courier.status = 'AVAILABLE';
    return route.fulfill({
      json: {
        orderId: receipt.orderId,
        status: 'CANCELED',
        version: receipt.version,
        serverTime: time(),
      },
    });
  });
  await page.route('**/api/v1/orders/*/pickup', (route) => {
    setOrderStatus('PICKED_UP');
    return route.fulfill({ json: order });
  });
  await page.route('**/api/v1/orders/*/deliver', (route) => {
    expect(route.request().postDataJSON().confirmationCode).toBe(receipt!.confirmationCode);
    setOrderStatus('DELIVERED');
    const delivered = order;
    order = null;
    courier.status = 'AVAILABLE';
    return route.fulfill({ json: delivered });
  });
  await page.route('**/api/v1/orders/*', (route) => {
    details.push(route.request().url());
    const requested = new URL(route.request().url()).pathname.split('/').at(-1)!;
    const snapshot = history.get(requested);
    expect(snapshot).toBeDefined();
    return route.fulfill({
      json: {
        ...snapshot,
        publicCode: 'T'.repeat(32),
        createdAt: time(),
        slaDeadline: time(),
        timeline: [
          {
            id: 1,
            type: 'OFFER_ACCEPTED',
            fromStatus: 'SEARCHING',
            toStatus: snapshot!.status,
            occurredAt: time(),
            orderVersion: snapshot!.version,
          },
        ],
      },
    });
  });
  await page.route('**/api/v1/courier/route', async (route) => {
    if (
      !courier.locationUpdatedAt ||
      Date.parse(courier.locationUpdatedAt) < Date.parse(order!.assignedAt)
    )
      return route.fulfill({
        status: 409,
        json: { detail: 'Envie uma posição recente para consultar a rota.' },
      });
    const value: Schemas['CourierRouteView'] = {
      orderId: order!.id,
      destination: order!.status === 'PICKED_UP' ? 'DROPOFF' : 'PICKUP',
      target: store.location,
      geometry: [courier.location!, { latitude: -23.57, longitude: -46.66 }, store.location],
      durationMillis: 40_000,
      simulatedDurationMillis: 4_000,
      distanceMeters: 2_000,
    };
    if (holdRoute)
      await new Promise<void>((resolve) => {
        releaseRoute = resolve;
        heldRoutes.push(resolve);
      });
    return route.fulfill({ json: value });
  });
  await page.route('**/api/v1/courier/positions', async (route) => {
    const sample = route.request().postDataJSON().positions[0] as {
      position: Schemas['GeoPoint'];
      recordedAt: string;
    };
    pendingPoint = sample;
    gps.push(sample.position);
    if (holdGps)
      await new Promise<void>((resolve) => {
        releaseGps = resolve;
      });
    if (autoPersist) {
      courier.location = sample.position;
      courier.locationUpdatedAt = sample.recordedAt;
    }
    return route.fulfill({
      status: 202,
      json: {
        accepted: 1,
        ignored: 0,
        latestRecordedAt: sample.recordedAt,
        flushDeadline: sample.recordedAt,
      },
    });
  });
  await page.goto('/entregador');
  await expect(page.getByRole('heading', { name: 'Seu trabalho como entregador' })).toBeVisible();
  return {
    advanceClock(milliseconds: number) {
      elapsed += milliseconds;
    },
    keys,
    details,
    subscriptions,
    gps,
    holdNextWork() {
      holdNextWork = true;
      releaseWork = undefined;
    },
    get workPending() {
      return Boolean(releaseWork);
    },
    releaseWork() {
      releaseWork?.();
    },
    moveConfirmed(point: Schemas['GeoPoint']) {
      elapsed += 1_000;
      courier.location = point;
      courier.locationUpdatedAt = time();
    },
    holdPersistence() {
      autoPersist = false;
    },
    automaticPersistence() {
      autoPersist = true;
    },
    get receipt() {
      return receipt;
    },
    arrive() {
      if (order) {
        courier.location = store.location;
        courier.locationUpdatedAt = time();
        setOrderStatus('AT_STORE');
      }
    },
    get committed() {
      return committed;
    },
    get routePending() {
      return Boolean(releaseRoute);
    },
    loseNextResponse() {
      loseResponse = true;
    },
    holdRoute() {
      holdRoute = true;
      releaseRoute = undefined;
    },
    releaseRoute() {
      holdRoute = false;
      for (const release of heldRoutes.splice(0)) release();
      releaseRoute = undefined;
    },
    holdGps() {
      holdGps = true;
    },
    releaseGps() {
      holdGps = false;
      releaseGps?.();
    },
    persist() {
      if (pendingPoint) {
        courier.location = pendingPoint.position;
        courier.locationUpdatedAt = pendingPoint.recordedAt;
      }
    },
    expire() {
      if (offer) offer.expiresAt = new Date(Date.now() + offset - 1_000).toISOString();
    },
  };
}
