#!/bin/bash
# Entrypoint of the PostgreSQL image (flake.nix: postgres-image). Like the official image: on first
# start it creates the cluster with POSTGRES_USER / POSTGRES_PASSWORD and the database POSTGRES_DB.
set -euo pipefail
: "${POSTGRES_USER:=postgres}" "${POSTGRES_DB:=$POSTGRES_USER}" "${POSTGRES_PASSWORD:?POSTGRES_PASSWORD is required}"
export PGDATA=/var/lib/postgresql/data
mkdir -p "$PGDATA" /run/postgresql
chown -R postgres:postgres /var/lib/postgresql /run/postgresql
chmod 700 "$PGDATA"

as_pg() { su-exec postgres "$@"; }

if [ ! -s "$PGDATA/PG_VERSION" ]; then
  pwfile=$(mktemp)
  printf '%s\n' "$POSTGRES_PASSWORD" > "$pwfile"
  chown postgres "$pwfile"
  as_pg initdb -D "$PGDATA" -U "$POSTGRES_USER" --pwfile="$pwfile" -E UTF8 \
    --auth-local=trust --auth-host=scram-sha-256
  rm -f "$pwfile"
  echo "host all all all scram-sha-256" >> "$PGDATA/pg_hba.conf"
  as_pg pg_ctl -D "$PGDATA" -o "-c listen_addresses='' -k /run/postgresql" -w start
  if [ "$POSTGRES_DB" != postgres ]; then
    as_pg createdb -h /run/postgresql -U "$POSTGRES_USER" "$POSTGRES_DB"
  fi
  as_pg pg_ctl -D "$PGDATA" -m fast -w stop
fi

exec su-exec postgres postgres -D "$PGDATA" -c listen_addresses='*' -k /run/postgresql
