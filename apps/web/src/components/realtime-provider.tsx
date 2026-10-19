import { useEffect, useState, type ReactNode } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import type { DemoUserView } from '@/lib/api';
import { RealtimeConnection } from '@/lib/realtime';
import { RealtimeContext, useRealtime } from '@/hooks/use-realtime';

export function RealtimeProvider({ user, children }: { user: DemoUserView; children: ReactNode }) {
  const queries = useQueryClient();
  const [connection] = useState(() => new RealtimeConnection(user, queries));
  useEffect(() => {
    connection.start();
    return connection.stop;
  }, [connection]);
  return <RealtimeContext value={connection}>{children}</RealtimeContext>;
}

export function ConnectionStatus() {
  const { state } = useRealtime();
  const labels = {
    connecting: 'Conectando atualizações ao vivo...',
    connected: 'Atualizações ao vivo',
    reconnecting: 'Reconectando. Atualizando por consultas periódicas.',
    offline: 'Sem conexão. Seus dados serão atualizados quando a rede voltar.',
  };
  return (
    <p className="connection-status" data-connection={state} role="status">
      {labels[state]}
    </p>
  );
}
