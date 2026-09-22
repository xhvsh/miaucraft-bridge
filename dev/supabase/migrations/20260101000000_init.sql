-- Local test schema for Miaucraft.
--
-- Reconstructed from the website + bridge plugin contract so the plugin can be
-- exercised end-to-end without touching production. This is NOT the production
-- schema verbatim: RLS here is intentionally permissive (local dev only).
-- When the real prod dump is available, reconcile/replace this migration.

-- =====================================================================
-- players / live state
-- =====================================================================

create table if not exists public.players (
  id                    uuid primary key,
  username              text not null,
  online                boolean not null default false,
  last_seen             timestamptz,
  afk                   boolean not null default false,
  last_moved            timestamptz,
  live_tracking_enabled boolean not null default true,
  hidden                boolean not null default false
);

create table if not exists public.player_stats (
  player_id   uuid not null references public.players (id) on delete cascade,
  stat_key    text not null,
  stat_value  bigint not null default 0,
  updated_at  timestamptz not null default now(),
  primary key (player_id, stat_key)
);
create index if not exists player_stats_stat_key_value_idx on public.player_stats (stat_key, stat_value desc);
create index if not exists player_stats_updated_at_idx on public.player_stats (updated_at desc);

create table if not exists public.live_positions (
  player_id   uuid primary key references public.players (id) on delete cascade,
  x           double precision not null,
  y           double precision not null,
  z           double precision not null,
  dimension   text not null,
  updated_at  timestamptz not null default now()
);

-- =====================================================================
-- achievements
-- =====================================================================

create table if not exists public.achievements (
  key            text primary key,
  title          text,
  description    text,
  frame          text,
  hidden         boolean not null default false,
  icon           text,
  total_criteria integer not null default 0,
  min_criteria   integer
);

create table if not exists public.achievement_criteria (
  achievement_key text not null references public.achievements (key) on delete cascade,
  criterion_key   text not null,
  primary key (achievement_key, criterion_key)
);

create table if not exists public.player_achievements (
  player_id       uuid not null references public.players (id) on delete cascade,
  achievement_key text not null,
  completed       boolean not null default false,
  criteria_done   integer not null default 0,
  criteria_total  integer not null default 0,
  completed_at    timestamptz,
  updated_at      timestamptz not null default now(),
  primary key (player_id, achievement_key)
);

create table if not exists public.player_achievement_criteria (
  player_id       uuid not null references public.players (id) on delete cascade,
  achievement_key text not null,
  criterion_key   text not null,
  done            boolean not null default false,
  awarded_at      timestamptz,
  updated_at      timestamptz not null default now(),
  primary key (player_id, achievement_key, criterion_key)
);

-- =====================================================================
-- server status
-- =====================================================================

create table if not exists public.server_status_public (
  id          integer primary key,
  tps_1m      double precision,
  tps_5m      double precision,
  tps_15m     double precision,
  days        bigint,
  started_at  timestamptz,
  updated_at  timestamptz not null default now()
);

-- =====================================================================
-- whitelist
-- =====================================================================

create table if not exists public.whitelist (
  id         uuid primary key,
  username   text not null,
  synced_at  timestamptz not null default now()
);

create table if not exists public.whitelist_commands (
  id            uuid primary key default gen_random_uuid(),
  action        text not null check (action in ('add', 'remove')),
  username      text not null,
  requested_by  uuid,
  status        text not null default 'pending' check (status in ('pending', 'done', 'failed')),
  requested_at  timestamptz not null default now(),
  processed_at  timestamptz
);

-- =====================================================================
-- chat
-- =====================================================================

create table if not exists public.chat_messages (
  id          uuid primary key default gen_random_uuid(),
  kind        text not null check (kind in ('web', 'server', 'system')),
  username    text,
  user_id     uuid references auth.users (id) on delete set null,
  message     text not null,
  created_at  timestamptz not null default now()
);
create index if not exists chat_messages_created_at_idx on public.chat_messages (created_at desc);

-- =====================================================================
-- profiles (website accounts)
-- =====================================================================

create table if not exists public.profiles (
  id               uuid primary key references auth.users (id) on delete cascade,
  username         text,
  role             text not null default 'member',
  discord_username text,
  created_at       timestamptz not null default now()
);

-- =====================================================================
-- grants (local dev: permissive)
-- =====================================================================

grant usage on schema public to anon, authenticated, service_role;
grant select on all tables in schema public to anon, authenticated;
grant insert, update, delete on all tables in schema public to authenticated;
grant all on all tables in schema public to service_role;

-- =====================================================================
-- RLS (local dev: read for everyone, writes for authenticated; the plugin
-- uses the service-role key which bypasses RLS entirely)
-- =====================================================================

