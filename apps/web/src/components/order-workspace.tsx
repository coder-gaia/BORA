import { notifyManager, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  lazy,
  Suspense,
  useCallback,
  useEffect,
  useRef,
  useState,
  useSyncExternalStore,
  type FormEvent,
} from 'react';
import {
  ArrowLeft,
  ArrowUpRight,
  Check,
  ListFilter,
  Package,
  Plus,
  RefreshCw,
  Search,
} from 'lucide-react';
import { Button } from '@/components/ui/button';
import { api, type DemoUserView, type OrderView, type StoreView } from '@/lib/api';
import { useOrderUpdates, useRealtime } from '@/hooks/use-realtime';
import { newerOrder } from '@/lib/realtime';
import {
  activeOrder,
  formatTime,
  normalizeOrderCode,
  orderStatuses,
  statusLabel,
  trackingPath,
  type OrderScope,
  type OrderStatus,
} from '@/lib/order-presentation';
import { WaitingAge } from '@/components/waiting-age';
import type { OperationsClock } from '@/lib/operations-clock';
import { OrderProgress } from '@/components/order-progress';
import { CopyValue, OrderCode } from '@/components/order-code';
import { TrackingDelivery } from '@/components/tracking-delivery';
import type { DeliveryPoint } from '@/components/delivery-map';
import './order-workspace.css';

const DeliveryMap = lazy(() =>
  import('@/components/delivery-map').then((module) => ({ default: module.DeliveryMap })),
);
type CreationAttempt = { payload: string; key: string };
type CreationIntent = CreationAttempt & { dropoff: DeliveryPoint };

export function OrderError({ error }: { error: Error | null }) {
  return error ? (
    <p className="demo-error" role="alert">
      {error.message}
    </p>
  ) : null;
}

export function OrderWorkspace({ user }: { user: DemoUserView }) {
  const queryClient = useQueryClient();
  const [view, setView] = useState<'create' | 'orders'>('create');
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [returnView, setReturnView] = useState<'create' | 'orders'>('create');
  const [created, setCreated] = useState<OrderView | null>(null);
  const [destination, setDestination] = useState({ latitude: '-23.5504', longitude: '-46.6333' });
  const createAttempt = useRef<CreationAttempt | null>(null);
  const workspaceAlive = useRef(true);
  useEffect(() => {
    workspaceAlive.current = true;
    return () => {
      workspaceAlive.current = false;
    };
  }, []);
  const workspace = useRef<HTMLDivElement>(null);
  const returnFocus = useRef<string | null>(null);
  const title = useRef<HTMLHeadingElement>(null);
  const focusNext = useRef(false);
  const store = useQuery({
    queryKey: ['store', user.id],
    queryFn: ({ signal }) => api.store(signal),
    enabled: user.role === 'STORE',
  });
  const ordersView = view === 'orders';
  function navigate(next: 'create' | 'orders') {
    focusNext.current = true;
    setSelectedId(null);
    setView(next);
  }
  function open(id: string) {
    returnFocus.current = id;
    setReturnView(view);
    setSelectedId(id);
  }
  useEffect(() => {
    if (!selectedId && focusNext.current) {
      focusNext.current = false;
      const row =
        view === 'orders' && returnFocus.current
          ? workspace.current?.querySelector<HTMLButtonElement>(
              `[data-order-id="${CSS.escape(returnFocus.current)}"]`,
            )
          : null;
      (row ?? title.current)?.focus();
    }
  }, [view, selectedId]);

  return (
    <div className="order-workspace" ref={workspace}>
      {!selectedId && (
        <nav className="workspace-nav" aria-label="Área de pedidos">
          {user.role === 'STORE' && (
            <Button
              variant={!ordersView ? 'default' : 'outline'}
              onClick={() => navigate('create')}
              aria-current={!ordersView ? 'page' : undefined}
            >
              <Plus aria-hidden="true" /> Novo pedido
            </Button>
          )}
          <Button
            variant={ordersView ? 'default' : 'outline'}
            onClick={() => navigate('orders')}
            aria-current={ordersView ? 'page' : undefined}
          >
            <ListFilter aria-hidden="true" /> Ver pedidos
          </Button>
        </nav>
      )}

      {selectedId ? (
        <OrderDetail
          key={selectedId}
          user={user}
          id={selectedId}
          onClose={() => {
            focusNext.current = true;
            setSelectedId(null);
            setView(returnView);
          }}
        />
      ) : null}

      <div hidden={Boolean(selectedId) || ordersView}>
        {user.role === 'STORE' ? (
          <section className="order-create" aria-labelledby="create-title">
            <div className="workspace-section-heading">
              <p className="eyebrow">Uma nova entrega</p>
              <h3 id="create-title" tabIndex={-1} ref={!ordersView ? title : undefined}>
                Novo pedido
              </h3>
              <p className="demo-description">
                Escolha o destino. A loja e a coleta já estão definidas.
              </p>
            </div>
            <OrderError error={store.error} />
            {store.isPending && <p role="status">Consultando sua loja...</p>}
            {store.data && !ordersView && !selectedId && (
              <CreateOrderForm
                store={store.data}
                destination={destination}
                setDestination={setDestination}
                attemptRef={createAttempt}
                onStarted={() => setCreated(null)}
                onCreated={(order) => {
                  if (!workspaceAlive.current) return;
                  setCreated(order);
                  queryClient.setQueryData(['order', user.id, order.id], order);
                  void queryClient.invalidateQueries({ queryKey: ['orders', user.id] });
                }}
              />
            )}
            {created && (
              <section className="created-order-notice" aria-labelledby="created-title">
                <div className="created-order-heading">
                  <Check aria-hidden="true" />
                  <h4 id="created-title">Pedido criado</h4>
                </div>
                <p role="status">
                  A busca por um entregador começou. Guarde o código para encontrar este pedido
                  depois.
                </p>
                <OrderCode code={created.publicCode} />
                <Button onClick={() => open(created.id)}>
                  Acompanhar pedido <ArrowUpRight aria-hidden="true" />
                </Button>
              </section>
            )}
          </section>
        ) : (
          <div className="orders-intro">
            <Package size={28} aria-hidden="true" />
            <h3 ref={!ordersView ? title : undefined} tabIndex={-1}>
              Sua operação, no seu ritmo
            </h3>
            <p className="demo-description">
              Abra os pedidos quando quiser consultar o cenário ou acompanhar uma entrega.
            </p>
          </div>
        )}
      </div>

      <div hidden={Boolean(selectedId) || !ordersView}>
        {ordersView && <OrderBrowser user={user} onOpen={open} titleRef={title} />}
      </div>
    </div>
  );
}

