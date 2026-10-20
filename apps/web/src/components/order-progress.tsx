import { useId, useRef, useState } from 'react';
import { Check, Circle, X } from 'lucide-react';
import {
  deliveryProgress,
  eventCategory,
  eventLabel,
  type DeliveryStageKey,
  type ProgressOrder,
} from '@/lib/order-progress';
import './order-progress.css';

const stateLabels = {
  completed: 'Concluído',
  current: 'Etapa atual',
  upcoming: 'Próxima etapa',
  interrupted: 'Interrompido',
  'not-reached': 'Não realizado',
};

const nextDescriptions: Record<DeliveryStageKey, string> = {
  SEARCHING: 'A busca começa quando a loja cria o pedido.',
  ASSIGNED: 'Esta etapa começa quando um entregador aceitar a oferta.',
  AT_STORE: 'A posição do entregador deverá confirmar a chegada à loja.',
  PICKED_UP: 'Esta etapa começa após o entregador confirmar a coleta.',
  DELIVERED: 'A confirmação no destino concluirá a entrega.',
};

function time(value: string) {
  return new Date(value).toLocaleString('pt-BR', {
    day: '2-digit',
    month: 'short',
    hour: '2-digit',
    minute: '2-digit',
  });
}

export function OrderProgress({
  order,
  publicView = false,
}: {
  order: ProgressOrder;
  publicView?: boolean;
}) {
  const id = useId();
  const history = useRef<HTMLDetailsElement>(null);
  const [inspected, inspect] = useState<DeliveryStageKey | null>(null);
  const model = deliveryProgress(order);
  const selected = model.stages.find((stage) => stage.key === inspected) ?? model.current;

  function showEvent(eventId: number) {
    if (history.current) history.current.open = true;
    requestAnimationFrame(() => {
      const element = document.getElementById(`${id}-event-${eventId}`);
      element?.focus({ preventScroll: true });
      element?.scrollIntoView({
        block: 'nearest',
        behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches
          ? 'instant'
          : 'smooth',
      });
    });
  }

  return (
    <section className="delivery-progress" aria-labelledby={`${id}-title`}>
      <div className="delivery-progress-heading">
        <div>
          <p className="delivery-progress-eyebrow">Acompanhe o caminho</p>
          <h4 id={`${id}-title`}>Linha do tempo</h4>
        </div>
        <span
          className={`delivery-progress-summary ${order.status === 'CANCELED' ? 'is-canceled' : ''}`}
        >
          {order.status === 'CANCELED'
            ? 'Pedido cancelado'
            : order.status === 'DELIVERED'
              ? 'Entrega concluída'
              : `Etapa ${model.stages.indexOf(model.current) + 1} de ${model.stages.length}`}
        </span>
      </div>
      {model.restart && (
        <p className="delivery-progress-note">
          Busca retomada em {time(model.restart.occurredAt)}. Os marcos abaixo acompanham a
          tentativa {model.attempt}; as anteriores continuam no histórico.
        </p>
      )}
      {order.status === 'CANCELED' && (
        <p className="delivery-progress-note">
          O caminho foi interrompido
          {model.cancellation ? ` em ${time(model.cancellation.occurredAt)}` : ''}. As etapas
          seguintes não foram realizadas.
        </p>
      )}
      <ol className="delivery-steps" role="list" aria-label="Etapas da entrega">
        {model.stages.map((stage, index) => (
          <li key={stage.key} data-state={stage.state}>
            <button
              className="delivery-step"
              type="button"
              aria-pressed={selected.key === stage.key}
              aria-current={stage.state === 'current' ? 'step' : undefined}
              aria-controls={`${id}-description`}
              onClick={() => inspect(stage.key)}
            >
              <span className="delivery-step-marker" aria-hidden="true">
                {stage.state === 'completed' ? (
                  <Check size={14} strokeWidth={2.5} />
                ) : stage.state === 'interrupted' ? (
                  <X size={14} strokeWidth={2.5} />
                ) : (
                  index + 1
                )}
              </span>
              <span className="delivery-step-content">
                <strong>{stage.title}</strong>
                <span>{stateLabels[stage.state]}</span>
                {stage.occurredAt && (
                  <time dateTime={stage.occurredAt}>{time(stage.occurredAt)}</time>
                )}
              </span>
            </button>
          </li>
        ))}
      </ol>
      <div className="delivery-step-description" id={`${id}-description`} aria-live="polite">
        <Circle size={14} aria-hidden="true" />
        <div>
          <strong>{selected.title}</strong>
          <p>
            {selected.state === 'not-reached'
              ? 'Esta etapa não foi realizada porque o pedido foi cancelado.'
              : selected.state === 'interrupted'
                ? 'O pedido foi cancelado durante esta etapa.'
                : selected.state === 'upcoming'
                  ? nextDescriptions[selected.key]
                  : selected.description}
          </p>
          {selected.occurredAt && (
            <p>
              {selected.key === 'DELIVERED' ? 'Entrega concluída em ' : 'Etapa iniciada em '}
              <time dateTime={selected.occurredAt}>
                {new Date(selected.occurredAt).toLocaleString('pt-BR')}
              </time>
              .
            </p>
          )}
          {selected.event && (
            <button
              className="delivery-history-link"
              type="button"
              onClick={() => showEvent(selected.event!.id)}
            >
              Ver registro no histórico
            </button>
          )}
        </div>
      </div>
      <details className="delivery-history" ref={history}>
        <summary>
          Histórico de eventos <span>{model.events.length}</span>
        </summary>
        <p className="delivery-history-explanation">
          {publicView
            ? 'Marcos públicos deste percurso, incluindo retomadas de busca e cancelamentos.'
            : 'Registros de todas as tentativas, incluindo ofertas e ajustes. Uma oferta não conclui uma etapa da entrega.'}
        </p>
        <ol className="order-timeline" aria-label="Histórico registrado do pedido">
          {model.events.map((event) => (
            <li key={event.id} id={`${id}-event-${event.id}`} tabIndex={-1}>
              <span className="delivery-event-category">{eventCategory(event)}</span>
              <span className="delivery-event-label">{eventLabel(event)}</span>
              <time dateTime={event.occurredAt}>{time(event.occurredAt)}</time>
            </li>
          ))}
        </ol>
      </details>
    </section>
  );
}