alter table public.players                    enable row level security;
alter table public.player_stats               enable row level security;
alter table public.live_positions             enable row level security;
alter table public.achievements               enable row level security;
alter table public.achievement_criteria       enable row level security;
alter table public.player_achievements        enable row level security;
alter table public.player_achievement_criteria enable row level security;
alter table public.server_status_public       enable row level security;
alter table public.whitelist                  enable row level security;
alter table public.whitelist_commands         enable row level security;
alter table public.chat_messages              enable row level security;
alter table public.profiles                   enable row level security;

do $$
declare
  t text;
begin
  foreach t in array array[
    'players', 'player_stats', 'live_positions', 'achievements',
    'achievement_criteria', 'player_achievements', 'player_achievement_criteria',
    'server_status_public', 'whitelist', 'whitelist_commands', 'chat_messages',
    'profiles'
  ]
  loop
    execute format('drop policy if exists %I_read on public.%I', t, t);
    execute format('create policy %I_read on public.%I for select to anon, authenticated using (true)', t, t);
  end loop;
end $$;

-- website writes
drop policy if exists chat_messages_insert_web on public.chat_messages;
create policy chat_messages_insert_web on public.chat_messages
  for insert to authenticated with check (kind in ('web', 'system'));

drop policy if exists whitelist_commands_insert on public.whitelist_commands;
create policy whitelist_commands_insert on public.whitelist_commands
  for insert to authenticated with check (true);

drop policy if exists whitelist_commands_delete on public.whitelist_commands;
create policy whitelist_commands_delete on public.whitelist_commands
  for delete to authenticated using (true);

drop policy if exists players_update_authed on public.players;
create policy players_update_authed on public.players
  for update to authenticated using (true) with check (true);

drop policy if exists profiles_update_own on public.profiles;
create policy profiles_update_own on public.profiles
  for update to authenticated using (auth.uid() = id) with check (auth.uid() = id);

-- =====================================================================
-- realtime
-- =====================================================================

do $$
declare
  t text;
begin
  if exists (select 1 from pg_publication where pubname = 'supabase_realtime') then
    foreach t in array array[
      'players', 'live_positions', 'server_status_public',
      'whitelist', 'whitelist_commands', 'chat_messages'
    ]
    loop
      begin
        execute format('alter publication supabase_realtime add table public.%I', t);
      exception when duplicate_object then null;
      end;
    end loop;
  end if;
end $$;

alter table public.players              replica identity full;
alter table public.live_positions       replica identity full;
alter table public.server_status_public replica identity full;
alter table public.whitelist            replica identity full;
alter table public.whitelist_commands   replica identity full;
alter table public.chat_messages        replica identity full;

-- =====================================================================
-- RPCs used by the website
-- =====================================================================

create or replace function public.list_account_usernames()
returns setof text
language sql stable security definer set search_path = public
as $$
  select username from public.profiles where username is not null;
$$;

create or replace function public.list_stat_keys()
returns setof text
language sql stable
as $$
  select distinct stat_key from public.player_stats order by stat_key;
$$;

create or replace function public.distance_leaderboard(limit_count integer default 10)
returns table (stat_value bigint, username text)
language sql stable
as $$
  select sum(ps.stat_value)::bigint as stat_value, p.username
  from public.player_stats ps
  join public.players p on p.id = ps.player_id
  where p.hidden = false
    and ps.stat_key like '%\_ONE\_CM' escape '\'
  group by p.username
  order by stat_value desc
  limit limit_count;
$$;

create or replace function public.get_top1_summary()
returns table (username text, top1 text)
language sql stable
as $$
  with ranked as (
    select ps.stat_key, p.username,
           row_number() over (partition by ps.stat_key order by ps.stat_value desc) as rn
    from public.player_stats ps
    join public.players p on p.id = ps.player_id
    where p.hidden = false and ps.stat_value > 0
  )
  select username, string_agg(stat_key, ',') as top1
  from ranked
  where rn = 1
  group by username;
$$;

create or replace function public.get_top3_summary()
returns table (username text, top1 text, top2 text, top3 text)
language sql stable
as $$
  with ranked as (
    select ps.stat_key, p.username,
           row_number() over (partition by ps.stat_key order by ps.stat_value desc) as rn
    from public.player_stats ps
    join public.players p on p.id = ps.player_id
    where p.hidden = false and ps.stat_value > 0
  ),
  agg as (
    select username,
           string_agg(case when rn = 1 then stat_key end, ',') as top1,
           string_agg(case when rn = 2 then stat_key end, ',') as top2,
           string_agg(case when rn = 3 then stat_key end, ',') as top3
    from ranked
    where rn <= 3
    group by username
  )
  select * from agg;
$$;

-- ensure the single status row exists
insert into public.server_status_public (id)
values (1)
on conflict (id) do nothing;
