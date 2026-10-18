FROM debian:trixie-20261005-slim@sha256:a29215f6a35e51e22adffa17f89e9d2ef06214e64a2bad10d765c46aea49f11f

RUN rm /etc/apt/sources.list.d/debian.sources \
    && printf '%s\n' 'deb [check-valid-until=no] http://snapshot.debian.org/archive/debian/20261018T000000Z/ trixie main' > /etc/apt/sources.list \
    && apt-get update \
    && apt-get install --yes --no-install-recommends osmium-tool=1.18.0-1 ca-certificates python3 time \
    && rm -rf /var/lib/apt/lists/* \
    && test "$(dpkg-query --show --showformat='${Version}' osmium-tool)" = 1.18.0-1

ENV LANG=C.UTF-8 PYTHONDONTWRITEBYTECODE=1
WORKDIR /workspace
ENTRYPOINT ["python3", "/workspace/infra/osm/prepare.py"]
