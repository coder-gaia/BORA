# 0006. Sessões compartilhadas e contextos da demonstração

Status: aceita.

## Contexto

A demonstração permite experimentar loja, entregador e operação no mesmo navegador. Trocar
de papel precisa preservar o cenário privado do visitante, e outra API deve reconhecer a mesma
sessão. A entrada temporária não comprova a identidade de uma empresa ou de um entregador real.

## Decisão

Usar Spring Session JDBC no PostgreSQL compartilhado e cookie `BORA_SESSION` HttpOnly,
SameSite=Lax e Secure em produção. A aplicação usa a mesma origem e exige CSRF nas escritas
HTTP e no `CONNECT` STOMP. O navegador não precisa administrar um token de acesso persistente.

O [`DemoAuthController`](../../apps/api/src/main/java/dev/bora/identity/internal/DemoAuthController.java)
renova a sessão e o token CSRF ao entrar ou trocar de papel, preservando o contexto ativo.
Sair invalida sessão e contexto. A autorização combina papel, propriedade do recurso e validade
do contexto, inclusive nas assinaturas WebSocket. O simulador usa identidades próprias e uma
chave interna; o proxy público bloqueia suas rotas de preparação.

Pedidos simulados são públicos. Pedidos de visitantes pertencem ao contexto que os criou;
trocar de papel permite continuar esse mesmo cenário. O link de rastreio concede uma visão
limitada por código aleatório, separado do código de confirmação. Limites de uso e idempotência
ficam compartilhados no banco.

## Consequências

As APIs reconhecem sessão e revogação sem afinidade de balanceamento. O banco participa da
disponibilidade da identidade, e a aplicação precisa preservar CSRF e autorização por recurso.
Cookies ou códigos de rastreio expostos concedem acesso dentro de seus respectivos escopos.
Uma aplicação comercial exigiria outro processo de cadastro, autenticação e recuperação.

Veja [identidade e pedidos](../identidade-e-pedidos.md), [tempo real](../tempo-real.md) e
[segurança e verificações](../seguranca.md).
