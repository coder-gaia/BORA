# Qualidade da interface

Este relatório registra a qualificação do frontend no M13, em 21 de outubro de 2026.
Os resultados de despacho, carga e continuidade do servidor são provas separadas.

## Correções

- Os títulos continuam recebendo foco depois da troca de área, abertura do detalhe e
  confirmação de uma ação. O contorno aparece com navegação por teclado, por meio de
  `:focus-visible`, e acompanha o comportamento do navegador ao usar o mouse. O ajuste é
  restrito aos títulos; os controles, atalhos e alvos da linha do tempo mantêm seus
  indicadores de foco.
- No rastreio público, "Linha do tempo" passou de `h4` para `h2`, respeitando a hierarquia
  iniciada pelo `h1` da página. O estilo visual é o mesmo. A primeira auditoria axe real
  encontrou e reprovou essa falha; seu relatório e screenshot foram preservados antes da
  correção.
- A assinatura do cache de pedidos agora agenda as notificações com
  `notifyManager.batchCalls`, o mesmo adaptador usado pelo TanStack Query para integrar
  observers ao React. Durante a suíte inicial, o Vite registrou três avisos de atualização
  de `OrderBrowser` durante outro render. As tentativas controladas anteriores ao ajuste
  não reproduziram o aviso; elas não são apresentadas como uma regressão vermelha. O
  registro original e os hashes das fontes anteriores ao ajuste foram preservados.

## Suíte de navegador

`apps/web/playwright.config.ts` executa os testes em um worker, sem retries. O catálogo
contém 39 casos em 16 arquivos. Há 18 casos com integração ao Compose, incluindo um
conflito HTTP controlado da operação, e 21 regressões com HTTP/WebSocket controlados.
Esses dois grupos não representam a mesma espécie de prova.

Os fluxos integrados cobrem criação e idempotência, filtros e busca por código, atribuição,
ofertas privadas, recusa e expiração, GPS persistido, coleta, entrega com código incorreto
e correto, redistribuição, rastreio anônimo, saída e reconexão nas duas instâncias.

As regressões controladas verificam paginação, respostas atrasadas, relógio divergente,
interrupção da direção, revogação de sessão, ordem estável da fila e foco por mouse e
teclado. Os seis casos de foco percorrem loja e operação em 320, 390 e 1440 pixels,
incluindo detalhes, confirmações e retorno ao pedido de origem.

Para executar os casos integrados é necessário o Compose local de duas APIs, com grafo
importado e simulador de 120 entregadores saudável. Os flags que habilitam provas que
controlam containers precisam ser explícitos:

| Variável                          | Uso                                                          |
| --------------------------------- | ------------------------------------------------------------ |
| `BORA_PREVIEW_URL`                | Origem HTTP local da interface, com proxy para o Compose.    |
| `BORA_TEST_CLUSTER=true`          | Habilita a prova de parada e recuperação das APIs.           |
| `BORA_CLUSTER_PROJECT`            | Projeto local inspecionado pelo teste de recuperação.        |
| `BORA_TEST_OPS_FIXTURE=true`      | Habilita a fixture isolada de redistribuição real.           |
| `BORA_OPS_FIXTURE_PROJECT`        | `bora-sim-soak` ou `bora-m13-e2e`.                           |
| `BORA_OPS_FIXTURE_DB_VOLUME`      | Nome exato do volume local de dados revisado para o teste.   |
| `BORA_OPS_FIXTURE_DOCKER_CONTEXT` | `desktop-linux` no Windows; `default` no Linux local.        |
| `BORA_OPS_VISUAL_QA=true`         | Habilita os três casos visuais controlados da operação.      |
| `BORA_OPS_QA_MAP_MODE=real`       | Usa MapLibre, worker e recursos externos reais nesses casos. |

A fixture recusa hosts externos, contextos Docker remotos, projetos desconhecidos,
portas diferentes das revisadas e volumes que não coincidam com o nome explícito. Ela
restaura o simulador e os atores depois de liberar seus próprios pedidos. Os testes que
usam autenticação interna do simulador mantêm tracing desligado.

A fase de acessibilidade usa nove entradas na demonstração; os demais casos usam 29.
Como todos compartilham o mesmo IP do proxy, as duas fases são separadas no harness local
e no CI. Entre elas, somente os buckets `demo:%` do banco isolado de testes são removidos,
depois da verificação de projeto e volume. Os limites do produto continuam ativos.