function CreateOrderForm({
  store,
  destination,
  setDestination: changeDestination,
  attemptRef,
  onStarted,
  onCreated,
}: {
  store: StoreView;
  destination: { latitude: string; longitude: string };
  setDestination: (value: { latitude: string; longitude: string }) => void;
  attemptRef: React.RefObject<CreationAttempt | null>;
  onStarted: () => void;
  onCreated: (order: OrderView) => void;
}) {
  const { latitude, longitude } = destination;
  const setLatitude = (value: string) => changeDestination({ ...destination, latitude: value });
  const setLongitude = (value: string) => changeDestination({ ...destination, longitude: value });
  const create = useMutation({
    mutationFn: async ({ dropoff, key }: CreationIntent) => {
      if (!Number.isFinite(dropoff.latitude) || !Number.isFinite(dropoff.longitude))
        throw new Error('Informe coordenadas válidas.');
      return api.createOrder(dropoff, key);
    },
    onSuccess(order, intent) {
      if (attemptRef.current?.key !== intent.key) return;
      attemptRef.current = null;
      onCreated(order);
    },
  });
  const point = { latitude: Number(latitude), longitude: Number(longitude) };
  const validPoint =
    latitude.trim() !== '' &&
    longitude.trim() !== '' &&
    Number.isFinite(point.latitude) &&
    Number.isFinite(point.longitude) &&
    Math.abs(point.latitude) <= 90 &&
    Math.abs(point.longitude) <= 180;
  function setDestination(value: DeliveryPoint) {
    if (!create.isPending)
      changeDestination({ latitude: String(value.latitude), longitude: String(value.longitude) });
  }
  return (
    <form
      onSubmit={(event: FormEvent<HTMLFormElement>) => {
        event.preventDefault();
        const dropoff = { latitude: Number(latitude), longitude: Number(longitude) };
        const payload = JSON.stringify(dropoff);
        if (attemptRef.current?.payload !== payload)
          attemptRef.current = { payload, key: crypto.randomUUID() };
        onStarted();
        create.mutate({ ...attemptRef.current, dropoff });
      }}
      className="create-order-form"
    >
      <div className="store-origin">
        <span className="store-origin-symbol" aria-hidden="true">
          L
        </span>
        <div>
          <span>Coleta na loja</span>
          <strong>{store.name}</strong>
        </div>
      </div>
      <Suspense fallback={<p className="demo-description">Preparando mapa do destino...</p>}>
        <DeliveryMap
          pickup={store.location}
          dropoff={validPoint ? point : { latitude: -23.5504, longitude: -46.6333 }}
          onDestination={setDestination}
        />
      </Suspense>
      <div className="destination-coordinates">
        <p className="demo-description">
          Destino no centro expandido de São Paulo. Você também pode informar as coordenadas.
        </p>
        <div className="coordinate-inputs">
          <label>
            Latitude
            <input
              name="latitude"
              type="number"
              step="any"
              min="-90"
              max="90"
              required
              value={latitude}
              onChange={(event) => setLatitude(event.target.value)}
              disabled={create.isPending}
            />
          </label>
          <label>
            Longitude
            <input
              name="longitude"
              type="number"
              step="any"
              min="-180"
              max="180"
              required
              value={longitude}
              onChange={(event) => setLongitude(event.target.value)}
              disabled={create.isPending}
            />
          </label>
        </div>
      </div>
      <OrderError error={create.error} />
      <Button type="submit" disabled={create.isPending}>
        {create.isPending ? 'Criando pedido...' : 'Criar pedido'}
      </Button>
    </form>
  );
}

