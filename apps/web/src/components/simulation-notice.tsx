import { useSimulationSpeed } from '@/hooks/use-simulation-speed';

export function SimulationNotice() {
  const speed = useSimulationSpeed();
  let label = 'Demonstração com dados simulados.';
  if (speed !== null) {
    const factor = speed.toLocaleString('pt-BR', {
      maximumSignificantDigits: 4,
      notation: speed < 0.001 ? 'scientific' : 'standard',
    });
    label =
      speed === 1
        ? 'Simulação em tempo real.'
        : `Simulação ${speed > 1 ? 'acelerada' : 'em velocidade'} ${factor}x`;
  }
  return <p className="simulation-notice">{label}</p>;
}
