-- pgecs base schema, version 3: the transactional outbox (docs/pgecs.md, "Processes, endpoints and
-- outbox routing").
--
-- An entry is an entity with pgecs:outbox:v1 = {capability, payload, ...} and at most one
-- (pgecs:outbox-target, <endpoint>). Checked at commit, when both rows are in place:
--   * the capability is a registered capability and the payload matches its JSON Schema;
--   * a targeted endpoint has (pgecs:can, <capability>), so a misrouted entry is never committed.
-- Inserting an entry notifies channel pgecs_outbox with the capability name (delivered after commit).

create function ecs_outbox_check() returns trigger language plpgsql as $$
declare
  entry jsonb;
  cap ecs_component_type;
  endpoint uuid;
begin
  select value into entry from ecs_component where entity = new.entity and rel = 'pgecs:outbox:v1';
  if not found then
    if new.rel = 'pgecs:outbox-target' then
      raise exception 'pgecs: (pgecs:outbox-target, %) on % without an outbox entry', new.target, new.entity
        using errcode = 'EC010';
    end if;
    return null; -- the entry was deleted later in this transaction
  end if;
  select * into cap from ecs_component_type where name = entry->>'capability' and kind = 'capability';
  if not found then
    raise exception 'pgecs: outbox entry % names %, which is not a registered capability', new.entity, entry->>'capability'
      using errcode = 'EC010';
  end if;
  if cap.json_schema is not null and not jsonb_matches_schema(cap.json_schema::json, coalesce(entry->'payload', 'null'::jsonb)) then
    raise exception 'pgecs: payload of outbox entry % does not match the JSON Schema of %', new.entity, cap.name
      using errcode = 'EC005', detail = left((entry->'payload')::text, 1000);
  end if;
  select target into endpoint from ecs_component where entity = new.entity and rel = 'pgecs:outbox-target';
  if found and not exists (
       select 1 from ecs_component where entity = endpoint and rel = 'pgecs:can' and target = cap.entity) then
    raise exception 'pgecs: outbox entry % is targeted at endpoint %, which cannot %', new.entity, endpoint, cap.name
      using errcode = 'EC010';
  end if;
  return null;
end
$$;

create constraint trigger ecs_component_30_outbox_check after insert or update on ecs_component
  deferrable initially deferred
  for each row when (new.rel in ('pgecs:outbox:v1', 'pgecs:outbox-target'))
  execute function ecs_outbox_check();

create function ecs_outbox_notify() returns trigger language plpgsql as $$
begin
  perform pg_notify('pgecs_outbox', new.value->>'capability');
  return null;
end
$$;

create trigger ecs_component_31_outbox_notify after insert on ecs_component
  for each row when (new.rel = 'pgecs:outbox:v1')
  execute function ecs_outbox_notify();
