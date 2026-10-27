-- 20260101000600_security_hardening.sql
--
-- Security hardening found by auditing the production schema (project
-- "miaucraft"). Idempotent: safe to run more than once, and every statement
-- that touches an object the local dev chain does not have is guarded, so
-- `supabase db reset` keeps working. Rollback: dev/supabase/rollbacks/
-- 20260101000600_security_hardening.down.sql.
--
-- APPLY ORDER (the edge functions in the website repo depend on section 1):
--   1. apply this migration,
--   2. deploy the register / signin edge functions from the website repo.
-- Nothing here changes the plugin's service_role access.
--
-- What it fixes:
--
--   1. rate_limits did not exist in production. The register/signin edge
--      functions read and wrote it, ignored the errors, and therefore never
--      throttled anything. Creates the table plus two ATOMIC functions so
--      concurrent requests can no longer race past the limit.
--   2. chat_messages: any signed-in account could insert kind='system' rows and
--      put any username / user_id on a 'web' row, i.e. impersonate players and
--      fake server notices that the plugin then relays into the game. Inserts
--      are now limited to the caller's own identity, 1-500 characters, with a
--      per-user burst limit. The message column also gets a hard length cap.
--   3. profiles: authenticated had UPDATE on every column, so an 'admin' could
--      rewrite another account's username / discord fields and bypass
--      update_username_by_profile's owner-only rule. Only `role` is writable
--      now (and prevent_non_owner_role_change still limits that to owners).
--   4. Authenticated-only read policies (chat, logs, server_info) accepted ANY
--      authenticated session, including a Discord OAuth sign-up that never
--      redeemed an access code. They now require a profile row.
--      handle_new_user() no longer creates a profile for non-email sign-ups.
--   5. Privilege hygiene: TRUNCATE/REFERENCES/TRIGGER and anon write grants
--      removed from API roles; trigger / internal SECURITY DEFINER functions are
--      no longer callable through /rest/v1/rpc; mutable search_path pinned;
--      list_account_usernames (which listed every account to anonymous
--      visitors) and merge_player are locked down.
--   6. waypoint-gallery bucket: image MIME types and a 5 MB cap are enforced
--      server-side (the 5 MB / jpg-png-webp limits were client-only).
--   7. Indexes: two redundant indexes dropped, three FK/lookup indexes added.
--   8. waypoints / gallery: the name, description, coordinate and colour limits
--      the UI applies are now enforced by the database too (a direct REST call
--      could store megabyte names or out-of-world coordinates). NOT VALID, so
--      existing rows are never rejected; production already conforms.

-- ---------------------------------------------------------------------------
-- 1. rate limiting that actually exists
-- ---------------------------------------------------------------------------

create table if not exists public.rate_limits (
  key      text primary key,
  count    integer not null default 0,
  reset_at timestamptz not null
);
create index if not exists rate_limits_reset_at_idx on public.rate_limits (reset_at);

alter table public.rate_limits enable row level security;
revoke all on public.rate_limits from anon, authenticated;
grant select, insert, update, delete on public.rate_limits to service_role;

-- true = the caller may proceed (fewer than p_max recorded failures in window)
create or replace function public.rate_limit_allowed(p_key text, p_max integer)
returns boolean
language sql
stable
security definer
set search_path = public
as $$
  select coalesce(
    (select r.count < p_max from public.rate_limits r
      where r.key = p_key and r.reset_at > now()),
    true);
$$;

-- records one failure; a single upsert, so concurrent failures all count
create or replace function public.rate_limit_fail(p_key text, p_window_seconds integer)
returns void
language plpgsql
security definer
set search_path = public
as $$
begin
  insert into public.rate_limits as r (key, count, reset_at)
  values (p_key, 1, now() + make_interval(secs => p_window_seconds))
  on conflict (key) do update
    set count    = case when r.reset_at <= now() then 1 else r.count + 1 end,
        reset_at = case when r.reset_at <= now()
                        then now() + make_interval(secs => p_window_seconds)
                        else r.reset_at end;
  -- opportunistic cleanup keeps the table tiny without a scheduled job
  delete from public.rate_limits where reset_at < now() - interval '1 day';
end;
$$;

create or replace function public.rate_limit_clear(p_key text)
returns void
language sql
security definer
set search_path = public
as $$
  delete from public.rate_limits where key = p_key;
$$;

revoke all on function public.rate_limit_allowed(text, integer) from public, anon, authenticated;
revoke all on function public.rate_limit_fail(text, integer)    from public, anon, authenticated;
revoke all on function public.rate_limit_clear(text)            from public, anon, authenticated;
grant execute on function public.rate_limit_allowed(text, integer) to service_role;
grant execute on function public.rate_limit_fail(text, integer)    to service_role;
grant execute on function public.rate_limit_clear(text)            to service_role;

