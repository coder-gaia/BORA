import { useEffect, useState } from 'react';

type HealthSnapshot = {
  kind: 'ready' | 'degraded' | 'unavailable';
  databaseAvailable: boolean | null;
  checkedAt: Date;
};

type HealthState = { kind: 'loading' } | HealthSnapshot;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

function readHealth(payload: unknown): Omit<HealthSnapshot, 'checkedAt'> {
  if (!isRecord(payload) || typeof payload.status !== 'string') {
    throw new Error('Invalid health response');
  }

  const components = payload.components;
  const database = isRecord(components) ? components.db : null;
  const databaseStatus = isRecord(database) ? database.status : null;
  const databaseAvailable =
    databaseStatus === 'UP' ? true : databaseStatus === 'DOWN' ? false : null;

  return {
    kind: payload.status === 'UP' && databaseAvailable === true ? 'ready' : 'degraded',
    databaseAvailable,
  };
}

export function useServiceHealth() {
  const [health, setHealth] = useState<HealthState>({ kind: 'loading' });
  const [revision, setRevision] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    const timeout = window.setTimeout(() => controller.abort(), 8_000);

    async function checkHealth() {
      let snapshot: Omit<HealthSnapshot, 'checkedAt'>;

      try {
        const response = await fetch('/actuator/health/readiness', {
          signal: controller.signal,
          cache: 'no-store',
          headers: { Accept: 'application/json' },
        });

        if (!response.ok && response.status !== 503) {
          throw new Error('Health request failed');
        }

        const payload: unknown = await response.json();
        snapshot = readHealth(payload);
      } catch {
        snapshot = { kind: 'unavailable', databaseAvailable: null };
      }

      window.clearTimeout(timeout);
      if (!disposed) {
        setHealth({ ...snapshot, checkedAt: new Date() });
      }
    }

    let disposed = false;
    void checkHealth();

    return () => {
      disposed = true;
      window.clearTimeout(timeout);
      controller.abort();
    };
  }, [revision]);

  function refresh() {
    setHealth({ kind: 'loading' });
    setRevision((current) => current + 1);
  }

  return { health, refresh };
}
