# Bora

Despacho de entregas em tempo real em São Paulo, desenvolvido com Java, Spring Boot, React e PostGIS.

Bora é uma demonstração de despacho de entregas: lojas criam pedidos e o sistema busca o
entregador que chega mais rápido. O ambiente local executa banco, API, simulador e web com uma
única composição Docker.

A [demonstração pública](https://bora.alexandregaia.me/) permite experimentar os três papéis.
O domínio já contém as máquinas de estado, o esquema espacial e 40 lojas fictícias;
o roteamento usa um recorte real das vias de São Paulo. A entrada de demonstração permite
trocar de papel, criar pedidos, consultar a linha do tempo e cancelar conforme a etapa.
O [guia de identidade e pedidos](docs/identidade-e-pedidos.md) descreve sessões, privacidade,
idempotência e o contrato da API.
Entregadores podem informar disponibilidade e posições em lote; coleta e entrega são
coordenadas com o estado do pedido. O despacho oferece pedidos por tempo estimado de chegada,
com prazo para aceitar, recusa e redistribuição pela operação. Consulte o
[guia de entregadores e posições](docs/entregadores-e-posicoes.md).
As mudanças chegam por [WebSocket](docs/tempo-real.md), com recuperação após desconexão.
O [simulador](docs/simulacao.md) mantém 120 entregadores e 40 lojas fictícias em atividade,
usando as mesmas ações HTTP de disponibilidade, oferta, coleta e entrega.
A página inicial mostra uma prévia ao vivo de São Paulo com entregadores em movimento.
**Explorar mapa** habilita mover e ajustar o zoom. O [guia de mapa e interface](docs/mapa-e-interface.md)
descreve a base real, a conexão, os controles e a linha do tempo dos pedidos.

## Executar localmente

Pré-requisitos: Git e Docker com Compose. No Windows, abra o Docker Desktop com o mecanismo
Linux ativo. Os comandos abaixo funcionam em PowerShell e bash, na raiz do repositório.

```sh
git clone https://github.com/coder-gaia/BORA.git
cd BORA
docker compose -f infra/compose/compose.dev.yml up --build --wait
```

Abra [localhost:5173](http://localhost:5173). A página consulta a saúde da API. A readiness
inclui o banco e o grafo de rotas aquecido. O primeiro build baixa as dependências; o Compose
prepara o grafo antes de iniciar a API. O recorte de ruas já está no repositório. O simulador
começa automaticamente após a API ficar pronta; o rodapé informa a velocidade de simulação.

| Serviço                             | Endereço local                                    |
| ----------------------------------- | ------------------------------------------------- |
| Web                                 | <http://localhost:5173>                           |
| API pronta para receber requisições | <http://localhost:8080/actuator/health/readiness> |
| Processo da API ativo               | <http://localhost:8080/actuator/health/liveness>  |
| PostgreSQL                          | `localhost:5432`, banco e usuário `bora`          |

Os serviços ficam restritos à interface local. A senha padrão `bora_dev` serve apenas para
desenvolvimento. Para alterar credenciais ou portas, copie `.env.example` para `.env` e passe
`--env-file .env` antes de `-f` no comando Compose.

```sh
docker compose -f infra/compose/compose.dev.yml logs --tail 100
docker compose -f infra/compose/compose.dev.yml down
```

O comando `down` preserva os volumes do banco e do grafo. Para apagar esses dados locais
deliberadamente, use `down --volumes`. Alterar a senha no `.env` não altera a senha de um volume
já inicializado.

Para verificar rotas e ETA dentro do motor, com o ambiente já preparado:

```sh
docker compose -f infra/compose/compose.dev.yml run --rm --no-deps api --routing-benchmark --warmup=100 --iterations=500 --max-p95-ms=50
```

O relatório JSON mede o cálculo dentro da JVM, sem a latência de uma chamada pela internet.
O ETA original usa as velocidades das vias, sem trânsito; `SIM_SPEED`, padrão `3`, também
produz a duração acelerada da demonstração. Consulte o [guia de roteamento](docs/roteamento.md).

## Desenvolver sem containers para API e web

Use JDK 25 e Node.js 24.22.0. O Maven Wrapper baixa o Maven 3.10.0; não é necessário instalá-lo.
Em bash, disponibilize também `unzip` para usar o arquivo ZIP cujo checksum está fixado no
wrapper. O banco continua em Docker.

```sh
docker compose -f infra/compose/compose.dev.yml up --build --wait db
npm ci --prefix apps/web
node scripts/install-hooks.mjs
```

Em um terminal PowerShell, inicie a API:

```powershell
.\mvnw.cmd --batch-mode -pl apps/api -am package -DskipTests
$env:BORA_GRAPH_ROOT = Join-Path (Get-Location) 'apps/api/target/routing-graphs'
java -jar apps/api/target/bora-api.jar --routing-import
$env:BORA_DISPATCH_REQUIRE_ACTIVATION = 'false'
.\mvnw.cmd --batch-mode -pl apps/api spring-boot:run
```

Em bash:

```sh
./mvnw --batch-mode -pl apps/api -am package -DskipTests
export BORA_GRAPH_ROOT="$(pwd)/apps/api/target/routing-graphs"
java -jar apps/api/target/bora-api.jar --routing-import
export BORA_DISPATCH_REQUIRE_ACTIVATION=false
./mvnw --batch-mode -pl apps/api spring-boot:run
```

Em outro terminal, inicie a web:

```sh
npm run dev --prefix apps/web
```

A preparação do grafo roda sem Spring, banco ou servidor HTTP. Reexecutá-la reutiliza o mesmo
grafo quando seu hash permanece igual. A API abre esse diretório apenas para leitura.

A API usa as mesmas credenciais locais padrão. Ajustes no `.env` do Compose precisam ser
repetidos nas variáveis `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME` e
`SPRING_DATASOURCE_PASSWORD` do terminal da API. O proxy do Vite usa `http://localhost:8080`;
para outra porta, defina `API_PROXY_TARGET` no terminal da web.

A liberação explícita do despacho nesses exemplos serve ao desenvolvimento local. Em produção,
o deploy controla a criação de ofertas por versão; consulte o [guia de publicação](docs/deploy.md).

## Verificar as alterações

Com Docker ativo, construa primeiro a imagem PostGIS usada pelo Testcontainers:

```sh
docker build --tag bora-postgis:18.6-3.6.4 infra/postgres
node --test scripts/check-rules.test.mjs
node --test scripts/check-http-continuity.test.mjs
node scripts/check-rules.mjs
npm ci --prefix apps/web
npm ci --prefix apps/web/tools/api-contract
node scripts/generate-api-types.mjs --check
node scripts/format.mjs
npm run lint --prefix apps/web
npm run typecheck --prefix apps/web
npm run build --prefix apps/web
```

Em PowerShell, execute a verificação Java completa:

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress verify
```

Em bash:

```sh
./mvnw --batch-mode --no-transfer-progress verify
```

O `verify` inclui formatação Java, compilação e integração com PostgreSQL e PostGIS reais.
Os testes de integração falham quando Docker não está disponível. Para corrigir formatação,
use `node scripts/format.mjs --write` e `./mvnw spotless:apply` (ou `.\mvnw.cmd` no PowerShell).

O CI executa regras, formatação, lint, tipos, build, integração, atualização gradual e recuperação
de uma versão inválida. A imagem PostGIS também é verificada em runners amd64 e arm64.
A documentação de [desenvolvimento](docs/desenvolvimento.md) explica as verificações e a
organização do código.

## Tecnologias

| Componente      | Versão fixada   |
| --------------- | --------------- |
| Java / Temurin  | 25 / 25.0.4.1+1 |
| Spring Boot     | 4.1.1           |
| Spring Modulith | 2.1.1           |
| Maven           | 3.10.0          |
| PostgreSQL      | 18.6            |
| PostGIS         | 3.6.4           |
| GraphHopper     | 11.1            |
| Node.js         | 24.22.0 LTS     |
| React           | 19.3.1          |
| Vite            | 8.3.4           |
| TypeScript      | 6.0.3           |
| Tailwind CSS    | 4.3.3           |

TypeScript 6.0.3 é a versão estável compatível com o lint escolhido. As demais dependências
da web estão fixadas em `apps/web/package.json` e `package-lock.json`. O GraphHopper executa
embutido na API, com o recorte de São Paulo versionado no repositório.

## Arquitetura

```text
apps/
  api/          API Spring Boot e migrações Flyway
  simulator/    lojas e entregadores simulados por HTTP e threads virtuais
  web/          React, Vite, TypeScript e componentes de interface
infra/
  compose/      ambientes local e de produção
  caddy/        HTTPS, arquivos estáticos e proxy
  deploy/       provisionamento, publicação gradual e recuperação
  osm/          recorte, hashes e preparo dos dados de ruas
  postgres/     PostgreSQL com PostGIS para amd64 e arm64
docs/           arquitetura, decisões e desenvolvimento
scripts/        regras, formatação e instalação do hook local
```

Veja a [arquitetura](docs/arquitetura.md) e as [decisões técnicas](docs/decisoes/0001-fundacao.md).
O guia de [domínio e persistência](docs/dominio.md) descreve os estados, as garantias do banco
e os comandos para conferir os dados iniciais.

## Publicação

O [guia de publicação](docs/deploy.md) descreve a preparação da VM, o domínio, os secrets do
GitHub e a atualização gradual das duas APIs. A web é compilada em arquivos estáticos;
o Caddy permanece em execução durante a publicação e preserva os assets de páginas já abertas.

Para exercitar atualização e recuperação localmente, siga o teste integrado do guia.
Para medir uma origem já disponível sob requisições contínuas:

```sh
node scripts/check-http-continuity.mjs --url http://localhost:18080 --duration 60
```

O relatório informa sucessos, falhas e latência p95. A verificação exige HTTP 200 na página
e readiness com estado `UP`; qualquer falha resulta em código de saída diferente de zero.
Acrescente `--demo-session` para criar uma sessão de demonstração e exigir a mesma identidade
durante toda a medição.

## Licença

O código do Bora está disponível sob a [licença MIT](LICENSE).
Os dados OpenStreetMap mantêm sua [licença e atribuição próprias](infra/osm/NOTICE.md).
