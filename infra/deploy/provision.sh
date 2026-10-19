#!/usr/bin/env bash
set -Eeuo pipefail

fail() { printf 'Erro: %s\n' "$*" >&2; exit 1; }
[[ $EUID == 0 ]] || fail 'Execute com sudo em uma VM Ubuntu nova.'
[[ $# == 1 ]] || fail 'Uso: sudo provision.sh <arquivo-da-chave-publica-do-deploy>'
[[ -f $1 ]] || fail 'Arquivo de chave pública ausente.'
# shellcheck source=/dev/null
source /etc/os-release
[[ $ID == ubuntu && $VERSION_ID == 24.04 ]] || fail 'Este script requer Ubuntu 24.04.'
[[ $(dpkg --print-architecture) == arm64 ]] || fail 'Este provisionamento requer arm64.'

deploy_user=${DEPLOY_USER:-deploy}
[[ $deploy_user =~ ^[a-z][a-z0-9_-]{0,30}$ && $deploy_user != root ]] || fail 'Usuário de deploy inválido.'
mapfile -t public_keys < "$1"
[[ ${#public_keys[@]} == 1 && -n ${public_keys[0]} ]] || fail 'Informe exatamente uma chave pública SSH.'
ssh-keygen -l -f "$1" >/dev/null || fail 'A chave pública SSH não é válida.'

for package in docker.io podman-docker containerd runc; do
  if dpkg-query -W -f='${Status}' "$package" 2>/dev/null | grep -q 'install ok installed'; then
    fail "Pacote conflitante $package instalado. Revise a VM antes de continuar."
  fi
done

export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install --yes ca-certificates curl gnupg iptables openssh-server openssl unattended-upgrades
install -d -m 0755 /etc/apt/keyrings
key_file=$(mktemp)
trap 'rm -f -- "$key_file"' EXIT
curl --fail --silent --show-error --location https://download.docker.com/linux/ubuntu/gpg > "$key_file"
fingerprint=$(gpg --show-keys --with-colons "$key_file" | awk -F: '$1 == "fpr" { print $10; exit }')
[[ $fingerprint == 9DC858229FC7DD38854AE2D88D81803C0EBFCD88 ]] || fail 'Fingerprint da chave Docker inesperado.'
install -m 0644 "$key_file" /etc/apt/keyrings/docker.asc
cat > /etc/apt/sources.list.d/docker.sources <<'EOF'
Types: deb
URIs: https://download.docker.com/linux/ubuntu
Suites: noble
Components: stable
Architectures: arm64
Signed-By: /etc/apt/keyrings/docker.asc
EOF
apt-get update
apt-get install --yes docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
systemctl enable --now docker

if ! id "$deploy_user" >/dev/null 2>&1; then
  useradd --create-home --shell /bin/bash "$deploy_user"
fi
usermod --append --groups docker "$deploy_user"
deploy_home=$(getent passwd "$deploy_user" | cut -d: -f6)
[[ $deploy_home == /home/"$deploy_user" ]] || fail 'Diretório pessoal inesperado para o usuário de deploy.'
install -d -m 0700 -o "$deploy_user" -g "$deploy_user" "$deploy_home/.ssh"
touch "$deploy_home/.ssh/authorized_keys"
if ! grep -Fxq -- "${public_keys[0]}" "$deploy_home/.ssh/authorized_keys"; then
  printf '%s\n' "${public_keys[0]}" >> "$deploy_home/.ssh/authorized_keys"
fi
chown "$deploy_user:$deploy_user" "$deploy_home/.ssh/authorized_keys"
chmod 0600 "$deploy_home/.ssh/authorized_keys"
install -d -m 0750 -o "$deploy_user" -g "$deploy_user" /opt/bora /opt/bora/releases
install -d -m 0755 -o "$deploy_user" -g "$deploy_user" /opt/bora/config

if [[ ! -e /opt/bora/env.production ]]; then
  password=$(openssl rand -hex 32)
  confirmation_secret=$(openssl rand -hex 32)
  simulator_secret=$(openssl rand -hex 32)
  umask 077
  cat > /opt/bora/env.production <<EOF
DOMAIN=bora.alexandregaia.me
ACME_EMAIL=alexandregaia911@gmail.com
POSTGRES_DB=bora
POSTGRES_USER=bora
POSTGRES_PASSWORD=$password
BORA_CONFIRMATION_SECRET=$confirmation_secret
BORA_SIMULATOR_KEY=$simulator_secret
BORA_SESSION_SECURE=true
SIM_SEED=2026
SIM_SPEED=3
API_IMAGE=ghcr.io/coder-gaia/bora-api
WEB_IMAGE=ghcr.io/coder-gaia/bora-web
DB_IMAGE=ghcr.io/coder-gaia/bora-postgis
SIMULATOR_IMAGE=ghcr.io/coder-gaia/bora-simulator
EOF
  unset password confirmation_secret simulator_secret
  chown "$deploy_user:$deploy_user" /opt/bora/env.production
  chmod 0600 /opt/bora/env.production
fi

install -d -m 0755 /etc/ssh/sshd_config.d /run/sshd
ssh_configuration=/etc/ssh/sshd_config.d/00-bora.conf
ssh_backup=$(mktemp)
if [[ -e $ssh_configuration ]]; then cp "$ssh_configuration" "$ssh_backup"; fi
cat > "$ssh_configuration" <<'EOF'
PubkeyAuthentication yes
PasswordAuthentication no
KbdInteractiveAuthentication no
PermitRootLogin no
EOF
chmod 0644 "$ssh_configuration"
if ! /usr/sbin/sshd -t; then
  if [[ -s $ssh_backup ]]; then cp "$ssh_backup" "$ssh_configuration"; else rm -f "$ssh_configuration"; fi
  rm -f "$ssh_backup"
  fail 'Configuração SSH inválida; a alteração foi desfeita.'
fi
rm -f "$ssh_backup"
systemctl reload ssh

# As regras do provedor e as portas SSH existentes são preservadas.
install -d -m 0755 /usr/local/sbin
cat > /usr/local/sbin/bora-firewall <<'EOF'
#!/bin/sh
set -eu
iptables -C INPUT -p tcp -m multiport --dports 80,443 -m conntrack --ctstate NEW \
  -m comment --comment 'bora public web' -j ACCEPT 2>/dev/null \
  || iptables -I INPUT 1 -p tcp -m multiport --dports 80,443 -m conntrack --ctstate NEW \
    -m comment --comment 'bora public web' -j ACCEPT
EOF
chmod 0755 /usr/local/sbin/bora-firewall
cat > /etc/systemd/system/bora-firewall.service <<'EOF'
[Unit]
Description=Portas web do Bora sem substituir o firewall existente
After=netfilter-persistent.service
Before=docker.service

[Service]
Type=oneshot
ExecStart=/usr/local/sbin/bora-firewall
RemainAfterExit=yes

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
systemctl enable --now bora-firewall.service
/usr/local/sbin/bora-firewall
cat > /etc/apt/apt.conf.d/20auto-upgrades <<'EOF'
APT::Periodic::Update-Package-Lists "1";
APT::Periodic::Unattended-Upgrade "1";
EOF

printf '%s\n' 'Provisionamento concluído. Mantenha esta sessão aberta e teste uma nova conexão como deploy.'
docker version --format '{{.Server.Version}}'
docker compose version
