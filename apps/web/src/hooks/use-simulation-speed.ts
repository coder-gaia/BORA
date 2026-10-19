import { useEffect, useState } from 'react';
import { api } from '@/lib/api';

export function useSimulationSpeed() {
  const [speed, setSpeed] = useState<number | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    let pending = false;

    async function refresh() {
      if (pending || document.visibilityState !== 'visible') return;
      pending = true;
      try {
        const config = await api.simulationConfig(controller.signal);
        if (
          !controller.signal.aborted &&
          Number.isFinite(config.simulationSpeed) &&
          config.simulationSpeed > 0
        ) {
          setSpeed(config.simulationSpeed);
        }
      } catch {
        // A configuração pública não impede os fluxos de pedidos e entregas.
      } finally {
        pending = false;
      }
    }

    void refresh();
    const timer = window.setInterval(() => void refresh(), 60_000);
    document.addEventListener('visibilitychange', refresh);
    return () => {
      controller.abort();
      window.clearInterval(timer);
      document.removeEventListener('visibilitychange', refresh);
    };
  }, []);

  return speed;
}
