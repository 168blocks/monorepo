-- Network state shared by the edge Velocity proxies (velocity/network). Run as the database owner
-- (role blocks) by NetworkSchema; the edges (blocks_edge) only get the privileges granted here.
create schema if not exists network;

-- one row per online player (unique by UUID: the newest login wins, older sessions are kicked)
create table network.online_players (
    uuid      uuid primary key,
    name      text        not null,
    edge      text        not null,
    -- backend server (lobby, survival, ...); null while connecting
    server    text,
    -- random per login, so an edge only touches the rows of its own sessions
    session   uuid        not null,
    since     timestamptz not null default now(),
    -- refreshed by the edge every ~10 s; rows older than 30 s count as gone (edge died)
    heartbeat timestamptz not null default now()
);
create index online_players_name on network.online_players (lower(name));
create index online_players_edge on network.online_players (edge);

-- the edges themselves: where players reach them (drain transfers players there) and their state
create table network.edges (
    name      text primary key,
    -- host:port players connect to
    address   text        not null,
    players   integer     not null default 0,
    draining  boolean     not null default false,
    heartbeat timestamptz not null default now()
);

create table network.bans (
    -- lower-case player name; uuid when known
    name       text primary key,
    uuid       uuid,
    reason     text        not null default '',
    created_by text        not null default '',
    created_at timestamptz not null default now()
);
create index bans_uuid on network.bans (uuid);

grant usage on schema network to blocks_edge, blocks_ops;
grant select, insert, update, delete on network.online_players, network.edges, network.bans to blocks_edge;
grant select on network.migrations to blocks_edge;
grant select on all tables in schema network to blocks_ops;
