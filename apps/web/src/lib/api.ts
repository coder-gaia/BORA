import type { components } from '@/generated/api';

export type SessionView = components['schemas']['SessionView'];
export type DemoUserView = components['schemas']['DemoUserView'];
export type StoreView = components['schemas']['StoreView'];
export type OrderPage = components['schemas']['OrderPage'];
export type OrderView = components['schemas']['OrderView'];
export type OrderSummary = components['schemas']['OrderSummary'];
export type DemoRole = NonNullable<DemoUserView['role']>;

type CsrfView = components['schemas']['CsrfView'];
type DemoLogin = components['schemas']['DemoLogin'];
type CreateOrderRequest = components['schemas']['CreateOrderRequest'];
type CancelOrderRequest = components['schemas']['CancelOrderRequest'];

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
      signal: controller.signal,
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
    if (error instanceof ApiError) throw error;
    throw new Error('A conexão foi interrompida. Confira sua rede e tente novamente.', {
      cause: error,
    });
  } finally {
    window.clearTimeout(timeout);
  }
}

export async function getCsrf() {
  return request<CsrfView>('/api/v1/auth/csrf');
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
  session: () => request<SessionView>('/api/v1/auth/me'),
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
  store: () => request<StoreView>('/api/v1/stores/me'),
  orders: (page: number) => request<OrderPage>(`/api/v1/orders?page=${page}&size=10`),
  order: (id: string) => request<OrderView>(`/api/v1/orders/${encodeURIComponent(id)}`),
  createOrder: (dropoff: NonNullable<CreateOrderRequest['dropoff']>, key: string) =>
    post<OrderView>('/api/v1/orders', { dropoff } satisfies CreateOrderRequest, {
      'Idempotency-Key': key,
    }),
  cancelOrder: (id: string, expectedVersion: number) =>
    post<OrderView>(`/api/v1/orders/${encodeURIComponent(id)}/cancel`, {
      expectedVersion,
    } satisfies CancelOrderRequest),
};
