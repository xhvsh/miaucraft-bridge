-- MiaucraftBridge: production parity fixes.
--
-- Everything here is idempotent, so it is safe to run against a database that
-- already has some of it. Apply with:
--   psql "$DATABASE_URL" -f dev/supabase/migrations/20260101000300_prod_parity.sql
-- or through `supabase db push` (dev/supabase/migrations is the migration dir).
--
-- Fixes, grouped by what was broken:
--   1. service_role could not write player_achievements / player_achievement_
--      criteria, so every achievement insert failed with permission denied.
--   2. server_tps_samples (written by the plugin) does not exist in the
--      production schema, so the TPS sink 404'd forever.
--   3. player_achievement* had no ON DELETE CASCADE, which blocked any
--      deletion of a players row, and account deletion never removed the
--      gameplay data at all.
--   4. player_stats / player_achievements / player_achievement_criteria /
--      live_positions were readable by anon (i.e. the public internet).
--   5. No retention: chat, positions and TPS samples grew forever.

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

-- The plugin's tables have no rows the website should ever read with anon.
revoke select on table public.player_stats from anon;
revoke select on table public.player_achievements from anon;
revoke select on table public.player_achievement_criteria from anon;
revoke select on table public.live_positions from anon;
revoke select on table public.whitelist from anon;

-- ---------------------------------------------------------------------------
-- 2. server_tps_samples (the plugin appends a sample every few seconds)
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
-- 3. Gameplay rows must follow the player (and the account) being deleted
-- ---------------------------------------------------------------------------

alter table public.player_achievements
  drop constraint if exists player_achievements_player_id_fkey;
alter table public.player_achievements
  add constraint player_achievements_player_id_fkey
  foreign key (player_id) references public.players(id) on delete cascade;

alter table public.player_achievement_criteria
  drop constraint if exists player_achievement_criteria_player_id_fkey;
alter table public.player_achievement_criteria
  add constraint player_achievement_criteria_player_id_fkey
  foreign key (player_id) references public.players(id) on delete cascade;

-- Chat is keyed by user_id (set null on delete) but a player's own messages
-- are also identified by username; keep the FK behaviour and add an index so
-- the account cleanup below stays cheap.
create index if not exists chat_messages_username_idx
  on public.chat_messages (username);
create index if not exists live_positions_updated_at_idx
  on public.live_positions (updated_at);
create index if not exists player_achievements_player_id_idx
  on public.player_achievements (player_id);

create or replace function public.delete_account_by_username(_username text) RETURNS jsonb
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO ''
    AS $$
declare
  caller_id   uuid;
  caller_role text;
  target_id   uuid;
  target_player uuid;
begin
  select auth.uid() into caller_id;
  if caller_id is null then
    return jsonb_build_object('error', 'Not authenticated.');
  end if;

  select role into caller_role from public.profiles where id = caller_id;
  if caller_role is null or caller_role <> 'owner' then
    return jsonb_build_object('error', 'Only owners can delete accounts.');
  end if;

  select id into target_id from public.profiles where username = _username;
  if target_id is null then
    return jsonb_build_object('error', format('Account "%s" not found.', _username));
  end if;

  if target_id = caller_id then
    return jsonb_build_object('error', 'You cannot delete your own account here; use Settings -> Delete account.');
  end if;

  delete from public.waypoints where created_by = target_id;
  delete from public.whitelist_commands where requested_by = target_id;
  delete from public.logs where user_id = target_id;
  delete from public.access_codes where used_by = target_id;

  -- Gameplay data is keyed by the Minecraft uuid, which is not the auth id, so
  -- it used to survive the account deletion. The cascade removes the stats,
  -- live position and achievement rows with it.
  select id into target_player from public.players where lower(username) = lower(_username);
  if target_player is not null then
    delete from public.player_achievement_criteria where player_id = target_player;
    delete from public.player_achievements where player_id = target_player;
    delete from public.player_stats where player_id = target_player;
    delete from public.live_positions where player_id = target_player;
    delete from public.players where id = target_player;
  end if;
  delete from public.chat_messages where username = _username;

  delete from public.profiles where id = target_id;
  delete from auth.identities where user_id = target_id;
  delete from auth.users where id = target_id;

  return jsonb_build_object('ok', true);
