-- LOCAL SAMPLE DATA - never applied to production.
--
-- This file lives outside migrations/ on purpose. The Supabase CLI runs it
-- only for `supabase db reset` (local stack), and never for `supabase db push`.
-- It used to sit in migrations/ as 20260101000100_seed.sql, which meant a db
-- push would have written a fake TestPlayer, fake stats and a fake live
-- position into production and overwritten the real server_status_public row.
--
-- It inserts a throwaway TestPlayer, some sample stats, a sample position and
-- a sample system message so the local site has something to render.

insert into public.players (id, username, online, last_seen, live_tracking_enabled, hidden)
values ('00000000-0000-0000-0000-000000000001', 'TestPlayer', true, now(), true, false)
on conflict (id) do nothing;

insert into public.player_stats (player_id, stat_key, stat_value, updated_at) values
  ('00000000-0000-0000-0000-000000000001', 'WALK_ONE_CM', 123456, now()),
  ('00000000-0000-0000-0000-000000000001', 'SPRINT_ONE_CM', 654321, now()),
  ('00000000-0000-0000-0000-000000000001', 'MINE_BLOCK:STONE', 4096, now()),
  ('00000000-0000-0000-0000-000000000001', 'KILL_ENTITY:ZOMBIE', 42, now()),
  ('00000000-0000-0000-0000-000000000001', 'BLOCKS_MINED_TOTAL', 4096, now()),
  ('00000000-0000-0000-0000-000000000001', 'MOB_KILLS_TOTAL', 42, now())
on conflict (player_id, stat_key) do update
  set stat_value = excluded.stat_value, updated_at = now();

insert into public.live_positions (player_id, x, y, z, dimension, updated_at)
values ('00000000-0000-0000-0000-000000000001', 100.5, 64, -200.25, 'overworld', now())
on conflict (player_id) do update
  set x = excluded.x, y = excluded.y, z = excluded.z,
      dimension = excluded.dimension, updated_at = now();

update public.server_status_public
set tps_1m = 20, tps_5m = 19.98, tps_15m = 19.95,
    days = 12, started_at = now() - interval '3 days', updated_at = now()
where id = 1;

insert into public.chat_messages (kind, message)
values ('system', 'Server came online');

-- Bridge operations: enough rows for the local /bridge page to render every
-- panel without a Minecraft server running - a status snapshot, a mixed event
-- feed, console output to copy, and one command in each terminal state.

insert into public.bridge_status (
  id, instance_id, plugin_version, server_version, online,
  started_at, updated_at, remote_config_version, sinks, counters)
values (
  1,
  '00000000-0000-0000-0000-00000000cafe',
'2.4.8',
  '1.21.11-R0.1-SNAPSHOT',
  true,
  now() - interval '2 days 4 hours',
  now(),
  3,
  jsonb_build_object(
    'players', jsonb_build_object('pending', 0),
    'player_stats', jsonb_build_object('pending', 4),
    'player_achievements', jsonb_build_object('pending', 1),
    'player_achievement_criteria', jsonb_build_object('pending', 0),
    'achievements', jsonb_build_object('pending', 0),
    'achievement_criteria', jsonb_build_object('pending', 0),
    'live_positions', jsonb_build_object('pending', 0),
    'chat_messages', jsonb_build_object('pending', 2),
    'whitelist', jsonb_build_object('pending', 0),
    'server_status_public', jsonb_build_object('pending', 0),
    'server_tps_samples', jsonb_build_object('pending', 1),
    'bridge_events', jsonb_build_object('pending', 0),
    'bridge_console', jsonb_build_object('pending', 0,
      'last_error', 'chat_messages: HTTP 401 - permission denied for table chat_messages')
  ),
  jsonb_build_object(
    'chat_relayed', 128,
    'whitelist_mirror_ms_ago', 42000,
    'achievement_scan_ms_ago', 31000,
    'stats_reconcile_running', false,
    'stats_last_players', 3,
    'stats_last_changed', 12,
    'console_suppressed', 4
  )
)
on conflict (id) do update
  set instance_id = excluded.instance_id,
      plugin_version = excluded.plugin_version,
      server_version = excluded.server_version,
      online = excluded.online,
      started_at = excluded.started_at,
      updated_at = excluded.updated_at,
      remote_config_version = excluded.remote_config_version,
      sinks = excluded.sinks,
      counters = excluded.counters;

