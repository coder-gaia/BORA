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
entregador, preservada também pelo despacho.

`dispatch` mantém a limpeza de ofertas pendentes: cancelar o pedido cancela a reserva na mesma
transação, e uma varredura libera ofertas cujo prazo expirou. Essa base permite que uma versão
anterior lide com reservas criadas pela seguinte durante uma publicação ou rollback.

## Ofertas e redistribuição

O despacho tenta pedidos a cada 500 ms, com até vinte transações independentes por ciclo.
O raio começa em dois quilômetros e dobra até oito quando não há candidato com rota válida.
As novas tentativas sem candidato aguardam cinco segundos. Depois de três minutos de busca,
um evento `ORDER_SEARCH_DELAYED` registra o alerta uma única vez na linha do tempo.

Entregadores precisam estar disponíveis, com posição persistida há no máximo trinta segundos.
Os oito mais próximos dentro do raio são classificados pelo tempo de rota simulado até a loja.
As posições são revalidadas ao reservar. A geração persistida muda também na reserva, aceite
e liberação: um lote antigo de outra API não pode mover uma atribuição nova nem marcar chegada.
Entregadores privados participam somente dos pedidos do próprio contexto de demonstração.
Pedidos públicos exigem loja simulada; contextos encerrados não geram novas ofertas.

`GET /api/v1/courier/offer` retorna `{offer, serverTime}`. Sem oferta ativa, `offer` é `null`.
Uma oferta contém loja, destino, ETA e prazo de trinta segundos; não contém o código de entrega.
`POST /api/v1/offers/{id}/accept` e `/reject` respondem `204` quando concluídos. Uma oferta
de outro entregador responde `404`. Prazo vencido ou estado alterado responde `409`; o servidor
persiste a expiração e libera a reserva mesmo quando o aceite chega depois do prazo.

Aceitar atribui o pedido e deixa o entregador ocupado na mesma transação. Recusar ou expirar
libera o entregador e permite nova tentativa. O mesmo pedido não volta a oferecer a um
entregador que já o aceitou, recusou ou deixou expirar. A operação pode solicitar
`POST /api/v1/ops/orders/{id}/reassign` com a versão atual em `expectedVersion`, antes da coleta.
A redistribuição libera a atribuição anterior e reinicia a busca; alterações concorrentes exigem
atualizar a tela antes de tentar novamente.

A interface consulta ofertas enquanto está visível. A contagem usa o horário do servidor e
não autoriza decisões: aceitar, recusar e redistribuir sempre passam pelas validações do backend.
O envio manual consulta esse horário imediatamente antes de registrar a posição e mede o tempo
decorrido com relógio monotônico. Assim, adiantar ou atrasar o relógio do navegador não transforma
uma posição coletada agora em uma posição antiga ou futura. O recebimento e a persistência
continuam sendo verificados separadamente.

## Área do entregador e treino

Abra `/entregador` e escolha **Sou entregador**. Abrir o endereço não cria uma conta nem
troca o papel automaticamente. O contexto é o mesmo usado na loja e na operação.

O pedido de treino é uma entrega privada criada para o próprio entregador disponível.
`POST /api/v1/courier/training-order` exige sessão, CSRF e `Idempotency-Key`, sem corpo.
A escolha de uma loja alcançável, a criação do pedido e a reserva de uma oferta de trinta
segundos pertencem à mesma transação. As validações de área, rota, limite de pedidos ativos
e versão continuam valendo.

O recibo contém `orderId`, `offerId`, `expiresAt`, `confirmationCode`, `status`, `version` e
`serverTime`. Repetir a mesma intenção devolve o pedido e o prazo originais, inclusive depois
de aceitar ou expirar a oferta. O código é rederivado somente para o criador autorizado;
não entra no JSON persistido de idempotência, na oferta, no trabalho ou no rastreio público.

A interface pode guardar a chave da intenção na `sessionStorage`, separada por usuário,
para recuperar o treino após recarregar a página. O código permanece em memória. Sair ou
perder o contexto encerra esse acesso; uma falha temporária de rede preserva a intenção
para recuperação explícita, evitando criar outro pedido por acidente.

