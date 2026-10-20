import { lazy, Suspense, useEffect, useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { ArrowLeft, LayoutDashboard, ListFilter, RefreshCw } from 'lucide-react';
import { api, ApiError, type DemoUserView } from '@/lib/api';
import { OrderBrowser, OrderDetail } from '@/components/order-workspace';
import { Button } from '@/components/ui/button';
import './operations.css';

const OperationsMap = lazy(() =>
  import('@/components/operations-map').then((module) => ({ default: module.OperationsMap })),
);

const indicators = [
  ['activeOrders', 'Pedidos em andamento', 'active'],
  ['searchingOrders', 'Buscando entregador', 'normal'],
  ['searchingOverThreeMinutes', 'Espera de 3 min ou mais', 'warning'],
  ['overdueOrders', 'Prazo vencido', 'danger'],
  ['dueWithinFiveMinutes', 'Prazo em até 5 min', 'warning'],
  ['deliveredLastHour', 'Entregues na última hora', 'normal'],
] as const;

export function OperationsDashboard({ user }: { user: DemoUserView }) {
  const queries = useQueryClient();
  const [view, setView] = useState<'overview' | 'orders'>('overview');
  const [openedOrders, setOpenedOrders] = useState(false);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [revoked, setRevoked] = useState(false);
  const lastSelected = useRef<string | null>(null);
  const overviewHeading = useRef<HTMLHeadingElement>(null);
  const ordersHeading = useRef<HTMLHeadingElement>(null);
  const workspace = useRef<HTMLDivElement>(null);
  const restoreFocus = useRef(false);
  const metrics = useQuery({
    queryKey: ['operations-metrics', user.id],
    async queryFn({ signal }) {
      try {
        const data = await api.operationsMetrics(signal);
        return { ...data, receivedAt: performance.now() };
      } catch (error) {
        if (!signal.aborted && error instanceof ApiError && [401, 403].includes(error.status))
          setRevoked(true);
        throw error;
      }
    },
    enabled: user.role === 'OPERATIONS' && !revoked,
    refetchInterval: 5_000,
    refetchIntervalInBackground: false,
  });
  const denied =
    revoked || (metrics.error instanceof ApiError && [401, 403].includes(metrics.error.status));
  const snapshot = metrics.data;
  useEffect(() => {
    if (!denied) return;
    void (async () => {
      await queries.cancelQueries({ predicate: (query) => query.queryKey[0] !== 'session' });
      queries.removeQueries({ predicate: (query) => query.queryKey[0] !== 'session' });
      await queries.invalidateQueries({ queryKey: ['session'] });
    })();
  }, [denied, queries]);
  useEffect(() => {
    if (!restoreFocus.current || selectedId) return;
    restoreFocus.current = false;
    const row =
      view === 'orders' && lastSelected.current
        ? workspace.current?.querySelector<HTMLButtonElement>(
            `[data-order-id="${CSS.escape(lastSelected.current)}"]`,
          )
        : null;
    (row ?? (view === 'orders' ? ordersHeading.current : overviewHeading.current))?.focus();
  }, [view, selectedId]);
  if (user.role !== 'OPERATIONS') return null;
  if (denied)
    return (
      <p role="alert" className="demo-error">
        Sua sessão da operação não está disponível. Entre novamente para continuar.
      </p>
    );
  function navigate(next: 'overview' | 'orders') {
    if (next === 'orders') setOpenedOrders(true);
    setView(next);
    setSelectedId(null);
    restoreFocus.current = true;
  }
  return (
    <div className="operations-workspace" ref={workspace}>
      {!selectedId && (
        <nav className="workspace-nav" aria-label="Área da operação">
          <Button
            variant={view === 'overview' ? 'default' : 'outline'}
            aria-current={view === 'overview' ? 'page' : undefined}
            onClick={() => navigate('overview')}
          >
            <LayoutDashboard aria-hidden="true" /> Visão geral
          </Button>
          <Button
            variant={view === 'orders' ? 'default' : 'outline'}
            aria-current={view === 'orders' ? 'page' : undefined}
            onClick={() => navigate('orders')}
          >
            <ListFilter aria-hidden="true" /> Ver pedidos
          </Button>
        </nav>
      )}
      {view === 'overview' && !selectedId && (
        <section className="operations-overview" aria-labelledby="operations-overview-title">
          <div className="demo-heading">
            <div className="workspace-section-heading">
              <p className="eyebrow">A cidade, por inteiro</p>
              <h3 id="operations-overview-title" tabIndex={-1} ref={overviewHeading}>
                Visão geral da operação
              </h3>
              <p className="demo-description">
                Cenário simulado e seu próprio contexto. Cada pedido tem seu lugar.
              </p>
            </div>
            <Button
              variant="outline"
              disabled={metrics.isFetching}
              onClick={() => void metrics.refetch()}
            >
              <RefreshCw aria-hidden="true" /> Atualizar indicadores
            </Button>
          </div>
          {metrics.isPending && <p role="status">Consultando os indicadores...</p>}
          {metrics.error && (
            <p className="demo-error" role="alert">
              {metrics.error.message}
            </p>
          )}
          {snapshot && (
            <>
              <dl className="operations-indicators">
                {indicators.map(([key, label, tone]) => (
                  <div key={key} data-tone={tone}>
                    <dt>{label}</dt>
                    <dd>{snapshot[key].toLocaleString('pt-BR')}</dd>
                  </div>
                ))}
              </dl>
              <p className="operations-metrics-note">
                Visão geral autorizada, independente da área do mapa e dos filtros da lista.
                Atualizado às {new Date(snapshot.serverTime).toLocaleTimeString('pt-BR')}.
              </p>
            </>
          )}
          <Suspense fallback={<p role="status">Preparando o mapa da operação...</p>}>
            <OperationsMap
              user={user}
              onUnauthorized={() => {
                setRevoked(true);
                void queries.invalidateQueries({ queryKey: ['session'] });
              }}
            />
          </Suspense>
        </section>
      )}
      {openedOrders && (
        <div hidden={view !== 'orders' || Boolean(selectedId)}>
          <OrderBrowser
            user={user}
            titleRef={ordersHeading}
            clock={view === 'orders' && !selectedId ? snapshot : undefined}
            onOpen={(id) => {
              lastSelected.current = id;
              setSelectedId(id);
            }}
          />
        </div>
      )}
      {selectedId && (
        <OrderDetail
          key={selectedId}
          user={user}
          id={selectedId}
          onClose={() => {
            restoreFocus.current = true;
            setSelectedId(null);
            setView('orders');
          }}
        />
      )}
      {view === 'orders' && !selectedId && (
        <p className="operations-bottom-link">
          <Button variant="outline" onClick={() => navigate('overview')}>
            <ArrowLeft aria-hidden="true" /> Voltar à visão geral
          </Button>
        </p>
      )}
    </div>
  );
}
