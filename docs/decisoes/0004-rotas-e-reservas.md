# 0004. Seleção espacial, tempo de rota e reserva transacional

Status: aceita.

## Contexto

Distância em linha reta não determina quem chega primeiro à loja. Calcular rotas para todos
os entregadores em cada tentativa tem custo, e duas APIs podem disputar o mesmo candidato.
Ofertas e seus prazos também precisam sobreviver ao reinício de um processo.

## Decisão

Usar PostGIS com índices GiST, `ST_DWithin` e ordenação KNN para pré-selecionar até oito
candidatos. Calcular seus percursos no GraphHopper embutido e ordenar pela duração simulada
em milissegundos. O grafo deriva de um recorte OSM versionado, preparado e aquecido fora da
API, publicado por hash e aberto somente para leitura.

O [`DispatchCoordinator`](../../apps/api/src/main/java/dev/bora/dispatch/DispatchCoordinator.java)
trata um pedido por transação, usando `FOR UPDATE SKIP LOCKED`. Antes da reserva, revalida
disponibilidade, timestamp e geração da posição. Reserva, oferta de 30 segundos e evento são
confirmados juntos. Prazo e próxima tentativa ficam no PostgreSQL; workers recuperam vencimentos.
Os [índices únicos parciais](../../apps/api/src/main/resources/db/migration/V2__create_domain_schema.sql)
restringem ofertas pendentes por pedido e entregador e entregas ativas por entregador.

## Consequências

A decisão combina busca espacial limitada com percursos reais e controle de concorrência no
banco. O resultado é o menor tempo entre os candidatos selecionados; não garante o melhor
entregador de toda a cidade. O perfil estático não considera trânsito em tempo real.
Cada API consome memória para o motor, e atualizar dados ou perfil exige preparar outro grafo.

Veja [roteamento e ETA](../roteamento.md), [entregadores e ofertas](../entregadores-e-posicoes.md)
e [carga e concorrência](../carga.md), incluindo os ensaios de concorrência e reinício.
