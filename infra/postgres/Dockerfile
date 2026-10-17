FROM postgres:18.6-trixie@sha256:74935e72241653ca55e0414067e6d8763aceb8a810eb51b452253ec3dcfc4336

ARG POSTGIS_VERSION=3.6.4+dfsg-2.pgdg13+1

RUN apt-get update \
    && apt-get install --yes --no-install-recommends \
        postgresql-18-postgis-3="${POSTGIS_VERSION}" \
        postgresql-18-postgis-3-scripts="${POSTGIS_VERSION}" \
    && rm -rf /var/lib/apt/lists/*

COPY initdb/ /docker-entrypoint-initdb.d/
