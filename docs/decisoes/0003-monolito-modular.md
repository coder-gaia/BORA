# 0003. Monolito modular com transações locais

Status: aceita.

## Contexto

Aceitar uma oferta, coletar, cancelar ou concluir uma entrega altera pedido e entregador.
Essas operações precisam preservar os mesmos invariantes mesmo com duas instâncias da API.
O projeto também precisa manter identidade, pedidos, roteamento e apresentação compreensíveis
sem administrar um processo independente para cada área de negócio.

## Decisão

Manter o backend como uma aplicação Spring Boot, organizada em módulos sob `dev.bora`.
Os contratos públicos ficam na raiz de cada módulo; implementações e controladores ficam
em `internal`. Spring Modulith verifica dependências e acesso às implementações no teste
[`BoraApplicationIT`](../../apps/api/src/test/java/dev/bora/BoraApplicationIT.java).

Coordenadores usam os contratos de pedidos e entregadores dentro de transações PostgreSQL.
Eventos síncronos permitem liberar recursos na mesma transação que encerra ou redistribui
o pedido. O [listener de liberação](../../apps/api/src/main/java/dev/bora/fulfillment/internal/OrderResourceReleaseListener.java)
exige uma transação existente. O simulador permanece separado e usa HTTP, sem acesso ao banco.

## Consequências

As invariantes podem usar locks, restrições e rollback locais. A fronteira modular é verificada
no código; disponibilidade e implantação continuam compartilhadas. Os módulos são publicados
e dimensionados juntos. Separá-los em serviços exigiria rever essas transações e o protocolo
de comunicação, além de demonstrar uma necessidade de escala ou operação independente.

Veja a [arquitetura](../arquitetura.md), o [domínio](../dominio.md) e a
[fundação do monorepo](0001-fundacao.md).
