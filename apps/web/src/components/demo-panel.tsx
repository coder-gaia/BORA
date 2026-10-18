import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useRef, useState, type FormEvent } from 'react';
import { ArrowLeft, LogOut, Package, RefreshCw, Store, Truck, Users } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { api, type DemoRole, type DemoUserView, type OrderView } from '@/lib/api';
import { CourierWorkspace } from '@/components/courier-workspace';

const roles = [
  { value: 'STORE', label: 'Sou loja', icon: Store },
  { value: 'COURIER', label: 'Sou entregador', icon: Truck },
  { value: 'OPERATIONS', label: 'Operação', icon: Users },
] as const;

const statusLabels: Record<string, string> = {
  SEARCHING: 'Procurando entregador',
  ASSIGNED: 'Entregador a caminho da loja',
  AT_STORE: 'Entregador na loja',
  PICKED_UP: 'A caminho do cliente',
  DELIVERED: 'Entregue',
  CANCELED: 'Cancelado',
};
const eventLabels: Record<string, string> = {
  OFFER_CREATED: 'Oferta enviada ao entregador',
  OFFER_ACCEPTED: 'Oferta aceita',
  OFFER_REJECTED: 'Oferta recusada',
  OFFER_EXPIRED: 'Prazo da oferta encerrado',
  OFFER_CANCELED: 'Oferta cancelada',
  ORDER_SEARCH_DELAYED: 'Busca por entregador está demorando',
  ORDER_REASSIGNED: 'Pedido redistribuído',
};

function active(status: string | undefined) {
  return status !== undefined && status !== 'CANCELED' && status !== 'DELIVERED';
}

function statusLabel(status: string | undefined) {
  return status ? (statusLabels[status] ?? 'Estado indisponível') : 'Estado indisponível';
}

function formatTime(value: string | undefined) {
  if (!value) return 'Horário indisponível';
  return new Date(value).toLocaleString('pt-BR');
}

function ErrorMessage({ error }: { error: Error | null }) {
  return error ? (
    <p className="demo-error" role="alert">
      {error.message}
    </p>
  ) : null;
}

