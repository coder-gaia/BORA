# Entregadores e posições

O entregador de demonstração pertence ao mesmo contexto usado ao alternar entre loja,
entregador e operação. As ações exigem sessão e CSRF; o servidor usa o entregador da sessão.
Identificadores enviados pelo cliente não permitem controlar outro entregador.

## Disponibilidade e localização

`GET /api/v1/courier/me` consulta disponibilidade e a última posição persistida.
`GET /api/v1/courier/work` combina o entregador e seu pedido ativo em um snapshot consistente;
sem entrega, o campo `order` é nulo. `GET /api/v1/courier/route` calcula a próxima etapa.
`POST /api/v1/courier/availability` permite ficar online ou offline. Para entrar online,
informe uma posição válida na área atendida e próxima de uma via utilizável. Um entregador
com oferta ou entrega em andamento não pode ficar offline.

`POST /api/v1/courier/positions` recebe lotes de posições. A resposta de aceitação confirma
o recebimento em memória; a posição passa a ser compartilhada após a gravação no PostgreSQL.
Consultar a última posição permite distinguir recebimento de persistência. O buffer é limitado
e os lotes são gravados periodicamente. Uma falha pode perder o pequeno intervalo ainda não
persistido; o banco continua sendo a fonte da última posição entre instâncias.

A gravação usa uma operação SQL por lote, com locks dos entregadores em ordem de UUID.
Se uma linha falhar, o lote inteiro sofre rollback e volta ao buffer para nova tentativa.
O aviso de posições persistidas só é publicado depois do commit.

Cada lote contém de 1 a 40 pontos, com coordenadas e o instante de coleta. Os horários precisam
ser estritamente crescentes, ter no máximo dois minutos e estar até cinco segundos no futuro.
O servidor também valida a área atendida e o acesso pela via. A atualização SQL só aceita um instante mais recente,
impedindo que uma instância atrasada sobrescreva uma posição já gravada por outra.
Os limites de envio também são compartilhados no PostgreSQL.

Uma geração persistida da posição separa períodos de disponibilidade. Um lote recebido antes
de ficar offline não pode reaparecer depois de uma nova entrada online, mesmo quando outra
instância ainda tem esse lote em memória. A resposta de recebimento inclui o prazo previsto
para a gravação; a interface confirma o resultado consultando novamente o banco.

## Coleta e entrega

O módulo `fulfillment` coordena pedidos e entregadores sem criar dependência circular entre
esses módulos. Posições persistidas alimentam a trilha da entrega e a chegada à loja, em uma
transação posterior à gravação do lote. A proximidade de até 60 metros avança o pedido de
`ASSIGNED` para `AT_STORE`. Uma reconciliação do estado persistido recupera esse avanço mesmo
quando o processo reinicia entre a gravação e sua notificação.

A coleta exige `AT_STORE`; a entrega exige `PICKED_UP`. As ações exigem o entregador atribuído
e a versão atual do pedido, enviada em `expectedVersion` para
`POST /api/v1/orders/{id}/pickup` ou `POST /api/v1/orders/{id}/deliver`.
Pedidos de visitantes exigem o código de seis dígitos exibido à
loja; o entregador não recebe esse código na consulta. As tentativas de confirmação têm limite
próprio de dez tentativas por minuto por entregador. Somente pedidos públicos do cenário simulado dispensam o código.

Pedido, evento e liberação do entregador pertencem à mesma transação. Cancelamento e
redistribuição usam a mesma coordenação de recursos. A ordem dos locks é pedido, ofertas e
entregador, preservada na futura etapa de despacho.

`dispatch` mantém a limpeza de ofertas pendentes: cancelar o pedido cancela a reserva na mesma
transação, e uma varredura libera ofertas cujo prazo expirou. Essa base permite que uma versão
anterior lide com reservas criadas pela seguinte durante uma publicação ou rollback. A busca
automática e as respostas às ofertas entram no M06.

## Verificações

Os testes com PostgreSQL e PostGIS reais verificam autorização, disponibilidade, posições
fora de ordem entre instâncias, recuperação de lotes e transições de coleta e entrega.
O benchmark local usa 500 entregadores e requisições HTTP autenticadas, com CSRF e limites
ativos. O preparo das contas e sessões ocorre fora da medição; o envio de posições passa
pelo mesmo caminho da aplicação.

A validação da identidade continua consultando o PostgreSQL em cada requisição. A renovação
do contexto e dos papéis usa uma operação SQL, confere todos os campos da identidade e
revalida a expiração depois do lock. Uma requisição que aguardava o logout não pode renovar
o contexto encerrado. Não há cache de autorização no envio de posições.

O relatório distingue latência HTTP de atraso de persistência. Os instantes planejados de
envio entram na medição para que atrasos do gerador de carga não escondam requisições lentas.
Erros e limites excedidos contam como falhas; o ensaio não repete requisições automaticamente.

O cenário usa o pequeno recorte de São Paulo versionado como fixture, com posições próximas
à Avenida Paulista. Ele mede ingestão e persistência, sem representar a cidade inteira ou
substituir o teste de carga do sistema completo previsto para a etapa de qualidade.

Com o Docker em modo Linux e sem builds ou outros testes concorrentes, execute na raiz:

```bash
bash infra/benchmark/positions.sh
```

O comando prepara um executor Linux com Java 25, quatro processadores e 3 GiB de memória.
A aplicação e o gerador HTTP usam esse executor; o PostgreSQL e o PostGIS ficam em outro
container, acessado pela rede interna do Docker. Há sessenta segundos de aquecimento e sessenta
segundos de medição, com 250 requisições por segundo e sem alterar os limites da aplicação.
O serviço de expiração de ofertas permanece ativo.

Os arquivos ficam em `target/benchmarks/positions/<execução>/`: `console.log`, `context.json`,
`courier-positions-warmup.json` e `courier-positions-benchmark.json`. O contexto registra o
hash dos fontes medidos; o relatório contém percentis, erros, atraso de gravação e a quantidade
de entregadores cuja última posição corresponde ao envio. O comando falha se houver erros,
posições pendentes ou p95 HTTP de 50 ms ou mais nos sessenta segundos medidos. Também exige
zero falhas de gravação e atraso máximo de persistência de até um segundo, incluindo o
aquecimento. As respostas e os descartes do aquecimento aparecem em seu próprio relatório.

API e gerador de carga compartilham os quatro processadores do executor. Esse perfil mede a
meta local de 500 entregadores; a capacidade da VM Azure de dois processadores exige seu
próprio ensaio. Para reproduzir o perfil de duas CPUs, use
`BORA_BENCHMARK_CPUS=2 bash infra/benchmark/positions.sh`. Ele não qualificou o M05: no ensaio
sem interrupção, houve 240 descartes entre 15 mil envios planejados e p95 de 96,64 ms.

Em 18/10/2026, o perfil local de quatro CPUs concluiu os 15 mil envios medidos com HTTP 202,
sem erros ou descartes, p95 de 12,68 ms e p99 de 22,20 ms. As 500 últimas posições foram
conferidas no banco; o buffer esvaziou e o maior atraso de gravação foi 617 ms, incluindo o
aquecimento. O aquecimento teve 1.741 descartes antes do envio e zero erros HTTP, registrados
separadamente da medição. O [relatório versionado](benchmarks/m05-positions.json) preserva os
resultados, a configuração do executor e o hash dos fontes medidos.
