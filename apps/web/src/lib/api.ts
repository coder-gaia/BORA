import type { components, paths } from '@/generated/api';

export type SessionView = components['schemas']['SessionView'];
export type DemoUserView = components['schemas']['DemoUserView'];
export type StoreView = components['schemas']['StoreView'];
export type OrderPage = components['schemas']['OrderPage'];
export type OrderView = components['schemas']['OrderView'];
export type OrderSummary = components['schemas']['OrderSummary'];
export type DemoRole = NonNullable<DemoUserView['role']>;
export type PositionReceipt = components['schemas']['PositionReceipt'];
export type CourierWorkView = components['schemas']['CourierWorkView'];
export type CourierRouteView = components['schemas']['CourierRouteView'];
export type CourierOfferInbox = components['schemas']['CourierOfferInbox'];
export type CourierOffer = components['schemas']['CourierOffer'];
type SimulationConfig = components['schemas']['SimulationConfig'];
type CourierView = components['schemas']['CourierView'];
type GeoPoint = components['schemas']['GeoPoint'];
type AvailabilityRequest = components['schemas']['AvailabilityRequest'];
type PositionBatchRequest = components['schemas']['PositionBatchRequest'];
type AssignedOrderView = components['schemas']['AssignedOrderView'];
type PickupBody = NonNullable<
  paths['/api/v1/orders/{id}/pickup']['post']['requestBody']
>['content']['application/json'];
type DeliveryBody = NonNullable<
  paths['/api/v1/orders/{id}/deliver']['post']['requestBody']
>['content']['application/json'];

type CsrfView = components['schemas']['CsrfView'];
type DemoLogin = components['schemas']['DemoLogin'];
type CreateOrderRequest = components['schemas']['CreateOrderRequest'];
type CancelOrderRequest = components['schemas']['CancelOrderRequest'];
type ReassignBody = NonNullable<
  paths['/api/v1/ops/orders/{id}/reassign']['post']['requestBody']
>['content']['application/json'];

export class ApiError extends Error {
  readonly status: number;

  constructor(status: number, message: string) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
  }
}

async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  const controller = new AbortController();
  const timeout = window.setTimeout(() => controller.abort(), 15_000);
  try {
    const response = await fetch(path, {
      ...options,
      credentials: 'same-origin',
      cache: 'no-store',
      signal: options.signal
        ? AbortSignal.any([options.signal, controller.signal])
        : controller.signal,
      headers: { Accept: 'application/json', ...options.headers },
    });
    if (!response.ok) {
      let detail = 'Não foi possível concluir a solicitação. Tente novamente.';
      try {
        const problem: unknown = await response.json();
        if (
          typeof problem === 'object' &&
          problem !== null &&
          'detail' in problem &&
          typeof problem.detail === 'string'
        ) {
          detail = problem.detail;
        }
      } catch {
        if (response.status === 401) detail = 'Sua sessão expirou. Entre novamente para continuar.';
      }
      throw new ApiError(response.status, detail);
    }
    return response.status === 204 ? (undefined as T) : ((await response.json()) as T);
  } catch (error) {
    if (options.signal?.aborted) throw error;
    if (error instanceof ApiError) throw error;
    throw new Error('A conexão foi interrompida. Confira sua rede e tente novamente.', {
      cause: error,
    });
  } finally {
    window.clearTimeout(timeout);
  }
}

export async function getCsrf(signal?: AbortSignal) {
  return request<CsrfView>('/api/v1/auth/csrf', { signal });
}

async function post<T>(path: string, body?: unknown, headers?: Record<string, string>) {
  const csrf = await getCsrf();
  return request<T>(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token, ...headers },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
}

export const api = {
  simulationConfig: (signal?: AbortSignal) =>
    request<SimulationConfig>('/api/v1/map/config', { signal }),
  session: (signal?: AbortSignal) => request<SessionView>('/api/v1/auth/me', { signal }),
  async signIn(role: DemoRole) {
    const session = await post<SessionView>('/api/v1/auth/demo', { role } satisfies DemoLogin);
    await getCsrf();
    return session;
  },
  async signOut() {
    await post<void>('/api/v1/auth/logout');
    await getCsrf();
    return api.session();
  },
  store: (signal?: AbortSignal) => request<StoreView>('/api/v1/stores/me', { signal }),
  orders: (page: number, signal?: AbortSignal) =>
    request<OrderPage>(`/api/v1/orders?page=${page}&size=10`, { signal }),
  order: (id: string, signal?: AbortSignal) =>
    request<OrderView>(`/api/v1/orders/${encodeURIComponent(id)}`, { signal }),
  createOrder: (dropoff: NonNullable<CreateOrderRequest['dropoff']>, key: string) =>
    post<OrderView>('/api/v1/orders', { dropoff } satisfies CreateOrderRequest, {
      'Idempotency-Key': key,
    }),
  cancelOrder: (id: string, expectedVersion: number) =>
    post<OrderView>(`/api/v1/orders/${encodeURIComponent(id)}/cancel`, {
      expectedVersion,
    } satisfies CancelOrderRequest),
  courierWork: (signal?: AbortSignal) =>
    request<CourierWorkView>('/api/v1/courier/work', { signal }),
  courierRoute: (signal?: AbortSignal) =>
    request<CourierRouteView>('/api/v1/courier/route', { signal }),
  courierOffer: (signal?: AbortSignal) =>
    request<CourierOfferInbox>('/api/v1/courier/offer', { signal }),
  acceptOffer: (id: string) => post<void>(`/api/v1/offers/${encodeURIComponent(id)}/accept`),
  rejectOffer: (id: string) => post<void>(`/api/v1/offers/${encodeURIComponent(id)}/reject`),
  reassignOrder: (id: string, expectedVersion: number) =>
    post<void>(`/api/v1/ops/orders/${encodeURIComponent(id)}/reassign`, {
      expectedVersion,
    } satisfies ReassignBody),
  availability: (online: boolean, position?: GeoPoint) =>
    post<CourierView>('/api/v1/courier/availability', {
      online,
      position,
    } satisfies AvailabilityRequest),
  async sendPosition(position: GeoPoint) {
    // A posição manual é coletada agora; o relógio local pode divergir do servidor.
    const clock = await api.courierOffer();
    const observedAt = performance.now();
    const csrf = await getCsrf();
    const recordedAt = new Date(
      Date.parse(clock.serverTime) + performance.now() - observedAt,
    ).toISOString();
    return request<PositionReceipt>('/api/v1/courier/positions', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token },
      body: JSON.stringify({
        positions: [{ recordedAt, position }],
      } satisfies PositionBatchRequest),
    });
  },
  pickUpOrder: (id: string, expectedVersion: number) =>
    post<AssignedOrderView>(`/api/v1/orders/${encodeURIComponent(id)}/pickup`, {
      expectedVersion,
    } satisfies PickupBody),
  deliverOrder: (id: string, expectedVersion: number, confirmationCode?: string) =>
    post<AssignedOrderView>(`/api/v1/orders/${encodeURIComponent(id)}/deliver`, {
      expectedVersion,
      confirmationCode,
    } satisfies DeliveryBody),
};