export function DemoPanel() {
  const queryClient = useQueryClient();
  const session = useQuery({ queryKey: ['session'], queryFn: api.session });
  const authentication = useMutation({
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

  return (
    <section
      className="demo-panel"
      aria-labelledby="demo-title"
      aria-busy={authentication.isPending}
    >
      <div className="demo-heading">
        <div>
          <p className="eyebrow">Demonstração</p>
          <h2 id="demo-title">Experimente um pedido</h2>
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
        Escolha como participar. Você pode trocar de papel e continuar no mesmo contexto.
      </p>
      <div className="role-options" aria-label="Escolher papel">
        {roles.map(({ value, label, icon: Icon }) => (
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
      <ErrorMessage error={authentication.error ?? session.error} />
      {session.isPending && <p role="status">Consultando sua sessão...</p>}
      {session.error && (
        <Button variant="outline" onClick={() => void session.refetch()}>
          Tentar novamente
        </Button>
      )}
      {user && !authentication.isPending && (
        <>
          <p className="session-summary" role="status">
            {user.displayName}. A sessão expira após duas horas sem uso.
          </p>
          {user.role === 'COURIER' ? (
            <CourierWorkspace key={user.id} user={user} />
          ) : (
            <OrderWorkspace key={`${user.id}:${user.role}`} user={user} />
          )}
        </>
      )}
      {!user && !session.isPending && !session.error && (
        <p className="demo-empty">
          Entre como loja para criar seu primeiro pedido. Seus dados ficam restritos ao seu contexto
          de demonstração.
        </p>
      )}
    </section>
  );
}

function OrderWorkspace({ user }: { user: DemoUserView }) {
  const queryClient = useQueryClient();
  const [page, setPage] = useState(0);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const enabled = user.role === 'STORE' || user.role === 'OPERATIONS';
  const orders = useQuery({
    queryKey: ['orders', user.id, page],
    queryFn: () => api.orders(page),
    enabled,
    refetchInterval: (query) =>
      query.state.data?.items.some((order) => active(order.status)) ? 3_000 : false,
    refetchIntervalInBackground: false,
  });
  const store = useQuery({
    queryKey: ['store', user.id],
    queryFn: api.store,
    enabled: user.role === 'STORE',
  });

  async function refreshOrders() {
    await queryClient.invalidateQueries({ queryKey: ['orders'] });
  }

  return (
    <div className="order-workspace">
      {user.role === 'STORE' && (
        <section className="order-create" aria-labelledby="create-title">
          <h3 id="create-title">Novo pedido</h3>
          {store.data && <p className="demo-description">Origem: {store.data.name}</p>}
          <ErrorMessage error={store.error} />
          <CreateOrderForm
            disabled={!store.data}
            onCreated={(order) => {
              setPage(0);
              setSelectedId(order.id!);
              void refreshOrders();
            }}
          />
        </section>
      )}

      <section aria-labelledby="orders-title">
        <div className="demo-heading">
          <h3 id="orders-title">Pedidos</h3>
          <Button
            variant="outline"
            disabled={orders.isFetching}
            onClick={() => void refreshOrders()}
          >
            <RefreshCw aria-hidden="true" />
            Atualizar
          </Button>
        </div>
        <p className="demo-description">
          Seus pedidos e o cenário simulado. Pedidos de outros visitantes ficam privados.
        </p>
        <ErrorMessage error={orders.error} />
        {orders.isPending && <p role="status">Carregando pedidos...</p>}
        {orders.data?.items?.length === 0 && (
          <p className="demo-empty">Nenhum pedido por aqui ainda.</p>
        )}
        <ul className="order-list" aria-label="Lista de pedidos">
          {orders.data?.items?.map((order) => (
            <li key={order.id}>
              <button
                className="order-list-button"
                type="button"
                onClick={() => setSelectedId(order.id!)}
                aria-pressed={selectedId === order.id}
              >
                <Package size={18} aria-hidden="true" />
                <span>
                  <strong>{order.store?.name}</strong>
                  <small>{formatTime(order.createdAt)}</small>
                </span>
                <span className="order-status">{statusLabel(order.status)}</span>
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

      {selectedId && (
        <OrderDetail
          user={user}
          id={selectedId}
          onClose={() => setSelectedId(null)}
          onChanged={() => void refreshOrders()}
        />
      )}
    </div>
  );
}

function CreateOrderForm({
  disabled,
  onCreated,
}: {
  disabled: boolean;
  onCreated: (order: OrderView) => void;
}) {
  const [latitude, setLatitude] = useState('-23.5504');
  const [longitude, setLongitude] = useState('-46.6333');
  const attempt = useRef<{ payload: string; key: string } | null>(null);
  const create = useMutation({
    mutationFn: async () => {
      const dropoff = { latitude: Number(latitude), longitude: Number(longitude) };
      if (!Number.isFinite(dropoff.latitude) || !Number.isFinite(dropoff.longitude))
        throw new Error('Informe coordenadas válidas.');
      const payload = JSON.stringify(dropoff);
      if (attempt.current?.payload !== payload)
        attempt.current = { payload, key: crypto.randomUUID() };
      return api.createOrder(dropoff, attempt.current.key);
    },
    onSuccess(order) {
      attempt.current = null;
      onCreated(order);
    },
  });

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    create.mutate();
  }

  return (
    <form onSubmit={submit} className="create-order-form">
      <p className="demo-description">
        Informe o destino dentro do centro expandido de São Paulo. O exemplo abaixo fica na região
        da Sé.
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
      <ErrorMessage error={create.error} />
      <Button type="submit" disabled={disabled || create.isPending}>
        {create.isPending ? 'Criando pedido...' : 'Criar pedido'}
      </Button>
    </form>
  );
}

function OrderDetail({
  user,
  id,
  onClose,
  onChanged,
}: {
  user: DemoUserView;
  id: string;
  onClose: () => void;
  onChanged: () => void;
}) {
  const queryClient = useQueryClient();
  const order = useQuery({
    queryKey: ['order', user.id, id],
    queryFn: () => api.order(id),
    refetchInterval: (query) => (active(query.state.data?.status) ? 2_000 : false),
    refetchIntervalInBackground: false,
  });
  const reassign = useMutation({
    mutationFn: () => api.reassignOrder(id, order.data!.version),
    async onSuccess() {
      await queryClient.invalidateQueries({ queryKey: ['order', user.id, id] });
      onChanged();
    },
    onError() {
      void queryClient.invalidateQueries({ queryKey: ['order', user.id, id] });
    },
  });
  const cancel = useMutation({
    mutationFn: () => api.cancelOrder(id, order.data!.version!),
    onSuccess(value) {
      queryClient.setQueryData(['order', user.id, id], value);
      onChanged();
    },
    onError() {
      void queryClient.invalidateQueries({ queryKey: ['order', user.id, id] });
    },
  });
  const item = order.data;
  const allowedStatus =
    item?.status === 'SEARCHING' ||
    item?.status === 'ASSIGNED' ||
    (user.role === 'OPERATIONS' && item?.status === 'AT_STORE');
  const canCancel =
    allowedStatus && (user.role === 'OPERATIONS' || item?.store?.id === user.storeId);
  const canReassign =
    user.role === 'OPERATIONS' && (item?.status === 'ASSIGNED' || item?.status === 'AT_STORE');
  const busy = cancel.isPending || reassign.isPending || order.isFetching;

  return (
    <section className="order-detail" aria-labelledby="detail-title">
      <div className="demo-heading">
        <h3 id="detail-title">Detalhe do pedido</h3>
        <Button variant="outline" onClick={onClose}>
          <ArrowLeft aria-hidden="true" />
          Fechar detalhe
        </Button>
      </div>
      <ErrorMessage error={order.error ?? cancel.error ?? reassign.error} />
      {order.isPending && <p role="status">Carregando detalhe...</p>}
      {item && (
        <>
          <p className="order-status" role="status">
            {statusLabel(item.status)}
          </p>
          <dl className="order-fields">
            <div>
              <dt>Loja</dt>
              <dd>{item.store?.name}</dd>
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
                {item.dropoff?.latitude}, {item.dropoff?.longitude}
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
                </dd>
              </div>
            )}
          </dl>
          <h4>Linha do tempo</h4>
          <ol className="order-timeline">
            {item.timeline?.map((event) => (
              <li key={event.id}>
                <span>{eventLabels[event.type] ?? statusLabel(event.toStatus)}</span>
                <time dateTime={event.occurredAt}>{formatTime(event.occurredAt)}</time>
              </li>
            ))}
          </ol>
          <div className="role-options">
            {canCancel && (
              <Button variant="outline" disabled={busy} onClick={() => cancel.mutate()}>
                {cancel.isPending ? 'Cancelando...' : 'Cancelar pedido'}
              </Button>
            )}
            {canReassign && (
              <Button variant="outline" disabled={busy} onClick={() => reassign.mutate()}>
                {reassign.isPending ? 'Redistribuindo...' : 'Redistribuir pedido'}
              </Button>
            )}
          </div>
        </>
      )}
    </section>
  );
}
