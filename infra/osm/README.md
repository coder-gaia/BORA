# Dados viários de São Paulo

O Bora usa um recorte do OpenStreetMap para calcular rotas localmente. O produto em `data/` e a fixture real em `apps/api/src/test/resources/routing/` são versionados; compilar e iniciar a aplicação não exige baixar novamente a fonte regional.

## Origem e licença

A fonte é o extrato mensal `sudeste-261001.osm.pbf` da [Geofabrik](https://download.geofabrik.de/south-america/brazil/sudeste.html), com 859.984.241 bytes e MD5 oficial `6730c6e9739a176bb6da3b911fb4de0e`. O SHA256 integral da fonte e o instante dos dados constam dos manifestos. A Geofabrik preserva os mensais por mais tempo que os diários, mas [o administrador informa retenção aproximada de três meses](https://community.openstreetmap.org/t/geofabrik-downloads-monthly-file-missing/136648/5); mantenha uma cópia da fonte se precisar reconstruir o recorte depois desse período.

Dados © OpenStreetMap contributors, sob [Open Database License 1.0](https://opendatacommons.org/licenses/odbl/1-0/). A licença dos dados também se aplica ao recorte e à fixture. Consulte [NOTICE.md](NOTICE.md) e a [página de atribuição do OpenStreetMap](https://www.openstreetmap.org/copyright).

## Reconstrução

É necessário ter Docker e Bash. No Windows, execute pelo Git Bash. A partir da raiz:

```bash
bash infra/osm/prepare.sh
```

O primeiro uso baixa a fonte para `infra/osm/work/`, fora do Git. As execuções seguintes conferem tamanho, SHA256 e MD5 antes de reutilizá-la. Um download interrompido conserva o arquivo `.part` para retomada quando o servidor aceita intervalos HTTP.

Para usar uma fonte local, mantendo a verificação do SHA256 fixado:

```bash
bash infra/osm/prepare.sh --source /caminho/sudeste-261001.osm.pbf
```

Também é possível fornecer `--source-sha256` com o valor de `source.sha256` do manifesto. Um hash diferente do fixado é rejeitado antes da extração. Atualizar o conjunto de dados exige revisar a origem, os checksums, os pares de aquecimento e os produtos no mesmo commit.

Para reconstruir e comparar os hashes e o conteúdo dos manifestos, preservando os produtos existentes:

```bash
bash infra/osm/prepare.sh --check
```

A ferramenta usa a imagem oficial Debian `trixie-20261005-slim` por digest, o snapshot de pacotes Debian `20261018T000000Z` e `osmium-tool=1.18.0-1`. O Dockerfile confere a versão instalada. O processo fica limitado a 2 CPUs e 7 GiB de memória; execute a preparação no computador de desenvolvimento ou no CI.

Um bloqueio exclusivo em `work/.prepare.lock` cobre download, processamento, publicação e comparação com `--check`. Uma segunda execução falha imediatamente com uma mensagem para aguardar a primeira; os produtos e o cache permanecem protegidos até a liberação do bloqueio.

## Recorte e verificações

O retângulo de produção é `[minLon, minLat, maxLon, maxLat] = [-46.77, -23.66, -46.48, -23.43]`, com margem ao redor da área atendida pela demonstração. A fixture usa `[-46.661, -23.570, -46.647, -23.557]`, próxima da Avenida Paulista. A extração completa vias e relações de restrição que cruzam as bordas; por isso alguns objetos podem ultrapassar o retângulo solicitado.

O processo usa `osmium extract --strategy=smart --option=types=restriction` e depois `osmium tags-filter` com `w/highway`, `w/route=ferry,shuttle_train` e `r/type=restriction`. Preserva os objetos referidos e suas tags, incluindo barreiras, acessos, sentidos e restrições de conversão. Remove metadados de edição e grava PBF com compressão zlib.

Antes de publicar os produtos, exige:

- recorte de produção com no máximo 30 MiB e fixture com no máximo 1 MiB;
- referências completas de nós, vias e relações em `osmium check-refs --check-relations`;
- tamanho e SHA256 calculados sobre os arquivos produzidos;
- pares de aquecimento sobre nós reais de vias com acesso permitido, com IDs de nós e vias em `provenance.warmupNodes`.

O motor confirma a conectividade dos pares ao importar o grafo. A fixture declara `minNetworkSize: 0` para conservar sua pequena rede. A seleção de nós não substitui a validação das regras de circulação feita pelo perfil de roteamento.

Os cinco pares de produção ligam vias distintas, separadas por 1 a 5 km em linha reta, próximas a Paulista/República, Pinheiros/Perdizes, Vila Mariana/Paulista, Sé/Mooca e República/Pari. Os dois pares da fixture percorrem trechos locais. As coordenadas dos manifestos vêm dos nós OSM; os pontos aproximados desses bairros apenas orientam a seleção.

`infra/osm/work/processing-report.json` registra comandos, duração e pico RSS de cada etapa. Os manifestos públicos guardam apenas metadados estáveis, para permitir a comparação dos hashes em uma reconstrução.

Na preparação de 18/10/2026, em Docker Linux/amd64 limitado a 2 CPUs e 7 GiB, os produtos foram:

| Produto  |     Bytes |     Nós |    Vias | Relações |
| -------- | --------: | ------: | ------: | -------: |
| Produção | 8.571.643 | 689.360 | 146.393 |    4.460 |
| Fixture  |    57.655 |   4.756 |     910 |       64 |

A primeira extração regional levou 66,57 s e o filtro, 6,40 s. Seu maior pico RSS entre as etapas do Osmium foi 4.109.632 KiB, aproximadamente 3,92 GiB. Uma segunda preparação com `--check` reproduziu os hashes dos dois PBFs e o conteúdo dos manifestos, com pico RSS de 4.129.068 KiB, aproximadamente 3,94 GiB. Esses valores descrevem a preparação dos dados, incluindo as tabelas de IDs OSM; o tamanho e a memória do grafo de roteamento são medidos separadamente na aplicação.

Documentação das ferramentas: [extração](https://docs.osmcode.org/osmium/latest/osmium-extract.html), [filtros e referências](https://docs.osmcode.org/osmium/latest/osmium-tags-filter.html) e [verificação de integridade](https://docs.osmcode.org/osmium/latest/osmium-check-refs.html).
