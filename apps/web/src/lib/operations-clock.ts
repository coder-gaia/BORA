export type OperationsClock = { serverTime: string; receivedAt: number };

export function serverElapsed(clock: OperationsClock, now: number) {
  return Date.parse(clock.serverTime) + Math.max(0, now - clock.receivedAt);
}

export function waitingLabel(since: string, clock: OperationsClock, now: number) {
  const seconds = Math.max(0, Math.floor((serverElapsed(clock, now) - Date.parse(since)) / 1_000));
  if (!Number.isFinite(seconds)) return 'Tempo de espera indisponível';
  if (seconds < 60) return `Aguardando há ${seconds} s`;
  return `Aguardando há ${Math.floor(seconds / 60)} min`;
}
