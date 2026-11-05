-- pgecs base schema, version 1 (docs/pgecs.md, "Postgres schema").
--
-- Applied by SchemaSetup under the pgecs schema advisory lock, in one SERIALIZABLE transaction.
-- Never edit a released migration; add V2__....sql instead. Migrations must stay compatible with
-- older pgecs versions that run against the upgraded schema during a rolling deploy.
--
-- Errors raised here use SQLSTATE class EC (net.blocks168.pgecs.db.SqlStates).

-- Every write in this database is SERIALIZABLE unless a session explicitly asks otherwise, and the
-- triggers below refuse pgecs writes that ask otherwise.
do $$
begin
  execute format('alter database %I set default_transaction_isolation = %L', current_database(), 'serializable');
end
$$;

-- ---------------------------------------------------------------------------------------- tables

-- Every entity, including component, relation and capability types. The nil UUID is a reserved
-- entity meaning "no target".
create table ecs_entity (
  id         uuid primary key default uuidv7(),
  created_at timestamptz not null default now(),
  version    bigint not null default 0   -- bumped by trigger on any component change
);
insert into ecs_entity (id) values ('00000000-0000-0000-0000-000000000000');

-- Catalog. Written only by registry freeze.
create table ecs_component_type (
  name        text primary key check (name ~ '^[^:\s''"\\]+(:[^:\s''"\\]+)+$'),
  entity      uuid not null unique references ecs_entity(id),  -- uuidv5(pgecs namespace, name)
  kind        text not null check (kind in ('tag', 'value', 'relation', 'capability')),
  state       text not null check (state in ('pending', 'active', 'retired')),
  traits      jsonb not null default '{}',  -- exclusive, acyclic, symmetric, oneOf, onDeleteTarget
  json_schema jsonb null,                   -- checked via pg_jsonschema
  fingerprint text not null                 -- per component, compared at freeze
);

-- All component data.
create table ecs_component (
  entity     uuid  not null references ecs_entity(id) on delete cascade,
  rel        text  not null references ecs_component_type(name),
  target     uuid  not null default '00000000-0000-0000-0000-000000000000'
                   references ecs_entity(id) on delete cascade,
  value      jsonb null,
  updated_at timestamptz not null default now(),
  primary key (entity, rel, target)
);

-- Generic indexes: component -> entities, and reverse pair lookups.
create index ecs_c_rel    on ecs_component (rel, target, entity);
create index ecs_c_target on ecs_component (target, rel, entity)
  where target <> '00000000-0000-0000-0000-000000000000';

-- Change log for post-commit observers, written by trigger in the writing transaction. Consumers
-- read rows with xid below their snapshot's xmin, so a late commit is never skipped.
create table ecs_change (
  id        bigint generated always as identity primary key,
  xid       xid8 not null default pg_current_xact_id(),
  at        timestamptz not null default now(),
  entity    uuid not null,
  rel       text not null,
  target    uuid not null,
  op        text not null check (op in ('insert', 'update', 'delete')),
  old_value jsonb null
);
create index ecs_change_rel_xid on ecs_change (rel, xid);
create index ecs_change_xid on ecs_change (xid);

-- Per-component indexes that freeze builds with CREATE INDEX CONCURRENTLY.
create table ecs_schema_index (
  name       text primary key,
  component  text not null references ecs_component_type(name),
  spec       jsonb not null,   -- canonical index declaration, compared at freeze
  ddl        text not null,    -- the CREATE INDEX statement
  state      text not null check (state in ('pending', 'valid', 'failed')),
  error      text null,
  updated_at timestamptz not null default now()
);

-- One row per freeze that changed something.
create table ecs_schema_history (
  id          bigint generated always as identity primary key,
  at          timestamptz not null default now(),
  db_user     text not null default current_user,
  application text not null default current_setting('application_name'),
  changes     jsonb not null
);

-- ---------------------------------------------------------------------------------------- isolation

-- Postgres' serializability guarantee only holds among SERIALIZABLE transactions, and the trigger
-- invariants below rely on it. Refuse every other write, even from a hand-written SQL session.
create function ecs_require_serializable() returns trigger language plpgsql as $$
begin
  if tg_op = 'TRUNCATE' then
    raise exception 'pgecs: TRUNCATE % bypasses its triggers and is not allowed', tg_table_name
      using errcode = 'EC002';
  end if;
  if current_setting('transaction_isolation') <> 'serializable' then
    raise exception 'pgecs: % on % outside a SERIALIZABLE transaction (isolation is %)',
      tg_op, tg_table_name, current_setting('transaction_isolation')
      using errcode = 'EC001';
  end if;
  return null;
end
$$;

create trigger ecs_entity_00_serializable before insert or update or delete or truncate on ecs_entity
  for each statement execute function ecs_require_serializable();
