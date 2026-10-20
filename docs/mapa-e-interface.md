# Mapa e interface

A página pública apresenta a cidade simulada e dá acesso à demonstração de loja, entregador e
operação. O mapa mostra os entregadores simulados na área visível; os pedidos do visitante
continuam restritos ao seu contexto. O [guia de tempo real](tempo-real.md) descreve os contratos
de mensagens, e o [guia de simulação](simulacao.md) explica como os atores usam a API.

## Carregamento da página

O build gera HTML público com React e incorpora o CSS inicial. A primeira pintura já contém
apresentação, navegação, a imagem do mapa e a estrutura da demonstração. A imagem tem dimensões
explícitas, prioridade alta e preload no HTML. Sua busca não depende da hidratação. Sessão,
posições e demais dados de uso são consultados pelo navegador depois dela.

A abertura usa **Prévia ao vivo**: uma base cartográfica real de área fixa com os entregadores
em movimento sobre um Canvas 2D. **Explorar mapa** carrega MapLibre GL JS 6.15, seu worker e seu
CSS por importação dinâmica e habilita mover o mapa e ajustar o zoom. O custo do motor WebGL
fica nessa ação; não desaparece. Não é necessário explorar para acompanhar o movimento inicial
ou experimentar os papéis da demonstração.

A interface usa Inter Variable instalada no projeto, com arquivos WOFF2 servidos pela própria
origem e preload da variante latina. O build continua sendo estático; o preparo do HTML não
exige um servidor Node em produção.

## Prévia e mapa interativo

### Base real da prévia

A imagem da região central de São Paulo foi renderizada com MapLibre e o estilo Positron do
OpenFreeMap em **19 de outubro de 2026 às 15:04:05 UTC, 12:04:05 em São Paulo**. Essa é a data
da renderização, não uma afirmação sobre quando os dados do OpenStreetMap foram atualizados.
Ruas, bairros, parques e nomes de lugares fazem parte da cartografia real. A imagem não inclui
entregadores: os pontos são recebidos da API em execução e desenhados no navegador.

Os créditos de **OpenFreeMap**, **OpenMapTiles** e **OpenStreetMap** permanecem visíveis e com
links na prévia e no mapa interativo. Os rótulos da imagem já estão no raster; ela não precisa
baixar glifos externos durante a abertura.

Os metadados em `apps/web/src/generated/public-basemap.json` são compilados com a interface e
registram URL do estilo, data de captura, centro, zoom, orientação,
limites geográficos, dimensões CSS e do raster, escala de pixels e hash da imagem. A captura
aprovada usa centro `[-46.645, -23.556]`, zoom `12.4`, rotação e inclinação zero, viewport de
`640 × 480` pixels CSS e raster WebP de `1280 × 960` pixels. Seu SHA-256 é
`4e2354958a9a93c108c90ab79bbe8bd6735928727818b4109d74cd1c8b3f570f`.

Imagem e metadados precisam ser atualizados juntos. O arquivo em `public/assets/` usa o padrão
`saopaulo-basemap-<SHA256>.webp`; o campo `assetUrl` compilado é igual no HTML e no cliente. A
imagem é publicada com nome que contém hash; uma versão anterior da página deve continuar apontando para a base correspondente aos
seus próprios metadados, mesmo depois de outra publicação. O arquivo pode envelhecer; atualizar
a base exige nova renderização e validação da imagem e dos créditos.

A projeção dos pontos usa Mercator e o mesmo recorte centralizado da imagem, sem distorcer sua
proporção. Ao redimensionar a página, o navegador recalcula a área realmente visível e pede ao
feed apenas esse recorte. O contador refere-se a essa área. A prévia usa orientação fixa; o
botão de exploração abre a navegação interativa a partir da região que estava visível.

### Explorar mapa

O botão **Explorar mapa**, com a dica **Mover e ajustar zoom**, abre o mapa interativo. A página
mantém a prévia e a demonstração disponíveis durante o carregamento. Depois que as camadas
ficam prontas, o foco passa ao canvas do mapa, com instruções de teclado. **Voltar à prévia**
encerra o mapa e sua conexão e devolve o foco ao botão de exploração. A transição respeita a
escolha de pausa do visitante e encerra os recursos do modo que deixou de ser usado.

