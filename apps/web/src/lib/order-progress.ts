import type { OrderView } from '@/lib/api';

export type OrderEvent = OrderView['timeline'][number];
export type DeliveryStageKey = Exclude<OrderView['status'], 'CANCELED'>;
export type DeliveryStageState =
  'completed' | 'current' | 'upcoming' | 'interrupted' | 'not-reached';

const definitions: { key: DeliveryStageKey; title: string; description: string }[] = [
  {
    key: 'SEARCHING',
    title: 'Busca por entregador',
    description: 'A loja criou o pedido. A busca considera quem chega mais rápido à coleta.',
  },
  {
    key: 'ASSIGNED',
    title: 'A caminho da loja',
    description: 'Um entregador aceitou a oferta para buscar este pedido.',
  },
  {
    key: 'AT_STORE',
    title: 'Na loja',
    description: 'A chegada à loja foi confirmada pela posição do entregador.',
  },
  {
    key: 'PICKED_UP',
    title: 'A caminho do cliente',
    description: 'A coleta foi confirmada e o pedido seguiu para o destino.',
  },
  {
    key: 'DELIVERED',
    title: 'Entregue',
    description: 'A entrega foi confirmada e o pedido foi concluído.',
  },
];

export type DeliveryStage = (typeof definitions)[number] & {
  state: DeliveryStageState;
  occurredAt?: string;
  event?: OrderEvent;
};

const eventLabels: Record<string, string> = {
  ORDER_CREATED: 'Pedido criado',
  ORDER_ASSIGNED: 'Entregador confirmado',
  ORDER_AT_STORE: 'Chegada à loja confirmada',
  ORDER_PICKED_UP: 'Coleta confirmada',
  ORDER_DELIVERED: 'Entrega confirmada',
  ORDER_CANCELED: 'Pedido cancelado',
  ORDER_REASSIGNED: 'Pedido redistribuído',
  OFFER_CREATED: 'Oferta enviada ao entregador',
  OFFER_ACCEPTED: 'Oferta aceita',
  OFFER_REJECTED: 'Oferta recusada',
  OFFER_EXPIRED: 'Prazo da oferta encerrado',
  OFFER_CANCELED: 'Oferta cancelada',
  ORDER_SEARCH_DELAYED: 'Busca por entregador está demorando',
};

export function eventLabel(event: OrderEvent) {
  return eventLabels[event.type] ?? 'Atualização do pedido';
}

export function eventCategory(event: OrderEvent) {
  return event.type.startsWith('OFFER_')
    ? 'Oferta'
    : ['ORDER_REASSIGNED', 'ORDER_CANCELED', 'ORDER_SEARCH_DELAYED'].includes(event.type)
      ? 'Atualização'
      : 'Etapa';
}

function last<T>(values: T[], matches: (value: T) => boolean) {
  for (let index = values.length - 1; index >= 0; index--) {
    const value = values[index]!;
    if (matches(value)) return value;
  }
  return undefined;
}

export function deliveryProgress(order: OrderView) {
  // O identificador persistido mantém a ordem dos registros mesmo com relógios divergentes.
  const events = [...order.timeline].sort((left, right) => left.id - right.id);
  const reassigned = events.filter((event) => event.type === 'ORDER_REASSIGNED');
  const restart = reassigned.at(-1);
  const attemptEvents = restart ? events.filter((event) => event.id > restart.id) : events;
  const cancellation = last(events, (event) => event.type === 'ORDER_CANCELED');
  const lastTransition = last(attemptEvents, (event) => event.fromStatus !== event.toStatus);
  const effectiveStatus =
    order.status === 'CANCELED'
      ? (cancellation?.fromStatus ?? lastTransition?.fromStatus ?? 'SEARCHING')
      : order.status;
  const activeIndex = Math.max(
    0,
    definitions.findIndex((stage) => stage.key === effectiveStatus),
  );
  const stages: DeliveryStage[] = definitions.map((stage, index) => {
    const event =
      stage.key === 'SEARCHING'
        ? (restart ?? events.find((value) => value.type === 'ORDER_CREATED'))
        : last(
            attemptEvents,
            (value) => value.toStatus === stage.key && value.fromStatus !== value.toStatus,
          );
    let state: DeliveryStageState;
    if (order.status === 'DELIVERED') state = 'completed';
    else if (order.status === 'CANCELED') {
      state =
        index < activeIndex ? 'completed' : index === activeIndex ? 'interrupted' : 'not-reached';
    } else
      state = index < activeIndex ? 'completed' : index === activeIndex ? 'current' : 'upcoming';
    const reached = state !== 'upcoming' && state !== 'not-reached';
    return {
      ...stage,
      state,
      event: reached ? event : undefined,
      occurredAt: reached
        ? (event?.occurredAt ??
          (stage.key === 'SEARCHING' && !restart ? order.createdAt : undefined))
        : undefined,
    };
  });
  return {
    stages,
    events,
    attempt: reassigned.length + 1,
    restart,
    cancellation,
    current: stages[activeIndex]!,
    finished: order.status === 'DELIVERED' || order.status === 'CANCELED',
  };
}
