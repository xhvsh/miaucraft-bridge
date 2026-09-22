-- Biome map: one row per generated chunk with its dominant surface biome.
-- The plugin scans the on-disk region files (never loads/generates chunks) and
-- upserts rows here; the website map page renders them as a canvas layer.

create table if not exists public.biomes (
  dimension text not null check (dimension in ('overworld', 'nether', 'end')),
  chunk_x integer not null,
  chunk_z integer not null,
  biome text not null,
  updated_at timestamptz not null default now(),
  primary key (dimension, chunk_x, chunk_z)
);

create index if not exists biomes_box_idx
  on public.biomes (dimension, chunk_x, chunk_z);

alter table public.biomes enable row level security;

drop policy if exists "biomes select" on public.biomes;
create policy "biomes select"
  on public.biomes for select
  using (true);

grant select on public.biomes to anon, authenticated;
grant select, insert, update, delete on public.biomes to service_role;

-- Box query used by the map tiles. Returns one row per stride x stride chunk
-- group (dominant biome), so far-zoom requests stay bounded regardless of how
-- many chunks exist. cell_x/cell_z are floor-aligned group indices: the group
-- covers chunks [cell_x*stride, cell_x*stride + stride) x [cell_z*stride, ...).
create or replace function public.biomes_sample(
  p_dimension text, p_stride int,
  p_min_cx int, p_max_cx int, p_min_cz int, p_max_cz int)
returns table (cell_x int, cell_z int, biome text)
language sql
stable
set search_path = public
as $$
  select distinct on (xb, zb) xb as cell_x, zb as cell_z, biome
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
  order by xb, zb, cnt desc, biome;
$$;

grant execute on function public.biomes_sample(text, int, int, int, int, int)
  to anon, authenticated, service_role;