import type { CourierWorkView } from './api';

/** A slow pre-flush read cannot replace a newer persisted position or work stage. */
export function reconcileCourierWork(incoming: CourierWorkView, current?: CourierWorkView) {
  if (!current) return incoming;
  if (incoming.courier.version < current.courier.version) return current;
  if (incoming.courier.version > current.courier.version) return incoming;
  const nextTime = Date.parse(incoming.courier.locationUpdatedAt ?? '') || 0;
  const currentTime = Date.parse(current.courier.locationUpdatedAt ?? '') || 0;
  if (nextTime < currentTime) return current;
  if (
    incoming.order?.id === current.order?.id &&
    incoming.order &&
    current.order &&
    incoming.order.version < current.order.version
  )
    return current;
  return incoming;
}
