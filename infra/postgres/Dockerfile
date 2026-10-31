FROM postgres:18.6-trixie@sha256:979bccfde35445db618c65107d27d08c6c21339d8dc3ad4c830c6633947be0e4

ARG POSTGIS_VERSION=3.6.4+dfsg-2.pgdg13+1

RUN apt-get update \
    && apt-get install --yes --no-install-recommends \
        postgresql-18-postgis-3="${POSTGIS_VERSION}" \
        postgresql-18-postgis-3-scripts="${POSTGIS_VERSION}" \
    && rm -rf /var/lib/apt/lists/*

COPY initdb/ /docker-entrypoint-initdb.d/
