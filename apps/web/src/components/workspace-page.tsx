import { ArrowLeft } from 'lucide-react';
import { DemoPanel } from '@/components/demo-panel';
import './order-workspace.css';

export function StorePage() {
  return (
    <div className="home-shell store-page-shell">
      <title>Área da loja | Bora</title>
      <a className="skip-link" href="#store-content">
        Pular para a loja
      </a>
      <header className="home-header">
        <a className="home-brand" href="/" aria-label="Bora, início">
          bora<span aria-hidden="true">.</span>
        </a>
        <a className="tracking-home-link" href="/">
          <ArrowLeft size={16} aria-hidden="true" /> Voltar à cidade
        </a>
      </header>
      <main id="store-content" tabIndex={-1} className="store-page-main">
        <div className="tracking-page-heading">
          <p className="eyebrow">Área da loja</p>
          <h1>
            Um destino.
            <br />
            Todo o caminho.
          </h1>
          <p className="demo-description">
            Crie uma entrega ou abra seus pedidos quando quiser acompanhá-los.
          </p>
        </div>
        <DemoPanel requestedRole="STORE" />
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
