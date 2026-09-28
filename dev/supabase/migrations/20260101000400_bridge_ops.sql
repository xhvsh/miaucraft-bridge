-- MiaucraftBridge: owner-facing operations surface.
--
-- Idempotent throughout, so it is safe to run more than once. Apply with:
--   psql "$DATABASE_URL" -f dev/supabase/migrations/20260101000400_bridge_ops.sql
-- or through `supabase db push` (dev/supabase/migrations is the migration dir).
--
-- What this adds: four tables that let the bridge plugin publish its own
-- diagnostics to the website, and let an owner run a small fixed set of
-- maintenance actions on the server from the website.
--
--   bridge_status    one row, the server's current health snapshot
--   bridge_events    structured lifecycle / error / summary events
--   bridge_console   the plugin's own log output, short-lived, for testing
--   bridge_commands  the web -> plugin command queue
--
-- What this deliberately does NOT change:
--
--   * Public read access. The leaderboard, stat pages, achievement menu and
--     live map stay world-readable, and live_positions keeps its
--     non-hidden / tracking-enabled filter. Nothing here touches them.
--
--   * The whitelist. It stays private and owner-only, as it already is.
--
--   * Account deletion. No gameplay or website data is deleted here, and
--     bridge_commands rows are removed by the existing account-deletion
--     function only insofar as that function already clears command queues.
--     Nothing in this file touches delete_account_by_username.
--
--   * Server-side command execution. The allowlist below is a fixed set of
--     symbols. There is deliberately no way to submit a console command
--     string, so a compromised owner session cannot reach arbitrary commands.

-- ---------------------------------------------------------------------------
-- 1. bridge_status - one row, the health snapshot
--
-- The single most useful thing missing today: a failed write is currently
-- only visible in-game via /bridge status. This row publishes per-table
-- queue depth and last error, so a dead sink is visible in a browser.
-- ---------------------------------------------------------------------------

create table if not exists public.bridge_status (
  id integer primary key default 1 check (id = 1),
  instance_id text,
  plugin_version text,
  server_version text,
  online boolean not null default true,
  started_at timestamptz,
  updated_at timestamptz not null default now(),
  remote_config_version integer,
  sinks jsonb not null default '{}'::jsonb,
  counters jsonb not null default '{}'::jsonb
);

comment on table public.bridge_status is
  'Single row (id = 1) holding the bridge plugin health snapshot: queue depth and last error per sink table, plus counters.';
comment on column public.bridge_status.sinks is
  'Per-table object, e.g. {"chat_messages": {"pending": 3, "last_error": null, "dropped": 0}}.';
comment on column public.bridge_status.counters is
  'Free-form counters published by the plugin, e.g. chat relayed, achievement rows queued.';

-- ---------------------------------------------------------------------------
-- 2. bridge_events - structured events
--
-- One row per meaningful thing that happened: enable, disable, config
-- applied, an error, or a rolled-up collector summary. This is deliberately
-- NOT one row per log line - see bridge_console for that.
-- ---------------------------------------------------------------------------

create table if not exists public.bridge_events (
  id bigint generated always as identity primary key,
  created_at timestamptz not null default now(),
  level text not null check (level in ('info', 'warn', 'error')),
  category text not null,
  event text not null,
  message text not null,
  details jsonb not null default '{}'::jsonb,
  instance_id text,
  plugin_version text
);

create index if not exists bridge_events_created_at_idx
  on public.bridge_events (created_at desc);
create index if not exists bridge_events_level_created_at_idx
  on public.bridge_events (level, created_at desc);
create index if not exists bridge_events_category_created_at_idx
  on public.bridge_events (category, created_at desc);

comment on table public.bridge_events is
  'Structured bridge events: lifecycle, errors and rolled-up collector summaries.';
comment on column public.bridge_events.event is
  'Stable machine-ish code for filtering, e.g. server.online, collector.summary, update.staged.';

-- ---------------------------------------------------------------------------
-- 3. bridge_console - the plugin log, for testing
--
-- Captures the plugin's own logger output (not the whole server console) so a
-- new feature can be exercised and its output read and copied from a browser
-- without attaching to the server terminal. Short retention and a hard row cap
-- keep it from becoming a log archive.
-- ---------------------------------------------------------------------------

create table if not exists public.bridge_console (
  id bigint generated always as identity primary key,
  created_at timestamptz not null default now(),
  level text not null check (level in ('info', 'warn', 'error')),
  logger text,
  message text not null,
  throwable text,
  instance_id text,
  plugin_version text
);

create index if not exists bridge_console_created_at_idx
  on public.bridge_console (created_at desc);
create index if not exists bridge_console_level_created_at_idx
  on public.bridge_console (level, created_at desc);

comment on table public.bridge_console is
  'The bridge plugin log lines, for reading and copying output while testing. Retained briefly and row-capped.';

-- ---------------------------------------------------------------------------
-- 4. bridge_commands - the web -> plugin queue
--
-- Mirrors the claim semantics of whitelist_commands: an owner inserts a
-- pending row, the plugin claims it atomically, then marks it done or failed.
-- The CHECK on command is the security boundary - the plugin maps each symbol
-- to a fixed method, so a row can never carry an arbitrary command line.
-- ---------------------------------------------------------------------------

create table if not exists public.bridge_commands (
  id bigint generated always as identity primary key,
  created_at timestamptz not null default now(),
  requested_at timestamptz not null default now(),
  command text not null check (command in (
    'update.check',
    'update.apply',
    'config.reload',
    'connection.test',
    'stats.reconcile',
    'sinks.drain',
    'sinks.flush'
  )),
  args jsonb not null default '{}'::jsonb,
  status text not null default 'pending'
    check (status in ('pending', 'processing', 'done', 'failed')),
  requested_by uuid,
  requested_by_username text,
  claimed_at timestamptz,
  claimed_by text,
  processed_at timestamptz,
  result jsonb,
  error text
);