**Encerrar treino** usa `POST /api/v1/courier/training-order/{id}/cancel`, com
`expectedVersion`, somente para o criador e antes da coleta. Também permite encerrar um
treino cuja oferta expirou ou foi recusada, mesmo sem trabalho atribuído. Depois da coleta,
a entrega deve seguir o fluxo normal. A ação não concede permissão para cancelar outros
pedidos privados ou pedidos comuns pelo endpoint de treino.

## Direção simulada

Depois de aceitar uma oferta, o entregador pode iniciar explicitamente o percurso até a
loja. O primeiro clique atualiza a posição de origem e aguarda sua persistência antes de
consultar a rota. O movimento segue os segmentos da rota calculada, usando a duração já acelerada da
simulação. Não há uma segunda multiplicação de `SIM_SPEED`. A interface envia uma posição
a cada dois segundos e mantém somente um envio em andamento.

Pausar interrompe novos envios e cancela a espera do cliente. Uma posição já aceita ainda
pode ser persistida pelo servidor. Retomar exige outro clique e uma rota a partir da
posição confirmada. Ocultar a aba, perder a conexão, sair, trocar de papel ou mudar de
pedido ou etapa interrompe a direção; voltar à aba não produz um salto de localização.

Se um envio for interrompido antes da resposta, a interface indica a incerteza. A ação
**Confirmar posição enviada** reenvia explicitamente aquele ponto e aguarda sua persistência
antes de liberar outra rota. Esse ponto pertence à intenção original; uma mudança de pedido,
etapa ou contexto não permite reaproveitá-lo para outro trabalho.

A chegada à loja depende da posição persistida e da transição real para `AT_STORE`.
**Confirmar coleta** continua manual. Depois dela, outro clique inicia o trajeto ao cliente.
Chegar ao destino também não conclui a entrega: é preciso informar o código do pedido
privado e escolher **Confirmar entrega**. Pedidos comuns da loja continuam exigindo o código
fornecido por ela; o código de treino não serve para outro pedido.

Os comandos manuais de posição ficam indisponíveis durante a direção. Erros ou mudanças de
versão interrompem o percurso e atualizam o trabalho, sem repetir mutações automaticamente.
O anel da oferta usa o horário remoto e o tempo monotônico do navegador; o prazo final e a
validade do aceite continuam sendo decididos no servidor.

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

## Verificar concorrência do despacho

Com o Docker Linux ativo, execute em uma janela sem outros builds ou testes:

```bash
bash infra/benchmark/dispatch.sh
```

O executor isolado usa quatro CPUs, 3 GiB e dois contextos Spring independentes, cada um com
seu próprio pool de cinco conexões para o mesmo PostgreSQL real. O cenário executa dez mil
rodadas concorrentes, divididas igualmente entre disputa de reserva, aceite duplo, decisão
contra recusa/expiração/cancelamento e coleta contra redistribuição. Cada par começa em uma
barreira; as invariantes são conferidas no banco após cada rodada. O teste também encerra os
dois contextos e abre outro para verificar a recuperação de um prazo persistido, com a criação
de novas ofertas bloqueada.

O teste falha diante de atribuição ou oferta duplicada, resultado inesperado ou prazo perdido.
Os resultados ficam em `target/benchmarks/dispatch/<execução>/`, com relatório JSON, contexto
e console. Esse ensaio verifica a correção transacional com o pequeno recorte de ruas de teste;
não mede vazão HTTP ou capacidade de produção.

Em 18/10/2026, as dez mil rodadas concluíram com todas as invariantes atendidas e zero violações
nas cinco consultas globais finais. Cada família executou 2.500 rodadas. O reinício dos contextos
preservou o prazo original e a varredura liberou a reserva com a criação bloqueada. O ensaio
levou 143,6 segundos; o [relatório versionado](benchmarks/dispatch-concurrency.json) preserva o
perfil, o hash dos fontes e os resultados. Esse tempo descreve a execução local de correção
concorrente, sem representar uma meta de latência da aplicação.
