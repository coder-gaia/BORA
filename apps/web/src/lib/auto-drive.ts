import { RouteMotion, type Position } from './route-motion.ts';

export type DriveIdentity = Readonly<{ orderId: string; destination: 'PICKUP' | 'DROPOFF' }>;
export type UncertainPosition = DriveIdentity & { point: Position };
export function pointForConfirmation(
  uncertain: UncertainPosition | null,
  identity: DriveIdentity | null,
): Position | null {
  return uncertain &&
    identity &&
    uncertain.orderId === identity.orderId &&
    uncertain.destination === identity.destination
    ? uncertain.point
    : null;
}
export type DriveRoute = DriveIdentity & { geometry: Position[]; simulatedDurationMillis: number };
export type DriveReceipt = { accepted: number; latestRecordedAt?: string | null };
export type DriveState =
  'idle' | 'loading' | 'driving' | 'paused' | 'awaiting-confirmation' | 'failed';
type Clock = {
  now(): number;
  later(callback: () => void, delay: number): unknown;
  cancel(timer: unknown): void;
};
type Ports = {
  identity(): DriveIdentity | null;
  origin?(signal: AbortSignal, identity: DriveIdentity): Promise<Position>;
  persisted?(receipt: DriveReceipt, signal: AbortSignal, identity: DriveIdentity): Promise<void>;
  route(signal: AbortSignal): Promise<DriveRoute>;
  acceptedRoute?(route: DriveRoute): void;
  send(position: Position, signal: AbortSignal): Promise<DriveReceipt>;
  state(value: DriveState, message: string): void;
  uncertainPosition?(position: Position): void;
  confirmedReceipt(receipt: DriveReceipt, position: Position): void;
  clock: Clock;
};
const same = (a: DriveIdentity | null, b: DriveIdentity | null) =>
  Boolean(a && b && a.orderId === b.orderId && a.destination === b.destination);

/** One explicit driving intent owns its route, timer and in-flight HTTP request. */
export class AutoDrive {
  private generation = 0;
  private active: DriveIdentity | null = null;
  private request: AbortController | null = null;
  private timer: unknown;
  private state: DriveState = 'idle';
  private pendingPosition: Position | null = null;
  private readonly ports: Ports;
  constructor(ports: Ports) {
    this.ports = ports;
  }

  private valid(generation: number, identity: DriveIdentity) {
    return (
      generation === this.generation &&
      same(identity, this.active) &&
      same(identity, this.ports.identity()) &&
      !this.request?.signal.aborted
    );
  }
  private stopWork() {
    this.generation++;
    if (this.timer !== undefined) this.ports.clock.cancel(this.timer);
    this.timer = undefined;
    this.request?.abort();
    this.request = null;
    this.active = null;
    this.pendingPosition = null;
  }
  private change(state: DriveState, message: string) {
    this.state = state;
    this.ports.state(state, message);
  }
  pause(message = 'Direção pausada. Retome quando quiser seguir pela rota atualizada.') {
    const active = this.state === 'loading' || this.state === 'driving';
    if (this.pendingPosition) this.ports.uncertainPosition?.(this.pendingPosition);
    this.stopWork();
    if (active) this.change('paused', message);
  }
  checkIdentity() {
    if (this.active && !same(this.active, this.ports.identity())) {
      this.stopWork();
      this.change('idle', 'O trabalho mudou. Confira a etapa antes de dirigir novamente.');
    }
  }
  dispose() {
    this.stopWork();
  }

  async start() {
    this.stopWork();
    const identity = this.ports.identity();
    if (!identity) {
      this.change('idle', 'Aceite uma oferta e confira a etapa antes de dirigir.');
      return;
    }
    this.active = { ...identity };
    const generation = this.generation;
    const request = new AbortController();
    this.request = request;
    this.change('loading', 'Confirmando sua posição para preparar o percurso...');
    try {
      if (this.ports.origin) {
        const origin = await this.ports.origin(request.signal, identity);
        if (!this.valid(generation, identity)) return;
        this.pendingPosition = origin;
        const receipt = await this.ports.send(origin, request.signal);
        if (!this.valid(generation, identity)) return;
        if (receipt.accepted !== 1)
          throw new Error('A posição não foi aceita. Confira seu trabalho antes de retomar.');
        this.pendingPosition = null;
        this.ports.confirmedReceipt(receipt, origin);
        await this.ports.persisted?.(receipt, request.signal, identity);
        if (!this.valid(generation, identity)) return;
      }
      this.change('loading', 'Calculando a rota a partir da posição confirmada...');
      const route = await this.ports.route(request.signal);
      if (!this.valid(generation, identity)) return;
      if (!same(route, identity))
        throw new Error('O pedido ou a etapa mudou durante o cálculo da rota.');
      const motion = new RouteMotion(route.geometry, route.simulatedDurationMillis);
      this.ports.acceptedRoute?.(route);
      const beganAt = this.ports.clock.now();
      this.change(
        'driving',
        'Direção automática ativa. As etapas de coleta e entrega continuam manuais.',
      );
      const sendNext = async () => {
        this.timer = undefined;
        if (!this.valid(generation, identity)) return;
        const startedAt = this.ports.clock.now();
        const elapsed = Math.max(0, startedAt - beganAt);
        const point = motion.pointAt(elapsed);
        this.pendingPosition = point;
        const receipt = await this.ports.send(point, request.signal);
        if (!this.valid(generation, identity)) return;
        if (receipt.accepted !== 1)
          throw new Error('A posição não foi aceita. Confira seu trabalho antes de retomar.');
        this.pendingPosition = null;
        this.ports.confirmedReceipt(receipt, point);
        if (elapsed >= motion.durationMillis) {
          this.request = null;
          this.change(
            'awaiting-confirmation',
            'Ponto final enviado. Aguarde a confirmação da posição pelo servidor.',
          );
          return;
        }
        this.timer = this.ports.clock.later(
          () => {
            void sendNext().catch(failed);
          },
          Math.max(0, 2_000 - (this.ports.clock.now() - startedAt)),
        );
      };
      const failed = (error: unknown) => {
        if (!this.valid(generation, identity)) return;
        if (this.pendingPosition) this.ports.uncertainPosition?.(this.pendingPosition);
        this.stopWork();
        this.change(
          'failed',
          error instanceof Error
            ? error.message
            : 'A direção foi interrompida. Confira seu trabalho antes de retomar.',
        );
      };
      // A pending send owns the only continuation; no interval can overlap it.
      this.timer = this.ports.clock.later(
        () => {
          void sendNext().catch(failed);
        },
        this.ports.origin ? 2_000 : 0,
      );
    } catch (error) {
      if (!this.valid(generation, identity)) return;
      if (this.pendingPosition) this.ports.uncertainPosition?.(this.pendingPosition);
      this.stopWork();
      this.change(
        'failed',
        error instanceof Error ? error.message : 'Não foi possível calcular a rota.',
      );
    }
  }
}
