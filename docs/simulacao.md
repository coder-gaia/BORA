# Simulação

O cenário tem 120 entregadores e 40 lojas fictícias. O Compose inicia o simulador depois da
readiness da API. Cada ator usa sua própria sessão e proteção CSRF; as ações de disponibilidade,
posição, oferta, coleta e entrega passam pelos endpoints normais. O processo não acessa o banco.

Uma thread virtual representa cada entregador e cada loja. Um cliente HTTP compartilhado evita
criar um conjunto de threads de rede por ator. Um semáforo justo limita o cliente inteiro a quatro
requisições em andamento; os atores aguardam sua vez em threads virtuais, preservando as ações
programadas. A espera na fila e a latência da API podem alongar o intervalo observado entre
posições. O entregador tenta informar posição a cada dois segundos, sem rajadas para compensar
atrasos de rede, aceita 85% das ofertas após um sorteio de 3 a 12 segundos e percorre a geometria devolvida pela
API. A espera na loja é de 1 a 3 minutos simulados. Ofertas, versões e conflitos continuam sendo
decididos pela API; a simulação reconcilia o estado após uma resposta perdida.

A demanda segue o horário de São Paulo, com mínimo à noite e picos no almoço e jantar.
Entregadores livres buscam destinos nas regiões com maior demanda. As posições iniciais das
lojas são ajustadas ao grafo de ruas antes de seu primeiro uso, preservando lojas com pedidos
ativos. Um reinício preserva identidade, posição e trabalho do entregador.

## Configuração

| Variável             | Padrão local                                     | Efeito                                                             |
| -------------------- | ------------------------------------------------ | ------------------------------------------------------------------ |
| `SIM_SEED`           | `2026`                                           | Mesma semente na API e no simulador.                               |
| `SIM_SPEED`          | `3`                                              | Velocidade do movimento e das durações calculadas pelo roteamento. |
| `SIM_COURIER_COUNT`  | `120`                                            | Redução opcional para testes, de 1 a 120.                          |
| `SIM_STORE_COUNT`    | `40`                                             | Redução opcional para testes, de 1 a 40.                           |
| `BORA_SIMULATOR_KEY` | Chave pública exclusiva do desenvolvimento local | Chave independente e aleatória em produção.                        |

Reduzir o número de lojas reduz a demanda total; a carga das 40 lojas não é transferida para uma
única identidade. A semente define os sorteios de cada ator. Concorrência, horário real e
latência continuam influenciando a sequência observada; não se promete uma reprodução exata
dos instantes de cada requisição.

O rodapé consulta `GET /api/v1/map/config` para mostrar o fator real. A configuração pública
contém somente `simulationSpeed`. O catálogo completo e a entrada de atores ficam em
`/api/v1/simulator/*` e `/api/v1/auth/simulator`, bloqueados pelo Caddy público e protegidos na
API pela chave interna. Pedidos de visitantes exigem código de confirmação; o simulador
recebe somente pedidos públicos sem esse código. Um entregador visitante também pode receber
pedidos públicos, desde que não haja pedido privado procurando entregador em seu próprio
contexto. Essa reserva protege o fluxo de teste do visitante; ofertas já existentes são
preservadas.

Em produção o Caddy termina o HTTPS e se comunica com as APIs na rede Docker. O simulador usa
essa mesma rede interna. `BORA_SIMULATOR_INTERNAL_HTTP=true` autoriza somente o cookie
`BORA_SESSION` de sua origem interna configurada a trafegar por HTTP; a política de domínio e
caminho continua ativa. O cliente restringe a opção aos hosts internos previstos e a loopback
para testes. Os cookies enviados aos navegadores continuam com `Secure=true`. Redirecionamentos
não são seguidos e as chaves não aparecem nos relatórios.

## Saúde e diagnóstico

Depois da persistência do GPS, o progresso da entrega é processado por um consumidor separado.
A fila admite até 16 eventos e 40 mil amostras, incluindo o trabalho em execução. Ela aguarda
capacidade quando cheia. O vínculo com o pedido e a época do entregador são capturados antes
dessa espera: uma redistribuição invalida a atribuição antiga, e pontos anteriores ao encerramento
podem completar a trilha de uma entrega já concluída ou cancelada. No encerramento ordenado,
os produtores param antes da drenagem da fila. Essa fila em memória não promete preservar
amostras intermediárias diante de encerramento forçado do processo.

Os jobs periódicos usam um scheduler próprio de quatro threads, separado do WebSocket.
Isso permite que a persistência de posições continue enquanto o progresso processa seus
pedidos. A proteção de um segundo contra atraso de armazenamento permanece ativa.

