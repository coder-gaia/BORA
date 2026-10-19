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

O rastreio exige o código aleatório do pedido e não inclui código de confirmação, histórico
privado ou identidade do visitante. A consulta HTTP equivalente é `GET /api/v1/tracking/{code}`.
Esse contrato inicial informa progresso e posição; ainda não estima o tempo restante da entrega.

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
