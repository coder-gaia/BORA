# Identidade e pedidos

A base HTTP é `/api/v1`. O navegador e a API usam a mesma origem, por meio do Vite no ambiente
local e do Caddy em produção. O documento OpenAPI fica em `/api/openapi`; seu snapshot está
em [api/openapi.json](api/openapi.json).

## Entrar e trocar de papel

`GET /auth/me` retorna `authenticated: false` e `user: null` para uma sessão anônima.
`GET /auth/csrf` cria ou consulta a proteção da sessão e devolve `headerName` e o token mascarado.
Requisições que alteram estado enviam esse token no cabeçalho indicado e mantêm o cookie.

`POST /auth/demo`, com `role` igual a `STORE`, `COURIER` ou `OPERATIONS`, cria uma conta temporária
do papel escolhido. O servidor escolhe o contexto, a loja e o entregador; esses vínculos não vêm
do corpo enviado pelo navegador. A loja visitante é particular e o entregador começa offline.

Trocar de papel na mesma sessão preserva o contexto da demonstração. A sessão e o CSRF são
rotacionados; o cliente consulta `/auth/csrf` novamente após a entrada. O cookie `BORA_SESSION`
é HttpOnly, SameSite=Lax e Secure em produção. No HTTP local, `BORA_SESSION_SECURE=false`.

A entrada invalida a sessão anterior e migra seus atributos para uma nova linha no PostgreSQL.
Assim, uma consulta do mapa iniciada antes da entrada pode terminar depois dela sem restaurar
o identificador antigo ou desfazer a troca de papel. O contexto da demonstração permanece o mesmo.

`POST /auth/logout` encerra a sessão e revoga seu contexto. Contas e sessões expiram após duas
horas de inatividade. Sessões ficam no PostgreSQL e funcionam nas duas instâncias da API.
Não compartilhar o cookie ou o token CSRF em relatórios de erro.

## Criar e consultar

Uma loja usa `GET /stores/me` para consultar sua localização. Para criar um pedido, envia:

```http
POST /api/v1/orders
Content-Type: application/json
Idempotency-Key: uma-chave-nova-por-intencao-de-criacao
X-CSRF-TOKEN: token-obtido-na-sessao
```

```json
{
  "dropoff": {
    "latitude": -23.5505,
    "longitude": -46.6333
  }
}
```

O servidor deriva a loja e o proprietário da sessão. Valida as coordenadas, a área atendida e a
rota de moto. O ponto solicitado e o acesso encaixado na via precisam estar dentro da área.
A resposta é HTTP 201 com `Location`, identificação do pedido, estado, versão e linha do tempo.

Uma chave de idempotência aceita de 1 a 128 caracteres ASCII sem espaços. Repetir a mesma chave
e destino no mesmo contexto retorna a representação original, inclusive depois de uma mudança
de estado do pedido. Reutilizar a chave com outro destino retorna 409. A interface mantém a chave
quando a resposta se perde, para permitir a repetição sem duplicar o pedido.

Cada contexto pode ter no máximo cinco pedidos ativos. A verificação e a inserção acontecem
sob o mesmo lock no PostgreSQL, inclusive quando as requisições chegam a instâncias diferentes.
Falhas na gravação do evento também desfazem pedido e idempotência.

`GET /orders?page=0&size=20` retorna uma página limitada. `GET /orders/{id}` inclui a linha do
tempo compatível com a versão consultada. A visibilidade permite o próprio contexto e o
cenário público simulado; pedidos particulares de outro visitante retornam 404, inclusive para
uma conta de operação. Dados internos de sessão e hashes não são enviados ao navegador.

`POST /orders/{id}/cancel`, com `expectedVersion`, cancela conforme o papel e a etapa. Versão
antiga ou transição inválida retorna 409. O cancelamento e seu evento pertencem à mesma transação.

## Código de confirmação

O código de seis dígitos é derivado por HMAC de uma chave privada do servidor e do UUID aleatório
do pedido. Um HMAC com rótulo separado é armazenado para verificação constante no tempo. O
código é recalculado apenas nas respostas autorizadas; não é persistido em claro, inclusive no
snapshot de idempotência. O código público de rastreio é outro valor aleatório independente.

`BORA_CONFIRMATION_SECRET` precisa ser persistente e igual nas duas APIs. O Compose de produção
exige seu preenchimento; o provisionamento gera 32 bytes aleatórios sem exibi-los. A chave
conhecida do ambiente local serve somente ao desenvolvimento. Trocar a chave altera os códigos
derivados dos pedidos existentes; guardar a configuração privada junto ao procedimento de backup.

## Limites e erros

Bucket4j mantém os limites no PostgreSQL, compartilhados pelas instâncias e preservados durante
atualizações. A entrada tem limites de 10 solicitações por minuto e 30 por hora por endereço IP;
a criação, 10 por minuto por contexto. A quantidade de pedidos ativos é uma regra adicional.
Uma resposta 429 por frequência inclui `Retry-After` em segundos. O Caddy define os cabeçalhos encaminhados e
o Tomcat confia na rede interna do proxy; a aplicação não lê um IP arbitrário do corpo ou do
cabeçalho `Forwarded` enviado pelo cliente.

Erros seguem Problem Details, com `code` estável e mensagens em pt-BR. Entradas inválidas retornam
400; sessão ausente, 401; CSRF ou papel inadequado, 403; recurso não visível, 404; conflito, 409;
destino fora da área ou inacessível, 422; limite excedido, 429. A interface apresenta a mensagem
sem exibir detalhes internos do servidor.

## Contrato e verificações

Com JDK 25 e Docker ativos, `mvnw verify` executa os testes HTTP, PostgreSQL, sessões, CSRF,
idempotência, privacidade e concorrência. Para atualizar deliberadamente o contrato após uma
alteração dos controladores:

```sh
./mvnw --batch-mode -pl apps/api -am verify -Dbora.openapi.update=true
npm ci --prefix apps/web/tools/api-contract
node scripts/generate-api-types.mjs
```

No PowerShell, use `.\mvnw.cmd`. Revisar o snapshot e os tipos gerados no mesmo commit da API.
O gerador usa uma instalação isolada de TypeScript 5.9.3 porque `openapi-typescript` 7.13.0
declara suporte a TypeScript 5. A aplicação permanece em TypeScript 6.0.3, sem forçar peers.

O CI compara o contrato exportado com o snapshot e os tipos gerados. Instale o navegador uma vez
com `npm exec --prefix apps/web -- playwright install chromium`. Com o Compose local iniciado,
`npm run test:e2e --prefix apps/web` testa os fluxos reais do navegador. O verificador de publicação
aceita `--demo-session` para conferir a mesma identidade durante as trocas de instância:

```sh
node scripts/check-http-continuity.mjs --url http://localhost:18080 --duration 180 --timeout 5000 --demo-session
```
