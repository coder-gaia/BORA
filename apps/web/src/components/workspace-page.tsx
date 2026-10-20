import { ArrowLeft } from 'lucide-react';
import { DemoPanel } from '@/components/demo-panel';
import type { DemoRole } from '@/lib/api';
import './order-workspace.css';

type Props = { role: DemoRole; name: string; headline: React.ReactNode; description: string };
export function WorkspacePage({ role, name, headline, description }: Props) {
  return (
    <div className="home-shell store-page-shell">
      <title>{`${name} | Bora`}</title>
      <a className="skip-link" href="#workspace-content">
        Pular para o conteúdo da área
      </a>
      <header className="home-header">
        <a className="home-brand" href="/" aria-label="Bora, início">
          bora<span aria-hidden="true">.</span>
        </a>
        <a className="tracking-home-link" href="/">
          <ArrowLeft size={16} aria-hidden="true" /> Voltar à cidade
        </a>
      </header>
      <main id="workspace-content" tabIndex={-1} className="store-page-main">
        <div className="tracking-page-heading">
          <p className="eyebrow">{name}</p>
          <h1>{headline}</h1>
          <p className="demo-description">{description}</p>
        </div>
        <DemoPanel requestedRole={role} />
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
export function StorePage() {
  return (
    <WorkspacePage
      role="STORE"
      name="Área da loja"
      headline={
        <>
          Um destino.
          <br />
          Todo o caminho.
        </>
      }
      description="Crie uma entrega ou abra seus pedidos quando quiser acompanhá-los."
    />
  );
}
export function CourierPage() {
  return (
    <WorkspacePage
      role="COURIER"
      name="Área do entregador"
      headline={
        <>
          Você escolhe.
          <br />A cidade segue.
        </>
      }
      description="Receba uma oferta, acompanhe seu percurso e confirme cada etapa no seu ritmo."
    />
  );
}

export function OperationsPage() {
  return (
    <WorkspacePage
      role="OPERATIONS"
      name="Área da operação"
      headline={
        <>
          A cidade segue.
          <br />
          Você acompanha.
        </>
      }
      description="Veja a cidade ao vivo ou abra os pedidos para acompanhar cada entrega e agir quando necessário."
    />
  );
}
