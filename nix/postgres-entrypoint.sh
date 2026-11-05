#!/bin/bash
# Entrypoint of the PostgreSQL image (flake.nix: postgres-image). No passwords anywhere: who may connect
# as which role is decided by where the connection comes from (pg_hba.conf, below). On first start it
# creates the cluster, the database POSTGRES_DB (default blocks) and the roles:
#
#   blocks       core network (backend, Fabric servers): full access through pgecs
#   blocks_edge  edge proxies, from the WireGuard edge subnet 10.168.1.0/24
#   blocks_ops   ops peers (admins, CI), from 10.168.2.0/24: read-only
#   postgres     only over the Unix socket inside this container (podman exec), via peer auth
#
# BLOCKS_PG_CORE_NETS: space-separated CIDRs of the core network (default: localhost; in the compose
# network the core services share the `core` container's network namespace and talk over localhost).
# BLOCKS_PG_LISTEN: listen_addresses (default: localhost and the WireGuard hub address 10.168.0.1, never
# `*`). Addresses other than localhost are waited for (the namespace owner brings up wg0) before start.
# The edges' tunnel addresses reach PostgreSQL unchanged (no NAT), so pg_hba can tell them apart.
# Design: https://claude.ai/artifact/WDswfCSv1YSxbGT7HWgtzd ("Postgres without passwords").
set -euo pipefail
: "${POSTGRES_DB:=blocks}" "${BLOCKS_PG_CORE_NETS:=127.0.0.1/32 ::1/128}" "${BLOCKS_PG_LISTEN:=localhost,10.168.0.1}"
export PGDATA=/var/lib/postgresql/data
mkdir -p "$PGDATA" /run/postgresql
chown -R postgres:postgres /var/lib/postgresql /run/postgresql
chmod 700 "$PGDATA"

as_pg() { su-exec postgres "$@"; }

if [ ! -s "$PGDATA/PG_VERSION" ]; then
  as_pg initdb -D "$PGDATA" -U postgres -E UTF8 --no-locale --auth-local=peer --auth-host=reject
  as_pg pg_ctl -D "$PGDATA" -o "-c listen_addresses='' -k /run/postgresql" -w start
  as_pg psql -v ON_ERROR_STOP=1 -h /run/postgresql -U postgres -d postgres <<SQL
create role blocks login;
create role blocks_edge login;
create role blocks_ops login;
create database "$POSTGRES_DB" owner blocks;
\connect "$POSTGRES_DB"
-- extensions pgecs requires; created here because blocks is no superuser
create extension if not exists pg_jsonschema;
grant connect on database "$POSTGRES_DB" to blocks_edge, blocks_ops;
alter default privileges for role blocks grant select on tables to blocks_ops;
SQL
  as_pg pg_ctl -D "$PGDATA" -m fast -w stop
fi

# pg_hba.conf is rewritten on every start from the current settings (first match wins)
{
  echo "local  all     postgres                    peer"
  for net in $BLOCKS_PG_CORE_NETS; do
    echo "host   $POSTGRES_DB  blocks       $net  trust"
  done
  echo "host   $POSTGRES_DB  blocks_edge  10.168.1.0/24  trust"
  echo "host   $POSTGRES_DB  blocks_ops   10.168.2.0/24  trust"
  echo "host   all     all          0.0.0.0/0      reject"
  echo "host   all     all          ::/0           reject"
} > "$PGDATA/pg_hba.conf"
chown postgres:postgres "$PGDATA/pg_hba.conf"

# PostgreSQL starts without an address it cannot bind (as long as another one works): wait for them
for addr in ${BLOCKS_PG_LISTEN//,/ }; do
  case "$addr" in localhost | 127.0.0.1 | *:* | '*') continue ;; esac
  for _ in $(seq 120); do
    [[ $(</proc/net/fib_trie) == *"-- $addr"$'\n'* ]] && break
    sleep 1
  done
done

exec su-exec postgres postgres -D "$PGDATA" -c listen_addresses="$BLOCKS_PG_LISTEN" -k /run/postgresql
