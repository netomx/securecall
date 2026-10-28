#!/bin/sh
# /etc/letsencrypt/renewal-hooks/deploy/kalloq.sh
# After each certificate renewal: give coturn a readable copy and reload services.
set -e
LIVE=/etc/letsencrypt/live/call.l8.nz
DEST=/etc/coturn/certs

install -d -o turnserver -g turnserver -m 700 "$DEST"
install -o turnserver -g turnserver -m 644 "$LIVE/fullchain.pem" "$DEST/fullchain.pem"
install -o turnserver -g turnserver -m 600 "$LIVE/privkey.pem" "$DEST/privkey.pem"

systemctl reload nginx
systemctl restart coturn
