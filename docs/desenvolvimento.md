# Desenvolvimento

## Ferramentas

O ambiente completo usa Docker Compose. O desenvolvimento nativo exige JDK 25, Node.js conforme
`.node-version` e npm. Em bash, instale `unzip` para o wrapper usar a distribuição ZIP fixada.
O Maven Wrapper verifica o checksum da distribuição antes de executá-la.
O compilador seleciona uma toolchain Java 25, inclusive quando há outras versões na máquina.

O PostgreSQL local usa os pacotes oficiais PGDG para instalar PostGIS. A imagem é construída
separadamente em amd64 e arm64 no CI; veja [infra/postgres](../infra/postgres/README.md).

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
o deploy controla a criação de ofertas por versão; consulte o [guia de publicação](deploy.md).

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

Execute também os testes unitários da web:

```sh
npm run test:unit --prefix apps/web
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
Os cenários e resultados de navegador estão no [guia de qualidade web](qualidade-web.md).

## Contrato, navegador e convenções

Com o ambiente local em execução e o Chromium do Playwright instalado, execute:

```sh
npm run test:e2e --prefix apps/web
```

O gerador de tipos possui ferramentas isoladas em `apps/web/tools/api-contract`; instalar com
`npm ci --prefix apps/web/tools/api-contract`. Isso mantém o TypeScript da aplicação independente
da versão exigida pelo gerador. O teste Java exporta o contrato real para
`apps/api/target/openapi.json`; o CI o compara com o snapshot versionado e com os tipos.
Para atualizar o contrato e preparar o navegador, consulte o
[guia de identidade e pedidos](identidade-e-pedidos.md).

No PowerShell, substitua `./mvnw` por `.\mvnw.cmd`. O hook local é instalado com
`node scripts/install-hooks.mjs`. Ele verifica o conteúdo do índice, a identidade efetiva de
autor e committer e a mensagem proposta antes do commit.

As mensagens usam Conventional Commits: título em inglês, minúsculas e no imperativo, uma linha
em branco e corpo curto em pt-BR. O formato e as identidades são verificados automaticamente;
idioma e clareza também exigem revisão humana. Cada entrega coerente mantém um único commit.

O Dependabot propõe atualizações em pull requests. Como o histórico exige a identidade do
proprietário, as mudanças aprovadas devem ser incorporadas em um commit feito por ele, após
as verificações. Os commits automáticos de dependências não são mesclados diretamente.

Código, tabelas e endpoints usam inglês. Interface e documentação usam pt-BR. Textos e nomes
versionados não usam caracteres U+2012 a U+2015; use o hífen comum quando necessário.

## Versões fixadas

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

TypeScript 6.0.3 é a versão fixada para a aplicação. As demais dependências
da web estão fixadas em `apps/web/package.json` e `package-lock.json`. O GraphHopper executa
embutido na API, com o recorte de São Paulo versionado no repositório.

## Diagnóstico local

Se a página informar indisponibilidade, consulte primeiro a readiness em
`http://localhost:8080/actuator/health/readiness` e depois os logs do Compose. A readiness retorna
HTTP 503 quando a API está ativa mas o banco ou o grafo de rotas não está pronto. A página permite consultar o
estado novamente após a recuperação.

A API abre o grafo somente para leitura. Ao desenvolver sem o Compose completo, prepare-o
primeiro com `java -jar apps/api/target/bora-api.jar --routing-import`, usando o mesmo
`BORA_GRAPH_ROOT` da API. O [guia de roteamento](roteamento.md) descreve as ferramentas e os
testes com recortes reais e sintéticos.

Se uma porta estiver ocupada, defina `POSTGRES_PORT`, `API_PORT` ou `WEB_PORT` no arquivo `.env`
e use `docker compose --env-file .env -f infra/compose/compose.dev.yml ...`.

Os volumes preservam os dados entre reinícios. O comando `down --volumes` apaga os dados locais
e só deve ser usado quando essa reinicialização for desejada.

Para testar a comunicação entre duas APIs por trás do Caddy, use o
[ambiente de tempo real](tempo-real.md#ambiente-local-com-duas-apis).

### Rotas e ETA

Para verificar rotas e ETA dentro do motor, com o ambiente já preparado:

```sh
docker compose -f infra/compose/compose.dev.yml run --rm --no-deps api --routing-benchmark --warmup=100 --iterations=500 --max-p95-ms=50
```

O relatório JSON mede o cálculo dentro da JVM, sem a latência de uma chamada pela internet.
O ETA original usa as velocidades das vias, sem trânsito; `SIM_SPEED`, padrão `3`, também
produz a duração acelerada da demonstração. Consulte o [guia de roteamento](roteamento.md).

### Continuidade HTTP

O [guia de publicação](deploy.md) descreve o ambiente local e o teste integrado de atualização e recuperação.
Para medir uma origem já disponível sob requisições contínuas:

```sh
node scripts/check-http-continuity.mjs --url http://localhost:18080 --duration 60
```

O relatório informa sucessos, falhas e latência p95. A verificação exige HTTP 200 na página
e readiness com estado `UP`; qualquer falha resulta em código de saída diferente de zero.
Acrescente `--demo-session` para criar uma sessão de demonstração e exigir a mesma identidade
durante toda a medição.
