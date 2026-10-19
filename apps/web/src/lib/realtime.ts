import { Client, type IMessage, type StompSubscription } from '@stomp/stompjs';
import type { Query, QueryClient } from '@tanstack/react-query';
import { api, ApiError, getCsrf, type DemoUserView, type OrderView } from '@/lib/api';

export type ConnectionState = 'connecting' | 'connected' | 'reconnecting' | 'offline';

function object(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

function parse(message: IMessage): Record<string, unknown> | null {
  try {
    const value: unknown = JSON.parse(message.body);
    return object(value) ? value : null;
  } catch {
    return null;
  }
}

// Oferta e expiração acrescentam eventos sem alterar a versão do estado do pedido.
export function newerOrder(incoming: OrderView, current: OrderView | undefined) {
  return (
    !current ||
    incoming.version > current.version ||
    (incoming.version === current.version && incoming.timeline.length > current.timeline.length)
  );
}

export class RealtimeConnection {
  private state: ConnectionState = 'connecting';
  private listeners = new Set<() => void>();
  private running = false;
  private generation = 0;
  private attempts = 0;
  private client: Client | undefined;
  private request: AbortController | undefined;
  private reconnectTimer: number | undefined;
  private reconcileTimer: number | undefined;
  private orders = new Map<string, { watchers: number; subscription?: StompSubscription }>();
  private seenEvents = new Set<string>();
  private readonly user: DemoUserView;
  private readonly queries: QueryClient;

  constructor(user: DemoUserView, queries: QueryClient) {
    this.user = user;
    this.queries = queries;
  }

  getState = () => this.state;

  subscribe = (listener: () => void) => {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  };

  private change(state: ConnectionState) {
    if (this.state === state) return;
    this.state = state;
    for (const listener of this.listeners) listener();
  }

  private ownQuery = (query: Query) => query.queryKey[1] === this.user.id;

  start = () => {
    if (this.running) return;
    this.running = true;
    window.addEventListener('online', this.online);
    window.addEventListener('offline', this.offline);
    document.addEventListener('visibilitychange', this.visible);
    this.reconcileTimer = window.setInterval(() => {
      if (document.visibilityState === 'visible') void this.reconcile();
    }, 30_000);
    void this.connect();
  };

  stop = () => {
    this.running = false;
    this.generation++;
    this.request?.abort();
    window.clearTimeout(this.reconnectTimer);
    window.clearInterval(this.reconcileTimer);
    window.removeEventListener('online', this.online);
    window.removeEventListener('offline', this.offline);
    document.removeEventListener('visibilitychange', this.visible);
    const client = this.client;
    this.client = undefined;
    for (const order of this.orders.values()) order.subscription = undefined;
    if (client) void client.deactivate({ force: true });
  };

  private online = () => {
    if (!this.running || this.client?.connected) return;
    window.clearTimeout(this.reconnectTimer);
    void this.connect();
  };

  private offline = () => {
    this.change('offline');
    this.client?.forceDisconnect();
  };

  private visible = () => {
    if (document.visibilityState !== 'visible' || !this.running) return;
    void this.reconcile();
    this.online();
  };

  private async validateSession(signal?: AbortSignal) {
    const session = await api.session(signal);
    if (!this.running || signal?.aborted) return false;
    if (session.user?.id === this.user.id && session.user.role === this.user.role) return true;
    this.stop();
    await this.queries.cancelQueries({ predicate: this.ownQuery });
    this.queries.removeQueries({ predicate: (query) => query.queryKey[0] !== 'session' });
    this.queries.setQueryData(['session'], session);
    return false;
  }

  private async reconcile() {
    try {
      if (await this.validateSession()) {
        await this.queries.invalidateQueries({ predicate: this.ownQuery });
      }
    } catch {
      // O polling REST continua disponível enquanto a conexão se recupera.
    }
  }

  private retry() {
    if (!this.running) return;
    this.change(navigator.onLine ? 'reconnecting' : 'offline');
    window.clearTimeout(this.reconnectTimer);
    const delay = Math.min(30_000, 1_000 * 2 ** Math.min(this.attempts++, 5));
    this.reconnectTimer = window.setTimeout(() => void this.connect(), delay + Math.random() * 500);
  }

  private async connect() {
    if (!this.running || this.client?.active) return;
    const generation = ++this.generation;
    this.request?.abort();
    const request = new AbortController();
    this.request = request;
    try {
      if (!(await this.validateSession(request.signal))) return;
      const csrf = await getCsrf(request.signal);
      if (!this.running || generation !== this.generation) return;
      const url = new URL('/ws', window.location.href);
      url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:';
      const client = new Client({
        brokerURL: url.href,
        connectHeaders: { [csrf.headerName]: csrf.token },
        reconnectDelay: 0,
        connectionTimeout: 8_000,
        heartbeatIncoming: 10_000,
        heartbeatOutgoing: 10_000,
        discardWebsocketOnCommFailure: true,
        onConnect: () => {
          if (!this.current(client)) return;
          this.attempts = 0;
          client.subscribe('/user/queue/events', (message) => this.event(client, message));
          if (this.user.role === 'COURIER') {
            client.subscribe('/user/queue/offers', (message) => this.offer(client, message));
          }
          for (const id of this.orders.keys()) this.subscribeOrder(id);
          this.change('connected');
          // Assinar antes do snapshot fecha a janela entre o HTTP e o broker.
          void this.reconcile();
        },
        onStompError: () => client.forceDisconnect(),
        onWebSocketClose: () => {
          if (!this.current(client)) return;
          this.client = undefined;
          for (const order of this.orders.values()) order.subscription = undefined;
          this.retry();
        },
      });
      this.client = client;
      client.activate();
    } catch (error) {
      if (!this.running || generation !== this.generation || request.signal.aborted) return;
      if (error instanceof ApiError && error.status === 401) {
        void this.queries.invalidateQueries({ queryKey: ['session'] });
      }
      this.retry();
    }
  }

  private current(client: Client) {
    return this.running && this.client === client;
  }

  private event(client: Client, message: IMessage) {
    if (!this.current(client)) return;
    const value = parse(message);
    if (!value || typeof value.eventId !== 'string' || this.seenEvents.has(value.eventId)) return;
    if (value.type !== 'ORDERS_CHANGED' && value.type !== 'COURIER_CHANGED') return;
    this.seenEvents.add(value.eventId);
    if (this.seenEvents.size > 512) this.seenEvents.delete(this.seenEvents.values().next().value!);
    if (value.type === 'ORDERS_CHANGED') {
      void this.queries.invalidateQueries({ queryKey: ['orders', this.user.id] });
    }
    if (this.user.role === 'COURIER') {
      void this.queries.invalidateQueries({ queryKey: ['courier-work', this.user.id] });
      void this.queries.invalidateQueries({ queryKey: ['courier-offer', this.user.id] });
    }
  }

  private offer(client: Client, message: IMessage) {
    if (!this.current(client)) return;
    const inbox = parse(message);
    if (!inbox || typeof inbox.serverTime !== 'string' || !('offer' in inbox)) return;
    if (inbox.offer !== null && (!object(inbox.offer) || typeof inbox.offer.id !== 'string'))
      return;
    const current = this.queries.getQueryData<{ serverTime: string }>([
      'courier-offer',
      this.user.id,
    ]);
    if (current && Date.parse(current.serverTime) > Date.parse(inbox.serverTime)) return;
    this.queries.setQueryData(['courier-offer', this.user.id], {
      ...inbox,
      receivedAt: performance.now(),
    });
    void this.queries.invalidateQueries({ queryKey: ['courier-work', this.user.id] });
  }

  watchOrder = (id: string) => {
    const watched = this.orders.get(id);
    if (watched) watched.watchers++;
    else this.orders.set(id, { watchers: 1 });
    this.subscribeOrder(id);
    return () => {
      const current = this.orders.get(id);
      if (!current || --current.watchers > 0) return;
      if (this.client?.connected) current.subscription?.unsubscribe();
      this.orders.delete(id);
    };
  };

  private subscribeOrder(id: string) {
    const watched = this.orders.get(id);
    const client = this.client;
    if (!watched || watched.subscription || !client?.connected) return;
    watched.subscription = client.subscribe(
      `/topic/orders/${encodeURIComponent(id)}`,
      (message) => {
        if (!this.current(client)) return;
        const value = parse(message);
        if (
          !value ||
          value.id !== id ||
          typeof value.version !== 'number' ||
          !Array.isArray(value.timeline)
        )
          return;
        const incoming = value as OrderView;
        let changed = false;
        this.queries.setQueryData<OrderView>(['order', this.user.id, id], (current) => {
          changed = newerOrder(incoming, current);
          return changed ? incoming : current;
        });
        if (!changed) return;
        void this.queries.invalidateQueries({ queryKey: ['orders', this.user.id] });
        if (this.user.role === 'COURIER') {
          void this.queries.invalidateQueries({ queryKey: ['courier-work', this.user.id] });
        }
      },
    );
  }
}
