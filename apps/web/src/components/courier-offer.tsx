import { useEffect, useState } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import { Button } from '@/components/ui/button';
import { api, type CourierOffer as Offer } from '@/lib/api';

function OfferDetails({
  offer,
  serverTime,
  receivedAt,
  busy,
  onDecision,
}: {
  offer: Offer;
  serverTime: string;
  receivedAt: number;
  busy: boolean;
  onDecision: (action: 'accept' | 'reject') => void;
}) {
  const [now, setNow] = useState(() => performance.now());
  useEffect(() => {
    const timer = window.setInterval(() => setNow(performance.now()), 500);
    return () => window.clearInterval(timer);
  }, []);
  const seconds = Math.max(
    0,
    Math.ceil(
      (Date.parse(offer.expiresAt) - Date.parse(serverTime) - Math.max(0, now - receivedAt)) /
        1_000,
    ),
  );
  return (
    <>
      <p className="offer-countdown">
        {seconds > 0 ? `Responda em até ${seconds} s.` : 'Prazo encerrado. Consultando a oferta...'}
      </p>
      <dl className="order-fields">
        <div>
          <dt>Loja</dt>
          <dd>{offer.store.name}</dd>
        </div>
        <div>
          <dt>Coleta</dt>
          <dd>
            {offer.store.location.latitude}, {offer.store.location.longitude}
          </dd>
        </div>
        <div>
          <dt>Destino</dt>
          <dd>
            {offer.dropoff.latitude}, {offer.dropoff.longitude}
          </dd>
        </div>
        <div>
          <dt>Tempo estimado até a loja</dt>
          <dd>
            {offer.etaToPickupSeconds < 60
              ? `${offer.etaToPickupSeconds} s`
              : `${Math.ceil(offer.etaToPickupSeconds / 60)} min`}
          </dd>
        </div>
      </dl>
      <div className="role-options">
        <Button disabled={busy || seconds === 0} onClick={() => onDecision('accept')}>
          Aceitar oferta
        </Button>
        <Button
          variant="outline"
          disabled={busy || seconds === 0}
          onClick={() => onDecision('reject')}
        >
          Recusar oferta
        </Button>
      </div>
    </>
  );
}

export function CourierOffer({
  userId,
  status,
  busy,
  onChanged,
}: {
  userId: string;
  status: string;
  busy: boolean;
  onChanged: () => Promise<void>;
}) {
  const [notice, setNotice] = useState('');
  const enabled = status === 'AVAILABLE' || status === 'OFFERED';
  const inbox = useQuery({
    queryKey: ['courier-offer', userId],
    async queryFn() {
      const value = await api.courierOffer();
      return { ...value, receivedAt: performance.now() };
    },
    enabled,
    refetchInterval: enabled ? 2_000 : false,
    refetchIntervalInBackground: false,
  });
  const decision = useMutation({
    mutationFn: ({ id, action }: { id: string; action: 'accept' | 'reject' }) =>
      action === 'accept' ? api.acceptOffer(id) : api.rejectOffer(id),
    async onSuccess(_, { action }) {
      setNotice(
        action === 'accept' ? '' : 'Oferta recusada. Você continua disponível para outros pedidos.',
      );
      await onChanged();
    },
    async onError() {
      setNotice('');
      await onChanged();
    },
  });
  if (!enabled) return null;
  const offer = inbox.data?.offer;
  return (
    <section className="courier-offer" aria-labelledby="offer-title">
      <h3 id="offer-title">Ofertas para você</h3>
      {(inbox.error ?? decision.error) && (
        <p className="demo-error" role="alert">
          {(inbox.error ?? decision.error)?.message}
        </p>
      )}
      {inbox.isPending && <p role="status">Consultando ofertas...</p>}
      {offer && inbox.data && (
        <OfferDetails
          key={offer.id}
          offer={offer}
          serverTime={inbox.data.serverTime}
          receivedAt={inbox.data.receivedAt}
          busy={busy || decision.isPending}
          onDecision={(action) => decision.mutate({ id: offer.id, action })}
        />
      )}
      {!offer && !inbox.isPending && !inbox.error && (
        <p className="demo-empty" role="status">
          {status === 'OFFERED'
            ? 'Atualizando a oferta reservada para você...'
            : 'Aguardando uma oferta de entrega.'}
        </p>
      )}
      {notice && !offer && (
        <p className="demo-description" role="status">
          {notice}
        </p>
      )}
    </section>
  );
}
