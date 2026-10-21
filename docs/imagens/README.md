# Imagens da demonstração

Capturas reais de 21 de outubro de 2026, feitas no build local M14, servido pelo Caddy
com a política de segurança de produção e backend com 120 entregadores simulados.
Os indicadores vieram da API e as posições de WebSocket reais, sem respostas simuladas
pelo navegador. A contagem no mapa representa somente a área visível.

- `operacao.png`: visão geral em viewport de 1440 px; PNG original de 1268 × 1277 px.
- `operacao-mobile.png`: visão geral em viewport de 390 px; PNG original de 316 × 1467 px.
- `operacao.gif`: mapa em movimento, 960 × 792 px, 7 segundos, 5 quadros por segundo,
  488.100 bytes. Derivado de 35 screenshots reais; sete snapshots de posições distintos
  chegaram durante a gravação.

Os PNGs não foram redimensionados. O GIF usa paleta de 192 cores e mantém as atribuições
visíveis de OpenFreeMap, OpenMapTiles e OpenStreetMap. O enquadramento foi obtido com os
controles normais da aplicação; o produto não foi alterado para a captura.

No README principal, use o poster estático e ofereça o GIF por link opcional, evitando
reprodução automática para quem prefere reduzir movimento. As imagens estáticas foram
capturadas com a preferência de movimento reduzido; somente o contexto isolado da
animação solicitou movimento normal.

Texto alternativo sugerido: "Painel da operação do Bora, com indicadores de pedidos e
mapa de entregadores simulados em São Paulo."

Legenda sugerida: "Demonstração local com 120 entregadores simulados; indicadores e
posições recebidos da API e por WebSocket reais."

## Capas de compartilhamento

A [capa do site](../../apps/web/public/og-image.png) tem 1200 × 630 px. A variante
[para o GitHub](github-social-preview.png) tem 1280 × 640 px e deve ser enviada em
Settings → Social preview → Edit → Upload an image nas configurações do repositório.

Com as dependências da web e o Chromium do Playwright instalados, regenere as duas
capas na raiz do projeto com `node apps/web/scripts/generate-og-image.mjs`. O gerador
usa somente a fonte e o mapa locais, com renderização própria para cada tamanho.