create index if not exists bridge_commands_pending_idx
  on public.bridge_commands (status, requested_at)
  where status = 'pending';
create index if not exists bridge_commands_created_at_idx
  on public.bridge_commands (created_at desc);

comment on table public.bridge_commands is
  'Owner-requested maintenance actions for the server, claimed and executed by the bridge plugin.';
comment on column public.bridge_commands.command is
  'One of the fixed allowlist values enforced by the table CHECK. Never a console command string.';

-- ---------------------------------------------------------------------------
-- 5. Access: owner-only reads, owner-only request, service_role writes
--
-- service_role bypasses row level security, so the plugin writes freely. The
-- website runs as `authenticated`, so every table is owner-scoped for SELECT
-- and only bridge_commands is writable. anon gets nothing at all.
-- ---------------------------------------------------------------------------

alter table public.bridge_status  enable row level security;
alter table public.bridge_events  enable row level security;
alter table public.bridge_console enable row level security;
alter table public.bridge_commands enable row level security;

do $$
declare
  t text;
begin
  foreach t in array array['bridge_status', 'bridge_events', 'bridge_console', 'bridge_commands'] loop
    execute format('drop policy if exists %I on public.%I', t || ' owner read', t);
    execute format(
      'create policy %I on public.%I for select to authenticated using ('
      || ' exists (select 1 from public.profiles p where p.id = auth.uid() and p.role = ''owner'')'
      || ')', t || ' owner read', t);
  end loop;
end;
$$;

-- Only an owner may queue an action, and only the allowlisted ones. The plugin
-- still claims and completes rows itself as service_role.
drop policy if exists "bridge_commands owner insert" on public.bridge_commands;
create policy "bridge_commands owner insert"
  on public.bridge_commands for insert
  to authenticated
  with check (exists (
    select 1 from public.profiles p where p.id = auth.uid() and p.role = 'owner'
  ));

-- The requester is recorded from the caller's own session, so a row cannot
-- claim to have been requested by someone else.
create or replace function public.request_bridge_command(
  p_command text,
  p_args jsonb default '{}'::jsonb
)
returns public.bridge_commands
language plpgsql
security invoker
set search_path = public
as $$
declare
  v_row public.bridge_commands;
begin
  if auth.uid() is null then
    raise exception 'authentication required';
  end if;
  if not exists (select 1 from public.profiles p where p.id = auth.uid() and p.role = 'owner') then
    raise exception 'owner role required';
  end if;

  insert into public.bridge_commands (command, args, requested_by, requested_by_username)
  values (
    p_command,
    coalesce(p_args, '{}'::jsonb),
    auth.uid(),
    coalesce(
      (select p.username from public.profiles p where p.id = auth.uid()),
      null
    )
  )
  returning * into v_row;

  return v_row;
end;
$$;

grant execute on function public.request_bridge_command(text, jsonb) to authenticated;

-- Reads for the owner, writes for the plugin. anon is revoked defensively in
-- case a blanket grant ever reaches these tables.
-- Deliberately read + insert only. Claiming and completing a command is the
-- plugin's job, and it runs as service_role, so a hijacked owner session
-- cannot rewrite a command's status or erase the record of one it issued.
grant select on public.bridge_status, public.bridge_events, public.bridge_console
  to authenticated;
grant select, insert on public.bridge_commands to authenticated;

grant select, insert, update, delete on table
  public.bridge_status,
  public.bridge_events,
  public.bridge_console,
  public.bridge_commands
  to service_role;

grant usage, select on all sequences in schema public to service_role;

revoke all on public.bridge_status, public.bridge_events, public.bridge_console, public.bridge_commands from anon;

-- ---------------------------------------------------------------------------
-- 6. Retention
--
-- bridge_console is the only table that can grow quickly, so it is capped
-- twice: by age and by row count. bridge_events keeps 30 days, which is
-- enough to cover an incident. Commands keep their outcome for 30 days.
-- ---------------------------------------------------------------------------

create or replace function public.purge_bridge_history()
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  removed_chat  bigint;
  removed_tps   bigint;
  removed_pos   bigint;
  removed_ev    bigint;
  removed_con   bigint;
  removed_cmd   bigint;
  con_total     bigint;
  con_cap       constant integer := 5000;
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

  delete from public.bridge_events
    where created_at < now() - interval '30 days';
  get diagnostics removed_ev = row_count;

  delete from public.bridge_console
    where created_at < now() - interval '2 days';
  get diagnostics removed_con = row_count;

  -- Row cap, so a busy debug session cannot fill the table even inside 2 days.
  select count(*) into con_total from public.bridge_console;
  if con_total > con_cap then
    delete from public.bridge_console
      where id not in (
        select id from public.bridge_console order by id desc limit con_cap
      );
    get diagnostics removed_con = removed_con + row_count;
  end if;

  delete from public.bridge_commands
    where status in ('done', 'failed')
      and requested_at < now() - interval '30 days';
  get diagnostics removed_cmd = row_count;

  return jsonb_build_object(
    'chat_messages', removed_chat,
    'server_tps_samples', removed_tps,
    'live_positions', removed_pos,
    'bridge_events', removed_ev,
    'bridge_console', removed_con,
    'bridge_commands', removed_cmd);
end;
$$;

-- SECURITY DEFINER, so the default PUBLIC execute is revoked: only the
-- scheduler or the service role may trigger a purge.
revoke all on function public.purge_bridge_history() from public;
grant execute on function public.purge_bridge_history() to service_role;
