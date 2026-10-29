#!/bin/sh
# Daily consistent copy of the Kalloq database (safe while sc-server runs). Keeps the last 14 days.
# The database holds no call content: accounts, password hashes, contacts, public keys, push tokens.
set -eu
dir=/var/backups/kalloq
install -d -m 700 "$dir"
out="$dir/kalloq-$(date +%Y%m%d).db"
sqlite3 /var/lib/kalloq/kalloq.db ".backup '$out.tmp'"
sqlite3 "$out.tmp" "PRAGMA integrity_check;" | grep -qx ok
chmod 600 "$out.tmp"
mv "$out.tmp" "$out"
gzip -f "$out"
find "$dir" -name 'kalloq-*.db.gz' -mtime +14 -delete
