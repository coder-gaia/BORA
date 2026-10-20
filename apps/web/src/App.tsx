import { lazy, Suspense } from 'react';
import { ArrowDown, ArrowUpRight, MapPin } from 'lucide-react';
import { PublicMap } from '@/components/public-map';
import { SimulationNotice } from '@/components/simulation-notice';
import './home.css';

const DemoPanel = lazy(() =>
  import('@/components/demo-panel').then((module) => ({ default: module.DemoPanel })),
);
const StorePage = lazy(() =>
  import('@/components/workspace-page').then((module) => ({ default: module.StorePage })),
);
const PublicTrackingPage = lazy(() =>
  import('@/components/public-tracking').then((module) => ({ default: module.PublicTrackingPage })),
);

export function App() {
  if (typeof window !== 'undefined' && window.location.pathname === '/loja') {
    return (
      <Suspense
        fallback={
          <main className="tracking-route-loading" role="status">
            Preparando a área da loja...
          </main>
        }
      >
        <StorePage />
      </Suspense>
    );
  }
  if (typeof window !== 'undefined' && window.location.pathname.startsWith('/rastreio/')) {
    let code = '';
    try {
      code = decodeURIComponent(window.location.pathname.slice('/rastreio/'.length));
    } catch {
      /* Invalid encoded links show the same safe error page. */
    }
    return (
      <Suspense
        fallback={
          <main className="tracking-route-loading" role="status">
            Preparando o rastreio da entrega...
          </main>
        }
      >
        <PublicTrackingPage code={code} />
      </Suspense>
    );
  }
  return (
    <div className="home-shell">
      <a className="skip-link" href="#main-content">
        Pular para o conteúdo
      </a>
      <header className="home-header">
        <a className="home-brand" href="/" aria-label="Bora, início">
          <svg viewBox="0 0 32 32" width="32" height="32" aria-hidden="true">
            <path
              d="M5 25V7h10a7 7 0 0 1 0 14H9m6-7h12m-5-5 5 5-5 5"
              fill="none"
              stroke="currentColor"
              strokeWidth="3"
              strokeLinecap="round"
              strokeLinejoin="round"
            />
          </svg>
          bora<span aria-hidden="true">.</span>
        </a>
        <nav aria-label="Navegação principal">
          <a href="#como-funciona">
            Como funciona <ArrowUpRight size={14} aria-hidden="true" />
          </a>
          <a className="home-header-cta" href="#demonstracao">
            Experimentar
          </a>
        </nav>
      </header>

      <main id="main-content" tabIndex={-1}>
        <section className="home-hero" aria-labelledby="home-title">
          <div className="home-intro">
            <p className="home-location">
              <MapPin size={15} aria-hidden="true" /> São Paulo, em movimento
            </p>
            <h1 id="home-title">
              Cada entrega
              <br />
              começa com um bora.
            </h1>
            <p>
              Uma loja precisa enviar. Um entregador está por perto. Acompanhe o encontro em tempo
              real.
            </p>
            <a className="home-text-link" href="#demonstracao">
              Faça parte do caminho <ArrowDown size={16} aria-hidden="true" />
            </a>
          </div>
          <PublicMap />
        </section>

        <section className="home-how" id="como-funciona" aria-labelledby="how-title">
          <div>
            <p className="home-kicker">Do pedido à entrega</p>
            <h2 id="how-title">
              A cidade segue.
              <br />
              Sua entrega também.
            </h2>
          </div>
          <ol>
            <li>
              <span aria-hidden="true">01</span>
              <div>
                <h3>A loja chama</h3>
                <p>Um destino, um pedido. A busca por um entregador começa.</p>
              </div>
            </li>
            <li>
              <span aria-hidden="true">02</span>
              <div>
                <h3>O entregador aceita</h3>
                <p>A oferta chega a quem consegue buscar mais rápido.</p>
              </div>
            </li>
            <li>
              <span aria-hidden="true">03</span>
              <div>
                <h3>Você acompanha</h3>
                <p>Da coleta à chegada, cada etapa tem seu lugar no caminho.</p>
              </div>
            </li>
          </ol>
        </section>

        <div className="home-demo" id="demonstracao">
          <Suspense
            fallback={
              <section className="demo-panel" aria-busy="true">
                <h2>Experimente um pedido</h2>
                <p role="status">Preparando a demonstração...</p>
              </section>
            }
          >
            <DemoPanel />
          </Suspense>
        </div>
      </main>

      <footer className="home-footer">
        <div>
          <strong>bora.</strong>
          <span>Entregas em movimento.</span>
        </div>
        <p className="home-map-credits">
          <a href="https://openfreemap.org/">OpenFreeMap</a>
          <span aria-hidden="true">/</span>
          <a href="https://openmaptiles.org/">OpenMapTiles</a>
          <span aria-hidden="true">/</span>
          <a href="https://www.openstreetmap.org/copyright">© OpenStreetMap</a>
        </p>
        <SimulationNotice />
      </footer>
    </div>
  );
}
