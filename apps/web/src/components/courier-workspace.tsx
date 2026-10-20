import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { lazy, Suspense } from 'react';
import { Navigation, Pause, RefreshCw } from 'lucide-react';
import { useAutoDrive } from '@/hooks/use-auto-drive';
import { useTrainingOrder } from '@/hooks/use-training-order';
import { CopyValue } from '@/components/order-code';
import { pointForConfirmation, type DriveIdentity, type UncertainPosition } from '@/lib/auto-drive';
import type { Position } from '@/lib/route-motion';
import './courier-workspace.css';
import { Button } from '@/components/ui/button';
import { CourierOffer } from '@/components/courier-offer';
import {
  api,
  type CourierWorkView,
  type DemoUserView,
  type PositionReceipt,
  type OrderView,
  type CourierRouteView,
} from '@/lib/api';
import { useOrderUpdates, useRealtime } from '@/hooks/use-realtime';
import { newerOrder } from '@/lib/realtime';
import { OrderProgress } from '@/components/order-progress';
import { statusLabel } from '@/lib/order-presentation';
import { reconcileCourierWork } from '@/lib/courier-work';

const DeliveryMap = lazy(() =>
  import('@/components/delivery-map').then((module) => ({ default: module.DeliveryMap })),
);

const courierStatuses: Record<string, string> = {
  OFFLINE: 'Offline',
  AVAILABLE: 'Disponível',
  OFFERED: 'Com oferta pendente',
  BUSY: 'Em entrega',
};
const orderStatuses: Record<string, string> = {
  ASSIGNED: 'A caminho da loja',
  AT_STORE: 'Na loja',
  PICKED_UP: 'A caminho do cliente',
};

function showTime(value: string | null | undefined) {
  return value ? new Date(value).toLocaleTimeString('pt-BR') : 'Ainda não informada';
}

function showDuration(milliseconds: number) {
  return milliseconds < 60_000
    ? `${Math.ceil(milliseconds / 1_000)} s`
    : `${Math.ceil(milliseconds / 60_000)} min`;
}

function Failure({ error }: { error: Error | null }) {
  return error ? (
    <p className="demo-error" role="alert">
      {error.message}
    </p>
  ) : null;
}

