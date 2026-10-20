import { lazy, Suspense } from 'react';
import { Clock3, Navigation } from 'lucide-react';
import { useTracking } from '@/hooks/use-tracking';
import { arrivalLabel, formatTime, statusLabel } from '@/lib/order-presentation';
import { OrderProgress } from '@/components/order-progress';

const DeliveryMap = lazy(() =>
  import('@/components/delivery-map').then((module) => ({ default: module.DeliveryMap })),
);

export function TrackingDelivery({
  code,
  showProgress = true,
}: {
  code: string;
  showProgress?: boolean;
}) {
  const { snapshot, error, connected } = useTracking(code);
  if (!snapshot)
    return (
      <div className="tracking-loading">
        {error ? (
          <p className="demo-error" role="alert">
            {error.message}
          </p>
        ) : (
          <p className="demo-description">Consultando o caminho da entrega...</p>
        )}
      </div>
    );
  const terminal = snapshot.status === 'DELIVERED' || snapshot.status === 'CANCELED';
  const progress = {
    status: snapshot.status,
    createdAt: snapshot.createdAt,
    timeline: snapshot.timeline.map((event) => ({ ...event, id: event.orderVersion })),
  };
  const geometry = snapshot.route
    ? {
        type: 'LineString',
        coordinates: snapshot.route.geometry.map((point) => [point.longitude, point.latitude]),
      }
    : null;
  return (
    <div className="tracking-delivery">
      {showProgress && (
        <p className="order-status tracking-status" role="status" data-status={snapshot.status}>
          {statusLabel(snapshot.status)}
        </p>
      )}
      {error && (
        <p className="demo-error" role="alert">
          {error.message} A última atualização está preservada.
        </p>
      )}
      <div className="delivery-eta">
        <div>
          <Clock3 aria-hidden="true" />
          <div>
            <span>
              {snapshot.status === 'ASSIGNED' || snapshot.route?.destination === 'PICKUP'
                ? 'Até a coleta'
                : 'Até o destino'}
            </span>
            <strong>
              {terminal
                ? snapshot.status === 'DELIVERED'
                  ? 'Entrega concluída'
                  : 'Entrega cancelada'
                : arrivalLabel(snapshot.etaSeconds)}
            </strong>
          </div>
        </div>
        <p>
          {terminal
            ? 'Este percurso foi encerrado.'
            : snapshot.etaSeconds != null
              ? 'Estimativa com o ritmo da simulação. Pode mudar durante o trajeto.'
              : snapshot.status === 'SEARCHING'
                ? 'A previsão aparece quando um entregador assumir.'
                : snapshot.status === 'AT_STORE'
                  ? 'O entregador chegou à loja e aguarda a coleta.'
                  : snapshot.courierPosition && snapshot.courierPositionUpdatedAt
                    ? 'Previsão temporariamente indisponível. A posição e as etapas continuam atualizando.'
                    : 'Aguardando uma posição recente do entregador.'}
          {snapshot.etaSeconds != null && snapshot.route && (
            <span className="eta-calculation-time">
              {' '}
              Calculada em {formatTime(snapshot.route.calculatedAt)}.
            </span>
          )}
        </p>
      </div>
      <Suspense fallback={<p className="demo-description">Preparando o mapa da entrega...</p>}>
        <DeliveryMap
          pickup={snapshot.pickup}
          dropoff={snapshot.dropoff}
          courier={snapshot.courierPosition}
          geometry={geometry}
        />
      </Suspense>
      <div className="tracking-freshness">
        <Navigation size={14} aria-hidden="true" />
        <p>
          {snapshot.courierPositionUpdatedAt
            ? `Posição recebida em ${formatTime(snapshot.courierPositionUpdatedAt)}.`
            : 'Sem posição recente do entregador.'}{' '}
          <span>
            {connected
              ? 'Atualizações ao vivo.'
              : 'Reconectando. Consultas periódicas estão ativas.'}
          </span>
        </p>
      </div>
      {showProgress && (
        <>
          <dl className="order-fields tracking-addresses">
            <div>
              <dt>Coleta</dt>
              <dd>
                {snapshot.storeName}
                <br />
                <span>
                  {snapshot.pickup.latitude}, {snapshot.pickup.longitude}
                </span>
              </dd>
            </div>
            <div>
              <dt>Destino</dt>
              <dd>
                {snapshot.dropoff.latitude}, {snapshot.dropoff.longitude}
              </dd>
            </div>
          </dl>
          <OrderProgress key={code} order={progress} publicView />
        </>
      )}
    </div>
  );
}
