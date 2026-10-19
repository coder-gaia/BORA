import { Client } from '@stomp/stompjs';
import { getCsrf } from '@/lib/api';
import { sameViewport, ViewportGate } from '@/lib/viewport-gate';
import { parseFrame, type MapFrame, type MapViewport } from '@/lib/courier-motion';

export type MapConnectionState = 'connecting' | 'connected' | 'reconnecting';

export class PublicMapFeed {
  private running = false;
  private lifecycle = 0;
  private connectionGeneration = 0;
  private viewportRevision = 0;
  private client: Client | undefined;
  private request: AbortController | undefined;
  private snapshotRequest: AbortController | undefined;
  private retries = 0;
  private reconnect: number | undefined;
  private fallback: number | undefined;
  private viewportTimer: number | undefined;
  private viewportGate: ViewportGate | undefined;
  private state: MapConnectionState = 'connecting';
  private viewport: MapViewport = {
    west: -46.75,
    south: -23.65,
    east: -46.5,
    north: -23.45,
    scope: 'PUBLIC',
  };
  private readonly onFrame: (frame: MapFrame) => void;
  private readonly onState: (state: MapConnectionState) => void;

  constructor(onFrame: (frame: MapFrame) => void, onState: (state: MapConnectionState) => void) {
    this.onFrame = onFrame;
    this.onState = onState;
  }

  start() {
    if (this.running) return;
    this.running = true;
    this.lifecycle++;
    this.fallback = window.setInterval(() => {
      if (document.visibilityState === 'visible' && !this.client?.connected) void this.snapshot();
    }, 5_000);
    window.addEventListener('online', this.online);
    void this.snapshot();
    void this.connect();
  }

  stop() {
    this.running = false;
    this.lifecycle++;
    this.connectionGeneration++;
    window.clearTimeout(this.reconnect);
    window.clearTimeout(this.viewportTimer);
    window.clearInterval(this.fallback);
    this.viewportGate?.stop();
    this.viewportGate = undefined;
    window.removeEventListener('online', this.online);
    this.request?.abort();
    this.snapshotRequest?.abort();
    const client = this.client;
    this.client = undefined;
    if (client) void client.deactivate({ force: true });
  }

  private online = () => {
    if (!this.running || this.client?.active) return;
    window.clearTimeout(this.reconnect);
    void this.connect();
  };

  setViewport(viewport: MapViewport) {
    if (sameViewport(this.viewport, viewport)) return;
    this.viewport = { ...viewport };
    this.viewportRevision++;
    this.snapshotRequest?.abort();
    this.viewportGate?.update(this.viewport);
    window.clearTimeout(this.viewportTimer);
    this.viewportTimer = window.setTimeout(() => {
      if (this.running) void this.snapshot();
    }, 250);
  }

  private emit(value: unknown) {
    const frame = parseFrame(value);
    if (!frame || !this.running) return false;
    const { west, east, south, north } = this.viewport;
    this.onFrame({
      ...frame,
      points: frame.points.filter(
        ([, longitude, latitude]) =>
          longitude >= west && longitude <= east && latitude >= south && latitude <= north,
      ),
    });
    return true;
  }

  private async snapshot() {
    if (!this.running) return;
    const lifecycle = this.lifecycle;
    const revision = this.viewportRevision;
    const viewport = { ...this.viewport };
    this.snapshotRequest?.abort();
    const controller = new AbortController();
    this.snapshotRequest = controller;
    const params = new URLSearchParams(
      Object.entries(viewport)
        .filter(([key]) => key !== 'scope')
        .map(([key, value]) => [key, String(value)]),
    );
    try {
      const response = await fetch(`/api/v1/map/snapshot?${params}`, {
        credentials: 'same-origin',
        cache: 'no-store',
        signal: AbortSignal.any([controller.signal, AbortSignal.timeout(5_000)]),
      });
      if (!response.ok) return;
      const value: unknown = await response.json();
      if (
        this.running &&
        lifecycle === this.lifecycle &&
        revision === this.viewportRevision &&
        this.snapshotRequest === controller &&
        !controller.signal.aborted
      )
        this.emit(value);
    } catch {
      /* Keep the last usable map while the network recovers. */
    } finally {
      if (this.snapshotRequest === controller) this.snapshotRequest = undefined;
    }
  }

  private retry() {
    if (!this.running) return;
    this.state = 'reconnecting';
    this.onState(this.state);
    const delay = Math.min(30_000, 1_000 * 2 ** Math.min(this.retries++, 5));
    this.reconnect = window.setTimeout(() => void this.connect(), delay + Math.random() * 500);
  }

  private async connect() {
    if (!this.running || this.client?.active) return;
    const generation = ++this.connectionGeneration;
    this.request?.abort();
    this.request = new AbortController();
    try {
      const csrf = await getCsrf(this.request.signal);
      if (!this.running || this.connectionGeneration !== generation) return;
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
          if (this.client !== client || !this.running) return;
          const recovering = this.state === 'reconnecting';
          this.retries = 0;
          this.state = 'connected';
          this.onState(this.state);
          this.viewportGate?.stop();
          const gate = new ViewportGate((viewport) => {
            if (this.client === client && this.running && client.connected) {
              client.publish({ destination: '/app/viewport', body: JSON.stringify(viewport) });
            }
          });
          this.viewportGate = gate;
          // Set the first viewport before SUBSCRIBE can perform synchronous snapshot work.
          gate.start(this.viewport);
          client.subscribe('/user/queue/map', (message) => {
            if (this.client !== client || !this.running) return;
            try {
              if (this.emit(JSON.parse(message.body))) gate.progress();
            } catch {
              /* Ignorar frame inválido. */
            }
          });
          if (recovering) void this.snapshot();
        },
        onStompError: () => client.forceDisconnect(),
        onWebSocketClose: () => {
          if (this.client !== client || !this.running) return;
          this.viewportGate?.stop();
          this.viewportGate = undefined;
          this.client = undefined;
          this.retry();
        },
      });
      this.client = client;
      client.activate();
    } catch {
      if (this.running && generation === this.connectionGeneration) this.retry();
    }
  }
}
