import { ArrowLeft, ShieldCheck } from 'lucide-react';
import { TrackingDelivery } from '@/components/tracking-delivery';
import './order-workspace.css';

export function PublicTrackingPage({ code }: { code: string }) {
  const valid = /^[a-zA-Z0-9_-]{20,64}$/.test(code);
  return (
    <div className="home-shell public-tracking-shell">
      <title>Rastreio da entrega | Bora</title>
      <a className="skip-link" href="#tracking-content">
        Pular para a entrega
      </a>
      <header className="home-header">
        <a className="home-brand" href="/" aria-label="Bora, início">
          bora<span aria-hidden="true">.</span>
        </a>
        <a className="tracking-home-link" href="/">
          <ArrowLeft size={16} aria-hidden="true" /> Conhecer o Bora
        </a>
      </header>
      <main id="tracking-content" className="public-tracking-main" tabIndex={-1}>
        <div className="tracking-page-heading">
          <p className="eyebrow">Acompanhe sua entrega</p>
          <h1>Cada etapa, no seu tempo.</h1>
          <p className="demo-description">Da loja ao destino, acompanhe o caminho deste pedido.</p>
        </div>
        {valid ? (
          <TrackingDelivery key={code} code={code} />
        ) : (
          <p className="demo-error" role="alert">
            Este link de rastreio é inválido. Confira o endereço ou peça um novo link à loja.
          </p>
        )}
        <p className="tracking-privacy-note">
          <ShieldCheck size={17} aria-hidden="true" /> Este link permite acompanhar somente esta
          entrega. Guarde o código de confirmação para o momento em que receber o pedido.
        </p>
      </main>
      <footer className="home-footer">
        <div>
          <strong>bora.</strong>
          <span>Entregas em movimento.</span>
        </div>
        <p className="demo-description">
          Demonstração com entregadores e pedidos simulados em São Paulo.
        </p>
      </footer>
    </div>
  );
}
