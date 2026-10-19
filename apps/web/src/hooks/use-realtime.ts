import { createContext, useContext, useEffect, useSyncExternalStore } from 'react';
import type { RealtimeConnection } from '@/lib/realtime';

export const RealtimeContext = createContext<RealtimeConnection | null>(null);

export function useRealtime() {
  const connection = useContext(RealtimeContext);
  if (!connection) throw new Error('A conexão precisa estar dentro da sessão de demonstração.');
  const state = useSyncExternalStore(connection.subscribe, connection.getState);
  return { connection, state, connected: state === 'connected' };
}

export function useOrderUpdates(id: string | null | undefined) {
  const { connection } = useRealtime();
  useEffect(() => (id ? connection.watchOrder(id) : undefined), [connection, id]);
}