O simulador expõe `/health` e `/status` somente no loopback de seu container, porta `8090`.
A saúde exige todos os entregadores esperados online, contato recente com a API e ausência
de falha interna persistente. O relatório agregado contém posições aceitas, pedidos criados,
coletas, entregas, conflitos e concorrência HTTP. Códigos simbólicos de erro da API ajudam a
distinguir conflitos esperados de falhas; mensagens, cookies e dados de visitantes não são
incluídos. O envio confirmado de GPS renova o sinal de atividade sem alterar o estado do ator.

`httpInFlight` informa quantas chamadas estão em execução e `httpPeakInFlight` registra o pico
desde o início do processo. `maxHttpQueueMillis` registra a maior espera para obter uma das
quatro vagas, incluindo a autenticação inicial dos atores. Cada vaga é liberada ao concluir a
chamada, inclusive diante de erro ou interrupção.

```sh
docker compose --env-file .env -f infra/compose/compose.dev.yml logs --tail 30 simulator
docker compose --env-file .env -f infra/compose/compose.dev.yml exec simulator curl --fail --silent http://127.0.0.1:8090/health
```

## Qualificação de uma hora

O observador verifica o banco em uma consulta consistente e a saúde do simulador a cada cinco
segundos. Ele falha diante de posições desatualizadas, entregador ocupado sem pedido,
reservas incoerentes, múltiplas entregas, pedido privado atribuído à simulação, falha interna,
HTTP 5xx, OOM ou reinício. A medição exige criação e conclusão reais de pedidos. Um relatório
só recebe `qualified: true` com 120 entregadores e pelo menos 3.600 segundos solicitados,
concluídos sem falhas. Esse resultado também exige ausência de registros `ERROR` nas APIs,
incluindo os de protocolo WebSocket; é mais amplo que a verificação das invariantes da simulação.

O perfil local faz APIs, PostgreSQL, simulador, Caddy e web compartilharem os mesmos dois
processadores lógicos. Os limites são 1.536 MiB por API, 2 GiB para PostgreSQL, 512 MiB para o
simulador, 512 MiB para a web e 128 MiB para Caddy. É um ensaio amd64 em Docker Desktop;
ele não equivale a uma medição da VM arm64 nem de seus créditos de CPU.

Prepare o grafo no cluster normal e encerre esse ambiente preservando os volumes. Em seguida:

```sh
docker compose --env-file .env -f infra/compose/compose.cluster.yml up --build --wait
docker compose --env-file .env -f infra/compose/compose.cluster.yml down
docker compose --env-file .env -p bora-sim-soak -f infra/compose/compose.cluster.yml -f infra/compose/compose.simulation-test.yml up --build --wait
python scripts/check-simulator-soak.py --duration 3600 --report apps/simulator/target/soak.json
```

O observador usa Python 3 e o contexto local `desktop-linux`; em Linux, informe seu contexto
local com `--context default`. O volume de grafo padrão é `bora-cluster_routing-graphs` e pode
ser configurado por `BORA_SIM_GRAPH_VOLUME`. Durante a medição, mantenha o notebook e o Docker
em execução. No Windows, o observador impede suspensão automática enquanto está ativo e
restaura esse estado ao terminar; suspensão manual ou pausa do Docker invalidam o ensaio.

O relatório registra hash das fontes, imagens, perfil de CPU e memória, observações e contadores
de pedidos. O [relatório de referência](benchmarks/simulator-soak.json) preserva o resultado
automático `qualified: false`: em 3.601 segundos e 644 observações, os 120 entregadores
permaneceram ativos e com GPS recente, com 1.083 pedidos criados, 1.063 entregas e zero
violações das oito invariantes. Foram aceitas 211.808 posições durante a janela.

A reprovação automática veio de dois registros `ERROR` do STOMP durante a avaliação de um
novo cliente de mapa, sem falha de persistência, progresso ou HTTP 5xx do simulador. Uma
reprodução posterior com a mesma API mostrou a rejeição do segundo viewport quando o
cliente envia a cada 250 ms e o processamento inicial comprime o intervalo recebido abaixo
dos 200 ms permitidos. Um controle com 500 ms passou. Os dois eventos históricos não têm
frames gravados, portanto essa atribuição é sustentada pela reprodução, sem prova individual.

A [avaliação de escopo](benchmarks/simulator-soak-review.json) registra separadamente o
atendimento dos critérios de atividade, fluxo e invariantes da simulação. Ela preserva a
reprovação do perfil completo e não afirma ausência de erros gerais ou qualificação do mapa.
Para encerrar o ambiente sem apagar dados:

```sh
docker compose --env-file .env -p bora-sim-soak -f infra/compose/compose.cluster.yml -f infra/compose/compose.simulation-test.yml down
```
