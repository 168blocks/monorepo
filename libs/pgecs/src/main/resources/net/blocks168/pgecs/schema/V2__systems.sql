-- pgecs base schema, version 2: systems and observers (docs/pgecs.md, "Systems and scheduling").
--
-- The rows of pgecs:system:v1 (leases, cursors) and pgecs:outbox:v1 (claims) change on every run and
-- have their own consumers, so they stay out of the change log; logging them would make every
-- observer poll write rows that the next poll reads.

create or replace function ecs_logged(rel text) returns boolean language sql immutable parallel safe as $$
  select rel not in ('pgecs:outbox:v1', 'pgecs:system:v1')
$$;

create or replace function ecs_component_after_insert() returns trigger language plpgsql as $$
begin
  update ecs_entity e set version = e.version + 1 where e.id in (select entity from new_rows);
  insert into ecs_change (entity, rel, target, op)
  select entity, rel, target, 'insert' from new_rows where ecs_logged(rel);
  return null;
end
$$;

create or replace function ecs_component_after_update() returns trigger language plpgsql as $$
begin
  update ecs_entity e set version = e.version + 1 where e.id in (select entity from new_rows);
  insert into ecs_change (entity, rel, target, op, old_value)
  select entity, rel, target, 'update', value from old_rows where ecs_logged(rel);
  return null;
end
$$;

create or replace function ecs_component_after_delete() returns trigger language plpgsql as $$
begin
  -- entities deleted by this statement's cascade are gone already and are simply not updated
  update ecs_entity e set version = e.version + 1 where e.id in (select entity from old_rows);
  insert into ecs_change (entity, rel, target, op, old_value)
  select entity, rel, target, 'delete', value from old_rows where ecs_logged(rel);
  return null;
end
$$;

-- Observers read the groups (xid, entity) after their cursor.
create index ecs_change_xid_entity on ecs_change (xid, entity, id);
