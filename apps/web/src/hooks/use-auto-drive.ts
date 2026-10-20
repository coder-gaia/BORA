import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { api, type CourierRouteView, type CourierWorkView, type PositionReceipt } from '@/lib/api';
import { AutoDrive, type DriveIdentity, type DriveState } from '@/lib/auto-drive';
import type { Position } from '@/lib/route-motion';

type Input = {
  identity: DriveIdentity | null;
  onReceipt: (receipt: PositionReceipt, position: Position) => void;
  onRoute: (route: CourierRouteView) => void;
  onInterrupted: () => void;
  onUncertain: (position: Position) => void;
  onWork: (work: CourierWorkView) => CourierWorkView;
};

function assignedWork(work: CourierWorkView, identity: DriveIdentity | null) {
  const order = work.order;
  return Boolean(
    identity &&
    order &&
    order.id === identity.orderId &&
    (identity.destination === 'PICKUP'
      ? order.status === 'ASSIGNED'
      : order.status === 'PICKED_UP'),
  );
}

function sameIdentity(a: DriveIdentity | null, b: DriveIdentity) {
  return a?.orderId === b.orderId && a.destination === b.destination;
}

function wait(signal: AbortSignal) {
  return new Promise<void>((resolve, reject) => {
    const aborted = () => {
      window.clearTimeout(timer);
      reject(signal.reason);
    };
    const timer = window.setTimeout(() => {
      signal.removeEventListener('abort', aborted);
      resolve();
    }, 1_000);
    signal.addEventListener('abort', aborted, { once: true });
    if (signal.aborted) aborted();
  });
}

export function useAutoDrive(input: Input) {
  const latest = useRef(input);
  const [status, setStatus] = useState<{ state: DriveState; message: string }>({
    state: 'idle',
    message: '',
  });
  const [drive] = useState(
    () =>
      new AutoDrive({
        identity: () => latest.current.identity,
        origin: async (signal, identity) => {
          const incoming = await api.courierWork(signal);
          signal.throwIfAborted();
          if (!sameIdentity(latest.current.identity, identity))
            throw new Error('O trabalho mudou.');
          const work = latest.current.onWork(incoming);
          if (!assignedWork(work, identity) || !work.courier.location)
            throw new Error('O trabalho mudou. Atualize a entrega antes de dirigir.');
          return work.courier.location;
        },
        persisted: async (receipt, signal, identity) => {
          const deadline = performance.now() + 15_000;
          while (performance.now() < deadline) {
            const incoming = await api.courierWork(signal);
            signal.throwIfAborted();
            if (!sameIdentity(latest.current.identity, identity))
              throw new Error('O trabalho mudou.');
            const work = latest.current.onWork(incoming);
            if (!assignedWork(work, identity))
              throw new Error('O trabalho mudou. Confira a etapa antes de continuar.');
            if (
              receipt.latestRecordedAt &&
              work.courier.locationUpdatedAt &&
              Date.parse(work.courier.locationUpdatedAt) >= Date.parse(receipt.latestRecordedAt)
            )
              return;
            await wait(signal);
          }
          throw new Error(
            'A posição ainda não foi confirmada. Atualize seu trabalho antes de retomar.',
          );
        },
        route: (signal) => api.courierRoute(signal),
        acceptedRoute: (route) => latest.current.onRoute(route as CourierRouteView),
        send: (position, signal) => api.sendPosition(position, signal),
        state: (state, message) => {
          setStatus({ state, message });
          if (state === 'failed' || state === 'paused') latest.current.onInterrupted();
        },
        uncertainPosition: (position) => latest.current.onUncertain(position),
        confirmedReceipt: (receipt, position) =>
          latest.current.onReceipt(receipt as PositionReceipt, position),
        clock: {
          now: () => performance.now(),
          later: (callback, delay) => window.setTimeout(callback, delay),
          cancel: (timer) => window.clearTimeout(timer as number),
        },
      }),
  );
  useLayoutEffect(() => {
    latest.current = input;
    drive.checkIdentity();
  }, [input, drive]);
  useEffect(() => {
    const hidden = () => {
      if (document.visibilityState !== 'visible')
        drive.pause('A aba foi ocultada. Retome a direção para seguir da posição confirmada.');
    };
    const offline = () =>
      drive.pause('A conexão caiu. Retome quando a rede voltar e seu trabalho estiver atualizado.');
    document.addEventListener('visibilitychange', hidden);
    window.addEventListener('offline', offline);
    return () => {
      document.removeEventListener('visibilitychange', hidden);
      window.removeEventListener('offline', offline);
      drive.dispose();
    };
  }, [drive]);
  return {
    ...status,
    driving: status.state === 'driving' || status.state === 'loading',
    start: () => {
      if (document.visibilityState === 'visible' && navigator.onLine) void drive.start();
    },
    pause: () => drive.pause(),
  };
}
