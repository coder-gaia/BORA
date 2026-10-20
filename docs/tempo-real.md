# Tempo real

O navegador usa STOMP sobre WebSocket em `/ws`, na mesma origem da página. A sessão permanece
no cookie HttpOnly. Antes de conectar, o cliente consulta `/api/v1/auth/csrf` e envia o token no
frame `CONNECT`. O servidor restringe a origem, os destinos e o acesso ao recurso solicitado.

## Mensagens e privacidade

| Destino                  | Conteúdo e acesso                                                        |
| ------------------------ | ------------------------------------------------------------------------ |
| `/topic/orders/{id}`     | Pedido completo, com as mesmas regras de propriedade da consulta HTTP.   |
| `/user/queue/offers`     | Oferta do próprio entregador autenticado.                                |
| `/user/queue/events`     | Aviso compacto para atualizar as consultas do próprio contexto.          |
| `/user/queue/map`        | Posições na área visível; público inclui somente entregadores simulados. |
| `/topic/tracking/{code}` | Progresso limitado concedido pelo código aleatório do link de rastreio.  |

A assinatura do mapa recebe sua área por `SEND /app/viewport`, com
`{west, south, east, north, scope}`. O escopo `PUBLIC` permite a visualização do cenário simulado.
`OPERATIONS` exige esse papel e acrescenta somente os dados permitidos ao seu contexto.
Frames do mapa são limitados a um por segundo e contêm
`[id, longitude, latitude, status, heading]` por ponto. O rumo ainda é zero no contrato inicial;
o cliente pode derivar a direção entre posições consecutivas. As consultas HTTP equivalentes são
`GET /api/v1/map/snapshot` e `GET /api/v1/ops/snapshot`, com os quatro limites como parâmetros.

O rastreio exige o código aleatório de 192 bits do pedido. Quem possui o link pode consultar
esse progresso limitado sem entrar na demonstração, por HTTP em `GET /api/v1/tracking/{code}`
ou por STOMP no destino correspondente. Não inclui código de confirmação, UUID do pedido ou do
entregador, identidade do visitante, atores, payloads privados nem identificadores globais de
eventos. A linha do tempo pública contém somente o tipo, os estados, o horário e a versão dos
marcos conhecidos da entrega. O código não é repetido no snapshot público.

Uma posição só é mostrada nas etapas atribuída, na loja e em entrega, quando recebida nos
últimos 30 segundos e após a atribuição atual. Posição antiga, pedido concluído ou cancelado
não expõe a localização do entregador. Um contexto privado expirado também torna o link
indisponível, mesmo que a geometria da rota esteja em cache.

O snapshot inclui uma rota e uma estimativa até o próximo marco quando há posição recente:
coleta para `ASSIGNED` e destino para `PICKED_UP`. `etaSeconds` usa o tempo simulado, não o prazo
de SLA, e corresponde ao instante `route.calculatedAt`. Enquanto busca entregador ou aguarda
coleta na loja, pode apresentar a rota planejada entre loja e destino, sem ETA. Ao encerrar,
ficar sem posição recente ou perder o grafo de roteamento, rota dinâmica e ETA ficam ausentes;
estado e linha do tempo permanecem disponíveis.

Para evitar GraphHopper em cada atualização WebSocket, cada instância mantém no máximo 256
geometrias em LRU, recalculadas após 10 segundos por pedido e geração. Trocar versão, etapa ou
entregador invalida a geração imediatamente. Falhas do grafo usam o mesmo intervalo de nova
tentativa. Um orçamento adicional permite no máximo 16 cálculos em cada janela deslizante de
um segundo por instância, inclusive quando existem mais links válidos que entradas no cache.
Quando ocupado, estado e linha do tempo permanecem disponíveis com rota e ETA ausentes; a
próxima janela pode tentar novamente, sem guardar uma falha de dez segundos por saturação.
Esse orçamento interno não altera os limites HTTP existentes. O cálculo ocorre fora da
transação de leitura; o cache não guarda autorização ou
snapshots privados, e cada consulta revalida o recurso no banco. A posição continua atualizada
independentemente desse intervalo da rota; a geometria e a ETA podem ter até 10 segundos de idade.

`serverTime` do rastreio é o `statement_timestamp()` do PostgreSQL capturado junto ao estado,
antes do cálculo de rota. As duas APIs usam assim a mesma fonte de relógio para ordenar leituras;
uma resposta HTTP antiga que terminar depois de um frame novo mantém o instante da sua leitura.
O cliente também compara versão e horário da posição para recusar dados atrasados.

Cada assinatura confere o recurso solicitado. A sessão, o papel e o acesso continuam sendo
validados durante a conexão; sair ou trocar de identidade encerra o acesso anterior. O cliente
não pode publicar diretamente nos destinos do broker.

## Duas APIs e recuperação

Triggers transacionais publicam identificadores curtos com `pg_notify`. O PostgreSQL só entrega
o aviso depois do commit. Cada API mantém uma conexão dedicada de `LISTEN`, separada do pool de
requisições, e consulta o estado persistido para montar a mensagem autorizada de cada cliente.
Os avisos não transportam o pedido nem substituem o banco como fonte do estado.

Uma reconciliação periódica recupera alterações quando um aviso é perdido. A interface
reconecta com espera progressiva e busca snapshots ao recuperar a conexão. Versão do pedido e
identidade dos eventos evitam aplicar atualizações repetidas. Enquanto desconectada, a tela
retoma a consulta HTTP periódica; pedidos e ofertas continuam tendo seus prazos validados no
servidor.

Uma publicação pode interromper o socket ligado à API substituída. A continuidade esperada é a
recuperação da sessão e do estado, com reconexão; as conexões de uma VM continuam sujeitas à
disponibilidade dessa máquina e do banco.

## Ambiente local com duas APIs

```sh
docker compose --env-file .env -f infra/compose/compose.cluster.yml up --build --wait
```

Abra `http://localhost:18080`. O Caddy distribui as requisições entre duas APIs que compartilham
o banco e as sessões. As portas locais `18081` e `18082` permitem consultar cada instância
diretamente, e o PostgreSQL usa `15433`. As variáveis `CLUSTER_HTTP_PORT`, `CLUSTER_API1_PORT`,
`CLUSTER_API2_PORT` e `CLUSTER_POSTGRES_PORT` permitem mudar essas portas.

Esse ambiente usa volumes próprios do projeto `bora-cluster`. Para encerrá-lo preservando os
dados:

```sh
docker compose --env-file .env -f infra/compose/compose.cluster.yml down
```

O teste Java de integração do módulo conecta um cliente WebSocket a uma API e escreve na outra,
usando PostgreSQL real. Ele também verifica os limites de autorização do protocolo. Os testes
de navegador cobrem atualização por mensagem e recuperação de estado após desconexão.

O ensaio de recuperação do cluster para uma API por vez e exige reconexão à outra, a mesma
identidade, novo snapshot e nenhuma resposta HTTP 5xx. Ele usa somente o projeto local
`bora-cluster` e precisa ser habilitado explicitamente. Com o cluster acima em execução:

```powershell
$env:BORA_PREVIEW_URL = 'http://localhost:18080'
$env:BORA_TEST_CLUSTER = 'true'
npm run test:e2e --prefix apps/web -- tests/cluster-recovery.spec.ts
Remove-Item Env:BORA_TEST_CLUSTER
Remove-Item Env:BORA_PREVIEW_URL
```

Em bash:

```sh
BORA_PREVIEW_URL=http://localhost:18080 BORA_TEST_CLUSTER=true npm run test:e2e --prefix apps/web -- tests/cluster-recovery.spec.ts
```
