# Carga, concorrência e recuperação

A qualificação mede ingestão de GPS e criação de pedidos na API real, com autenticação,
CSRF, PostgreSQL/PostGIS e os limites normais de Bucket4j. O gerador é o Gatling 3.16.0,
fixado num projeto Maven separado para não misturar suas dependências com a aplicação.

## Perfis e critérios

| Perfil         | GPS                            | Pedidos     | Janela medida |
| -------------- | ------------------------------ | ----------- | ------------- |
| Despacho       | 150 entregadores, 75 envios/s  | 1 pedido/s  | 60 s          |
| Ingestão mista | 500 entregadores, 250 envios/s | 2 pedidos/s | 60 s          |

Na taxa plena, cada entregador envia uma posição a cada dois segundos. Cada perfil começa com
uma API nova e um banco descartável próprio. O aquecimento de GPS sobe de zero à taxa alvo
em 20 segundos e mantém essa taxa por mais 45 segundos. Durante a rampa a cadência é variável.
Os pedidos começam no quinto segundo, já na taxa alvo, por 60 segundos de aquecimento.
A medição seguinte dura 60 segundos com ambas as taxas plenas; o mesmo feeder de GPS
continua entre as fases, sem reiniciar a sequência de entregadores. Aquecimento e medição
têm nomes separados no relatório do Gatling.
Não se apagam erros do aquecimento: qualquer requisição malsucedida reprova o perfil.

O teste exige o número exato de requisições planejadas, nenhuma falha HTTP/check e p95 de GPS
abaixo de 50 ms. No perfil de 150 entregadores, todos os pedidos medidos precisam ganhar uma
primeira oferta e o p95 de criação até essa oferta deve ficar abaixo de 2 s. Esse intervalo usa
os dois instantes persistidos pelo servidor, sem polling do navegador ou relógios de máquinas distintas.

Oito consultas de invariantes são observadas juntas numa única fotografia SQL, a cada 500 ms:
reservas duplicadas por pedido/entregador, entregas ativas duplicadas, entregador reservado sem
oferta, oferta com estados incompatíveis, vínculo de entrega incompatível, pedido privado atribuído
ao simulador e prazos inválidos. Todas precisam permanecer em zero. A última posição aceita no
epoch atual de cada entregador precisa estar exatamente no banco. Uma reserva, aceite ou liberação
incrementa `position_epoch` e invalida posições ainda enfileiradas no epoch anterior; isso evita
reaplicar movimentos antigos após uma mudança de estado. O teste de contrato
`CouriersApiIT.fencesQueuedFuturePointsAcrossReservationsAssignmentsAndReleases` exige esse descarte
e comprova que reenviar a posição no novo epoch a persiste.

O benchmark usa um spy de teste em `enqueue`: executa o método real e guarda o epoch e o timestamp
aceitos em memória, sem SQL adicional por envio. O custo do Mockito está incluído na medição.
Uma fotografia SQL final contabiliza todos os entregadores. No mesmo epoch, exige timestamp exato;
um epoch posterior permite a invalidação prevista pelo contrato e registra os dois epochs, os
timestamps de posição e a última oferta no JSON. Regressão de epoch ou divergência sem mudança
de epoch reprova o teste. A comparação crua com o último HTTP 202 continua registrada separadamente.
O buffer deve terminar vazio, sem falhas de gravação e sem atraso máximo acima de 1 s.
Uma pausa do observador maior ou igual a 5 s reprova a execução.

As sessões são preparadas pelo host de teste apenas no banco descartável e o CSRF é obtido por
HTTP. O endpoint, os filtros de segurança, o limitador, o buffer e o despachante são os de produção.
São 20 lojas públicas co-localizadas na Paulista e entregadores no mesmo recorte. As ofertas não
são aceitas neste ensaio: o fluxo completo de coleta e entrega é qualificado separadamente por E2E.
O pequeno grafo real versionado como fixture e seu SHA-256 constam no JSON. Esse cenário não
substitui um teste de toda a malha urbana nem um ensaio de capacidade da VM pública.

## Reproduzir

Com Docker Linux, Java 25 e acesso ao Maven Central, execute na raiz:

```bash
BORA_BENCHMARK_CPUS=4 bash infra/benchmark/load.sh
```

