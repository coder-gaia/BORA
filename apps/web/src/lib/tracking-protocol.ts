import type { TrackingSnapshot } from './api';

const statuses = new Set([
  'SEARCHING',
  'ASSIGNED',
  'AT_STORE',
  'PICKED_UP',
  'DELIVERED',
  'CANCELED',
]);
const object = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null;
const instant = (value: unknown): value is string =>
  typeof value === 'string' && Number.isFinite(Date.parse(value));
const nonnegative = (value: unknown): value is number =>
  typeof value === 'number' && Number.isFinite(value) && value >= 0;
function point(value: unknown) {
  return (
    object(value) &&
    typeof value.latitude === 'number' &&
    typeof value.longitude === 'number' &&
    Number.isFinite(value.latitude) &&
    Number.isFinite(value.longitude) &&
    Math.abs(value.latitude) <= 90 &&
    Math.abs(value.longitude) <= 180
  );
}

export function parseTrackingSnapshot(value: unknown): TrackingSnapshot | null {
  if (
    !object(value) ||
    typeof value.status !== 'string' ||
    !statuses.has(value.status) ||
    !nonnegative(value.version) ||
    !Number.isSafeInteger(value.version) ||
    typeof value.storeName !== 'string' ||
    !point(value.pickup) ||
    !point(value.dropoff) ||
    !instant(value.createdAt) ||
    !instant(value.serverTime) ||
    !Array.isArray(value.timeline)
  )
    return null;
  if (value.courierPosition != null && !point(value.courierPosition)) return null;
  if (value.courierPositionUpdatedAt != null && !instant(value.courierPositionUpdatedAt))
    return null;
  if (value.etaSeconds != null && !nonnegative(value.etaSeconds)) return null;
  if (
    value.timeline.some(
      (event) =>
        !object(event) ||
        typeof event.type !== 'string' ||
        !instant(event.occurredAt) ||
        !nonnegative(event.orderVersion) ||
        (event.fromStatus !== null &&
          (typeof event.fromStatus !== 'string' || !statuses.has(event.fromStatus))) ||
        typeof event.toStatus !== 'string' ||
        !statuses.has(event.toStatus),
    )
  )
    return null;
  if (value.route != null) {
    const route = value.route;
    if (
      !object(route) ||
      !['PICKUP', 'DROPOFF'].includes(String(route.destination)) ||
      !Array.isArray(route.geometry) ||
      route.geometry.some((value) => !point(value)) ||
      !nonnegative(route.distanceMeters) ||
      !nonnegative(route.durationMillis) ||
      !nonnegative(route.simulatedDurationMillis) ||
      !instant(route.calculatedAt)
    )
      return null;
  }
  return value as TrackingSnapshot;
}

export function newerTracking(incoming: TrackingSnapshot, current: TrackingSnapshot | null) {
  if (
    current &&
    incoming.version === current.version &&
    incoming.courierPositionUpdatedAt &&
    current.courierPositionUpdatedAt &&
    Date.parse(incoming.courierPositionUpdatedAt) < Date.parse(current.courierPositionUpdatedAt)
  )
    return false;
  return (
    !current ||
    incoming.version > current.version ||
    (incoming.version === current.version &&
      Date.parse(incoming.serverTime) >= Date.parse(current.serverTime))
  );
}
