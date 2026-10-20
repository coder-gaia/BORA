export function OfferRing({ seconds, fraction }: { seconds: number; fraction: number }) {
  return (
    <div className="offer-ring-summary">
      <div className="offer-ring" aria-hidden="true">
        <svg viewBox="0 0 100 100">
          <circle className="offer-ring-track" cx="50" cy="50" r="44" />
          <circle
            className="offer-ring-progress"
            cx="50"
            cy="50"
            r="44"
            pathLength="1"
            strokeDasharray="1"
            strokeDashoffset={1 - fraction}
          />
        </svg>
        <span>
          {seconds}
          <small>s</small>
        </span>
      </div>
      <div>
        <h4>Uma entrega por perto</h4>
        <p className="offer-countdown">
          {seconds === 0
            ? 'Prazo encerrado. Consultando a oferta...'
            : `Responda em até ${seconds} s.`}
        </p>
        <span className="sr-only" role="status">
          {seconds === 0
            ? 'O prazo desta oferta terminou.'
            : 'Você recebeu uma nova oferta de entrega.'}
        </span>
      </div>
    </div>
  );
}
