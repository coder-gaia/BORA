export type Position = Readonly<{ latitude: number; longitude: number }>;

function valid(point: Position) {
  return (
    Number.isFinite(point.latitude) &&
    Number.isFinite(point.longitude) &&
    Math.abs(point.latitude) <= 90 &&
    Math.abs(point.longitude) <= 180
  );
}

function distance(a: Position, b: Position) {
  const radians = Math.PI / 180;
  const latitude = (b.latitude - a.latitude) * radians;
  const longitude = (b.longitude - a.longitude) * radians;
  const term =
    Math.sin(latitude / 2) ** 2 +
    Math.cos(a.latitude * radians) * Math.cos(b.latitude * radians) * Math.sin(longitude / 2) ** 2;
  return 6_371_000 * 2 * Math.asin(Math.sqrt(Math.min(1, Math.max(0, term))));
}

/** Move only along the routed segments. The API duration already includes SIM_SPEED. */
export class RouteMotion {
  private readonly geometry: readonly Position[];
  private readonly cumulative: readonly number[];
  readonly durationMillis: number;

  constructor(geometry: readonly Position[], durationMillis: number) {
    if (
      !geometry.length ||
      geometry.some((point) => !valid(point)) ||
      !Number.isFinite(durationMillis) ||
      durationMillis < 0
    ) {
      throw new Error('A rota recebida é inválida.');
    }
    this.geometry = geometry.map((point) => ({ ...point }));
    const cumulative = [0];
    for (let i = 1; i < geometry.length; i++) {
      cumulative.push(cumulative[i - 1]! + distance(geometry[i - 1]!, geometry[i]!));
    }
    this.cumulative = cumulative;
    this.durationMillis = durationMillis;
  }

  pointAt(elapsedMillis: number): Position {
    if (!Number.isFinite(elapsedMillis)) throw new Error('O tempo da rota é inválido.');
    const geometry = this.geometry;
    const final = geometry[geometry.length - 1]!;
    const total = this.cumulative[this.cumulative.length - 1]!;
    if (
      geometry.length === 1 ||
      this.durationMillis === 0 ||
      total === 0 ||
      elapsedMillis >= this.durationMillis
    )
      return { ...final };
    if (elapsedMillis <= 0) return { ...geometry[0]! };
    const target = (total * elapsedMillis) / this.durationMillis;
    let start = 1;
    let end = geometry.length - 1;
    while (start < end) {
      const middle = Math.floor((start + end) / 2);
      if (this.cumulative[middle]! < target) start = middle + 1;
      else end = middle;
    }
    const previous = geometry[start - 1]!;
    const next = geometry[start]!;
    const length = this.cumulative[start]! - this.cumulative[start - 1]!;
    const part = length === 0 ? 1 : (target - this.cumulative[start - 1]!) / length;
    return {
      latitude: previous.latitude + (next.latitude - previous.latitude) * part,
      longitude: previous.longitude + (next.longitude - previous.longitude) * part,
    };
  }
}

/** Use the server clock plus performance.now, independent of the device wall clock. */
export function offerWindow(
  createdAt: string,
  expiresAt: string,
  serverTime: string,
  receivedAt: number,
  now: number,
) {
  const starts = Date.parse(createdAt);
  const ends = Date.parse(expiresAt);
  const server = Date.parse(serverTime);
  if (![starts, ends, server, receivedAt, now].every(Number.isFinite) || ends <= starts) {
    return { remainingMillis: 0, seconds: 0, fraction: 0 };
  }
  const remainingMillis = Math.max(0, ends - server - Math.max(0, now - receivedAt));
  return {
    remainingMillis,
    seconds: Math.ceil(remainingMillis / 1_000),
    fraction: Math.min(1, remainingMillis / (ends - starts)),
  };
}
