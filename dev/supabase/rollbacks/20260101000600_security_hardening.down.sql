-- Rollback for 20260101000600_security_hardening.sql.
--
-- NOT part of the migration chain (it lives outside migrations/ on purpose).
-- Run it manually only if the hardening breaks something, then report the
-- break: every item below re-opens a gap the migration closed.
--
-- Not restored on purpose: the TRUNCATE/REFERENCES/TRIGGER revokes, the anon
-- write revokes and the pinned search_paths. They never gave the site any
-- capability, so restoring them would only widen the attack surface.

-- chat: previous (forgeable) insert policy and open read policy
drop trigger if exists chat_rate_limit on public.chat_messages;
drop function if exists public.enforce_chat_rate_limit();
drop policy if exists chat_insert_web on public.chat_messages;
create policy chat_insert_web on public.chat_messages
  for insert to authenticated with check (kind = any (array['web', 'system']));
alter table public.chat_messages drop constraint if exists chat_messages_message_len;
drop index if exists public.chat_messages_user_created_idx;

-- profiles: previous blanket column grant
grant update on public.profiles to authenticated;

-- authenticated-only reads: back to "any authenticated session"
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
      execute format('create policy %I on public.%I for select to authenticated using (true)',
                     spec.pol, spec.tbl);
    end if;
  end loop;
end $$;

-- handle_new_user: previous behaviour (profile for every sign-up)
create or replace function public.handle_new_user()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
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
$$;

-- function execute grants as they were
grant execute on function public.list_account_usernames() to anon, authenticated;
grant execute on function public.merge_player(uuid, uuid) to anon, authenticated;

-- bucket: no server-side MIME / size limits (10 MB was the previous cap)
update storage.buckets
   set allowed_mime_types = null, file_size_limit = 10485760
 where id = 'waypoint-gallery';

-- indexes
create index if not exists idx_player_stats_player_id on public.player_stats (player_id);
create index if not exists waypoint_collaborators_waypoint_idx
  on public.waypoint_collaborators (waypoint_id);
drop index if exists public.waypoints_created_by_idx;
drop index if exists public.whitelist_commands_requested_by_idx;

-- rate_limits and its functions are left in place: they are inert without the
-- edge functions and dropping them would discard live lockout state.

-- waypoint / gallery limits
alter table if exists public.waypoints drop constraint if exists waypoints_name_len;
alter table if exists public.waypoints drop constraint if exists waypoints_description_len;
alter table if exists public.waypoints drop constraint if exists waypoints_xz_range;
alter table if exists public.waypoints drop constraint if exists waypoints_color_format;
alter table if exists public.waypoint_gallery drop constraint if exists waypoint_gallery_caption_len;