A base interativa também usa Positron do OpenFreeMap. O estilo preserva a ordem das camadas;
elementos de aeroportos, alguns limites administrativos e símbolos baseados em sprites são
removidos do recorte urbano. Rótulos usam Noto Sans Regular. Esses glifos são obtidos pelo
estilo externo e são separados dos arquivos de fonte da interface.

A cor de fundo é aplicada ao contêiner quando o estilo fornece uma única camada de fundo de
cor constante, opaca e sem padrão. As camadas de área aparecem primeiro; linhas e rótulos são
acrescentados depois de pinturas úteis do mapa. Esse carregamento completo acontece após
explorar; ruas e nomes de lugares continuam fazendo parte do resultado.

Se a exploração falhar, a página oferece recuperação sem impedir o uso da demonstração. A
indisponibilidade de estilo ou tiles externos não deve apagar uma prévia já disponível. A
falha da própria imagem estática também precisa ser informada, com alternativa para explorar
o mapa ou continuar pela demonstração.

### Desenho e ciclo de vida

Os pontos dos entregadores usam um único Canvas 2D sobre a base de cada modo. O desenho
diferencia entregadores disponíveis, com oferta e em entrega, sem criar um elemento HTML por
ator. Entre posições recebidas, a interface interpola o movimento por até um segundo, termina
no último ponto e aguarda a próxima atualização. Frames anteriores ao horário já aceito são
ignorados; a remoção de um ponto do snapshot também o retira do desenho.

**Pausar movimento** congela a animação visual; a simulação e a comunicação com a API continuam
em execução. Redimensionar reprojeta a posição exibida sobre o novo recorte sem retomar o
movimento. A escolha de pausa é preservada ao explorar. Com `prefers-reduced-motion`, cada
atualização usa diretamente a posição recebida. Uma aba oculta suspende o desenho contínuo;
ao voltar, a interface usa os dados disponíveis.

Cada modo cancela requisições substituídas e encerra timers, listeners, observadores, frames de
animação e conexões que deixam de ser necessários. A troca de modo não deve manter dois feeds
permanentes nem receber callbacks de uma instância já encerrada.

### Conexão e privacidade

A prévia e o mapa interativo usam o mesmo contrato público. O feed é STOMP sobre `/ws`, na
mesma origem da página: obtém o token CSRF da sessão, assina `/user/queue/map` e envia a área
visível para `/app/viewport`, com escopo `PUBLIC`. A câmera ou o recorte responsivo atualiza essa
área e seu contador.

O primeiro viewport é enviado antes da assinatura do mapa. Mudanças posteriores guardam
somente o recorte mais recente e aguardam progresso por um frame WebSocket válido, seguido de
uma margem de 500 ms. Consultas HTTP não liberam essa proteção. Isso evita que a leitura
inicial da assinatura comprima no servidor uma sequência de viewports enviados pelo cliente.
Um frame posterior indica progresso; o protocolo não o apresenta como confirmação exata de
uma revisão de viewport.

`GET /api/v1/map/snapshot` fornece o estado inicial e recupera posições durante a reconexão.
Enquanto o WebSocket está desconectado e a página está visível, o feed tenta essa consulta a
cada cinco segundos. A reconexão usa espera progressiva. Respostas HTTP de uma área anterior
são descartadas mesmo quando terminam depois da consulta da área atual.

O servidor determina quais entregadores podem aparecer. O escopo público inclui o cenário
simulado e protege os vínculos privados; os pontos contêm identificador, coordenadas, estado e
rumo. O feed público não inclui nomes de visitantes, códigos de confirmação ou detalhes de seus
pedidos. Os limites de autorização são conferidos pela API, inclusive nas assinaturas.

A imagem inicial é servida pela própria origem e não exige rede do OpenFreeMap em cada visita.
Explorar depende dos recursos externos do mapa. API e feed permanecem necessários para os
pontos serem atualizados ao vivo; a imagem sozinha não representa posições atuais.

