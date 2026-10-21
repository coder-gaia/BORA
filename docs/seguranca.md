# Segurança e verificações

O Bora é uma demonstração pública com identidades temporárias. A entrada em um papel não
equivale à autenticação de uma empresa ou de um entregador real. O isolamento entre visitantes,
as permissões de cada papel e a separação do simulador continuam sendo exigidos pelo servidor.

## Fronteiras revisadas

| Área                   | Proteção                                                                                                        | Verificação reproduzível                                      |
| ---------------------- | --------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------- |
| Sessão                 | PostgreSQL compartilhado, rotação na entrada e troca de papel, expiração e revogação no logout                  | `IdentitySessionsIT` e `IdentityTouchSecurityIT`              |
| Requisições de escrita | CSRF obrigatório, inclusive na entrada e no protocolo STOMP                                                     | `IdentitySessionsIT`, `RealtimeClusterIT`                     |
| Autorização            | Loja, entregador e operação com permissões e transições próprias                                                | `OrdersApiIT`, `CouriersApiIT`, `DispatchApiIT`               |
| Privacidade            | Pedido privado não aparece para outro visitante; rastreio público sem código de confirmação ou dados de sessão  | `OrdersApiIT`, `TrainingOrderApiIT`, `RealtimeClusterIT`      |
| Simulador              | Chave exclusiva e comparação constante no tempo; caminhos internos bloqueados no proxy público                  | `SimulatorGuardTest`, `SimulatorApiIT`, `check-security.mjs`  |
| Abuso                  | Limites compartilhados por IP/contexto, idempotência e limite de pedidos ativos                                 | `DemoLimitsIT`, `OrdersApiIT`                                 |
| Navegador              | Cookie HttpOnly, SameSite=Lax, Secure em produção, CSP, HSTS e MIME protegido                                   | `check-security.mjs`, teste do Caddy e auditoria no navegador |
| Publicação             | Atualização gradual, despacho bloqueado durante mistura de versões, recuperação e retenção de assets anteriores | `infra/deploy/test.sh`                                        |

Os testes de integração usam PostGIS e requisições reais. Os testes de foco usam dados
controlados e o mecanismo de foco real do navegador. As duas formas de teste têm escopos
distintos; fixtures de interface não comprovam autorização do servidor.

## Dependências consultadas em 21/10/2026

A primeira consulta OSV identificou dez avisos distintos em cinco componentes de execução.
O projeto atualiza Jackson 2 de 2.21.5 para 2.21.7, Jackson 3 de 3.1.5 para 3.1.7 e Tomcat
de 11.0.24 para 11.0.27. As duas versões de Jackson pertencem a linhas diferentes usadas
pelas dependências; os respectivos BOMs mantêm os módulos alinhados.

A consulta posterior às correções retornou zero achados nas 148 dependências Maven
consultadas. O [relatório com versões e horário](benchmarks/security-dependencies.json)
registra a consulta. `npm audit` também retornou zero alertas para a web, incluindo o axe
dos testes, e para a ferramenta isolada de geração do contrato.

Esses resultados descrevem as bases consultadas naquele momento. Não cobrem falhas inéditas,
pacotes do sistema operacional, imagens de container ou uma auditoria independente completa.
Os avisos de referência incluem [Jackson core](https://github.com/advisories/GHSA-7hhh-6rmp-j9qf),
[Jackson databind](https://github.com/advisories/GHSA-cxp5-3px4-pw24) e
[Tomcat](https://github.com/advisories/GHSA-9xv2-5v5q-p794).

O importador também emite um aviso de código Protobuf antigo nas classes de
`osmosis-osm-binary:0.48.3`, dependência do GraphHopper. O runtime Protobuf 4.35.1 não
elimina sozinho o problema do código gerado; a
[orientação do Protobuf](https://github.com/protocolbuffers/protobuf/blob/main/java/core/src/main/java/com/google/protobuf/GeneratedMessage.java)
é regenerar essas classes com um compilador corrigido. Esse aviso continua registrado,
sem flag para silenciá-lo. A consulta OSV de versões de artefatos não o identificou.

No Bora, o PBF é um arquivo offline do projeto, validado por SHA-256 antes da importação.
O importador de produção roda sem rede e a API não oferece upload ou importação de PBF.
Essa restrição reduz a exposição do fluxo atual; não transforma o parser em seguro para
arquivos externos. Aceitar novos arquivos exige revisar a origem, o manifesto e essa
dependência, incluindo a atualização do código gerado.

Para repetir a consulta Maven com JDK 25 e Node 24:

```sh
mkdir -p target/quality
./mvnw --batch-mode --no-transfer-progress dependency:list -DincludeScope=runtime -DoutputFile=target/runtime-dependencies.txt
node scripts/audit-maven.mjs apps/api/target/runtime-dependencies.txt apps/simulator/target/runtime-dependencies.txt --output target/quality/maven-dependencies.json
npm audit --prefix apps/web
npm audit --prefix apps/web/tools/api-contract
```

O CI reprova achados e respostas incompletas da auditoria. A consulta externa recebe somente
coordenadas e versões de dependências públicas. O comportamento da consulta em lote é descrito
na [documentação OSV](https://google.github.io/osv.dev/api/).

## Conferir a origem pública

```sh
node scripts/check-security.mjs https://bora.alexandregaia.me --output target/quality/http-security.json
```

O script verifica cabeçalhos, flags do cookie, bloqueio de caminhos internos, exigência de
sessão e recusa da entrada sem CSRF. Não imprime cookies, tokens nem corpos das respostas.
Ele não inicia uma sessão autenticada nem cria pedidos. A consulta de CSRF pode criar uma
sessão anônima, que expira normalmente.

As evidências de navegador e carga devem usar exclusivamente os ambientes locais isolados
descritos nos respectivos guias. Chaves internas não devem ser gravadas em traces ou logs.

## Limites operacionais

A VM única continua sendo um ponto de falha. HTTPS, testes de recuperação e duas APIs não
garantem disponibilidade diante da parada da VM ou do esgotamento do crédito estudantil.

A publicação da CSP usa um arquivo validado e substituído atomicamente. Alterações estruturais
do Caddy ainda podem exigir reload, sujeito ao defeito reproduzido de encerramento de conexões.
O procedimento e a distinção entre as duas condições estão em [deploy](deploy.md).
