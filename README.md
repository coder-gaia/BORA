# Bora

Despacho de entregas em tempo real em São Paulo, desenvolvido com Java, Spring Boot, React e PostGIS.

Bora é uma demonstração de despacho de entregas: lojas criam pedidos e o sistema busca o
entregador que chega mais rápido. A base local já executa banco, API e web com uma única
composição Docker. As funcionalidades de entrega estão em desenvolvimento.

## Executar localmente

Pré-requisitos: Git e Docker com Compose. No Windows, abra o Docker Desktop com o mecanismo
Linux ativo. Os comandos abaixo funcionam em PowerShell e bash, na raiz do repositório.

```sh
git clone https://github.com/coder-gaia/BORA.git
cd BORA
docker compose -f infra/compose/compose.dev.yml up --build --wait
```

Abra [localhost:5173](http://localhost:5173). A página consulta a API e informa se o serviço e o
banco estão disponíveis. O primeiro build baixa as dependências e pode demorar alguns minutos.

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

O comando `down` preserva o volume do banco. Para apagar os dados locais deliberadamente, use
`down --volumes`. Alterar a senha no `.env` não altera a senha de um volume já inicializado.

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
.\mvnw.cmd --batch-mode -pl apps/api spring-boot:run
```

Em bash:

```sh
./mvnw --batch-mode -pl apps/api spring-boot:run
```

Em outro terminal, inicie a web:

```sh
npm run dev --prefix apps/web
```

A API usa as mesmas credenciais locais padrão. Ajustes no `.env` do Compose precisam ser
repetidos nas variáveis `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME` e
`SPRING_DATASOURCE_PASSWORD` do terminal da API. O proxy do Vite usa `http://localhost:8080`;
para outra porta, defina `API_PROXY_TARGET` no terminal da web.

## Verificar as alterações

Com Docker ativo, construa primeiro a imagem PostGIS usada pelo Testcontainers:

```sh
docker build --tag bora-postgis:18.6-3.6.4 infra/postgres
node --test scripts/check-rules.test.mjs
node scripts/check-rules.mjs
npm ci --prefix apps/web
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

## Stack

| Componente                            | Versão fixada   |
| ------------------------------------- | --------------- |
| Java / Temurin                        | 25 / 25.0.4.1+1 |
| Spring Boot                           | 4.1.1           |
| Spring Modulith                       | 2.1.1           |
| Maven                                 | 3.10.0          |
| PostgreSQL                            | 18.6            |
| PostGIS                               | 3.6.4           |
| GraphHopper (reservado ao roteamento) | 11.1            |
| Node.js                               | 24.22.0 LTS     |
| React                                 | 19.3.1          |
| Vite                                  | 8.3.4           |
| TypeScript                            | 6.0.3           |
| Tailwind CSS                          | 4.3.3           |

TypeScript 6.0.3 é a versão estável compatível com o lint escolhido. As demais dependências
da web estão fixadas em `apps/web/package.json` e `package-lock.json`. A versão do GraphHopper
está reservada no POM; a biblioteca será adicionada quando o roteamento for implementado.

## Arquitetura

```text
apps/
  api/          API Spring Boot e migrações Flyway
  simulator/    módulo Java reservado à simulação
  web/          React, Vite, TypeScript e componentes de interface
infra/
  compose/      ambiente local integrado
  postgres/     PostgreSQL com PostGIS para amd64 e arm64
docs/           arquitetura, decisões e desenvolvimento
scripts/        regras, formatação e instalação do hook local
```

Veja a [arquitetura](docs/arquitetura.md) e as [decisões técnicas](docs/decisoes/0001-fundacao.md).

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