## Linha do tempo do pedido

A loja, a operação e o entregador atribuído consultam as etapas reais do mesmo pedido. Cada
consulta e assinatura mantém as regras de propriedade e contexto. A consulta HTTP do entregador
usa o mesmo snapshot autorizado do WebSocket e não recebe o código de confirmação.

| Estado      | Marco apresentado    |
| ----------- | -------------------- |
| `SEARCHING` | Busca por entregador |
| `ASSIGNED`  | A caminho da loja    |
| `AT_STORE`  | Na loja              |
| `PICKED_UP` | A caminho do cliente |
| `DELIVERED` | Entregue             |

Os marcos usam os eventos persistidos e seus horários. Ofertas, recusas e expirações permanecem
no histórico, sem concluir etapas da entrega. Um horário ausente continua ausente na interface.

A redistribuição reinicia os marcos na busca, informa a tentativa atual e preserva os registros
das anteriores. O cancelamento marca a etapa interrompida e identifica as seguintes como não
realizadas. Somente a confirmação de entrega conclui o caminho inteiro.

Selecionar uma etapa mostra sua descrição, situação e horário disponível. **Ver registro no
histórico** abre os eventos e leva o foco ao registro correspondente. O histórico usa uma lista
com tipo e instante dos eventos de todas as tentativas. Snapshots HTTP atrasados preservam a
versão mais recente já recebida por WebSocket.

Os marcos são botões acessíveis por Tab e ativados por Enter ou Espaço, com foco visível. O
histórico pode ser aberto pelo teclado. Na tela estreita, a sequência passa para a disposição
vertical. O mapa também oferece controles de zoom, instruções de teclado e gesto cooperativo
para evitar capturar a rolagem da página por acidente.

## Loja e consulta de pedidos

A rota `/loja` abre uma área dedicada. Visitar o endereço não cria uma identidade nem troca
o papel da sessão: a entrada acontece pelo botão da loja. A página inicial mantém o mapa e
suas entradas de demonstração.

**Novo pedido** apresenta a origem e um mapa para escolher o destino. É possível clicar no
mapa, usar o centro após navegar pelo teclado ou preencher latitude e longitude. A API continua
validando área atendida e acesso por via. O mapa não substitui essas validações.

Depois de criar, a confirmação aparece junto ao formulário, com o código do pedido e a ação
**Acompanhar pedido**. O detalhe só abre por essa ação ou pela seleção de um pedido na lista.
Ele ocupa uma área própria, com título e foco previsíveis, em vez de ser acrescentado depois
de uma lista longa.

**Ver pedidos** abre a consulta e seus controles. A loja começa em **Meus pedidos**; o visitante
pode escolher **Cenário simulado** ou o conjunto dos dois. O filtro por estado inclui busca,
trajeto até a loja, chegada, entrega em andamento, entrega concluída e cancelamento. A busca
usa o código público completo do pedido, respeitando maiúsculas e minúsculas. Esse código
não é o código secreto de seis dígitos usado na confirmação da entrega.

Busca e filtros são aplicados pelo servidor sobre todo o conjunto autorizado, antes da
paginação. Novidades sinalizam que há atualizações disponíveis; a lista apresentada muda
quando o visitante pede uma atualização. O detalhe mantém seu próprio acompanhamento ao
vivo. **Voltar** restaura a consulta, os filtros e a página que estavam abertos.

## Rastreio de uma entrega

O detalhe permite abrir ou copiar o link `/rastreio/<codigo>`. Outra pessoa pode abri-lo sem
escolher um papel. O link concede somente o progresso daquele pedido: coleta, destino,
etapas, posição recente do entregador e, quando disponível, a rota e a estimativa até o
próximo marco. O código de confirmação, as identidades e os detalhes internos das ofertas
não são apresentados.

O tempo exibido considera a velocidade da demonstração e a data de cálculo da rota. Não é
uma previsão de trânsito. Uma posição desatualizada deixa de aparecer; se a rota não puder
ser calculada naquele momento, o estado e a linha do tempo continuam disponíveis. Depois
de entrega ou cancelamento, o acompanhamento mostra o encerramento, sem reutilizar posição
do entregador em outro trabalho.

