import { useEffect } from 'react';
import { ArrowDown, ArrowLeft, ArrowUpRight, Check, Package, Store, Truck } from 'lucide-react';
import './how-it-works.css';

const description =
  'Entenda o Bora em um minuto: crie um pedido, receba uma oferta, confirme a coleta e acompanhe a entrega. Experimente com dados fictícios em São Paulo.';

export function HowItWorksPage() {
  useEffect(() => {
    const meta = document.querySelector('meta[name="description"]');
    const previous = meta?.getAttribute('content');
    meta?.setAttribute('content', description);
    return () => {
      if (previous !== undefined && previous !== null) meta?.setAttribute('content', previous);
    };
  }, []);

  return (
    <div className="home-shell how-page-shell">
      <title>Como funciona | Bora</title>
      <a className="skip-link" href="#how-content">
        Pular para o conteúdo
      </a>
      <header className="how-page-header">
        <a className="home-brand" href="/" aria-label="Bora, início">
          bora<span aria-hidden="true">.</span>
        </a>
        <nav aria-label="Navegação principal">
          <a href="/" className="how-back-link">
            <ArrowLeft size={15} aria-hidden="true" /> Voltar à cidade
          </a>
          <a href="/como-funciona" aria-current="page">
            Como funciona
          </a>
        </nav>
      </header>

      <main id="how-content" className="how-page-main" tabIndex={-1}>
        <section className="how-intro" aria-labelledby="how-page-title">
          <div>
            <p className="how-kicker">Como funciona</p>
            <h1 id="how-page-title">Uma entrega, do começo ao fim.</h1>
            <p className="how-intro-copy">
              A loja cria o pedido. O Bora procura quem chega mais rápido à coleta. O entregador
              aceita, busca e leva até o destino. Você acompanha cada etapa ao vivo.
            </p>
            <div className="how-intro-actions">
              <a className="how-primary-link" href="#treino">
                Praticar uma entrega <ArrowDown size={16} aria-hidden="true" />
              </a>
              <a className="how-text-link" href="#etapas">
                Ver as etapas <ArrowDown size={15} aria-hidden="true" />
              </a>
            </div>
            <p className="how-demo-note">
              Demonstração em São Paulo. Lojas, entregadores e pedidos são fictícios.
            </p>
          </div>
          <aside className="how-journey" aria-label="Resumo do caminho da entrega">
            <p className="how-kicker">Do pedido à chegada</p>
            <ol>
              <li>
                <span className="how-journey-icon">
                  <Store size={18} aria-hidden="true" />
                </span>
                <span>
                  Pedido criado <small>A loja escolhe o destino</small>
                </span>
              </li>
              <li>
                <span className="how-journey-icon">
                  <Truck size={18} aria-hidden="true" />
                </span>
                <span>
                  Entregador a caminho <small>A oferta foi aceita</small>
                </span>
              </li>
              <li>
                <span className="how-journey-icon">
                  <Package size={18} aria-hidden="true" />
                </span>
                <span>
                  Pedido coletado <small>Agora, até o destino</small>
                </span>
              </li>
              <li>
                <span className="how-journey-icon how-journey-finish">
                  <Check size={18} aria-hidden="true" />
                </span>
                <span>
                  Entrega confirmada <small>O caminho foi concluído</small>
                </span>
              </li>
            </ol>
            <p className="how-journey-caption">Exemplo das etapas, sem um pedido em andamento.</p>
          </aside>
        </section>

        <section className="how-section" aria-labelledby="etapas">
          <div className="how-section-heading">
            <p className="how-kicker">O caminho de um pedido</p>
            <h2 id="etapas" tabIndex={-1}>
              Cada passo tem sua confirmação.
            </h2>
          </div>
          <ol className="how-steps">
            <li>
              <span className="how-step-number" aria-hidden="true">
                01
              </span>
              <h3>A loja chama</h3>
              <p>
                Na área da loja, escolha um destino dentro da área atendida e crie o pedido. Guarde
                o código de confirmação exibido na criação.
              </p>
            </li>
            <li>
              <span className="how-step-number" aria-hidden="true">
                02
              </span>
              <h3>O entregador decide</h3>
              <p>
                Quem está disponível recebe a oferta, com loja, destino e previsão até a coleta. Há
                30 segundos para aceitar ou recusar. Se a oferta vencer, a busca continua.
              </p>
            </li>
            <li>
              <span className="how-step-number" aria-hidden="true">
                03
              </span>
              <h3>A coleta acontece</h3>
              <p>
                Depois do aceite, o entregador vai até a loja. A chegada precisa ser confirmada pela
                posição antes de usar Confirmar coleta.
              </p>
            </li>
            <li>
              <span className="how-step-number" aria-hidden="true">
                04
              </span>
              <h3>A entrega é confirmada</h3>
              <p>
                O entregador segue até o destino e usa o código de seis dígitos para confirmar a
                entrega de um pedido privado. Chegar ao destino não conclui a entrega sozinho.
              </p>
            </li>
          </ol>
        </section>

        <section className="how-training" aria-labelledby="treino">
          <div>
            <p className="how-kicker">Para experimentar sozinho</p>
            <h2 id="treino" tabIndex={-1}>
              Seu primeiro treino.
            </h2>
            <p>
              Na área do entregador, escolha Sou entregador e depois Ficar online. Use Criar pedido
              de treino para receber uma oferta só sua.
            </p>
            <a className="how-primary-link" href="/entregador">
              Ir para a área do entregador <ArrowUpRight size={16} aria-hidden="true" />
            </a>
          </div>
          <ol>
            <li>
              <strong>Aceite a oferta em até 30 segundos.</strong> Guarde o código deste treino,
              exibido na própria tela.
            </li>
            <li>
              <strong>Escolha Dirigir até a loja.</strong> Você pode pausar e retomar. Aguarde a
              chegada e confirme a coleta.
            </li>
            <li>
              <strong>Dirija até o cliente.</strong> Ao chegar, informe o código do treino e escolha
              Confirmar entrega.
            </li>
          </ol>
        </section>

        <section className="how-section" aria-labelledby="how-areas-title">
          <div className="how-section-heading">
            <p className="how-kicker">Escolha por onde começar</p>
            <h2 id="how-areas-title">Três pontos de vista da mesma entrega.</h2>
            <p>
              Abrir uma área preserva sua sessão. Para entrar ou trocar de papel, escolha o botão
              dessa área.
            </p>
          </div>
          <nav className="how-areas" aria-label="Áreas de demonstração">
            <a href="/loja">
              <Store size={21} aria-hidden="true" />
              <strong>Área da loja</strong>
              <span>
                Crie um pedido, consulte Meus pedidos e abra o detalhe para acompanhar ou copiar o
                link de rastreio.
              </span>
              <span className="how-area-action">
                Abrir área da loja <ArrowUpRight size={15} aria-hidden="true" />
              </span>
            </a>
            <a href="/entregador">
              <Truck size={21} aria-hidden="true" />
              <strong>Área do entregador</strong>
              <span>
                Fique online, receba ofertas e pratique o percurso. A direção simulada começa ao seu
                comando.
              </span>
              <span className="how-area-action">
                Abrir área do entregador <ArrowUpRight size={15} aria-hidden="true" />
              </span>
            </a>
            <a href="/operacao">
              <Package size={21} aria-hidden="true" />
              <strong>Área da operação</strong>
              <span>
                Veja a cidade, os prazos e a fila de busca. Abra os pedidos para consultar, cancelar
                ou redistribuir quando permitido.
              </span>
              <span className="how-area-action">
                Abrir área da operação <ArrowUpRight size={15} aria-hidden="true" />
              </span>
            </a>
          </nav>
        </section>

        <section className="how-details" aria-labelledby="how-details-title">
          <div className="how-section-heading">
            <p className="how-kicker">Antes de começar</p>
            <h2 id="how-details-title">Para acompanhar com tranquilidade.</h2>
          </div>
          <dl>
            <div>
              <dt>Rastreio e código têm funções diferentes</dt>
              <dd>
                Quem recebe o link de rastreio acompanha aquela entrega sem entrar. O link não
                mostra o código de confirmação. Guarde os seis dígitos para o momento de receber o
                pedido; o código de treino serve só para aquele treino.
              </dd>
            </div>
            <div>
              <dt>Previsão e prazo</dt>
              <dd>
                O detalhe do pedido mostra seu prazo de entrega. O tempo estimado do percurso
                acompanha o ritmo da simulação e pode mudar. A contagem de 30 segundos vale para
                responder à oferta.
              </dd>
            </div>
            <div>
              <dt>Dados fictícios, acesso temporário</dt>
              <dd>
                Use apenas destinos de teste. Seus pedidos ficam no seu contexto; o cenário público
                é simulado. O acesso expira após duas horas de inatividade. Sair encerra esse
                acesso, e o link de rastreio deixa de funcionar quando o contexto termina.
              </dd>
            </div>
          </dl>
        </section>
      </main>

      <footer className="how-page-footer">
        <div>
          <strong>bora.</strong>
          <span>Entregas em movimento.</span>
        </div>
        <p>
          Quer conhecer o projeto?{' '}
          <a href="https://github.com/coder-gaia/BORA">
            Código e documentação no GitHub <ArrowUpRight size={13} aria-hidden="true" />
          </a>
        </p>
      </footer>
    </div>
  );
}