create trigger ecs_component_00_serializable before insert or update or delete or truncate on ecs_component
  for each statement execute function ecs_require_serializable();
create trigger ecs_component_type_00_serializable before insert or update or delete or truncate on ecs_component_type
  for each statement execute function ecs_require_serializable();
create trigger ecs_schema_index_00_serializable before insert or update or delete or truncate on ecs_schema_index
  for each statement execute function ecs_require_serializable();

-- ---------------------------------------------------------------------------------------- catalog

-- The catalog changes only in freeze, which sets the transaction-local pgecs.in_freeze = 'on'. This is a
-- guard against mistakes, not a security boundary.
create function ecs_catalog_guard() returns trigger language plpgsql as $$
begin
  if coalesce(current_setting('pgecs.in_freeze', true), '') <> 'on' then
    raise exception 'pgecs: % is written only by registry freeze', tg_table_name
      using errcode = 'EC009';
  end if;
  return null;
end
$$;

create trigger ecs_component_type_10_guard before insert or update or delete on ecs_component_type
  for each statement execute function ecs_catalog_guard();
create trigger ecs_schema_index_10_guard before insert or update or delete on ecs_schema_index
  for each statement execute function ecs_catalog_guard();

-- ---------------------------------------------------------------------------------------- entities

-- Reserved entities: the nil entity (deleting it would cascade through every plain component) and
-- component type entities are never deleted. Entity ids never change.
create function ecs_entity_protect() returns trigger language plpgsql as $$
declare
  type_name text;
begin
  if tg_op = 'UPDATE' then
    if new.id <> old.id then
      raise exception 'pgecs: entity ids never change (% -> %)', old.id, new.id using errcode = 'EC002';
    end if;
    return new;
  end if;
  if old.id = '00000000-0000-0000-0000-000000000000' then
    raise exception 'pgecs: the nil entity is reserved and cannot be deleted' using errcode = 'EC002';
  end if;
  select name into type_name from ecs_component_type where entity = old.id;
  if found then
    raise exception 'pgecs: entity % is the component type %; retire it in the registry instead', old.id, type_name
      using errcode = 'EC002';
  end if;
  return old;
end
$$;

create trigger ecs_entity_10_protect before update or delete on ecs_entity
  for each row execute function ecs_entity_protect();

-- Cleanup policies for the targets of pairs (flecs OnDeleteTarget). `remove` (the default) is the
-- foreign key cascade on ecs_component.target. `delete` deletes the entities holding the pair, which
-- recurses through this trigger (as for pgecs:child-of). `panic` refuses the delete.
create function ecs_entity_cleanup() returns trigger language plpgsql as $$
declare
  panic_rel text;
begin
  select c.rel into panic_rel
    from ecs_component c join ecs_component_type t on t.name = c.rel
   where c.target = old.id and t.traits->>'onDeleteTarget' = 'panic'
   limit 1;
  if found then
    raise exception 'pgecs: entity % is the target of a % pair, whose relation forbids deleting its targets', old.id, panic_rel
      using errcode = 'EC008';
  end if;
  delete from ecs_entity e
   where e.id in (select c.entity
                    from ecs_component c join ecs_component_type t on t.name = c.rel
                   where c.target = old.id and c.entity <> old.id and t.traits->>'onDeleteTarget' = 'delete');
  return old;
end
$$;

create trigger ecs_entity_20_cleanup before delete on ecs_entity
  for each row execute function ecs_entity_cleanup();

-- ---------------------------------------------------------------------------------------- components

-- Kinds, states, JSON Schemas and the acyclic / oneOf traits.
create function ecs_component_check() returns trigger language plpgsql as $$
declare
  t ecs_component_type;
  nil constant uuid := '00000000-0000-0000-0000-000000000000';