export function CourierWorkspace({ user }: { user: DemoUserView }) {
  const queryClient = useQueryClient();
  const { connected } = useRealtime();
  const [inputLatitude, setLatitude] = useState<string | null>(null);
  const [inputLongitude, setLongitude] = useState<string | null>(null);
  const [confirmationCode, setConfirmationCode] = useState('');
  const [receipt, setReceipt] = useState<(PositionReceipt & { observedAt: number }) | null>(null);
  const [receiptClock, setReceiptClock] = useState(0);
  const [notice, setNotice] = useState('');
  const [observedOrderId, setObservedOrderId] = useState<string | null>(null);
  const [completedOrderId, setCompletedOrderId] = useState<string | null>(null);
  const [uncertainPosition, setUncertainPosition] = useState<UncertainPosition | null>(null);
  const [drivenRoute, setDrivenRoute] = useState<CourierRouteView | null>(null);
  const work = useQuery({
    queryKey: ['courier-work', user.id],
    async queryFn({ signal }) {
      const incoming = await api.courierWork(signal);
      const value = reconcileCourierWork(
        incoming,
        queryClient.getQueryData<CourierWorkView>(['courier-work', user.id]),
      );
      if (!signal.aborted) {
        if (value.order) setObservedOrderId(value.order.id);
        const current = value.order;
        const nextIdentity: DriveIdentity | null =
          current?.status === 'ASSIGNED'
            ? { orderId: current.id, destination: 'PICKUP' }
            : current?.status === 'PICKED_UP'
              ? { orderId: current.id, destination: 'DROPOFF' }
              : null;
        setUncertainPosition((point) => (pointForConfirmation(point, nextIdentity) ? point : null));
      }
      return value;
    },
    refetchInterval(query) {
      const status = query.state.data?.courier.status;
      const pendingPosition =
        receipt?.accepted &&
        receipt.latestRecordedAt &&
        Date.parse(query.state.data?.courier.locationUpdatedAt ?? '') <
          Date.parse(receipt.latestRecordedAt);
      return status && ((!connected && status !== 'OFFLINE') || pendingPosition) ? 2_000 : false;
    },
    refetchIntervalInBackground: false,
  });
  const courier = work.data?.courier;
  const order = work.data?.order;
  const timelineOrderId = order?.id ?? observedOrderId ?? completedOrderId;
  useOrderUpdates(timelineOrderId);
  const timeline = useQuery({
    queryKey: ['order', user.id, timelineOrderId],
    async queryFn({ signal }) {
      const incoming = await api.order(timelineOrderId!, signal);
      const current = queryClient.getQueryData<OrderView>(['order', user.id, timelineOrderId]);
      return newerOrder(incoming, current) ? incoming : current!;
    },
    enabled: Boolean(timelineOrderId),
    refetchInterval: (query) =>
      !connected && query.state.data && !['DELIVERED', 'CANCELED'].includes(query.state.data.status)
        ? 2_000
        : false,
    refetchIntervalInBackground: false,
  });
  const latitude = inputLatitude ?? String(courier?.location?.latitude ?? -23.5614);
  const longitude = inputLongitude ?? String(courier?.location?.longitude ?? -46.6559);

  const identity: DriveIdentity | null =
    order?.status === 'ASSIGNED'
      ? { orderId: order.id, destination: 'PICKUP' }
      : order?.status === 'PICKED_UP'
        ? { orderId: order.id, destination: 'DROPOFF' }
        : null;
  const receiveDrivenPosition = useCallback(
    (value: PositionReceipt) => {
      setReceipt({ ...value, observedAt: performance.now() });
      setNotice('');
      void queryClient.invalidateQueries({ queryKey: ['courier-work', user.id] });
    },
    [queryClient, user.id],
  );
  const drive = useAutoDrive({
    identity,
    onUncertain(point) {
      if (identity) setUncertainPosition({ ...identity, point });
    },
    onRoute(value) {
      setDrivenRoute(value);
      queryClient.setQueryData(['courier-route', user.id, order?.id, order?.status], value);
    },
    onWork(value) {
      return queryClient.setQueryData<CourierWorkView>(['courier-work', user.id], (current) =>
        reconcileCourierWork(value, current),
      )!;
    },
    onReceipt: receiveDrivenPosition,
    onInterrupted() {
      void queryClient.invalidateQueries({ queryKey: ['courier-work', user.id] });
    },
  });
  const route = useQuery({
    queryKey: ['courier-route', user.id, order?.id, order?.status],
    queryFn: ({ signal }) => api.courierRoute(signal),
    enabled:
      Boolean(
        order &&
        courier?.location &&
        courier.locationUpdatedAt &&
        Date.parse(courier.locationUpdatedAt) >= Date.parse(order.assignedAt),
      ) && !drive.driving,
    staleTime: 10_000,
    refetchOnMount: 'always',
  });
  const latestOrder =
    order &&
    (!timeline.data || order.id !== timeline.data.id || order.version > timeline.data.version)
      ? order
      : timeline.data;
  const training = useTrainingOrder(user.id, refresh, latestOrder);
  const mapRoute =
    drivenRoute &&
    drivenRoute.orderId === order?.id &&
    drivenRoute.destination === identity?.destination
      ? drivenRoute
      : route.data;

  async function refresh() {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ['courier-work'] }),
      queryClient.invalidateQueries({ queryKey: ['courier-offer'] }),
      queryClient.invalidateQueries({ queryKey: ['order', user.id] }),
    ]);
    const current = queryClient.getQueryData<CourierWorkView>(['courier-work', user.id]);
    if (current?.order) {
      await queryClient.invalidateQueries({ queryKey: ['courier-route'] });
    }
  }

  function position() {
    const point = { latitude: Number(latitude), longitude: Number(longitude) };
    if (
      !latitude ||
      !longitude ||
      !Number.isFinite(point.latitude) ||
      !Number.isFinite(point.longitude) ||
      Math.abs(point.latitude) > 90 ||
      Math.abs(point.longitude) > 180
    ) {
      throw new Error('Informe latitude e longitude válidas.');
    }
    return point;
  }

  const availability = useMutation({
    mutationFn: (online: boolean) => api.availability(online, online ? position() : undefined),
    onSuccess() {
      setReceipt(null);
      setNotice('');
      void refresh();
    },
    onError() {
      void refresh();
    },
  });
  const movement = useMutation({
    mutationFn: (confirmedPoint?: Position) => api.sendPosition(confirmedPoint ?? position()),
    onMutate() {
      setReceipt(null);
      setNotice('');
    },
    onSuccess(value) {
      if (value.accepted === 1) setUncertainPosition(null);
      setReceipt({ ...value, observedAt: performance.now() });
      setNotice('');
      void refresh();
    },
  });
  const pickup = useMutation({
    mutationFn: () => api.pickUpOrder(order!.id, order!.version),
    onSuccess() {
      setNotice('Coleta confirmada. Siga para o destino.');
      void refresh();
    },
    onError() {
      void refresh();
    },
  });
  const delivery = useMutation({
    mutationFn: () => api.deliverOrder(order!.id, order!.version, confirmationCode || undefined),
    onSuccess(value) {
      setCompletedOrderId(value.id);
      training.observeOrder(value);
      setConfirmationCode('');
      setNotice('Entrega concluída. Você está disponível novamente.');
      void refresh();
    },
    onError() {
      void refresh();
    },
  });
  const persisted =
    receipt?.latestRecordedAt &&
    courier?.locationUpdatedAt &&
    Date.parse(courier.locationUpdatedAt) >= Date.parse(receipt.latestRecordedAt);
  const awaitingPersistence = Boolean(receipt?.accepted === 1 && !persisted);
  const busy =
    availability.isPending ||
    movement.isPending ||
    pickup.isPending ||
    delivery.isPending ||
    drive.driving ||
    training.pending;
  const uncertainPoint = pointForConfirmation(uncertainPosition, identity);
  const needsPositionConfirmation = Boolean(uncertainPoint);
  const movementBlocked =
    busy || awaitingPersistence || work.isFetching || Boolean(needsPositionConfirmation);
  useEffect(() => {
    if (!receipt || receipt.accepted === 0 || persisted) return;
    const timer = window.setInterval(() => setReceiptClock(performance.now()), 1_000);
    return () => window.clearInterval(timer);
  }, [receipt, persisted]);
  const confirmationDelayed = receipt && receiptClock - receipt.observedAt > 6_000;

  function submitPosition(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!movementBlocked) movement.mutate();
  }

  return (
    <div className="order-workspace courier-workspace">
      <section aria-labelledby="courier-title">
        <div className="demo-heading">
          <h3 id="courier-title">Seu trabalho como entregador</h3>
          <Button variant="outline" disabled={work.isFetching} onClick={() => void refresh()}>
            <RefreshCw aria-hidden="true" />
            Atualizar trabalho
          </Button>
        </div>
        <Failure error={work.error} />
        {work.isPending && <p role="status">Consultando seu trabalho...</p>}
        {courier && (
          <>
            <p className="courier-status" role="status">
              {courierStatuses[courier.status] ?? 'Estado indisponível'}
            </p>
            <dl className="order-fields">
              <div>
                <dt>Posição confirmada</dt>
                <dd>
                  {courier.location
                    ? `${courier.location.latitude}, ${courier.location.longitude}`
                    : 'Ainda não informada'}
                </dd>
              </div>
              <div>
                <dt>Atualizada às</dt>
                <dd>{showTime(courier.locationUpdatedAt)}</dd>
              </div>
            </dl>
            <form className="create-order-form" onSubmit={submitPosition}>
              <p className="demo-description">
                Informe sua posição. O exemplo começa na loja da Paulista. Durante a entrega, use as
                coordenadas de coleta e destino mostradas abaixo.
              </p>
              <div className="coordinate-inputs">
                <label>
                  Latitude da sua posição
                  <input
                    name="courierLatitude"
                    type="number"
                    min="-90"
                    max="90"
                    step="any"
                    required
                    value={latitude}
                    onChange={(event) => setLatitude(event.target.value)}
                    disabled={movementBlocked}
                  />
                </label>
                <label>
                  Longitude da sua posição
                  <input
                    name="courierLongitude"
                    type="number"
                    min="-180"
                    max="180"
                    step="any"
                    required
                    value={longitude}
                    onChange={(event) => setLongitude(event.target.value)}
                    disabled={movementBlocked}
                  />
                </label>
              </div>
              <div className="role-options">
                {courier.status === 'OFFLINE' ? (
                  <Button
                    type="button"
                    disabled={movementBlocked}
                    onClick={() => availability.mutate(true)}
                  >
                    Ficar online
                  </Button>
                ) : (
                  <Button
                    type="button"
                    variant="outline"
                    disabled={movementBlocked || courier.status !== 'AVAILABLE'}
                    onClick={() => availability.mutate(false)}
                  >
                    Ficar offline
                  </Button>
                )}
                <Button
                  type="submit"
                  variant="outline"
                  disabled={movementBlocked || courier.status === 'OFFLINE'}
                >
                  {movement.isPending ? 'Enviando posição...' : 'Enviar posição'}
                </Button>
              </div>
            </form>
            <Failure
              error={availability.error ?? movement.error ?? pickup.error ?? delivery.error}
            />
            {receipt && (
              <p className="position-receipt" role="status">
                {receipt.accepted === 0
                  ? 'A posição foi ignorada por estar desatualizada.'
                  : persisted
                    ? 'Posição confirmada pelo servidor.'
                    : confirmationDelayed
                      ? 'A confirmação está demorando. Atualize seu trabalho para conferir a gravação.'
                      : 'Posição recebida. Aguardando confirmação do servidor.'}
              </p>
            )}
            {notice && (
              <p role="status" className="demo-description">
                {notice}
              </p>
            )}
          </>
        )}
      </section>
      {courier && (
        <section className="courier-training" aria-labelledby="training-title">
          <div>
            <p className="eyebrow">Pratique uma entrega</p>
            <h3 id="training-title">Treino no seu ritmo</h3>
            <p className="demo-description">
              Receba uma oferta privada e percorra cada etapa. Você decide quando aceitar, coletar e
              concluir. O percurso automático começa somente ao seu comando.
            </p>
          </div>
          <Button
            variant="outline"
            disabled={busy || (!training.recoverable && courier.status !== 'AVAILABLE')}
            onClick={training.startOrRecover}
          >
            {training.recoverable ? 'Recuperar treino' : 'Criar pedido de treino'}
          </Button>
          <Failure error={training.error} />
          {training.receipt && (
            <div className="training-receipt">
              <p>
                Treino {training.receipt.orderId}. Estado: {statusLabel(training.receipt.status)}.
              </p>
              <p className="demo-description">
                O código fica somente nesta tela. Use-o apenas ao concluir a entrega do treino.
              </p>
              <div className="training-code">
                <span>Código deste treino</span>
                <strong className="confirmation-code">{training.receipt.confirmationCode}</strong>
                <CopyValue
                  value={training.receipt.confirmationCode}
                  label="Copiar código deste treino"
                />
              </div>
              {['SEARCHING', 'ASSIGNED', 'AT_STORE'].includes(training.receipt.status) && (
                <Button variant="outline" disabled={movementBlocked} onClick={training.finish}>
                  Encerrar treino
                </Button>
              )}
              {['DELIVERED', 'CANCELED'].includes(training.receipt.status) && (
                <Button variant="outline" onClick={training.nextIntent}>
                  Preparar novo treino
                </Button>
              )}
            </div>
          )}
        </section>
      )}
      {courier && (
        <CourierOffer userId={user.id} status={courier.status} busy={busy} onChanged={refresh} />
      )}
      {courier?.status === 'OFFLINE' && !order && (
        <p className="demo-empty">Fique online para receber ofertas de entrega.</p>
      )}
      {order && (
        <section className="order-detail" aria-labelledby="courier-order-title">
          <h3 id="courier-order-title">Entrega em andamento</h3>
          <div className="courier-driving">
            <Suspense fallback={<p className="demo-description">Preparando seu percurso...</p>}>
              <DeliveryMap
                pickup={order.store.location}
                dropoff={order.dropoff}
                courier={courier?.location}
                geometry={
                  mapRoute && mapRoute.geometry.length >= 2
                    ? {
                        type: 'LineString',
                        coordinates: mapRoute.geometry.map((point) => [
                          point.longitude,
                          point.latitude,
                        ]),
                      }
                    : null
                }
              />
            </Suspense>
            {identity && (
              <div className="drive-actions">
                {drive.driving ? (
                  <Button onClick={drive.pause}>
                    <Pause aria-hidden="true" /> Pausar direção
                  </Button>
                ) : (
                  <Button
                    disabled={movementBlocked || !courier?.location}
                    onClick={() => {
                      setLatitude(null);
                      setLongitude(null);
                      drive.start();
                    }}
                  >
                    <Navigation aria-hidden="true" />
                    {drive.state === 'paused' || drive.state === 'failed'
                      ? 'Retomar direção'
                      : identity.destination === 'PICKUP'
                        ? 'Dirigir até a loja'
                        : 'Dirigir até o cliente'}
                  </Button>
                )}
              </div>
            )}
            {needsPositionConfirmation && (
              <div className="drive-reconciliation">
                <p className="demo-description" role="status">
                  A resposta do último envio não foi confirmada. A posição pode ter sido recebida.
                  Confirme o ponto enviado antes de retomar o percurso.
                </p>
                <Button
                  variant="outline"
                  disabled={busy || work.isFetching}
                  onClick={() => {
                    const point = pointForConfirmation(uncertainPosition, identity);
                    if (point) movement.mutate(point);
                    else void refresh();
                  }}
                >
                  Confirmar posição enviada
                </Button>
              </div>
            )}
            {drive.message && (
              <p className="demo-description" role="status">
                {drive.message}
              </p>
            )}
          </div>
          <p role="status">{orderStatuses[order.status] ?? 'Consulte o estado da entrega.'}</p>
          <dl className="order-fields">
            <div>
              <dt>Loja</dt>
              <dd>{order.store.name}</dd>
            </div>
            <div>
              <dt>Coleta</dt>
              <dd>
                {order.store.location.latitude}, {order.store.location.longitude}
              </dd>
            </div>
            <div>
              <dt>Destino</dt>
              <dd>
                {order.dropoff.latitude}, {order.dropoff.longitude}
              </dd>
            </div>
          </dl>
          <div className="role-options">
            <Button
              variant="outline"
              disabled={movementBlocked}
              onClick={() => {
                setLatitude(String(order.store.location.latitude));
                setLongitude(String(order.store.location.longitude));
              }}
            >
              Usar posição da coleta
            </Button>
            <Button
              variant="outline"
              disabled={movementBlocked}
              onClick={() => {
                setLatitude(String(order.dropoff.latitude));
                setLongitude(String(order.dropoff.longitude));
              }}
            >
              Usar posição do destino
            </Button>
          </div>
          {mapRoute && (
            <p className="demo-description">
              Rota até {mapRoute.destination === 'PICKUP' ? 'a loja' : 'o cliente'}:{' '}
              {(mapRoute.distanceMeters / 1_000).toLocaleString('pt-BR', {
                maximumFractionDigits: 2,
              })}{' '}
              km. Tempo estimado no ritmo da simulação:{' '}
              {showDuration(mapRoute.simulatedDurationMillis)}. A chegada depende da posição
              confirmada.
            </p>
          )}
          <Failure error={route.error} />
          {order.status === 'ASSIGNED' && (
            <p className="demo-description">
              Envie sua posição ao chegar a até 60 metros da loja. Aguarde a confirmação de chegada
              para coletar.
            </p>
          )}
          {order.status === 'AT_STORE' && (
            <Button disabled={movementBlocked} onClick={() => pickup.mutate()}>
              Confirmar coleta
            </Button>
          )}
          {order.status === 'PICKED_UP' && (
            <form
              onSubmit={(event) => {
                event.preventDefault();
                delivery.mutate();
              }}
            >
              {order.confirmationRequired && (
                <label className="delivery-code-label">
                  Código de confirmação
                  <input
                    name="confirmationCode"
                    inputMode="numeric"
                    pattern="[0-9]{6}"
                    minLength={6}
                    maxLength={6}
                    required
                    value={confirmationCode}
                    onChange={(event) => setConfirmationCode(event.target.value)}
                    disabled={movementBlocked}
                    autoComplete="off"
                  />
                </label>
              )}
              <Button type="submit" disabled={movementBlocked}>
                {delivery.isPending ? 'Concluindo...' : 'Confirmar entrega'}
              </Button>
            </form>
          )}
          {timeline.data && <OrderProgress key={timeline.data.id} order={timeline.data} />}
        </section>
      )}
      {!order && completedOrderId && timeline.data?.status === 'DELIVERED' && (
        <section className="order-detail" aria-labelledby="last-delivery-title">
          <h3 id="last-delivery-title">Última entrega</h3>
          <OrderProgress key={timeline.data.id} order={timeline.data} />
        </section>
      )}
    </div>
  );
}