O executor usa quota de 4 CPUs e 3 GiB para API, Maven e Gatling. O PostgreSQL usa um container
separado, limitado a 1 CPU e 512 MiB. Para fixar ambos no mesmo conjunto de CPUs, use, por
exemplo, `BORA_BENCHMARK_CPUSET=2-5`. Interrompa outros testes, builds e navegadores de medição
durante a janela; mantenha o notebook ligado, sem suspensão.

As fontes são copiadas para um volume exclusivo e o SHA-256 do snapshot é registrado. O runner
não monta nem modifica os volumes de desenvolvimento ou produção. Ao terminar ele copia o
relatório JSON, o console e os relatórios HTML/binários nativos do Gatling para
`target/benchmarks/load/<execução>/`; depois remove apenas seu próprio container e volume de trabalho.
Os arquivos temporários de cookies/CSRF são apagados antes de exportar os resultados.

Gatling usa injeção aberta, sem bloco de retry de cenário ou limite que reduza a taxa oferecida
quando a aplicação fica lenta. O cliente HTTP do Gatling pode reabrir uma conexão reutilizada
que fecha antes de qualquer resposta; este ensaio não mede continuidade de rede. A qualificação
de deploy usa um observador próprio, sem retentativas, descrito no [guia de deploy](deploy.md).
O p95 HTTP do Gatling mede a requisição, sem incluir o atraso anterior à injeção. As contagens
exatas, a duração e a verificação de suspensão complementam esse número.

## Concorrência e reinício

O [ensaio de dez mil rodadas](benchmarks/dispatch-concurrency.json) executado em 18/10/2026
usa dois contextos Spring e pools independentes, com reserva, aceite duplo, decisões concorrentes
e redistribuição contra coleta. São 2.500 rodadas por família, com invariantes verificadas após
cada rodada e cinco consultas globais finais zeradas. O relatório preserva condições e duração
daquela execução; ele não é apresentado como uma nova medição de carga.

`DispatchRestartIT` participa do CI e usa banco e roteamento reais. Ele cria uma oferta numa
instância, mantém a outra disponível e reinicia a primeira. Um relógio controlado verifica o instante
imediatamente anterior ao vencimento e o vencimento exato, preservando o mesmo prazo no banco.
A oferta expira uma única vez, o entregador volta a ficar disponível e a próxima tentativa do pedido
continua agendada. Os workers automáticos ficam desligados nesse teste para controlar a ordem das operações concorrentes;
as operações executadas são as mesmas dos workers de produção.

## Resultados e limites

A [primeira tentativa com degrau imediato de zero a 250 GPS/s](benchmarks/m13-load-500-cold-failed.json) reprovou ainda no aquecimento,
antes de haver pedidos suficientes para atribuir a falha à carga mista. O pool de cinco conexões
saturou e houve HTTP 500/503. A [primeira amostra de 150 entregadores](benchmarks/m13-load-150-initial.json) passou (p95 GPS 14 ms;
60 primeiras ofertas com p95 de 483,744 ms), mas seu HTML não foi exportado corretamente;
console nativo, amostras e JSON foram preservados. Essa tentativa não é substituída pelos
resultados finais. A investigação SQL fora do pool não encontrou bloqueadores nas primeiras
amostras; isso não comprova a causa inicial em Java. A saturação posterior também gerou
backpressure na saída de logs e o gerador continuou temporariamente após morrer seu Maven
pai. O harness passou a registrar o console diretamente em arquivo e encerrar os processos
filhos. Não houve mudança do pool nem dos limites de produção.

A [primeira execução de 500 com rampa](benchmarks/m13-load-500-oracle-failed.json) teve zero falhas
HTTP, p95 GPS de 30 ms, 120 primeiras ofertas com p95 de 496,357 ms e oito invariantes zeradas,
mas reprovou a comparação crua de timestamps: 495 de 500 eram idênticos ao último HTTP 202.
O oráculo original ignorava a invalidação por epoch exigida pelo domínio. Os epochs daqueles cinco
casos não foram capturados antes da remoção do banco descartável; a tentativa permanece reprovada,
sem afirmar que os cinco descartes foram legítimos. A versão seguinte instrumenta o ponto real de
aceitação para distinguir essa proteção de perda de posição no mesmo epoch.