begin
  if tg_op = 'UPDATE' and (new.entity, new.rel, new.target) is distinct from (old.entity, old.rel, old.target) then
    raise exception 'pgecs: the key (entity, rel, target) of a component row never changes; delete and insert instead'
      using errcode = 'EC003';
  end if;
  select * into t from ecs_component_type where name = new.rel;
  if not found then
    raise exception 'pgecs: unknown component %', new.rel using errcode = 'EC003';
  end if;
  if t.state <> 'active' then
    raise exception 'pgecs: component % is %, not active', new.rel, t.state using errcode = 'EC004';
  end if;
  if new.entity = nil then
    raise exception 'pgecs: the nil entity cannot hold components' using errcode = 'EC002';
  end if;

  case t.kind
    when 'tag' then
      if new.target <> nil or new.value is not null then
        raise exception 'pgecs: tag % has neither target nor value', new.rel using errcode = 'EC003';
      end if;
    when 'value' then
      if new.target <> nil then
        raise exception 'pgecs: value component % has no target', new.rel using errcode = 'EC003';
      end if;
      if new.value is null then
        raise exception 'pgecs: value component % needs a value', new.rel using errcode = 'EC003';
      end if;
    when 'relation' then
      if new.target = nil then
        raise exception 'pgecs: relation % needs a target', new.rel using errcode = 'EC003';
      end if;
      if (t.json_schema is null) <> (new.value is null) then
        raise exception 'pgecs: relation % %', new.rel,
          case when t.json_schema is null then 'carries no value' else 'needs a value' end
          using errcode = 'EC003';
      end if;
    else
      raise exception 'pgecs: % is a %, which is not stored as a component', new.rel, t.kind
        using errcode = 'EC003';
  end case;

  if new.value is not null and t.json_schema is not null
     and not jsonb_matches_schema(t.json_schema::json, new.value) then
    raise exception 'pgecs: value of % does not match its JSON Schema', new.rel
      using errcode = 'EC005', detail = left(new.value::text, 1000);
  end if;

  if tg_op = 'INSERT' and t.kind = 'relation' then
    if coalesce((t.traits->>'acyclic')::boolean, false) then
      if new.target = new.entity or exists (
           with recursive up(id) as (
             select c.target from ecs_component c where c.entity = new.target and c.rel = new.rel
             union
             select c.target from ecs_component c join up on c.entity = up.id where c.rel = new.rel
           )
           select 1 from up where up.id = new.entity) then
        raise exception 'pgecs: (%, %) on % would close a cycle', new.rel, new.target, new.entity
          using errcode = 'EC006';
      end if;
    end if;
    if t.traits ? 'oneOf' and not exists (
         select 1 from ecs_component c
          where c.entity = new.target and c.rel = 'pgecs:child-of' and c.target = (t.traits->>'oneOf')::uuid) then
      raise exception 'pgecs: target % of % is not a child of %', new.target, new.rel, t.traits->>'oneOf'
        using errcode = 'EC007';
    end if;
  end if;

  new.updated_at := now();
  return new;
end
$$;

create trigger ecs_component_10_check before insert or update on ecs_component
  for each row execute function ecs_component_check();

-- Symmetric relations: (R, b) on a implies (R, a) on b, added and removed together.
create function ecs_component_symmetric() returns trigger language plpgsql as $$
begin
  if tg_op = 'INSERT' then
    if new.entity <> new.target and coalesce(
         (select (traits->>'symmetric')::boolean from ecs_component_type where name = new.rel), false) then
      insert into ecs_component (entity, rel, target, value)
      values (new.target, new.rel, new.entity, new.value)
      on conflict do nothing;
    end if;
  elsif coalesce((select (traits->>'symmetric')::boolean from ecs_component_type where name = old.rel), false) then
    delete from ecs_component where entity = old.target and rel = old.rel and target = old.entity;
  end if;
  return null;
end
$$;

create trigger ecs_component_20_symmetric_insert after insert on ecs_component
  for each row when (new.target <> '00000000-0000-0000-0000-000000000000')
  execute function ecs_component_symmetric();
create trigger ecs_component_20_symmetric_delete after delete on ecs_component
  for each row when (old.target <> '00000000-0000-0000-0000-000000000000')
  execute function ecs_component_symmetric();

-- Entity version and change log. Every component write bumps its entity's version, so two
-- transactions touching one entity always conflict instead of interleaving. The outbox has its own
-- delivery and stays out of the change log.
create function ecs_component_after_insert() returns trigger language plpgsql as $$
begin
  update ecs_entity e set version = e.version + 1 where e.id in (select entity from new_rows);
  insert into ecs_change (entity, rel, target, op)
  select entity, rel, target, 'insert' from new_rows where rel <> 'pgecs:outbox:v1';
  return null;
end
$$;

create function ecs_component_after_update() returns trigger language plpgsql as $$
begin
  update ecs_entity e set version = e.version + 1 where e.id in (select entity from new_rows);
  insert into ecs_change (entity, rel, target, op, old_value)
  select entity, rel, target, 'update', value from old_rows where rel <> 'pgecs:outbox:v1';
  return null;
end
$$;

create function ecs_component_after_delete() returns trigger language plpgsql as $$
begin
  -- entities deleted by this statement's cascade are gone already and are simply not updated
  update ecs_entity e set version = e.version + 1 where e.id in (select entity from old_rows);
  insert into ecs_change (entity, rel, target, op, old_value)
  select entity, rel, target, 'delete', value from old_rows where rel <> 'pgecs:outbox:v1';
  return null;
end
$$;

create trigger ecs_component_90_after_insert after insert on ecs_component
  referencing new table as new_rows for each statement execute function ecs_component_after_insert();
create trigger ecs_component_90_after_update after update on ecs_component
  referencing old table as old_rows new table as new_rows for each statement execute function ecs_component_after_update();
create trigger ecs_component_90_after_delete after delete on ecs_component
  referencing old table as old_rows for each statement execute function ecs_component_after_delete();
