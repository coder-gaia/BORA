# Roteamento e ETA

O módulo `routing` usa GraphHopper 11.1 embutido. Calcula rotas e encaixa coordenadas nas vias
sem chamar uma API externa. O recorte real de São Paulo vem do OpenStreetMap, distribuído pela
Geofabrik; o PBF, seu hash e a proveniência ficam em [infra/osm](../infra/osm/README.md).
O arquivo versionado elimina o download de mapas durante builds e inicializações.

## Perfil de moto

O perfil combina velocidades do parser de carro com regras próprias de acesso de moto.
Respeita permissões específicas, sentidos de circulação, exceções de mão única para motos,
barreiras e restrições de conversão. Vias privadas são bloqueadas por padrão. A configuração
de conversões considera `motorcycle` e `motor_vehicle`, com custo de retorno de 60 segundos.

Uma exceção de moto pode superar uma restrição mais geral. Permissões de cada sentido são
avaliadas separadamente, preservando as barreiras e os dados OSM originais. Sentidos reversíveis,
alternados ou desconhecidos são bloqueados pelo perfil estático. Vias de acesso `destination`
continuam permitidas sem penalidade adicional por travessia.

O motor usa as velocidades das vias sem trânsito em tempo real. Qualidade, cobertura e restrições
dependem dos dados OSM e das regras suportadas pelo GraphHopper; o perfil é uma aproximação para
a demonstração. O tempo original da rota é preservado. A duração simulada divide esse tempo por
`SIM_SPEED`, padrão `3`, arredondando para cima em milissegundos. O fator não muda o caminho.

`GeoPoint` usa latitude e longitude nessa ordem; ao gerar GeoJSON, a ordem é longitude e latitude.
O serviço devolve geometria, distância, durações e pontos efetivamente encaixados pelo percurso.
Coordenadas inválidas, ausência de rota e pontos sem via a até 150 metros são tratados explicitamente.

## Preparação e leitura

O hash do grafo inclui PBF, versão do GraphHopper, versão do parser e configuração do perfil e
da importação. O fator de simulação não participa do hash. O importador adquire um lock por hash,
valida tamanho e SHA-256 do PBF, importa em um diretório temporário e abre um leitor novo para
aquecer as rotas do manifesto. Só publica o diretório definitivo após essa validação.

Uma repetição reutiliza um grafo concluído e validado. Um diretório definitivo incompleto não é
sobrescrito silenciosamente. A limpeza por falha remove apenas o preparo temporário daquela execução.
Versões anteriores são preservadas para rollback.

As APIs abrem o volume somente para leitura, com `MMAP_RO`, e não importam dados. A readiness
depende da carga e do aquecimento bem-sucedidos. Sem grafo, retorna HTTP 503; a liveness continua
independente. O health informa apenas o estado, sem expor caminhos ou detalhes do motor.

| Configuração                            | Padrão                       | Uso                                               |
| --------------------------------------- | ---------------------------- | ------------------------------------------------- |
| `BORA_GRAPH_ROOT`                       | `target/routing-graphs`      | Raiz dos grafos no modo nativo                    |
| `SIM_SPEED`                             | `3`                          | Fator positivo de até 100 para a duração simulada |
| `bora.routing.manifest`                 | `routing/data/manifest.json` | Manifesto usado pela API                          |
| `bora.routing.max-snap-distance-meters` | `150`                        | Distância máxima para encaixar um ponto           |

No Compose, a raiz é `/var/lib/bora/routing`, em volume próprio. O importador tem limite de
2 GiB e cada API tem 1.536 MiB. O [guia de publicação](deploy.md) descreve ordem, falhas e rollback.

## Verificar rotas e desempenho

Com o ambiente local iniciado conforme o README:

```sh
docker compose -f infra/compose/compose.dev.yml run --rm --no-deps api --routing-benchmark --warmup=100 --iterations=500 --max-p95-ms=50
```

Se usar `.env`, inclua `--env-file .env` antes de `-f`. O comando executa uma JVM separada e
não inicializa Spring ou o banco. O JSON informa hash, arquitetura, aquecimento, pares, contagem,
p50, p95, máximo, memória e uma rota de exemplo com distância e durações. `success` deve ser
`true`; o código de saída é 1 se o p95 não ficar abaixo do limite informado.

O tempo medido inclui o serviço de rota e ETA dentro da JVM. A carga do grafo e o aquecimento
são registrados separadamente. A medição não inclui rede, banco, controle HTTP ou carga concorrente.
Resultados dependem da máquina, dos pares escolhidos e dos créditos de CPU disponíveis.
Medir também na VM de produção antes de avaliar carga sustentada.

Sem containers para a API, após construir o JAR:

```sh
java -jar apps/api/target/bora-api.jar --routing-import
java -jar apps/api/target/bora-api.jar --routing-benchmark --warmup=100 --iterations=500 --max-p95-ms=50
```

As ferramentas aceitam `--graph-root` e `--manifest`. Use a mesma raiz na API nativa, conforme
o README. A preparação retorna `reused: true` quando reaproveita o grafo. Argumentos inválidos
retornam código 2; falhas operacionais retornam código 1. Contadores Linux ausentes ficam `null`.

Os testes importam uma rede sintética com mão única, barreira e conversão proibida, além de um
recorte real pequeno de São Paulo. Também verificam leitores simultâneos, imutabilidade do grafo,
falhas de preparo, limites de encaixe e a diferença entre readiness e liveness.

Dados: [© OpenStreetMap contributors, ODbL](https://www.openstreetmap.org/copyright).
Motor: [GraphHopper 11.1, Apache 2.0](https://github.com/graphhopper/graphhopper/tree/11.1).
