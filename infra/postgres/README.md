# PostgreSQL com PostGIS

A imagem usa PostgreSQL 18.6 em Debian Trixie e instala PostGIS 3.6.4 pelo
repositório oficial PGDG. A versão dos dois pacotes PostGIS está fixada em
`3.6.4+dfsg-2.pgdg13+1`. Não há compilação local das extensões.

A base `postgres:18.6-trixie` está fixada pelo digest do índice multiarch e
oferece `linux/amd64` e `linux/arm64/v8` no manifesto. Os pacotes PGDG têm
binários para ambas as arquiteturas. A imagem
`postgis/postgis` upstream documenta suporte apenas a amd64, por isso usamos
este Dockerfile. O CI constrói e inicia o banco em runners das duas arquiteturas.

Fontes das versões e arquiteturas:

- [Tags oficiais do PostgreSQL](https://github.com/docker-library/official-images/blob/master/library/postgres).
- [Plataformas suportadas pelo PGDG](https://www.postgresql.org/download/linux/debian/).
- [Pacotes PostGIS no PGDG](https://apt.postgresql.org/pub/repos/apt/pool/main/p/postgis/).
- [Arquiteturas da imagem PostGIS upstream](https://github.com/postgis/docker-postgis#versions).

## Uso local

Na raiz do repositório, em PowerShell ou bash:

```text
docker compose -f infra/compose/compose.dev.yml up --build --wait
docker compose -f infra/compose/compose.dev.yml exec db psql -U bora -d bora -c "SELECT version(), postgis_full_version();"
docker compose -f infra/compose/compose.dev.yml exec db psql -U bora -d bora -c "SELECT ST_Distance(ST_Point(-46.6333, -23.5505, 4326)::geography, ST_Point(-46.6323, -23.5505, 4326)::geography) AS distance_m;"
```

A primeira consulta deve mostrar PostgreSQL 18.6 e PostGIS 3.6.4. A segunda deve
retornar aproximadamente 102 metros. Se alterar o usuário ou o banco no `.env`,
ajuste `-U` e `-d` nos comandos. O banco fica acessível em `127.0.0.1:5432`.

O script de inicialização habilita `postgis` apenas no banco de desenvolvimento
criado pelo entrypoint, quando o volume está vazio. As migrações do domínio
ficam na API. A verificação de saúde exige uma conexão TCP e uma consulta à
extensão, para aguardar o término da inicialização.

Os dados ficam no volume `bora-dev_postgres-data`, montado em
`/var/lib/postgresql`, caminho exigido pela imagem a partir do PostgreSQL 18.
`docker compose -f infra/compose/compose.dev.yml down` encerra os serviços e
preserva os dados. As credenciais padrão servem apenas ao ambiente local.
