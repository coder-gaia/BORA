import type { OrderView } from './api';

export const orderStatuses = [
  ['SEARCHING', 'Buscando entregador'],
  ['ASSIGNED', 'A caminho da loja'],
  ['AT_STORE', 'Na loja'],
  ['PICKED_UP', 'Em entrega'],
  ['DELIVERED', 'Entregue'],
  ['CANCELED', 'Cancelado'],
] as const;

export type OrderStatus = OrderView['status'];
export type OrderScope = 'OWN' | 'PUBLIC' | 'ALL';

export function statusLabel(status: string | undefined) {
  const labels: Record<string, string> = {
    SEARCHING: 'Procurando entregador',
    ASSIGNED: 'Entregador a caminho da loja',
    AT_STORE: 'Entregador na loja',
    PICKED_UP: 'A caminho do cliente',
    DELIVERED: 'Entregue',
    CANCELED: 'Cancelado',
  };
  return status ? (labels[status] ?? 'Estado indisponível') : 'Estado indisponível';
}

export function activeOrder(status: string | undefined) {
  return status !== undefined && status !== 'CANCELED' && status !== 'DELIVERED';
}

export function formatTime(value: string | undefined | null) {
  return value ? new Date(value).toLocaleString('pt-BR') : 'Horário indisponível';
}

export function trackingPath(code: string) {
  return `/rastreio/${encodeURIComponent(code)}`;
}

export function normalizeOrderCode(value: string) {
  return value.trim();
}

export function arrivalLabel(seconds: number | null | undefined) {
  if (seconds === null || seconds === undefined || !Number.isFinite(seconds))
    return 'Calculando o próximo trecho';
  if (seconds < 60) return 'Menos de 1 min';
  return `Cerca de ${Math.ceil(seconds / 60)} min`;
}
