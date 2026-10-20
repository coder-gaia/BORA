import { useEffect, useState } from 'react';
import { serverElapsed, waitingLabel, type OperationsClock } from '@/lib/operations-clock';

export function WaitingAge({ since, clock }: { since: string | null; clock?: OperationsClock }) {
  const [now, setNow] = useState(0);
  useEffect(() => {
    if (!since || !clock) return;
    const timer = window.setInterval(() => {
      if (document.visibilityState === 'visible') setNow(performance.now());
    }, 1_000);
    return () => window.clearInterval(timer);
  }, [since, clock]);
  if (!since || !clock) return null;
  const time = Math.max(now, clock.receivedAt);
  const delayed = serverElapsed(clock, time) - Date.parse(since) >= 180_000;
  return (
    <small className="operations-wait" data-delayed={delayed}>
      {waitingLabel(since, clock, time)}
      {delayed ? ' · Busca demorada' : ''}
    </small>
  );
}