end;
$$;

grant all on function public.delete_account_by_username(text) to authenticated;
-- SECURITY DEFINER with the default PUBLIC execute would let any role call it
-- (the body re-checks the role, but there is no reason to expose it at all).
revoke all on function public.delete_account_by_username(text) from public;

-- ---------------------------------------------------------------------------
-- 4. Gameplay data is for signed-in users only
--
-- These four tables were readable with the SUPABASE_ANON_KEY, i.e. by anyone
-- who found the project URL: per-player statistics, achievement progress and
-- live coordinates. The catalog (achievements/achievement_criteria) stays
-- world-readable on purpose - the site needs it to render the menu.
--
-- The plugin is unaffected: it writes with the service_role key, which bypasses
-- RLS. Website visitors must be signed in for stats/achievement progress and
-- for the live map.
-- ---------------------------------------------------------------------------

drop policy if exists "player_stats are publicly readable" on public.player_stats;
create policy player_stats_read
  on public.player_stats for select
  to authenticated
  using (true);

drop policy if exists "Public read access" on public.player_achievements;
create policy player_achievements_read
  on public.player_achievements for select
  to authenticated
  using (true);

drop policy if exists "Public read access" on public.player_achievement_criteria;
create policy player_achievement_criteria_read
  on public.player_achievement_criteria for select
  to authenticated
  using (true);

-- Same visibility rule as before (online, non-hidden, tracking-enabled
-- players) but for signed-in users only: live coordinates are gameplay
-- telemetry and were readable with the public anon key.
drop policy if exists live_positions_public_read on public.live_positions;
create policy live_positions_read
  on public.live_positions for select
  to authenticated
  using (EXISTS (
    SELECT 1 FROM public.players p
    WHERE p.id = live_positions.player_id
      AND p.hidden = false
      AND COALESCE(p.live_tracking_enabled, true)));

grant select on table public.player_stats to authenticated;
grant select on table public.player_achievements to authenticated;
grant select on table public.player_achievement_criteria to authenticated;
grant select on table public.live_positions to authenticated;

-- players stays world-readable through its own policy because it is what the
-- public leaderboard reads, but it is restricted to non-hidden players and
-- only exposes the columns the site needs via its view/RPC.

-- ---------------------------------------------------------------------------
-- 5. Whitelist command claiming
-- ---------------------------------------------------------------------------

alter table public.whitelist_commands
  add column if not exists claimed_at timestamptz;
alter table public.whitelist_commands
  add column if not exists claimed_by text;

-- 'processing' is the state a server puts a row in while it runs the console
-- command, so two servers can never apply the same request twice. The old
-- check constraint rejected it.
alter table public.whitelist_commands
  drop constraint if exists whitelist_commands_status_check;
alter table public.whitelist_commands
  add constraint whitelist_commands_status_check
  check (status in ('pending', 'processing', 'done', 'failed'));

create index if not exists whitelist_commands_pending_idx
  on public.whitelist_commands (requested_at)
  where status = 'pending';

-- ---------------------------------------------------------------------------
-- 6. Retention
-- ---------------------------------------------------------------------------

-- chat_messages keeps 30 days, TPS samples 7 days. Live positions are not
-- accumulated (one row per online player) but stale markers from a crashed
-- server are, so anything older than an hour is cleared.
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
    where sampled_at < now() - interval '7 days';
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

-- Schedule it (Supabase enables pg_cron by default):
--   select cron.schedule('bridge-retention', '*/15 * * * *',
--     $$select public.purge_bridge_history()$$);
-- SECURITY DEFINER, so the default PUBLIC execute is revoked: only the
-- scheduler/service role may trigger a purge.
revoke all on function public.purge_bridge_history() from public;
grant execute on function public.purge_bridge_history() to service_role;
