import { useEffect, useState } from 'react';
import { Client } from '@stomp/stompjs';
import { api, ApiError, getCsrf, type TrackingSnapshot } from '@/lib/api';
import { newerTracking, parseTrackingSnapshot } from '@/lib/tracking-protocol';

type State = { snapshot: TrackingSnapshot | null; error: Error | null; connected: boolean };
const initial: State = { snapshot: null, error: null, connected: false };

export function useTracking(code: string) {
  const [state, setState] = useState<State>(initial);
  useEffect(() => {
    let alive = true;
    let client: Client | undefined;
    let request: AbortController | undefined;
    const lifetime = new AbortController();
    let reconnect: number | undefined;
    let attempts = 0;
    let last: TrackingSnapshot | null = null;
    let receivedAt = 0;
    let lastHttpAt = 0;
    let connecting = false;
    let unavailable = false;
    function receive(raw: unknown) {
      const value = parseTrackingSnapshot(raw);
      if (!alive || unavailable || !value || !newerTracking(value, last)) return;
      last = value;
      receivedAt = performance.now();
      setState({ snapshot: value, error: null, connected: Boolean(client?.connected) });
    }
    async function snapshot() {
      if (!alive || unavailable || document.visibilityState !== 'visible') return;
      request?.abort();
      const current = new AbortController();
      request = current;
      lastHttpAt = performance.now();
      try {
        const value = await api.tracking(code, current.signal);
        if (!current.signal.aborted && request === current) receive(value);
      } catch (error) {
        if (!alive || current.signal.aborted || request !== current) return;
        if (error instanceof ApiError && error.status === 404) {
          unavailable = true;
          client?.forceDisconnect();
          setState({
            snapshot: null,
            error: new Error(
              'Este link de rastreio não está disponível. Confira o código ou peça um novo link à loja.',
            ),
            connected: false,
          });
        } else
          setState((value) => ({
            ...value,
            error:
              error instanceof Error
                ? error
                : new Error('Não foi possível atualizar esta entrega.'),
          }));
      }
    }
    function retry() {
      if (!alive || unavailable) return;
      setState((value) => ({ ...value, connected: false }));
      window.clearTimeout(reconnect);
      reconnect = window.setTimeout(
        () => void connect(),
        Math.min(30_000, 1_000 * 2 ** Math.min(attempts++, 5)),
      );
    }
    async function connect() {
      if (!alive || unavailable || connecting || client?.active) return;
      connecting = true;
      try {
        const csrf = await getCsrf(lifetime.signal);
        if (!alive || unavailable) return;
        const url = new URL('/ws', window.location.href);
        url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:';
        const next = new Client({
          brokerURL: url.href,
          connectHeaders: { [csrf.headerName]: csrf.token },
          reconnectDelay: 0,
          connectionTimeout: 8_000,
          heartbeatIncoming: 10_000,
          heartbeatOutgoing: 10_000,
          discardWebsocketOnCommFailure: true,
          onConnect() {
            if (!alive || unavailable || client !== next) return;
            attempts = 0;
            next.subscribe(`/topic/tracking/${encodeURIComponent(code)}`, (message) => {
              if (!alive || unavailable || client !== next) return;
              try {
                receive(JSON.parse(message.body) as TrackingSnapshot);
              } catch {
                /* Keep the last valid snapshot. */
              }
            });
            setState((value) => ({ ...value, connected: true }));
            // Subscribe before the HTTP read so reconnects cannot lose a transition.
            void snapshot();
          },
          onStompError: () => next.forceDisconnect(),
          onWebSocketClose() {
            if (client === next) {
              client = undefined;
              retry();
            }
          },
        });
        client = next;
        next.activate();
      } catch {
        retry();
      } finally {
        connecting = false;
      }
    }
    const recover = () => {
      if (document.visibilityState !== 'visible' || !alive) return;
      void snapshot();
      if (!client?.active) {
        window.clearTimeout(reconnect);
        void connect();
      }
    };
    const polling = window.setInterval(() => {
      // Expiration can revoke a tracking subscription without closing the public socket.
      if (!client?.connected || performance.now() - lastHttpAt >= 30_000) void snapshot();
      if (
        last?.courierPosition &&
        last.courierPositionUpdatedAt &&
        Date.parse(last.serverTime) +
          performance.now() -
          receivedAt -
          Date.parse(last.courierPositionUpdatedAt) >
          30_000
      ) {
        setState((value) =>
          value.snapshot?.courierPosition
            ? {
                ...value,
                snapshot: {
                  ...value.snapshot,
                  courierPosition: null,
                  courierPositionUpdatedAt: null,
                  route: null,
                  etaSeconds: null,
                },
              }
            : value,
        );
      }
    }, 5_000);
    void (async () => {
      await snapshot();
      if (alive && !unavailable) void connect();
    })();
    window.addEventListener('online', recover);
    document.addEventListener('visibilitychange', recover);
    return () => {
      alive = false;
      lifetime.abort();
      request?.abort();
      window.clearTimeout(reconnect);
      window.clearInterval(polling);
      window.removeEventListener('online', recover);
      document.removeEventListener('visibilitychange', recover);
      if (client) void client.deactivate({ force: true });
    };
  }, [code]);
  return state;
}
