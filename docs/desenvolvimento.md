# Desenvolvimento

## Ferramentas

O ambiente completo usa Docker Compose. O desenvolvimento nativo exige JDK 25, Node.js conforme
`.node-version` e npm. Em bash, instale `unzip` para o wrapper usar a distribuição ZIP fixada.
O Maven Wrapper verifica o checksum da distribuição antes de executá-la.
O compilador seleciona uma toolchain Java 25, inclusive quando há outras versões na máquina.

O PostgreSQL local usa os pacotes oficiais PGDG para instalar PostGIS. A imagem é construída
separadamente em amd64 e arm64 no CI; veja [infra/postgres](../infra/postgres/README.md).

## Qualidade

- `./mvnw verify`: Spotless, compilação e testes, incluindo integração com banco real.
- `npm run lint --prefix apps/web`: regras TypeScript e React.
- `npm run typecheck --prefix apps/web`: tipos do frontend e da configuração Vite.
- `npm run build --prefix apps/web`: artefatos estáticos de produção.
- `node scripts/format.mjs`: Prettier sobre os arquivos versionados com formatos suportados.
- `node scripts/check-rules.mjs`: conteúdo dos textos versionados, nomes e histórico de commits.
- `node --test scripts/check-rules.test.mjs`: cenários reais de Git para as regras de autoria,
  mensagens e conteúdo preparado para commit.
- `node --test scripts/check-http-continuity.test.mjs`: continuidade HTTP e detecção de perda
  de sessão durante a atualização.
- `node scripts/generate-api-types.mjs --check`: contrato OpenAPI e tipos gerados sincronizados.
- `npm run test:e2e --prefix apps/web`: fluxos reais de identidade e pedidos no Chromium,
  com o ambiente local em execução e o navegador do Playwright instalado.

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
