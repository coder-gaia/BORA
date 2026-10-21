# 0005. Avisos transacionais e recuperação por snapshots

Status: aceita.

## Contexto

Um cliente conectado à API 1 precisa observar mudanças gravadas pela API 2. Uma conexão
WebSocket pode cair, e avisos do PostgreSQL não oferecem reprodução durável das mensagens.
Pedido, oferta e posição persistidos precisam continuar sendo a fonte do estado.

## Decisão

Usar [triggers transacionais com `pg_notify`](../../apps/api/src/main/resources/db/migration/V7__notify_realtime_changes.sql)
para publicar apenas chaves curtas. O aviso é entregue após commit. Cada API mantém uma
[conexão dedicada de `LISTEN`](../../apps/api/src/main/java/dev/bora/realtime/internal/PostgresChangeBridge.java),
fora do pool das requisições, e atende seus clientes por STOMP sobre WebSocket.

Ao perceber mudança, a API consulta o recurso e monta um snapshot autorizado para cada
assinatura. A [reconciliação do servidor](../../apps/api/src/main/java/dev/bora/realtime/internal/RealtimeSessions.java)
repete a leitura periodicamente, mesmo sem aviso. O navegador reconecta com espera progressiva,
recupera consultas HTTP e compara versões ou identidades de eventos antes de aplicar novidades.
Sessão e acesso ao recurso são revalidados durante a conexão. O mapa limita o envio à área
visível e a um frame por segundo.

## Consequências

As duas APIs propagam mudanças sem operar um broker externo. Há leituras adicionais no banco,
e a atualização pode atrasar durante falhas. O mecanismo recupera o estado atual; não promete
entregar cada aviso uma única vez ou reproduzir todos os avisos intermediários. O histórico
do pedido permanece em `order_events`. Uma atualização pode fechar o socket e exigir reconexão.

Veja [tempo real](../tempo-real.md) para destinos, privacidade, recuperação e teste entre APIs.
