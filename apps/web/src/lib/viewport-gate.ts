import type { MapViewport } from './courier-motion';

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
export function sameViewport(a: MapViewport, b: MapViewport) {
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
export class ViewportGate {
  private active = false;
  private awaitingProgress = true;
  private latest: MapViewport | undefined;
  private sent: MapViewport | undefined;
  private notBefore = Infinity;
  private timer: number | undefined;
  private readonly send: (viewport: MapViewport) => void;
  private readonly clock: Clock;
  constructor(send: (viewport: MapViewport) => void, clock: Clock = browserClock) {
    this.send = send;
    this.clock = clock;
  }

  start(viewport: MapViewport) {
    this.stop();
    this.active = true;
    this.latest = { ...viewport };
    this.transmit();
  }
  update(viewport: MapViewport) {
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