-- ---------------------------------------------------------------------------
-- 2. chat_messages: no forged identities, no forged notices, bounded size
-- ---------------------------------------------------------------------------

alter table public.chat_messages drop constraint if exists chat_messages_message_len;
alter table public.chat_messages
  add constraint chat_messages_message_len
  check (char_length(message) <= 2000) not valid;

create index if not exists chat_messages_user_created_idx
  on public.chat_messages (user_id, created_at desc);

drop policy if exists chat_insert_web on public.chat_messages;
drop policy if exists chat_messages_insert_web on public.chat_messages;
create policy chat_insert_web on public.chat_messages
  for insert to authenticated
  with check (
    kind = 'web'
    and user_id = (select auth.uid())
    and char_length(message) between 1 and 500
    and username is not distinct from
        (select p.username from public.profiles p where p.id = (select auth.uid()))
    and (select p.username from public.profiles p where p.id = (select auth.uid())) is not null
  );

-- Burst limit for website senders (service_role / plugin rows are kind
-- 'server' or 'system' and are never counted).
create or replace function public.enforce_chat_rate_limit()
returns trigger
language plpgsql
set search_path = public
as $$
begin
  if new.kind = 'web' and new.user_id is not null and
     (select count(*) from public.chat_messages m
        where m.user_id = new.user_id
          and m.kind = 'web'
          and m.created_at > now() - interval '10 seconds') >= 5 then
    raise exception 'You are sending messages too fast. Wait a few seconds.'
      using errcode = '42501';
  end if;
  return new;
end;
$$;

drop trigger if exists chat_rate_limit on public.chat_messages;
create trigger chat_rate_limit
  before insert on public.chat_messages
  for each row execute function public.enforce_chat_rate_limit();
revoke all on function public.enforce_chat_rate_limit() from public, anon, authenticated;

-- ---------------------------------------------------------------------------
-- 3. profiles: only `role` is writable by API users
-- ---------------------------------------------------------------------------

revoke update on public.profiles from anon, authenticated;
grant update (role) on public.profiles to authenticated;

-- ---------------------------------------------------------------------------
-- 4. sessions without a profile (OAuth sign-ups) get no read access
-- ---------------------------------------------------------------------------

do $$
declare
  spec record;
begin
  for spec in
    select * from (values
      ('chat_messages', 'chat_select_authed'),
      ('logs',          'logs_select_authenticated'),
      ('server_info',   'server_info_select_authenticated')
    ) as t(tbl, pol)
  loop
    if exists (select 1 from pg_policies
                where schemaname = 'public' and tablename = spec.tbl and policyname = spec.pol) then
      execute format('drop policy %I on public.%I', spec.pol, spec.tbl);
      execute format(
        'create policy %I on public.%I for select to authenticated '
        'using ((select public.current_user_role()) is not null)',
        spec.pol, spec.tbl);
    end if;
  end loop;
end $$;

-- A Discord (or any non-email) sign-up must not receive a profile: profiles are
-- only ever created by the access-code registration function, which uses the
-- email provider. Existing profiles are untouched.
do $$
begin
  if to_regprocedure('public.handle_new_user()') is not null then
    execute $f$
      create or replace function public.handle_new_user()
      returns trigger
      language plpgsql
      security definer
      set search_path = public
      as $body$
      begin
        if coalesce(new.raw_app_meta_data ->> 'provider', 'email') <> 'email' then
          return new;
        end if;

        insert into public.profiles (id, username, role)
        values (
          new.id,
          new.raw_user_meta_data ->> 'username',
          coalesce(new.raw_app_meta_data ->> 'role', 'user')
        )
        on conflict (id) do update
          set role = coalesce(new.raw_app_meta_data ->> 'role', public.profiles.role)
          where new.raw_app_meta_data ->> 'role' is not null
            and public.profiles.role is distinct from (new.raw_app_meta_data ->> 'role');

        return new;
      end;
      $body$
    $f$;
  end if;
end $$;

-- ---------------------------------------------------------------------------
-- 5. privilege hygiene
-- ---------------------------------------------------------------------------

do $$
declare
  t record;
begin
  for t in select tablename from pg_tables where schemaname = 'public' loop
    -- TRUNCATE ignores RLS; REFERENCES/TRIGGER are never needed by API roles.
    execute format('revoke truncate, references, trigger on public.%I from anon, authenticated', t.tablename);
    -- anon is read-only everywhere; RLS was the only thing stopping writes.
    execute format('revoke insert, update, delete on public.%I from anon', t.tablename);
  end loop;
end $$;

alter default privileges for role postgres in schema public
  revoke truncate, references, trigger on tables from anon, authenticated;