export function OrderBrowser({
  user,
  onOpen,
  titleRef,
  clock,
}: {
  user: DemoUserView;
  onOpen: (id: string) => void;
  titleRef: React.RefObject<HTMLHeadingElement | null>;
  clock?: OperationsClock;
}) {
  const queryClient = useQueryClient();
  const [status, setStatus] = useState<OrderStatus | ''>('');
  const [scope, setScope] = useState<OrderScope>(user.role === 'STORE' ? 'OWN' : 'ALL');
  const [sort, setSort] = useState<'RECENT' | 'WAITING'>('RECENT');
  const [code, setCode] = useState('');
  const [draftCode, setDraftCode] = useState('');
  const [page, setPage] = useState(0);
  const [revision, setRevision] = useState(0);
  const generation = useRef(0);
  const snapshot = useRef<string | undefined>(undefined);
  const queryKey = [
    'orders',
    user.id,
    'browser',
    revision,
    status,
    code,
    scope,
    sort,
    page,
  ] as const;
  const orders = useQuery({
    queryKey,
    queryFn: async ({ signal }) => {
      const result = await api.orders(page, signal, {
        status: status || undefined,
        code: code || undefined,
        scope,
        snapshotAt: snapshot.current,
        sort,
      });
      if (!signal.aborted && revision === generation.current)
        snapshot.current ??= result.snapshotAt;
      return result;
    },
    enabled: false,
  });
  const refetch = orders.refetch;
  useEffect(() => {
    void refetch();
  }, [refetch, revision, status, code, scope, sort, page]);
  const observe = useCallback(
    (notify: () => void) => queryClient.getQueryCache().subscribe(notifyManager.batchCalls(notify)),
    [queryClient],
  );
  const invalidated = useSyncExternalStore(
    observe,
    () => queryClient.getQueryState(queryKey)?.isInvalidated ?? false,
    () => false,
  );
  function restart() {
    generation.current++;
    snapshot.current = undefined;
    setPage(0);
    setRevision(generation.current);
  }
  return (
    <section className="order-browser" aria-labelledby="orders-title">
      <div className="demo-heading">
        <div className="workspace-section-heading">
          <p className="eyebrow">Encontre e acompanhe</p>
          <h3 id="orders-title" ref={titleRef} tabIndex={-1}>
            Pedidos
          </h3>
        </div>
        <Button variant="outline" disabled={orders.isFetching} onClick={restart}>
          <RefreshCw aria-hidden="true" /> Atualizar
        </Button>
      </div>
      <form
        className="order-filters"
        onSubmit={(event) => {
          event.preventDefault();
          setCode(normalizeOrderCode(draftCode));
          restart();
        }}
      >
        <label>
          Mostrar
          <select
            value={scope}
            onChange={(event) => {
              setScope(event.target.value as OrderScope);
              restart();
            }}
          >
            <option value="OWN">Meus pedidos</option>
            <option value="PUBLIC">Cenário simulado</option>
            <option value="ALL">Meus pedidos e cenário</option>
          </select>
        </label>
        <label>
          Status do pedido
          <select
            value={status}
            onChange={(event) => {
              setStatus(event.target.value as OrderStatus | '');
              if (event.target.value !== 'SEARCHING') setSort('RECENT');
              restart();
            }}
          >
            <option value="">Todos os status</option>
            {orderStatuses.map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </select>
        </label>
        {user.role === 'OPERATIONS' && (
          <label>
            Ordenar pedidos
            <select
              value={sort}
              onChange={(event) => {
                const next = event.target.value as 'RECENT' | 'WAITING';
                setSort(next);
                if (next === 'WAITING') setStatus('SEARCHING');
                restart();
              }}
            >
              <option value="RECENT">Mais recentes</option>
              <option value="WAITING">Fila de busca</option>
            </select>
          </label>
        )}
        <label className="order-search-label">
          Código do pedido
          <div className="order-search-field">
            <Search size={17} aria-hidden="true" />
            <input
              type="search"
              autoComplete="off"
              placeholder="Cole o código completo"
              value={draftCode}
              onChange={(event) => setDraftCode(event.target.value)}
            />
          </div>
        </label>
        <Button type="submit" variant="outline">
          Buscar pedido
        </Button>
        {(code || status) && (
          <Button
            type="button"
            variant="outline"
            onClick={() => {
              setStatus('');
              setSort('RECENT');
              setCode('');
              setDraftCode('');
              restart();
            }}
          >
            Limpar filtros
          </Button>
        )}
      </form>
      <p className="demo-description">
        {scope === 'OWN'
          ? 'Somente os pedidos deste contexto de demonstração.'
          : scope === 'PUBLIC'
            ? 'Pedidos criados pelo simulador da cidade.'
            : 'Seus pedidos e os do simulador. Outros visitantes permanecem privados.'}{' '}
        A lista fica estável enquanto você lê.
      </p>
      {invalidated && orders.data && (
        <div className="orders-update-notice">
          <p role="status">Há atualizações disponíveis. Seu lugar na lista foi preservado.</p>
          <Button variant="outline" disabled={orders.isFetching} onClick={restart}>
            Ver atualizações
          </Button>
        </div>
      )}
      <OrderError error={orders.error} />
      {orders.isPending && <p role="status">Carregando pedidos...</p>}
      {orders.data?.items.length === 0 && (
        <div className="orders-empty">
          <Package aria-hidden="true" />
          <h4>Nenhum pedido encontrado</h4>
          <p className="demo-description">
            {code
              ? 'Confira o código completo, o status e a origem escolhidos. A busca respeita os pedidos que você pode acessar.'
              : 'Experimente outro status ou crie seu primeiro pedido.'}
          </p>
        </div>
      )}
      <ul className="order-list" aria-label="Lista de pedidos">
        {orders.data?.items.map((order) => (
          <li key={order.id}>
            <button
              className="order-list-button"
              data-order-id={order.id}
              type="button"
              onClick={() => onOpen(order.id)}
            >
              <Package size={18} aria-hidden="true" />
              <span>
                <strong>{order.store.name}</strong>
                <code className="order-list-code">{order.publicCode}</code>
                <small>{formatTime(order.createdAt)}</small>
                {user.role === 'OPERATIONS' && (
                  <WaitingAge since={order.searchingSince} clock={clock} />
                )}
              </span>
              <span className="order-status" data-status={order.status}>
                {statusLabel(order.status)}
              </span>
              <ArrowUpRight size={18} aria-hidden="true" />
            </button>
          </li>
        ))}
      </ul>
      {(orders.data?.totalPages ?? 0) > 1 && (
        <nav className="pagination" aria-label="Páginas de pedidos">
          <Button
            variant="outline"
            disabled={page === 0 || orders.isFetching}
            onClick={() => setPage(page - 1)}
          >
            Anterior
          </Button>
          <span>
            Página {page + 1} de {orders.data?.totalPages}
          </span>
          <Button
            variant="outline"
            disabled={page + 1 >= (orders.data?.totalPages ?? 0) || orders.isFetching}
            onClick={() => setPage(page + 1)}
          >
            Próxima
          </Button>
        </nav>
      )}
    </section>
  );
}

export function OrderDetail({
  user,
  id,
  onClose,
}: {
  user: DemoUserView;
  id: string;
  onClose: () => void;
}) {
  const queryClient = useQueryClient();
  const { connected } = useRealtime();
  const heading = useRef<HTMLHeadingElement>(null);
  const confirmationHeading = useRef<HTMLHeadingElement>(null);
  const [intent, setIntent] = useState<{ action: 'cancel' | 'reassign'; version: number } | null>(
    null,
  );
  const [operationNotice, setOperationNotice] = useState('');
  const hadIntent = useRef(false);
  useEffect(() => {
    if (intent) confirmationHeading.current?.focus();
    else if (hadIntent.current) heading.current?.focus();
    hadIntent.current = Boolean(intent);
  }, [intent]);
  useEffect(() => {
    heading.current?.focus();
  }, []);
  useOrderUpdates(id);
  const order = useQuery({
    queryKey: ['order', user.id, id],
    async queryFn({ signal }) {
      const value = await api.order(id, signal);
      const current = queryClient.getQueryData<OrderView>(['order', user.id, id]);
      return newerOrder(value, current) ? value : current!;
    },
    refetchInterval: (query) =>
      !connected && activeOrder(query.state.data?.status) ? 2_000 : false,
    refetchIntervalInBackground: false,
  });
  const reassign = useMutation({
    mutationFn: (version: number) => api.reassignOrder(id, version),
    async onSuccess() {
      setIntent(null);
      setOperationNotice(
        'Busca por outro entregador iniciada. O pedido continua aberto neste detalhe.',
      );
      await queryClient.invalidateQueries({ queryKey: ['order', user.id, id] });
      void queryClient.invalidateQueries({ queryKey: ['orders', user.id] });
    },
    onError() {
      setIntent(null);
      void queryClient.invalidateQueries({ queryKey: ['order', user.id, id] });
    },
  });
  const cancel = useMutation({
    mutationFn: (version: number) => api.cancelOrder(id, version),
    onSuccess(value) {
      setIntent(null);
      if (user.role === 'OPERATIONS')
        setOperationNotice('Pedido cancelado. O histórico permanece disponível.');
      queryClient.setQueryData(['order', user.id, id], value);
      void queryClient.invalidateQueries({ queryKey: ['orders', user.id] });
    },
    onError() {
      setIntent(null);
      void queryClient.invalidateQueries({ queryKey: ['order', user.id, id] });
    },
  });
  const item = order.data;
  const allowedStatus =
    item?.status === 'SEARCHING' ||
    item?.status === 'ASSIGNED' ||
    (user.role === 'OPERATIONS' && item?.status === 'AT_STORE');
  const canCancel =
    allowedStatus && (user.role === 'OPERATIONS' || item?.store.id === user.storeId);
  const canReassign =
    user.role === 'OPERATIONS' && (item?.status === 'ASSIGNED' || item?.status === 'AT_STORE');
  const busy = cancel.isPending || reassign.isPending || order.isFetching;
  return (
    <section className="order-detail" data-order-id={id} aria-labelledby="detail-title">
      <div className="demo-heading">
        <div className="workspace-section-heading">
          <p className="eyebrow">Uma entrega, cada etapa</p>
          <h3 id="detail-title" ref={heading} tabIndex={-1}>
            Detalhe do pedido
          </h3>
        </div>
        <Button variant="outline" onClick={onClose}>
          <ArrowLeft aria-hidden="true" /> Voltar
        </Button>
      </div>
      <OrderError error={order.error ?? cancel.error ?? reassign.error} />
      {order.isPending && <p role="status">Carregando detalhe...</p>}
      {item && (
        <>
          <p className="order-status" role="status" data-status={item.status}>
            {statusLabel(item.status)}
          </p>
          <OrderCode code={item.publicCode} />
          <div className="tracking-share">
            <div>
              <h4>Compartilhe o caminho</h4>
              <p>
                Quem receber este link acompanha somente esta entrega. O código de confirmação não é
                compartilhado.
              </p>
              <a
                className="tracking-public-link"
                href={trackingPath(item.publicCode)}
                target="_blank"
                rel="noopener noreferrer"
              >
                Abrir rastreio público <ArrowUpRight size={16} aria-hidden="true" />
              </a>
            </div>
            <CopyValue
              value={`${window.location.origin}${trackingPath(item.publicCode)}`}
              label="Copiar link de rastreio"
            />
          </div>
          <TrackingDelivery code={item.publicCode} showProgress={false} />
          <dl className="order-fields">
            <div>
              <dt>Loja</dt>
              <dd>{item.store.name}</dd>
            </div>
            <div>
              <dt>Coleta</dt>
              <dd>
                {item.store.location.latitude}, {item.store.location.longitude}
              </dd>
            </div>
            <div>
              <dt>Destino</dt>
              <dd>
                {item.dropoff.latitude}, {item.dropoff.longitude}
              </dd>
            </div>
            <div>
              <dt>Criado em</dt>
              <dd>{formatTime(item.createdAt)}</dd>
            </div>
            <div>
              <dt>Prazo da entrega</dt>
              <dd>{formatTime(item.slaDeadline)}</dd>
            </div>
            {item.confirmationCode && (
              <div>
                <dt>Código de confirmação</dt>
                <dd>
                  <strong className="confirmation-code">{item.confirmationCode}</strong>
                  <small className="confirmation-help">
                    Informe ao entregador apenas no momento da entrega.
                  </small>
                </dd>
              </div>
            )}
          </dl>
          <OrderProgress key={item.id} order={item} />
          <div className="role-options">
            {canCancel && (
              <Button
                variant="outline"
                disabled={busy || Boolean(intent)}
                onClick={() => {
                  if (user.role === 'OPERATIONS') {
                    cancel.reset();
                    setOperationNotice('');
                    setIntent({ action: 'cancel', version: item.version });
                  } else cancel.mutate(item.version);
                }}
              >
                {cancel.isPending ? 'Cancelando...' : 'Cancelar pedido'}
              </Button>
            )}
            {canReassign && (
              <Button
                variant="outline"
                disabled={busy || Boolean(intent)}
                onClick={() => {
                  reassign.reset();
                  setOperationNotice('');
                  setIntent({ action: 'reassign', version: item.version });
                }}
              >
                {reassign.isPending ? 'Redistribuindo...' : 'Redistribuir pedido'}
              </Button>
            )}
          </div>
          {operationNotice && (
            <p role="status" className="operations-action-notice">
              {operationNotice}
            </p>
          )}
          {intent && user.role === 'OPERATIONS' && (
            <section
              className="operations-confirmation"
              aria-labelledby="operations-confirmation-title"
            >
              <h4 id="operations-confirmation-title" tabIndex={-1} ref={confirmationHeading}>
                {intent.action === 'reassign'
                  ? 'Redistribuir este pedido?'
                  : 'Cancelar este pedido?'}
              </h4>
              <code>{item.publicCode}</code>
              <p>
                {intent.action === 'reassign'
                  ? 'O entregador atual será liberado e uma nova busca começará. O histórico será preservado.'
                  : 'A entrega será encerrada e os recursos reservados serão liberados. O histórico será preservado.'}
              </p>
              {intent.version !== item.version && (
                <p role="status">
                  O pedido mudou. Reveja a etapa atual antes de confirmar outra ação.
                </p>
              )}
              <div className="role-options">
                <Button
                  disabled={
                    busy ||
                    intent.version !== item.version ||
                    (intent.action === 'reassign' ? !canReassign : !canCancel)
                  }
                  onClick={() =>
                    intent.action === 'reassign'
                      ? reassign.mutate(intent.version)
                      : cancel.mutate(intent.version)
                  }
                >
                  {intent.action === 'reassign'
                    ? 'Confirmar redistribuição'
                    : 'Confirmar cancelamento'}
                </Button>
                <Button
                  variant="outline"
                  disabled={cancel.isPending || reassign.isPending}
                  onClick={() => setIntent(null)}
                >
                  Rever pedido
                </Button>
              </div>
            </section>
          )}
        </>
      )}
    </section>
  );
}
