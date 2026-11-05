-- pgecs base schema, version 4: validation rules (docs/pgecs.md, "Validation rules").
--
-- A component's `validate { }` rules are rendered by the query compiler to boolean expressions over
-- the column `value` and stored here by freeze, like the JSON Schema. Every write of a value checks
-- them and false rejects; rendered rules are two-valued like where { } predicates, so a value passes
-- exactly when `where { rule }` matches it.
-- The catalog is written only by freeze (ecs_catalog_guard), so the stored SQL is the registry's.

alter table ecs_component_type add column rules jsonb not null default '[]'
  check (jsonb_typeof(rules) = 'array');

create function ecs_component_rules() returns trigger language plpgsql as $$
declare
  r jsonb;
  ok boolean;
begin
  for r in
    select jsonb_array_elements(t.rules) from ecs_component_type t where t.name = new.rel and t.rules <> '[]'
  loop
    execute 'select (' || (r->>'sql') || ') from (select $1 as value) v' into ok using new.value;
    if ok is false then
      raise exception 'pgecs: % does not hold for %', r->>'description', new.rel
        using errcode = 'EC011', detail = left(new.value::text, 1000);
    end if;
  end loop;
  return new;
end
$$;

-- after ecs_component_10_check, so rows of unknown or inactive components fail there first
create trigger ecs_component_11_rules before insert or update of value on ecs_component
  for each row when (new.value is not null) execute function ecs_component_rules();
