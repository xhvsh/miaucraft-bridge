-- TPS history for the server page chart.
-- The plugin appends a sample every few seconds; the page pulls a bucketed
-- series through get_tps_series so payloads stay small regardless of range.

create table if not exists public.server_tps_samples (
  id bigint generated always as identity primary key,
  sampled_at timestamptz not null default now(),
  tps double precision not null,
  players_online integer not null default 0
);

create index if not exists server_tps_samples_sampled_at_idx
  on public.server_tps_samples (sampled_at desc);

alter table public.server_tps_samples enable row level security;

drop policy if exists "server_tps_samples select" on public.server_tps_samples;
create policy "server_tps_samples select"
  on public.server_tps_samples for select
  using (true);

grant select on public.server_tps_samples to anon, authenticated;
grant select, insert, update, delete on public.server_tps_samples to service_role;

-- Aggregated, bucketed series for charting. Bucket width is passed in seconds
-- (10 for Last hour ~= 360 rows, 300 for Last day ~= 288 rows) so the result
-- fits comfortably in one REST response. Buckets align to a fixed epoch.
create or replace function public.get_tps_series(p_hours int, p_bucket_seconds int)
returns table (bucket timestamptz, tps numeric, players_online numeric)
language sql
stable
set search_path = public
as $$
  select
    date_bin(make_interval(secs => p_bucket_seconds), sampled_at, '2001-01-01 00:00:00+00') as bucket,
    round(avg(tps)::numeric, 1) as tps,
    round(avg(players_online)::numeric, 1) as players_online
  from public.server_tps_samples
  where sampled_at > now() - p_hours * interval '1 hour'
  group by 1
  order by 1;
$$;

grant execute on function public.get_tps_series(int, int) to anon, authenticated, service_role;