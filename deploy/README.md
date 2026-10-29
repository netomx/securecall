# Despliegue — call.l8.nz

Servidor: `72.11.156.223` (IPv4 únicamente, sin registro AAAA), **Ubuntu 24.04 LTS**, 1 vCPU, 1 GB RAM + 2 GB swap.

| Servicio | Puertos | Config en el servidor | Fuente en el repo |
|---|---|---|---|
| nginx (TLS → sc-server) | 80, 443/tcp | `/etc/nginx/sites-available/call.l8.nz` | `nginx/call.l8.nz.conf` |
| coturn (STUN/TURN) | 3478, 5349 tcp/udp · 49152–65535/udp | `/etc/turnserver.conf` | `coturn/turnserver.conf` |
| sc-server | 127.0.0.1:8080 (solo local) | `/etc/kalloq/sc-server.conf`, BD en `/var/lib/kalloq/` | `../apps/server/`, `sc-server.conf`, `systemd/sc-server.service` |
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
install -m 644 www/invite.html www/privacy.html /var/www/call.l8.nz/   # privacy.html: https://call.l8.nz/privacy (Google Play)
install -D -m 644 www/.well-known/assetlinks.json /var/www/call.l8.nz/.well-known/assetlinks.json  # huella de la llave release
install -D -m 644 www/.well-known/apple-app-site-association /var/www/call.l8.nz/.well-known/apple-app-site-association  # Universal Links de iOS
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

## sc-server

```bash
# Compilar (en el servidor)
cmake -S apps/server -B apps/server/build -G Ninja -DCMAKE_BUILD_TYPE=Release
cmake --build apps/server/build

# Pruebas de integración (instancia temporal con rate_limits = false; ver el script)
apt-get install -y python3-websockets
python3 apps/server/tests/test_protocol.py ws://127.0.0.1:18080/ws

# Instalar
useradd --system --no-create-home --home-dir /var/lib/kalloq --shell /usr/sbin/nologin kalloq
chown root:kalloq /etc/kalloq/turn_secret && chmod 640 /etc/kalloq/turn_secret
install -m 644 deploy/sc-server.conf /etc/kalloq/sc-server.conf
install -m 644 deploy/systemd/sc-server.service /etc/systemd/system/
install -m 755 apps/server/build/sc-server /usr/local/bin/sc-server
systemctl daemon-reload && systemctl enable --now sc-server
```

El servicio corre como `kalloq` con aislamiento de systemd (`systemd-analyze security sc-server` → 1.7 OK).
La base de datos SQLite vive en `/var/lib/kalloq/kalloq.db` (modo 600).

## Respaldos

Copia diaria consistente (`sqlite3 .backup`, verificada con `integrity_check`) en
`/var/backups/kalloq/`, 14 días. Conviene copiarlos también fuera del servidor.

```bash
apt-get install -y sqlite3
install -m 700 deploy/backup/kalloq-backup.sh /usr/local/sbin/kalloq-backup
install -m 644 deploy/systemd/kalloq-backup.service deploy/systemd/kalloq-backup.timer /etc/systemd/system/
systemctl daemon-reload && systemctl enable --now kalloq-backup.timer
```

Restaurar: `systemctl stop sc-server`, `gunzip -c respaldo.db.gz > /var/lib/kalloq/kalloq.db`,
`chown kalloq:kalloq` y `chmod 600`, borrar `kalloq.db-wal`/`-shm`, `systemctl start sc-server`.

## Push (Firebase Cloud Messaging)

Sin esto el servidor funciona igual, pero las llamadas solo llegan a teléfonos con la app viva.

1. En [console.firebase.google.com](https://console.firebase.google.com) crear un proyecto (sin Analytics)
   y agregarle una app Android con el paquete `com.cim.securecall`.
2. Descargar `google-services.json` y ponerlo en `apps/android/app/` (está en `.gitignore`); la
   compilación lo lee sola.
3. Configuración del proyecto → Cuentas de servicio → "Generar nueva clave privada". Ese JSON es
   una credencial: copiarlo al servidor y protegerlo:

```bash
install -m 640 -o root -g kalloq fcm.json /etc/kalloq/fcm.json
systemctl restart sc-server   # el log dice "push enabled (Firebase project ...)"
```

## Push para iPhone (APNs)

Sin esto el iPhone solo recibe llamadas con la app abierta. Hace falta la membresía de pago del
Apple Developer Program: con un equipo personal gratuito no aparecen las llaves de APNs.

1. En [developer.apple.com/account](https://developer.apple.com/account) → **Certificates, Identifiers
   & Profiles** → **Keys** → **+**. Darle un nombre (p. ej. "Kalloq APNs"), marcar **Apple Push
   Notifications service (APNs)**, en *Configure* elegir **Sandbox & Production**, y **Register**.
2. **Download** baja `AuthKey_XXXXXXXXXX.p8`. Apple deja bajarlo **una sola vez**. Las 10 letras del
   nombre son el **Key ID**, que también aparece en la página de la llave.
3. En **Identifiers**, el App ID `com.cim.securecall` necesita **Push Notifications** activado (Xcode
   lo crea al compilar con firma automática; si no, se crea ahí).
4. El `.p8` es una credencial: copiarlo directo al servidor, nunca al repo:

```bash
install -m 640 -o root -g kalloq AuthKey_XXXXXXXXXX.p8 /etc/kalloq/apns.p8
# en /etc/kalloq/sc-server.conf:
#   apns_key_file = /etc/kalloq/apns.p8
#   apns_key_id = XXXXXXXXXX
#   apns_team_id = <Team ID de la cuenta, en developer.apple.com/account → Membership>
systemctl restart sc-server   # el log dice "APNs enabled (team ..., key ..., topic com.cim.securecall.voip)"
```

Una misma llave sirve para producción y sandbox. La app avisa cuál le toca en `set_push`:
`apns_sandbox` en compilaciones de desarrollo (las de Xcode) y `apns` en TestFlight y App Store.

## Credenciales TURN temporales (formato `use-auth-secret`)

```
username = "<unix_expiry>:<12 caracteres aleatorios>"   # no revela el usuario a los logs de coturn
password = base64(HMAC-SHA1(turn_secret, username))
```