insert into public.bridge_events (created_at, level, category, event, message, details, plugin_version, instance_id) values
  (now() - interval '2 days 4 hours', 'info', 'lifecycle', 'server.online', 'Plugin enabled', '{}'::jsonb, '2.4.8', '00000000-0000-0000-0000-00000000cafe'),
  (now() - interval '2 days 3 hours', 'info', 'config', 'config.applied', 'Remote config v3 applied (github)', jsonb_build_object('version', 3, 'source', 'github'), '2.4.8', '00000000-0000-0000-0000-00000000cafe'),
  (now() - interval '50 minutes', 'info', 'update', 'update.staged', 'staged v2.4.8 (284913 bytes - sha 91f0ac2d)', jsonb_build_object('version', '2.4.8'), '2.4.8', '00000000-0000-0000-0000-00000000cafe'),
  (now() - interval '5 minutes', 'error', 'command', 'command.failed', 'stats.reconcile failed', jsonb_build_object('command_id', 3), '2.4.8', '00000000-0000-0000-0000-00000000cafe'),
  (now() - interval '4 minutes', 'warn', 'collector', 'collector.summary', '12 queued row(s), 1 sink(s) failing', jsonb_build_object('players_online', 3, 'chat_relayed', 128, 'rows_queued', 12, 'failing_sinks', jsonb_build_object('bridge_console', 'chat_messages: HTTP 401')), '2.4.8', '00000000-0000-0000-0000-00000000cafe'),
  (now() - interval '1 minute', 'info', 'collector', 'collector.summary', '8 queued row(s), all sinks healthy', jsonb_build_object('players_online', 2, 'chat_relayed', 6, 'rows_queued', 8), '2.4.8', '00000000-0000-0000-0000-00000000cafe');

insert into public.bridge_console (created_at, level, logger, message, plugin_version, instance_id) values
  (now() - interval '2 days 4 hours', 'info', 'MiaucraftBridge', 'Enabled - remote config from https://raw.githubusercontent.com/xhvsh/miaucraft-bridge/main/remote-config.json', '2.4.8', '00000000-0000-0000-0000-00000000cafe'),
  (now() - interval '49 minutes', 'info', 'MiaucraftBridge', 'update staged v2.4.8 (284913 bytes - sha 91f0ac2d) - run /bridge update apply to install', '2.4.8', '00000000-0000-0000-0000-00000000cafe'),
  (now() - interval '5 minutes', 'error', 'MiaucraftBridge', 'chat_messages: HTTP 401 - permission denied for table chat_messages', '2.4.8', '00000000-0000-0000-0000-00000000cafe'),
  (now() - interval '30 seconds', 'info', 'MiaucraftBridge', 'Remote config v3 applied (github).', '2.4.8', '00000000-0000-0000-0000-00000000cafe');

insert into public.bridge_commands (
  id, created_at, requested_at, command, status, requested_by_username,
  claimed_at, claimed_by, processed_at, result, error)
values
  (1, now() - interval '30 minutes', now() - interval '30 minutes', 'update.check', 'done',
   'xhvsh', now() - interval '30 minutes', '00000000-0000-0000-0000-00000000cafe', now() - interval '30 minutes',
   '[MiaucraftBridge] Checking for updates...
[MiaucraftBridge] staged v2.4.8 (284913 bytes - sha 91f0ac2d)', null),
  (2, now() - interval '25 minutes', now() - interval '25 minutes', 'update.apply', 'done',
   'xhvsh', now() - interval '25 minutes', '00000000-0000-0000-0000-00000000cafe', now() - interval '25 minutes',
   '[MiaucraftBridge] Installed v2.4.8 (sha 91f0ac2d) - the server will restart now.', null),
  (3, now() - interval '5 minutes', now() - interval '5 minutes', 'stats.reconcile', 'failed',
   'xhvsh', now() - interval '5 minutes', '00000000-0000-0000-0000-00000000cafe', now() - interval '5 minutes',
   '[MiaucraftBridge] Reconciling stats for online players...', 'A stat reconcile is already running.');

select setval(pg_get_serial_sequence('public.bridge_commands', 'id'), 100);