## Acessibilidade

`accessibility.spec.ts` usa axe-core 4.14.0, fixado no lockfile, sem desabilitar regras.
São 12 estados por largura em 320, 390 e 1440 pixels: início, loja anônima, criação,
confirmação de criação, detalhe, rastreio anônimo, entregador disponível, oferta, entrega
aceita, visão geral da operação, consulta de pedidos e confirmação de cancelamento.

Essas 36 auditorias não interceptam a API, a autenticação, as posições, o mapa ou o
WebSocket. Somente o script do próprio axe é fornecido pelo harness em uma URL de mesma
origem, para obedecer ao `script-src` real. Não há `bypassCSP`.

As 12 auditorias adicionais da operação usam dados controlados para cobrir fila, conflito
e revogação, além da visão geral. Todos os resultados `incomplete`, com seus alvos e
verificações, são anexados ao relatório para revisão; ausência de violações automáticas
não substitui essa revisão.

Na qualificação final, as 48 auditorias não tiveram violações automáticas. Houve resultados
`incomplete` em 16 auditorias, com 41 alvos de contraste. Todos foram revisados com cores
computadas, CSS, empilhamento e screenshots representativos, sem pendências. As razões
mínimas revisadas foram 6,87:1 na escala do mapa; 10,95:1 e 16,52:1 nos marcadores da linha
do tempo; 8,86:1 na nota de exploração; 14,47:1 na atribuição; 17,72:1 no número da oferta
e 5,69:1 na unidade de segundos. Para fundos translúcidos, o cálculo usa preto por baixo
do branco, produzindo um limite conservador independente da imagem do mapa.
Depois do ajuste do cache, os 41 alvos mantiveram as mesmas cores computadas, fundos,
fontes e seletores, e os hashes dos cinco estilos revisados permaneceram idênticos.
Os screenshots finais também foram conferidos; a revisão manual foi revalidada sem
descartar resultados `incomplete` nem desabilitar regras.

## Mapa e desempenho

O comportamento aprovado continua sendo prévia ao vivo com exploração explícita. O
ensaio de renderização não mede a capacidade de despacho do backend.

`npm run test:map-performance` serve para repetir a medição com o `dist` de produção já
servido. `BORA_PREVIEW_URL` define a origem HTTP local e `BORA_BENCHMARK_OUTPUT` define a
pasta de evidências. O script exige que o HTML servido tenha exatamente o SHA-256 do
build atual.

O cenário fornece 300 posições sintéticas em snapshots de 1 Hz, com a base WebP real.
Depois de cinco segundos de aquecimento, mede 30 segundos de pinturas Canvas2D reais,
incluindo o número de círculos de cada pintura. Registra GPU, navegador, hardware,
intervalos e duração de desenho; verifica pausa, movimento reduzido e ausência de WebGL
antes da exploração. Depois abre "Explorar mapa", espera o MapLibre real e repete o
aquecimento e a janela de 30 segundos, com a câmera estática. Registra separadamente os
desenhos WebGL desde a abertura e durante essa janela. Pan e zoom por teclado são
verificados em seguida, por nova posição de câmera enviada e por novos desenhos da GPU.
O critério declarado de cadência visual para cada fase é média de pelo menos 50 pinturas
Canvas por segundo e intervalo p95 abaixo de 34 ms. Pinturas Canvas são uma aproximação
da fluidez; o ensaio não mede quadros apresentados pelo compositor ou pela GPU. Uma
atualização de snapshot pode produzir uma pintura adicional entre callbacks de animação.

As medições de Lighthouse 13.5.0 usam navegação mobile padrão com CPU simulada em 4 vezes,
viewport de 412 por 823 e DPR 1,75. Cada uma das três execuções usa um processo novo do
Chromium e a limpeza padrão de storage/cache. O custo posterior do clique em "Explorar
mapa" é conferido funcionalmente e não integra a nota de navegação inicial.

O ambiente local de renderização é Windows 10.0.26300 x64, AMD Ryzen 7 5700U, 16
processadores lógicos e 19.165.339.648 bytes de memória física visível ao sistema. O
Chromium 156.0.8078.4 usa ANGLE com a GPU AMD Radeon e Direct3D11, driver
31.0.21923.11000. Isso descreve o notebook usado; não representa um telefone físico.

