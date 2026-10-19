import {
  ArrowUpRight,
  Check,
  CircleDashed,
  Database,
  MapPin,
  RefreshCw,
  Server,
  Zap,
} from 'lucide-react';
import { Button } from '@/components/ui/button';
import { useServiceHealth } from '@/hooks/use-service-health';
import { cn } from '@/lib/utils';
import { DemoPanel } from '@/components/demo-panel';
import { SimulationNotice } from '@/components/simulation-notice';

const healthMessages = {
  loading: {
    title: 'Verificando os serviços',
    description: 'Só um instante. Estamos conferindo a conexão.',
  },
  ready: {
    title: 'Tudo pronto por aqui',
    description: 'A aplicação e o banco de dados estão disponíveis.',
  },
  degraded: {
    title: 'Um serviço precisa de atenção',
    description: 'A aplicação respondeu, mas ainda não está pronta para uso.',
  },
  unavailable: {
    title: 'Não foi possível conectar',
    description: 'Os serviços podem estar iniciando. Tente verificar novamente.',
  },
};

export function App() {
  const { health, refresh } = useServiceHealth();
  const message = healthMessages[health.kind];
  const isLoading = health.kind === 'loading';
  const apiAvailable = health.kind === 'loading' ? null : health.kind !== 'unavailable';
  const databaseAvailable = health.kind === 'loading' ? null : health.databaseAvailable;

  return (
    <div className="page-shell">
      <a className="skip-link" href="#main-content">
        Pular para o conteúdo
      </a>

      <header className="site-header">
        <a className="brand" href="/" aria-label="Bora, início">
          <span className="brand-symbol" aria-hidden="true">
            <Zap size={22} strokeWidth={2.25} />
          </span>
          bora<span className="brand-period">.</span>
        </a>
        <span className="construction-label">
          <span className="construction-dot" aria-hidden="true" />
          Em construção
        </span>
      </header>

      <main id="main-content" className="main-content" tabIndex={-1}>
        <div className="intro-grid">
          <section className="intro" aria-labelledby="intro-title">
            <p className="eyebrow">
              <MapPin size={15} aria-hidden="true" />
              São Paulo, em movimento
            </p>
            <h1 id="intro-title">Cada entrega começa com um bora.</h1>
            <p className="intro-description">
              Lojas, entregadores e pessoas conectados em tempo real. Estamos preparando o caminho
              para a próxima entrega.
            </p>
            <p className="intro-note">A primeira parada é uma base funcionando bem.</p>
            <div className="intro-rule" aria-hidden="true">
              <span />
              <ArrowUpRight size={20} />
            </div>
          </section>

          <section className="health-panel" aria-labelledby="health-title">
            <div className="panel-heading">
              <span className="section-label">Status dos serviços</span>
              <span className="panel-mark" aria-hidden="true">
                01
              </span>
            </div>

            <div role="status" aria-live="polite" aria-atomic="true">
              <span
                className={cn(
                  'health-symbol',
                  health.kind === 'ready' && 'health-symbol-ready',
                  (health.kind === 'degraded' || health.kind === 'unavailable') &&
                    'health-symbol-error',
                )}
                aria-hidden="true"
              >
                {health.kind === 'ready' ? (
                  <Check size={22} />
                ) : (
                  <CircleDashed
                    size={22}
                    className={isLoading ? 'motion-safe:animate-spin' : undefined}
                  />
                )}
              </span>
              <h2 id="health-title">{message.title}</h2>
              <p className="health-description">{message.description}</p>
            </div>

            <dl className="service-list" aria-busy={isLoading}>
              <ServiceRow
                label="Aplicação"
                icon={<Server size={17} aria-hidden="true" />}
                available={apiAvailable}
                loading={isLoading}
              />
              <ServiceRow
                label="Banco de dados"
                icon={<Database size={17} aria-hidden="true" />}
                available={databaseAvailable}
                loading={isLoading}
              />
            </dl>

            <Button className="w-full" type="button" disabled={isLoading} onClick={refresh}>
              <RefreshCw
                className={isLoading ? 'motion-safe:animate-spin' : undefined}
                aria-hidden="true"
              />
              {isLoading ? 'Verificando...' : 'Verificar novamente'}
            </Button>

            <p className="last-check">
              {health.kind === 'loading' ? (
                'Consulta em andamento'
              ) : (
                <>
                  Última verificação às{' '}
                  <time dateTime={health.checkedAt.toISOString()}>
                    {health.checkedAt.toLocaleTimeString('pt-BR')}
                  </time>
                </>
              )}
            </p>
          </section>
        </div>

        <DemoPanel />

        <aside className="project-note" aria-label="Sobre a demonstração">
          <span className="note-number" aria-hidden="true">
            Em desenvolvimento
          </span>
          <p>
            Comece pela demonstração de pedidos.
            <span> Crie uma entrega como loja ou receba ofertas como entregador.</span>
          </p>
        </aside>
      </main>

      <footer className="site-footer">
        <p>Bora. Entregas em movimento.</p>
        <SimulationNotice />
      </footer>
    </div>
  );
}

function ServiceRow({
  label,
  icon,
  available,
  loading,
}: {
  label: string;
  icon: React.ReactNode;
  available: boolean | null;
  loading: boolean;
}) {
  const status = loading
    ? 'Verificando'
    : available === null
      ? 'Não verificado'
      : available
        ? 'Disponível'
        : 'Indisponível';

  return (
    <div className="service-row">
      <dt>
        {icon}
        {label}
      </dt>
      <dd>
        <span
          className={cn(
            'service-dot',
            available === true && 'service-dot-ready',
            available === false && 'service-dot-error',
          )}
          aria-hidden="true"
        />
        {status}
      </dd>
    </div>
  );
}