A rampa de aquecimento é parte explícita do método de regime estável; ela não qualifica o
arranque frio com degrau abrupto. Os dois perfis finais aprovaram em 21/10/2026, com o mesmo
snapshot de fontes `c891fdce3193b30b77d0466b6ae8dce8ca707877bb9bfc1ce367154ed7eecd06`:

| Resultado medido                           | [150 entregadores](benchmarks/m13-load-150.json) | [500 entregadores](benchmarks/m13-load-500.json) |
| ------------------------------------------ | ------------------------------------------------ | ------------------------------------------------ |
| GPS em 60 s                                | 4.500, zero falhas                               | 15.000, zero falhas                              |
| GPS p95 / p99                              | 10 / 12 ms                                       | 12 / 41 ms                                       |
| Pedidos em 60 s                            | 60, zero falhas                                  | 120, zero falhas                                 |
| Primeira oferta                            | 60 de 60; p95 480,055 ms                         | 120 de 120; p95 503,629 ms                       |
| Fotografias de invariantes                 | 266; oito máximos zerados                        | 268; oito máximos zerados                        |
| Recibos GPS aceitos, incluindo aquecimento | 8.625 de 8.625                                   | 28.750 de 28.750                                 |
| Mesmo epoch, posição exata                 | 141                                              | 480                                              |
| Epoch posterior comprovado                 | 9                                                | 20                                               |
| Divergências sem explicação pelo epoch     | 0                                                | 0                                                |
| Buffer final / falhas de gravação          | 0 / 0                                            | 0 / 0                                            |
| Maior atraso de persistência               | 259 ms                                           | 312 ms                                           |

Também houve zero falhas durante todo o aquecimento. A comparação crua encontrou o último
timestamp persistido em 149 de 150 e 497 de 500 entregadores. Nos quatro casos restantes,
o epoch no banco era maior que o capturado na aceitação; os JSONs preservam IDs, epochs,
timestamps e última oferta. Mudança de epoch nem sempre descarta uma posição: nos demais
casos com epoch posterior, o ponto já havia sido gravado antes da transição. Todos os
150 e 500 entregadores foram contabilizados e tinham uma posição gravada durante o ensaio.

As [condições capturadas dos containers](benchmarks/m13-load-environment.json) registram Docker
Desktop Linux x86-64, com 16 CPUs lógicas e 8,65 GiB disponíveis ao Docker. API, Maven e Gatling
compartilharam o runner de 4 CPUs e 3 GiB; o heap máximo da API foi de 1 GiB. O PostgreSQL foi
limitado a 1 CPU e 512 MiB. Ambos ficaram no mesmo conjunto de CPUs `2-5`, restringindo o
conjunto a quatro CPUs lógicas, sem somar uma quinta CPU livre para o banco. Não houve outro
build, navegador de teste ou ensaio concorrente. A suspensão automática do notebook ficou
temporariamente impedida durante as duas execuções, com restauração ao terminar.

Os relatórios nativos completos, com `index.html`, e os XMLs JUnit ficaram preservados em
`target/benchmarks/load/20261021-113946-202/` (500) e
`target/benchmarks/load/20261021-115021-1834/` (150), além de cópias temporárias fora dos módulos
que o Maven limpa. Os JSONs compactos acima são os resultados versionados. Esses números
qualificam este recorte local aquecido, com esta configuração e estas versões.

O [benchmark de GPS de 18/10/2026](benchmarks/m05-positions.json) aprovou 500 entregadores
e 250 envios/s em 4 CPUs locais, com p95 de 12,68 ms. A tentativa anterior em 2 CPUs não atingiu
a meta: p95 de 96,64 ms e 240 envios descartados entre 15 mil planejados. Esses dados históricos
não qualificam a carga mista atual nem a VM Azure ARM64 de 2 vCPUs. Créditos de CPU, distância
de rede e arquitetura precisam ser medidos no ambiente de destino antes de afirmar capacidade.

Referências do gerador: [projeto Java oficial e versões](https://github.com/gatling/gatling-maven-plugin-demo-java),
[injeção de carga](https://docs.gatling.io/concepts/injection/) e
[assertions](https://docs.gatling.io/concepts/assertions/).
