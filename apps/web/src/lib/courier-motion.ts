export type CourierStatus = 'AVAILABLE' | 'OFFERED' | 'BUSY';
export type CourierPoint = readonly [string, number, number, CourierStatus, number];
export type MapFrame = { serverTime: string; points: CourierPoint[] };
export type MapViewport = {
  west: number;
  south: number;
  east: number;
  north: number;
  scope: 'PUBLIC';
};
export type CouriersGeoJSON = {
  type: 'FeatureCollection';
  features: {
    type: 'Feature';
    id: string;
    geometry: { type: 'Point'; coordinates: [number, number] };
    properties: { status: CourierStatus };
  }[];
};

type Motion = {
  point: CourierPoint;
  start: [number, number];
  target: [number, number];
};

export function parseFrame(value: unknown): MapFrame | null {
  if (
    typeof value !== 'object' ||
    value === null ||
    !('points' in value) ||
    !('serverTime' in value) ||
    typeof value.serverTime !== 'string' ||
    !Number.isFinite(Date.parse(value.serverTime)) ||
    !Array.isArray(value.points) ||
    value.points.length > 2_000
  )
    return null;
  const points: CourierPoint[] = [];
  const ids = new Set<string>();
  for (const point of value.points) {
    if (
      !Array.isArray(point) ||
      point.length !== 5 ||
      typeof point[0] !== 'string' ||
      ids.has(point[0]) ||
      typeof point[1] !== 'number' ||
      !Number.isFinite(point[1]) ||
      Math.abs(point[1]) > 180 ||
      typeof point[2] !== 'number' ||
      !Number.isFinite(point[2]) ||
      Math.abs(point[2]) > 90 ||
      !['AVAILABLE', 'OFFERED', 'BUSY'].includes(point[3]) ||
      typeof point[4] !== 'number' ||
      !Number.isFinite(point[4])
    )
      return null;
    ids.add(point[0]);
    points.push(point as unknown as CourierPoint);
  }
  return { serverTime: value.serverTime, points };
}

// Uma fonte GeoJSON e uma camada de círculos. Nenhum elemento DOM por entregador.
export class CourierMotion {
  private motions = new Map<string, Motion>();
  private receivedAt = 0;
  private serverTime = -Infinity;
  private moving = false;

  receive(frame: MapFrame, now: number) {
    const time = Date.parse(frame.serverTime);
    if (time < this.serverTime) return false;
    const previous = new Map(
      this.sample(now, true).features.map((feature) => [feature.id, feature.geometry.coordinates]),
    );
    this.motions = new Map(
      frame.points.map((point) => [
        point[0],
        {
          point,
          start: previous.get(point[0]) ?? [point[1], point[2]],
          target: [point[1], point[2]],
        },
      ]),
    );
    this.receivedAt = now;
    this.serverTime = time;
    this.moving = [...this.motions.values()].some(
      ({ start, target }) => start[0] !== target[0] || start[1] !== target[1],
    );
    return true;
  }

  get frameTime() {
    return this.receivedAt;
  }

  segments() {
    return [...this.motions.values()];
  }

  isAnimating(now: number) {
    return this.moving && now < this.receivedAt + 1_000;
  }

  sample(now: number, animate: boolean): CouriersGeoJSON {
    const progress = animate ? Math.min(1, Math.max(0, (now - this.receivedAt) / 1_000)) : 1;
    return {
      type: 'FeatureCollection',
      features: [...this.motions.values()].map(({ point, start, target }) => ({
        type: 'Feature',
        id: point[0],
        geometry: {
          type: 'Point',
          coordinates: [
            start[0] + (target[0] - start[0]) * progress,
            start[1] + (target[1] - start[1]) * progress,
          ],
        },
        properties: { status: point[3] },
      })),
    };
  }
}
