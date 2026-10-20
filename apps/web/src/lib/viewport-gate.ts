import type { MapViewport } from './courier-motion';

export type ScopedViewport = Omit<MapViewport, 'scope'> & { scope: 'PUBLIC' | 'OPERATIONS' };

type Clock = {
  now: () => number;
  schedule: (callback: () => void, delay: number) => number;
  cancel: (timer: number) => void;
};
const browserClock: Clock = {
  now: () => performance.now(),
  schedule: (callback, delay) => window.setTimeout(callback, delay),
  cancel: (timer) => window.clearTimeout(timer),
};
export function sameViewport(a: ScopedViewport, b: ScopedViewport) {
  return (
    a.west === b.west &&
    a.south === b.south &&
    a.east === b.east &&
    a.north === b.north &&
    a.scope === b.scope
  );
}

// The initial MAP follows the initial SEND + synchronous SUBSCRIBE on this protocol.
// Later MAPs indicate server progress, not an exact acknowledgment of a viewport.
export class ViewportGate<T extends ScopedViewport = MapViewport> {
  private active = false;
  private awaitingProgress = true;
  private latest: T | undefined;
  private sent: T | undefined;
  private notBefore = Infinity;
  private timer: number | undefined;
  private readonly send: (viewport: T) => void;
  private readonly clock: Clock;
  constructor(send: (viewport: T) => void, clock: Clock = browserClock) {
    this.send = send;
    this.clock = clock;
  }

  start(viewport: T) {
    this.stop();
    this.active = true;
    this.latest = { ...viewport };
    this.transmit();
  }
  update(viewport: T) {
    this.latest = { ...viewport };
    this.schedule();
  }
  progress() {
    if (!this.active) return;
    this.awaitingProgress = false;
    // Leave a conservative margin after observable server work, not just local emission.
    this.notBefore = this.clock.now() + 500;
    this.schedule();
  }
  stop() {
    this.active = false;
    if (this.timer !== undefined) this.clock.cancel(this.timer);
    this.timer = undefined;
    this.latest = undefined;
    this.sent = undefined;
    this.awaitingProgress = true;
    this.notBefore = Infinity;
  }
  private schedule() {
    if (this.timer !== undefined) this.clock.cancel(this.timer);
    this.timer = undefined;
    if (
      !this.active ||
      this.awaitingProgress ||
      !this.latest ||
      (this.sent && sameViewport(this.latest, this.sent))
    )
      return;
    this.timer = this.clock.schedule(
      () => {
        this.timer = undefined;
        this.transmit();
      },
      Math.max(0, this.notBefore - this.clock.now()),
    );
  }
  private transmit() {
    if (!this.active || !this.latest) return;
    this.sent = { ...this.latest };
    this.awaitingProgress = true;
    this.notBefore = Infinity;
    this.send(this.sent);
  }
}