O rastreio usa STOMP e consulta HTTP durante recuperação de conexão. Um link inexistente
ou de contexto expirado mostra indisponibilidade e encerra o acompanhamento daquele código.
Em uma falha temporária de rede, o último estado válido pode permanecer visível, identificado
como uma atualização anterior.

## Entregador

A rota `/entregador` reúne disponibilidade, oferta, percurso e ações da entrega. O mapa da
etapa carrega depois da atribuição; a página inicial mantém sua prévia e a escolha
**Explorar mapa**. A contagem da oferta tem representação visual e texto em segundos,
sem anunciar uma mudança a cada quadro para leitores de tela.

O treino permite percorrer o fluxo sem depender de outra pessoa alternando para a loja.
A direção é uma ação explícita, pode ser pausada e exige uma nova intenção para retomar.
Os marcos de chegada, coleta e entrega refletem o estado confirmado pelo backend.
Consulte o [guia de entregadores](entregadores-e-posicoes.md#área-do-entregador-e-treino)
para os limites, a recuperação da intenção e o código privado de confirmação.

## Operação

Em `/operacao`, **Sou da operação** abre a visão geral do cenário simulado e do próprio
contexto. Os indicadores representam esse conjunto autorizado, independentemente da área
visível no mapa e dos filtros de uma página de pedidos.

O mapa agrupa entregadores próximos. Aproximar um grupo permite distinguir seus pontos;
a escala métrica ajuda a comparar distâncias. A lista acessível oferece outra forma de
consultar e localizar entregadores. Esse mapa usa uma conexão privada da operação, separada
da conexão pública da página inicial. Sair ou perder a autorização remove esses pontos.

**Ver pedidos** revela a consulta e a fila de busca. A espera considera a tentativa atual,
inclusive depois de redistribuir. A confirmação de uma ação mostra o código do pedido e seu
efeito; um conflito atualiza o detalhe sem repetir a ação automaticamente. Consulte o
[guia da operação](operacao.md) para os intervalos dos indicadores e as regras da fila.

## Publicação e política de conteúdo

A política de produção permite `https://tiles.openfreemap.org` em `connect-src` e `img-src` para
estilo, tiles e recursos do mapa. O worker é emitido em `/assets/` e configurado explicitamente
antes de criar o mapa, permanecendo na origem da aplicação. As fontes da interface também são
servidas nessa origem. Consulte os cuidados de CSP e assets no [guia de publicação](deploy.md).

O exportador copia os assets com hash antes de ativar o novo HTML e preserva arquivos de
versões anteriores. Isso permite que uma aba já aberta encontre seus chunks e worker quando
uma importação dinâmica acontecer depois de uma atualização.

## Conferência funcional

Com o [ambiente local](../README.md#executar-localmente) em execução:

1. Abra a página e confira a prévia, ruas, nomes de lugares, créditos e contador. Antes de
   explorar, os entregadores já devem se mover e a rede não deve carregar MapLibre ou worker.
   Confira também a página em larguras de 320, 390 e 1440 pixels.
2. Pause, redimensione e retome. Os pontos devem continuar alinhados à base durante a pausa.
   Explore pelo teclado e confira a mesma região, o foco e a pausa preservada. Retome, mova o
   mapa e ajuste o zoom; posições e contador devem corresponder à nova área.
3. Ative movimento reduzido e confira atualizações sem interpolação. Oculte e reabra a aba.
   Bloqueie somente o WebSocket, mantendo HTTP disponível; confira recuperação por snapshot e
   reconexão. Bloqueie os recursos externos e confira a recuperação da exploração. Teste a
   falha da imagem da prévia sem bloquear o restante da demonstração.
4. Abra `/loja` e entre como loja. Confira que a lista e seus filtros só aparecem ao clicar
   em **Ver pedidos**. Crie um pedido antes de deixar seu entregador disponível e use
   **Acompanhar pedido**. Guarde o código de confirmação da loja; ele permanece oculto no
   papel de entregador. Busque o pedido pelo código público e teste os filtros. Confira que
   novidades não mudam a ordem ou o foco da consulta aberta.
5. Como entregador, aceite a oferta, informe posição na coleta e aguarde a chegada confirmada.
   Confira a sequência na loja e no entregador, confirme a coleta e conclua com o código.
6. Em outro pedido, use a operação para redistribuir durante uma etapa permitida. Confira o
   retorno à busca e a preservação do histórico. Cancele um pedido em uma etapa autorizada e
   confira que as etapas seguintes continuam não realizadas.
7. Percorra marcos, descrição e histórico somente pelo teclado. Em tela estreita, confira a
   sequência vertical e a legibilidade das datas e dos controles.
8. Abra o rastreio em outra sessão de navegador. Confira etapas, mapa, posição e estimativa
   sem entrar em um papel. A confirmação da entrega deve encerrar o trajeto nas duas sessões.
   Confira também um código inexistente e a recuperação após interromper a conexão.

As ações e permissões detalhadas estão no [guia de identidade e pedidos](identidade-e-pedidos.md)
e no [guia de entregadores](entregadores-e-posicoes.md). A validação de CSP precisa usar também
os headers de produção, pois o proxy local de desenvolvimento não aplica essa política.

## Medições

A escolha de produto é **Prévia ao vivo + Explorar mapa**. A exploração passou a ser uma ação
explícita; uma nota maior de carregamento inicial não significa que a compilação WebGL deixou
de existir. O relatório precisa medir também a transição e registrar versão, navegador,
dispositivo ou GPU, viewport, escala, cache e limitação de CPU e rede.

O build definitivo foi medido em 19 de outubro de 2026 com Lighthouse 13.5, perfil mobile
padrão, CPU simulada em 4×, viewport `412 × 823`, DPR `1.75` e GPU AMD. Três execuções em
processos novos do Chromium deram **93, 94 e 94**, mediana **94**, faixa **93-94**. Todas tiveram
acessibilidade **100** e CLS **0**. A mediana de LCP foi **2,99 s** e a de TBT **49 ms**.

Essa prova usou o Caddyfile de produção intacto, o HTML estático do build e dados reais da
simulação local de 120 entregadores, com backend acessado por proxy local. Não houve outros
processos Chromium, Maven ou builds Docker durante a medição. A contagem no mapa varia com o
recorte e com o tempo. Os resultados não medem
latência até o Canadá, RAM total, consumo de GPU, bateria ou um celular físico. O clique para
explorar e sua compilação de shaders não fazem parte dessa nota de navegação inicial.

O candidato anterior, com mapa interativo carregado automaticamente, ficou aproximadamente
entre **72 e 77** no histórico do ambiente. Ele usou uma fixture sintética nos ensaios de
navegação. A referência ajuda a explicar a investigação, mas os dois cenários não formam uma
comparação controlada de backend ou uma garantia de ganho no deploy.

O ensaio definitivo da prévia com **300 pontos sintéticos** foi executado em Chromium com GPU
AMD, viewport `1440 × 1000` e DPR `1`. Foram **1.799 pinturas efetivas em 30 segundos**, cerca
de **60 fps**, após cinco segundos de aquecimento. Todas desenharam os 300 pontos. O intervalo
entre pinturas teve p95 de **17,9 ms**, p99 de **18,4 ms** e máximo de **23,1 ms**; o desenho teve
p95 de **0,9 ms**. A pausa não produziu pinturas nos 2,2 segundos observados. Nenhum contexto
WebGL foi criado antes de explorar.

Esse ensaio avalia o desenho no navegador; o cenário publicado mantém os **120 entregadores**
configurados na simulação. Seus resultados são separados dos dados da VM arm64 e do
carregamento inicial móvel. O [relatório de medições](benchmarks/map-rendering.json) preserva
também os resultados dos candidatos anteriores, suas diferenças de cenário e os hashes dos
artefatos. A navegação por teclado entre prévia e exploração foi conferida com os mesmos
headers de produção, incluindo foco, pausa e encerramento do worker e da conexão substituída.