## Resultados locais

A primeira qualificação completa executou os 39 casos sem skips ou retries: 36 casos
em 687,36 segundos e os três casos de acessibilidade em 64,47 segundos. O HTML desse
artefato tinha SHA-256
`88814bedc9d34dbbcfc460952c59b44cc442f21fce950df847aed1fc973c7632`.
Essa é a origem das observações de aviso React e das evidências preservadas antes da
correção da assinatura do cache.

| Medição inicial, anterior ao ajuste do cache | Resultado                                                      |
| -------------------------------------------- | -------------------------------------------------------------- |
| Lighthouse mobile, três processos novos      | Desempenho 92 / 92 / 92; acessibilidade 100 / 100 / 100        |
| CLS nas três execuções                       | 0                                                              |
| Prévia, 300 posições                         | 60,00 pinturas Canvas/s; intervalo p95 17,9 ms; máximo 25,5 ms |
| Exploração com MapLibre, 300 posições        | 61,00 pinturas Canvas/s; intervalo p95 17,9 ms; máximo 27,6 ms |
| Pausa e movimento reduzido                   | 0 pinturas em 2,2 s; 3 pinturas em 3,2 s, respectivamente      |
| Pan e zoom por teclado após a medição        | Câmera alterada e novos desenhos WebGL confirmados             |

Depois de `notifyManager.batchCalls`, os 11 casos direcionados de paginação, criação
concorrente, foco e operação controlada passaram em 50,14 segundos, sem skips, retries
ou avisos React observados. Formatação, lint, tipos, 65 testes unitários e build com
pré-renderização também passaram. O HTML reconstruído tem SHA-256
`ce1f9027c00f42a1c6dec1560b5c714f288136c985549319be9f524dcb30c227`.
As medições desse último artefato são registradas separadamente, sem substituir os
relatórios originais.

O artefato final servido pelo Caddy tem o mesmo hash HTML do build nativo. A imagem é
`sha256:f3a6758cc5b82f1df55d3b1bc1680d8cf939cbc8fa093a3412fd638bb9baf017`.
A requalificação executou novamente os três casos reais de acessibilidade em 55,30
segundos e a navegação integrada da loja em 6,04 segundos, sem skips, retries, erros de
JavaScript ou violações de CSP. Esses casos repetidos não são somados aos 39 casos
únicos do catálogo.

| Medição final, entre 14:58:31 e 15:00:29 UTC | Resultado                                                    |
| -------------------------------------------- | ------------------------------------------------------------ |
| Lighthouse mobile: desempenho                | 93 / 92 / 92; mediana 92                                     |
| Lighthouse mobile: acessibilidade            | 100 / 100 / 100                                              |
| LCP nas três execuções                       | 3.100 / 3.175 / 3.199 ms                                     |
| TBT nas três execuções                       | 23 / 10 / 10,5 ms                                            |
| CLS e avisos do Lighthouse                   | 0 e nenhum, nas três execuções                               |
| Prévia, 300 posições                         | 60,00 pinturas Canvas/s; intervalo p95 17,9 ms; máximo 19 ms |
| Exploração com MapLibre, 300 posições        | 60,97 pinturas Canvas/s; intervalo p95 18,1 ms; máximo 19 ms |
| Tempo de desenho Canvas p95                  | 0,8 ms na prévia; 0,4 ms na exploração                       |
| Pausa e movimento reduzido                   | 0 pinturas em 2,2 s; 3 pinturas em 3,2 s                     |
| WebGL                                        | 0 contextos antes de explorar; 1 contexto depois de explorar |
| Pan e zoom por teclado                       | Novo viewport e aumento de 2.061 para 5.622 desenhos WebGL   |

Todas as pinturas medidas desenharam os 300 marcadores. Na janela com câmera estática,
o MapLibre não redesenhou a base WebGL; os marcadores são pintados na camada Canvas.
As 60,97 pinturas por segundo incluem as pinturas adicionais dos snapshots e não
representam uma medição de quadros apresentados pela GPU.

## Evidências

Os relatórios completos, screenshots, falhas iniciais e hashes ficam em
`%TEMP%/bora-m13-web` na qualificação local. O CI preserva seus relatórios e anexos como
artefatos. Os arquivos de planejamento pessoal não fazem parte dessas evidências
versionadas.
