# 0001. Fundação do monorepo

Status: aceita.

## Contexto

Bora combina uma API Java, um cliente Java para simulação e uma interface React. Alterações
futuras precisam manter contratos e telas coerentes sem acrescentar uma camada de orquestração
de builds entre ecossistemas diferentes.

## Decisão

Manter Maven e npm nativos em um monorepo. Maven agrega API e simulador; a web possui seu
próprio manifesto e lockfile. Docker Compose oferece a entrada comum para execução local.

Usar Java 25, Spring Boot 4, Spring Modulith, PostgreSQL 18 e PostGIS, com versões estáveis
compatíveis e fixadas. As imagens locais usam versões explícitas. O PostGIS é instalado pelos
pacotes PGDG sobre a imagem oficial PostgreSQL para suportar amd64 e arm64.

Manter os módulos de negócio e o roteamento fora da fundação até que suas funcionalidades sejam
introduzidas. A primeira interface apresenta a saúde real do serviço e do banco para permitir
validar o caminho completo desde o navegador.

## Consequências

O projeto exige somente Docker para execução completa. Quem desenvolve nativamente precisa
de JDK 25 e Node.js. A primeira construção depende dos repositórios de imagens, Maven e npm.
O CI verifica os dois ecossistemas e as duas arquiteturas do banco separadamente.
