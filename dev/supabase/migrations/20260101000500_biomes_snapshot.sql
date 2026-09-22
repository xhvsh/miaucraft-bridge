-- Biome map: single-row packed snapshot so the map can fetch a whole viewport
-- in ONE HTTP request. Returns every stride-group in the box as one text[]
-- of "cell_x,cell_z,biome" entries (empty array when the box has no data).
-- Keeps biomes_sample for anything that still wants row-per-cell paging.

create or replace function public.biomes_snapshot(
  p_dimension text,
  p_stride int,
  p_min_cx int,
  p_max_cx int,
  p_min_cz int,
  p_max_cz int
)
returns table (cells text[])
language sql
stable
set search_path = public
as $$
  select coalesce(array_agg(d.cell || ',' || d.biome order by d.cell), array[]::text[]) as cells
  from (
    select distinct on (xb, zb) xb || ',' || zb as cell, biome
    from (
      select floor(chunk_x::float8 / p_stride)::int as xb,
             floor(chunk_z::float8 / p_stride)::int as zb,
             biome,
             count(*) as cnt
      from public.biomes
      where dimension = p_dimension
        and chunk_x >= p_min_cx and chunk_x <= p_max_cx
        and chunk_z >= p_min_cz and chunk_z <= p_max_cz
      group by 1, 2, 3
    ) g
    order by xb, zb, cnt desc, biome
  ) d;
$$;

grant execute on function public.biomes_snapshot(text, int, int, int, int, int)
  to anon, authenticated, service_role;