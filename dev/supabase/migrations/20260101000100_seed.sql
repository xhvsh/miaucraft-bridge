-- Seed data for local testing. Delete this file (or run `supabase db reset`)
-- once the production dump is in place.

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
