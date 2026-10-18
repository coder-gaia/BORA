import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState, type FormEvent } from 'react';
import { RefreshCw } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { CourierOffer } from '@/components/courier-offer';
import { api, type CourierWorkView, type DemoUserView, type PositionReceipt } from '@/lib/api';

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
  const [inputLatitude, setLatitude] = useState<string | null>(null);
  const [inputLongitude, setLongitude] = useState<string | null>(null);
  const [confirmationCode, setConfirmationCode] = useState('');
  const [receipt, setReceipt] = useState<(PositionReceipt & { observedAt: number }) | null>(null);
  const [receiptClock, setReceiptClock] = useState(0);
  const [notice, setNotice] = useState('');
  const work = useQuery({
    queryKey: ['courier-work', user.id],
    queryFn: api.courierWork,
    refetchInterval(query) {
      const status = query.state.data?.courier.status;
      return status && (status !== 'OFFLINE' || receipt !== null) ? 2_000 : false;
    },
    refetchIntervalInBackground: false,
  });
  const courier = work.data?.courier;
  const order = work.data?.order;
  const latitude = inputLatitude ?? String(courier?.location?.latitude ?? -23.5614);
  const longitude = inputLongitude ?? String(courier?.location?.longitude ?? -46.6559);
  const route = useQuery({
    queryKey: ['courier-route', user.id, order?.id, order?.status, courier?.locationUpdatedAt],
    queryFn: api.courierRoute,
    enabled: Boolean(order && courier?.location),
  });

  async function refresh() {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ['courier-work'] }),
      queryClient.invalidateQueries({ queryKey: ['courier-offer'] }),
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
    mutationFn: () => api.sendPosition(position()),
    onMutate() {
      setReceipt(null);
      setNotice('');
    },
    onSuccess(value) {
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
    onSuccess() {
      setConfirmationCode('');
      setNotice('Entrega concluída. Você está disponível novamente.');
      void refresh();
    },
    onError() {
      void refresh();
    },
  });
  const busy =
    availability.isPending || movement.isPending || pickup.isPending || delivery.isPending;
  const persisted =
    receipt?.latestRecordedAt &&
    courier?.locationUpdatedAt &&
    Date.parse(courier.locationUpdatedAt) >= Date.parse(receipt.latestRecordedAt);
  useEffect(() => {
    if (!receipt || receipt.accepted === 0 || persisted) return;
    const timer = window.setInterval(() => setReceiptClock(performance.now()), 1_000);
    return () => window.clearInterval(timer);
  }, [receipt, persisted]);
  const confirmationDelayed = receipt && receiptClock - receipt.observedAt > 6_000;

  function submitPosition(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    movement.mutate();
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
                    disabled={busy}
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
                    disabled={busy}
                  />
                </label>
              </div>
              <div className="role-options">
                {courier.status === 'OFFLINE' ? (
                  <Button type="button" disabled={busy} onClick={() => availability.mutate(true)}>
                    Ficar online
                  </Button>
                ) : (
                  <Button
                    type="button"
                    variant="outline"
                    disabled={busy || courier.status !== 'AVAILABLE'}
                    onClick={() => availability.mutate(false)}
                  >
                    Ficar offline
                  </Button>
                )}
                <Button
                  type="submit"
                  variant="outline"
                  disabled={busy || courier.status === 'OFFLINE'}
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
                      ? 'A confirmação está demorando. Envie a posição novamente.'
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
        <CourierOffer userId={user.id} status={courier.status} busy={busy} onChanged={refresh} />
      )}
      {courier?.status === 'OFFLINE' && !order && (
        <p className="demo-empty">Fique online para receber ofertas de entrega.</p>
      )}
      {order && (
        <section className="order-detail" aria-labelledby="courier-order-title">
          <h3 id="courier-order-title">Entrega em andamento</h3>
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
              disabled={busy}
              onClick={() => {
                setLatitude(String(order.store.location.latitude));
                setLongitude(String(order.store.location.longitude));
              }}
            >
              Usar posição da coleta
            </Button>
            <Button
              variant="outline"
              disabled={busy}
              onClick={() => {
                setLatitude(String(order.dropoff.latitude));
                setLongitude(String(order.dropoff.longitude));
              }}
            >
              Usar posição do destino
            </Button>
          </div>
          {route.data && (
            <p className="demo-description">
              Rota até {route.data.destination === 'PICKUP' ? 'a loja' : 'o cliente'}:{' '}
              {(route.data.distanceMeters / 1_000).toLocaleString('pt-BR', {
                maximumFractionDigits: 2,
              })}{' '}
              km. Tempo estimado: {showDuration(route.data.simulatedDurationMillis)}.
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
            <Button disabled={busy} onClick={() => pickup.mutate()}>
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
                    disabled={busy}
                    autoComplete="off"
                  />
                </label>
              )}
              <Button type="submit" disabled={busy}>
                {delivery.isPending ? 'Concluindo...' : 'Confirmar entrega'}
              </Button>
            </form>
          )}
        </section>
      )}
    </div>
  );
}
