# Operação

Em `/operacao`, você acompanha o cenário simulado e os pedidos do seu próprio contexto. Outros visitantes permanecem privados. **Ver pedidos** abre a lista, a busca e os filtros quando você quiser consultar uma entrega.

## Indicadores

Os totais abrangem todos os pedidos autorizados, independentemente do zoom, da página ou dos filtros da lista.

| Indicador                | Significado                                                        |
| ------------------------ | ------------------------------------------------------------------ |
| Pedidos em andamento     | Buscando entregador, atribuído, na loja ou coletado.               |
| Buscando entregador      | Pedidos esperando uma oferta aceita.                               |
| Espera de 3 min ou mais  | Busca de pelo menos três minutos na tentativa atual.               |
| Prazo vencido            | Pedido em andamento cujo prazo chegou ou passou.                   |
| Prazo em até 5 min       | Pedido em andamento com prazo futuro nos próximos cinco minutos.   |
| Entregues na última hora | Conclusões nos últimos 60 minutos, incluindo os limites da janela. |

A espera usa o horário do servidor e começa na criação ou na redistribuição mais recente. Redistribuir reinicia a busca, preservando o histórico anterior. O alerta de três minutos descreve espera por entregador; o de cinco minutos sinaliza proximidade do prazo, sem prever a probabilidade de atraso.

## Encontrar um pedido

Escolha origem e status, ou informe o código completo, respeitando maiúsculas e minúsculas. A busca considera os filtros selecionados.

**Fila de busca** mostra primeiro quem espera há mais tempo na tentativa atual. Um pedido antigo redistribuído agora pode aparecer depois de outro que continua esperando.

A lista preserva seu lugar enquanto você lê. Use **Ver atualizações** ou **Atualizar** para consultar os estados e a ordem mais recentes. As entregas continuam acontecendo durante a consulta. Ao voltar do detalhe, filtros, busca e foco permanecem preservados.

## Mapa e teclado

**Aproximar grupo central** expande os entregadores próximos. Enter com o mapa em foco faz a mesma ação; setas e controles ajustam a navegação. O aviso confirma a aproximação e a escala ajuda a comparar distâncias.

Abra **Entregadores nesta área em lista** para navegar por páginas e usar **Localizar**. Essa lista só muda ao solicitar **Atualizar lista**. Sua contagem descreve a área visível, enquanto os indicadores abrangem todos os pedidos autorizados.

## Agir sobre uma entrega

Confira o código no detalhe antes de confirmar uma ação.

- **Redistribuir pedido:** disponível quando atribuído ou na loja. Libera o entregador atual e inicia outra busca para o mesmo pedido. O próximo entregador ainda precisa aceitar a oferta.
- **Cancelar pedido:** disponível enquanto busca entregador, está atribuído ou na loja. Encerra a entrega e libera os recursos reservados. Depois da coleta, a ação deixa de aparecer.

A confirmação vale para a versão que você conferiu. Se a versão ou a etapa mudar, ela é desabilitada. Um conflito 409 atualiza o detalhe sem repetir a ação automaticamente: reveja a etapa antes de abrir outra confirmação.

Quando a sessão perder acesso ou expirar, indicadores, mapa e pedidos em cache deixam de aparecer. Entre novamente para continuar.
