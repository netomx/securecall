# Despliegue — call.l8.nz

Servidor: `72.11.156.223` (IPv4 únicamente, sin registro AAAA), **Ubuntu 24.04 LTS**, 1 vCPU, 1 GB RAM + 2 GB swap.

| Servicio | Puertos | Config en el servidor | Fuente en el repo |
|---|---|---|---|
| nginx (TLS → sc-server) | 80, 443/tcp | `/etc/nginx/sites-available/call.l8.nz` | `nginx/call.l8.nz.conf` |
| coturn (STUN/TURN) | 3478, 5349 tcp/udp · 49152–65535/udp | `/etc/turnserver.conf` | `coturn/turnserver.conf` |
| sc-server | 127.0.0.1:8080 (solo local) | `/etc/kalloq/` | `server/` (fase 1) |
| Let's Encrypt | — | `/etc/letsencrypt/` | `letsencrypt/renewal-hooks/deploy/kalloq.sh` |

Secretos (nunca en el repo):
- `/etc/kalloq/turn_secret`: secreto compartido entre coturn y sc-server para las credenciales TURN temporales.

## Instalación desde cero

```bash
apt-get install -y nginx certbot python3-certbot-nginx coturn ufw git build-essential cmake ninja-build pkg-config \
  libboost-dev libboost-system-dev libsodium-dev libsqlite3-dev nlohmann-json3-dev libspdlog-dev libcurl4-openssl-dev libssl-dev

# Firewall
ufw default deny incoming && ufw allow 22/tcp && ufw allow 80/tcp && ufw allow 443/tcp
ufw allow 3478 && ufw allow 5349 && ufw allow 49152:65535/udp && ufw enable

# Web + certificado
mkdir -p /var/www/letsencrypt /var/www/call.l8.nz
install -m 644 www/invite.html /var/www/call.l8.nz/
rm -f /etc/nginx/sites-enabled/default
install -m 644 nginx/acme-only.conf /etc/nginx/sites-available/call.l8.nz
ln -sf /etc/nginx/sites-available/call.l8.nz /etc/nginx/sites-enabled/
systemctl reload nginx
certbot certonly --webroot -w /var/www/letsencrypt -d call.l8.nz --agree-tos --register-unsafely-without-email
install -m 644 nginx/call.l8.nz.conf /etc/nginx/sites-available/call.l8.nz
install -m 755 letsencrypt/renewal-hooks/deploy/kalloq.sh /etc/letsencrypt/renewal-hooks/deploy/

# coturn
install -d -m 700 /etc/kalloq
(umask 077; openssl rand -hex 32 > /etc/kalloq/turn_secret)
sed "s/@TURN_SECRET@/$(cat /etc/kalloq/turn_secret)/" coturn/turnserver.conf > /etc/turnserver.conf
chown root:turnserver /etc/turnserver.conf && chmod 640 /etc/turnserver.conf
/etc/letsencrypt/renewal-hooks/deploy/kalloq.sh   # copia certificados a coturn y recarga
```

El hook de renovación copia el certificado a `/etc/coturn/certs/` (coturn no puede leer `/etc/letsencrypt/live`) y recarga nginx y coturn. `certbot.timer` renueva solo.

## Credenciales TURN temporales (formato `use-auth-secret`)

```
username = "<unix_expiry>:<user_id>"
password = base64(HMAC-SHA1(turn_secret, username))
```