-- Pin search_path on SECURITY INVOKER/DEFINER helpers flagged by the linter.
do $$
declare
  sig text;
begin
  foreach sig in array array[
    'public.prevent_non_owner_role_change()',
    'public.restrict_self_player_update()',
    'public.check_whitelist_command_cap()',
    'public.waypoints_sync_owner()',
    'public.waypoints_touch_updated_at()',
    'public.set_updated_at()',
    'public.get_top1_summary()',
    'public.get_top3_summary()',
    'public.sync_discord_to_profile()',
    'public.merge_player(uuid, uuid)'
  ]
  loop
    if to_regprocedure(sig) is not null then
      execute format('alter function %s set search_path = public', sig);
    end if;
  end loop;
end $$;

-- Trigger / event-trigger functions and admin tools are not part of the API.
do $$
declare
  sig text;
begin
  foreach sig in array array[
    'public.handle_new_user()',
    'public.log_change()',
    'public.log_whitelist_command()',
    'public.enforce_owner_change_owner()',
    'public.enforce_visibility_owner()',
    'public.enforce_waypoint_min_distance()',
    'public.enforce_waypoint_rate_limit()',
    'public.prevent_non_owner_role_change()',
    'public.restrict_self_player_update()',
    'public.sync_discord_to_profile()',
    'public.wp_sync_created_by()',
    'public.wp_transfer_housekeeping()',
    'public.clear_display_image_on_gallery_delete()',
    'public.rls_auto_enable()',
    'public.merge_player(uuid, uuid)',
    'public.list_account_usernames()'
  ]
  loop
    if to_regprocedure(sig) is not null then
      execute format('revoke all on function %s from public, anon, authenticated', sig);
      execute format('grant execute on function %s to service_role', sig);
    end if;
  end loop;

  -- Owner/user-facing RPCs: signed-in only, never anonymous.
  foreach sig in array array[
    'public.delete_account_by_username(text)',
    'public.update_username_by_profile(uuid, text)',
    'public.wp_transfer_waypoint(uuid, uuid, text)',
    'public.request_bridge_command(text)'
  ]
  loop
    if to_regprocedure(sig) is not null then
      execute format('revoke all on function %s from public, anon', sig);
      execute format('grant execute on function %s to authenticated, service_role', sig);
    end if;
  end loop;
end $$;

-- ---------------------------------------------------------------------------
-- 6. waypoint-gallery bucket: enforce what the UI already promises
-- ---------------------------------------------------------------------------

do $$
begin
  if to_regclass('storage.buckets') is not null then
    update storage.buckets
       set allowed_mime_types = array['image/jpeg', 'image/png', 'image/webp'],
           file_size_limit    = 5242880
     where id = 'waypoint-gallery';
  end if;
end $$;

-- ---------------------------------------------------------------------------
-- 7. indexes
-- ---------------------------------------------------------------------------

-- Covered by the (player_id, stat_key) / (waypoint_id, user_id) unique keys.
drop index if exists public.idx_player_stats_player_id;
drop index if exists public.waypoint_collaborators_waypoint_idx;

do $$
begin
  if to_regclass('public.waypoints') is not null then
    create index if not exists waypoints_created_by_idx on public.waypoints (created_by);
  end if;
  if to_regclass('public.whitelist_commands') is not null then
    create index if not exists whitelist_commands_requested_by_idx
      on public.whitelist_commands (requested_by);
  end if;
end $$;

-- ---------------------------------------------------------------------------
-- 8. waypoint / gallery limits enforced server-side
-- ---------------------------------------------------------------------------

do $$
begin
  if to_regclass('public.waypoints') is not null then
    alter table public.waypoints drop constraint if exists waypoints_name_len;
    alter table public.waypoints add constraint waypoints_name_len
      check (char_length(name) between 1 and 60) not valid;

    alter table public.waypoints drop constraint if exists waypoints_description_len;
    alter table public.waypoints add constraint waypoints_description_len
      check (description is null or char_length(description) <= 160) not valid;

    -- the vanilla world border is +/-30,000,000 blocks
    alter table public.waypoints drop constraint if exists waypoints_xz_range;
    alter table public.waypoints add constraint waypoints_xz_range
      check (abs(x) <= 30000000 and abs(z) <= 30000000) not valid;

    alter table public.waypoints drop constraint if exists waypoints_color_format;
    alter table public.waypoints add constraint waypoints_color_format
      check (color is null or color ~ '^#[0-9a-fA-F]{6}$') not valid;
  end if;

  if to_regclass('public.waypoint_gallery') is not null then
    alter table public.waypoint_gallery drop constraint if exists waypoint_gallery_caption_len;
    alter table public.waypoint_gallery add constraint waypoint_gallery_caption_len
      check (caption is null or char_length(caption) <= 200) not valid;
  end if;
end $$;
