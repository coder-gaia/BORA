import {
  QueryClient,
  QueryClientProvider,
  useMutation,
  useQuery,
  useQueryClient,
} from '@tanstack/react-query';
import { LogOut, Store, Truck, Users } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { api, type DemoRole } from '@/lib/api';
import { CourierWorkspace } from '@/components/courier-workspace';
import { ConnectionStatus, RealtimeProvider } from '@/components/realtime-provider';
import { OrderWorkspace } from '@/components/order-workspace';
import { forgetTrainingIntents } from '@/lib/training-intent';
import { useEffect } from 'react';

const roles = [
  { value: 'STORE', label: 'Sou loja', icon: Store },
  { value: 'COURIER', label: 'Sou entregador', icon: Truck },
  { value: 'OPERATIONS', label: 'Operação', icon: Users },
] as const;

function ErrorMessage({ error }: { error: Error | null }) {
  return error ? (
    <p className="demo-error" role="alert">
      {error.message}
    </p>
  ) : null;
}

const queryClient = new QueryClient({
  defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } },
});

export function DemoPanel({ requestedRole }: { requestedRole?: DemoRole }) {
  return (
    <QueryClientProvider client={queryClient}>
      <DemoWorkspace requestedRole={requestedRole} />
    </QueryClientProvider>
  );
}

function DemoWorkspace({ requestedRole }: { requestedRole?: DemoRole }) {
  const queryClient = useQueryClient();
  const session = useQuery({
    queryKey: ['session'],
    queryFn: ({ signal }) => api.session(signal),
  });
  const authentication = useMutation({
    async onMutate() {
      await queryClient.cancelQueries();
      queryClient.removeQueries({ predicate: (query) => query.queryKey[0] !== 'session' });
    },
    mutationFn: async (role: DemoRole | null) => (role ? api.signIn(role) : api.signOut()),
    onSuccess(value) {
      queryClient.removeQueries({ predicate: (query) => query.queryKey[0] !== 'session' });
      queryClient.setQueryData(['session'], value);
    },
    onSettled() {
      void queryClient.invalidateQueries({ queryKey: ['session'] });
    },
  });
  const user = session.data?.user;
  useEffect(() => {
    if (session.isSuccess && !user) forgetTrainingIntents();
  }, [user, session.isSuccess]);

  return (
    <section
      className="demo-panel"
      aria-labelledby="demo-title"
      aria-busy={authentication.isPending}
    >
      <div className="demo-heading">
        <div>
          <p className="eyebrow">Demonstração</p>
          <h2 id="demo-title">
            {requestedRole === 'STORE'
              ? 'Sua loja, suas entregas'
              : requestedRole === 'COURIER'
                ? 'Sua próxima entrega'
                : 'Experimente um pedido'}
          </h2>
        </div>
        {user && (
          <Button
            variant="outline"
            disabled={authentication.isPending}
            onClick={() => authentication.mutate(null)}
          >
            <LogOut aria-hidden="true" /> Sair
          </Button>
        )}
      </div>
      <p className="demo-description">
        {requestedRole === 'STORE'
          ? 'Entre como loja para criar e acompanhar seus pedidos neste contexto.'
          : requestedRole === 'COURIER'
            ? 'Entre como entregador para receber ofertas e percorrer cada etapa.'
            : 'Escolha como participar. Você pode trocar de papel e continuar no mesmo contexto.'}
      </p>
      <div className="role-options" role="group" aria-label="Escolher papel">
        {roles
          .filter(({ value }) => !requestedRole || requestedRole === value)
          .map(({ value, label, icon: Icon }) => (
            <Button
              key={value}
              variant={user?.role === value ? 'default' : 'outline'}
              disabled={authentication.isPending || session.isPending}
              aria-pressed={user?.role === value}
              onClick={() => authentication.mutate(value)}
            >
              <Icon aria-hidden="true" />
              {label}
            </Button>
          ))}
      </div>
      {!requestedRole && (
        <nav className="workspace-links" aria-label="Áreas de demonstração">
          <a className="store-area-link" href="/loja">
            Abrir área da loja <span aria-hidden="true">↗</span>
          </a>
          <a className="store-area-link" href="/entregador">
            Abrir área do entregador <span aria-hidden="true">↗</span>
          </a>
        </nav>
      )}
      <ErrorMessage error={authentication.error ?? session.error} />
      {session.isPending && <p role="status">Consultando sua sessão...</p>}
      {session.error && (
        <Button variant="outline" onClick={() => void session.refetch()}>
          Tentar novamente
        </Button>
      )}
      {user && (!requestedRole || user.role === requestedRole) && !authentication.isPending && (
        <RealtimeProvider key={`${user.id}:${user.role}`} user={user}>
          <p className="session-summary" role="status">
            {user.displayName}. A sessão expira após duas horas sem uso.
          </p>
          <ConnectionStatus />
          {user.role === 'COURIER' ? (
            <CourierWorkspace key={user.id} user={user} />
          ) : (
            <OrderWorkspace key={`${user.id}:${user.role}`} user={user} />
          )}
        </RealtimeProvider>
      )}
      {user && requestedRole && user.role !== requestedRole && (
        <p className="demo-empty">
          Seu contexto está preservado. Escolha o papel desta área para continuar.
        </p>
      )}
      {!user && !session.isPending && !session.error && (
        <p className="demo-empty">
          {requestedRole === 'COURIER'
            ? 'Escolha Sou entregador para receber ofertas e praticar uma entrega.'
            : 'Entre como loja para criar seu primeiro pedido.'}{' '}
          Seus dados ficam restritos ao seu contexto de demonstração.
        </p>
      )}
    </section>
  );
}
