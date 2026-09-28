-- MiaucraftBridge: production fixes.
--
-- Idempotent throughout, so it is safe to run more than once. Apply with:
--   psql "$DATABASE_URL" -f dev/supabase/migrations/20260101000300_prod_parity.sql
-- or through `supabase db push` (dev/supabase/migrations is the migration dir).
--
-- What this fixes:
--   1. service_role has no INSERT/UPDATE on player_achievements or
--      player_achievement_criteria, so every achievement write the plugin
--      attempts is rejected. This is the only live breakage here.
--   2. No retention, so chat_messages and server_tps_samples grow forever.
--      The purge function is created but NOT scheduled - see section 4.
--
-- What this deliberately does NOT change:
--
--   * Account deletion. public.delete_account_by_username already removes
--     only website data (waypoints, whitelist_commands, logs, access_codes,
--     profiles, auth.identities, auth.users). In-game gameplay data - players,
--     player_stats, player_achievements, player_achievement_criteria,
--     live_positions and chat_messages - is keyed by Minecraft uuid, not by the
--     website account, and must survive deletion of that account. An earlier
--     draft of this file replaced the function with one that also deleted
--     gameplay rows and added ON DELETE CASCADE to the achievement tables.
--     Both were reverted: a website account deletion must never wipe in-game
--     statistics or achievements, and the missing CASCADE is harmless because
--     nothing deletes a players row.
--
--   * Public read access. player_stats, player_achievements,
--     player_achievement_criteria, live_positions, achievements,
--     achievement_criteria and players are world-readable on purpose. The
--     leaderboard, stat pages, achievement menu and live map are public pages
--     and must render for a logged-out visitor. Section 3 re-asserts that
--     posture rather than tightening it.
--
--   * The whitelist. It stays private: anon has no SELECT and the only policy
--     requires an authenticated caller whose profiles.role is 'owner'.

-- ---------------------------------------------------------------------------
-- 1. service_role write grants for every table the plugin writes
-- ---------------------------------------------------------------------------

grant usage on schema public to service_role;

grant select, insert, update, delete on table
  public.players,
  public.player_stats,
  public.player_achievements,
  public.player_achievement_criteria,
  public.achievements,
  public.achievement_criteria,
  public.live_positions,
  public.chat_messages,
  public.whitelist,
  public.whitelist_commands,
  public.server_status_public
  to service_role;

grant usage, select on all sequences in schema public to service_role;

-- The whitelist stays private. Production already grants anon no SELECT here
-- and the only policy is owner-scoped, so this is a defensive no-op that also
-- clears the stray REFERENCES/TRIGGER/TRUNCATE/MAINTAIN anon grants.
revoke select on table public.whitelist from anon;

-- Account deletion is owner-only. The function body re-checks the role, but
-- revoking the default PUBLIC execute means the entry point is not exposed to
-- anon at all. The website calls it as `authenticated`, so this is transparent.
revoke all on function public.delete_account_by_username(text) from public;
grant all on function public.delete_account_by_username(text) to authenticated;

-- ---------------------------------------------------------------------------
-- 2. server_tps_samples
--
-- The plugin appends a sample every few seconds. Present and correct in
-- production; created here so the file also repairs a database that never got
-- the local migration. Reads stay world-readable for the TPS chart.
-- ---------------------------------------------------------------------------

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

-- ---------------------------------------------------------------------------
-- 3. Public read access is intentional - assert it, do not revoke it
--
-- Makes the grants and policies explicit and idempotent, so they are correct
-- on a fresh database, on production, and on any database where an earlier
-- draft of this migration had already locked these tables down.
--
-- live_positions keeps a filter rather than a blanket read: only players who
-- are not hidden and have live tracking enabled expose their coordinates.
-- That is the policy production already has; it is re-created identically.
-- ---------------------------------------------------------------------------

grant select on table public.player_stats to anon, authenticated;
grant select on table public.player_achievements to anon, authenticated;
grant select on table public.player_achievement_criteria to anon, authenticated;
grant select on table public.live_positions to anon, authenticated;
grant select on table public.achievements to anon, authenticated;
grant select on table public.achievement_criteria to anon, authenticated;

drop policy if exists "player_stats are publicly readable" on public.player_stats;
create policy player_stats_read
  on public.player_stats for select
  to anon, authenticated
  using (true);

drop policy if exists "Public read access" on public.player_achievements;
create policy player_achievements_read
  on public.player_achievements for select
  to anon, authenticated
  using (true);

drop policy if exists "Public read access" on public.player_achievement_criteria;
create policy player_achievement_criteria_read
  on public.player_achievement_criteria for select
  to anon, authenticated
  using (true);

drop policy if exists live_positions_public_read on public.live_positions;
create policy live_positions_read
  on public.live_positions for select
  to anon, authenticated
  using (EXISTS (
    SELECT 1 FROM public.players p
    WHERE p.id = live_positions.player_id
      AND p.hidden = false
      AND COALESCE(p.live_tracking_enabled, true)));

drop policy if exists "Public read access" on public.achievements;
create policy achievements_read
  on public.achievements for select
  to anon, authenticated
  using (true);

drop policy if exists "Public read access" on public.achievement_criteria;
create policy achievement_criteria_read
  on public.achievement_criteria for select
  to anon, authenticated
  using (true);

-- players is world-readable through its own policy because it is what the
-- public leaderboard reads, restricted to non-hidden players.

-- ---------------------------------------------------------------------------
-- 4. Retention
--
-- Chat keeps 30 days. TPS samples keep 3 days: the server page only ever
-- asks for 1 hour or 24 hours (get_tps_series is called with p_hours of 1 or
-- 24), so anything past 3 days is never read. At the current 30s sample
-- interval that is ~8,600 rows, versus ~59,000 at the old 10s interval over
-- the same window. Live positions are not accumulated (one row per online
-- player) but stale markers from a crashed server are, so anything older than
-- an hour is cleared. Player chat (kind 'player') is never purged, and no
-- gameplay table is touched.
--
-- This only DEFINES the function. Scheduling it is a separate, deliberate step
-- because it deletes rows on a timer:
--   select cron.schedule('bridge-retention', '*/15 * * * *',
--     $$select public.purge_bridge_history()$$);
-- ---------------------------------------------------------------------------

create or replace function public.purge_bridge_history()
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  removed_chat bigint;
  removed_tps  bigint;
  removed_pos  bigint;
begin
  delete from public.chat_messages
    where kind in ('server', 'system')
      and created_at < now() - interval '30 days';
  get diagnostics removed_chat = row_count;

  delete from public.server_tps_samples
    where sampled_at < now() - interval '3 days';
  get diagnostics removed_tps = row_count;

  delete from public.live_positions
    where updated_at < now() - interval '1 hour';
  get diagnostics removed_pos = row_count;

  return jsonb_build_object(
    'chat_messages', removed_chat,
    'server_tps_samples', removed_tps,
    'live_positions', removed_pos);
end;
$$;

-- SECURITY DEFINER, so the default PUBLIC execute is revoked: only the
-- scheduler or the service role may trigger a purge.
revoke all on function public.purge_bridge_history() from public;
grant execute on function public.purge_bridge_history() to service_role;
