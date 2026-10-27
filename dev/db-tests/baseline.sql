-- Minimal, prod-shaped baseline used ONLY by the SQL regression tests.
--
-- It reproduces the parts of the production "miaucraft" schema that the
-- security migration touches (policies, grants, trigger functions and
-- SECURITY DEFINER helpers were read from the live project's catalogs), on top
-- of a stub of the Supabase auth/storage schemas. It is not a full schema dump.

create role anon nologin;
create role authenticated nologin;
create role service_role nologin bypassrls;

create schema auth;
create table auth.users (
  id uuid primary key,
  raw_app_meta_data jsonb not null default '{}'::jsonb,
  raw_user_meta_data jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now()
);
create function auth.uid() returns uuid language sql stable
  as $$ select nullif(current_setting('request.jwt.claim.sub', true), '')::uuid $$;
grant usage on schema auth to anon, authenticated, service_role;
grant execute on function auth.uid() to anon, authenticated, service_role;

create schema storage;
create table storage.buckets (
  id text primary key,
  public boolean default false,
  allowed_mime_types text[],
  file_size_limit bigint
);
insert into storage.buckets (id, public, file_size_limit) values ('waypoint-gallery', true, 10485760);

grant usage on schema public to anon, authenticated, service_role;

create table public.profiles (
  id uuid primary key references auth.users (id) on delete cascade,
  username text unique,
  role text not null default 'user',
  created_at timestamptz not null default now(),
  discord_id text,
  discord_username text
);

create function public.current_user_role() returns text
  language sql stable security definer set search_path = public
  as $$ select role from public.profiles where id = auth.uid() $$;

create table public.chat_messages (
  id uuid primary key default gen_random_uuid(),
  kind text not null check (kind in ('web', 'server', 'system')),
  username text,
  user_id uuid references auth.users (id) on delete set null,
  message text not null,
  created_at timestamptz not null default now()
);
create index chat_messages_created_at_idx on public.chat_messages (created_at desc);

create table public.logs (
  id uuid primary key default gen_random_uuid(),
  action text, entity_type text, entity_name text, username text,
  user_id uuid, created_at timestamptz not null default now()
);
create table public.server_info (key text primary key, value text);
create table public.waypoints (
  id uuid primary key default gen_random_uuid(), created_by uuid, owner_id uuid,
  name text not null, description text, x integer not null, z integer not null, color text
);
create table public.waypoint_gallery (id uuid primary key default gen_random_uuid(), caption text);
create table public.whitelist_commands (id uuid primary key default gen_random_uuid(), requested_by uuid);
create table public.player_stats (
  player_id uuid not null, stat_key text not null, stat_value bigint not null default 0,
  primary key (player_id, stat_key)
);
create index idx_player_stats_player_id on public.player_stats (player_id);
create table public.waypoint_collaborators (
  id uuid primary key default gen_random_uuid(),
  waypoint_id uuid not null, user_id uuid not null,
  unique (waypoint_id, user_id)
);
create index waypoint_collaborators_waypoint_idx on public.waypoint_collaborators (waypoint_id);

-- Supabase default privileges: everything granted to the API roles.
grant all on all tables in schema public to anon, authenticated, service_role;
grant execute on all functions in schema public to anon, authenticated, service_role;

alter table public.profiles           enable row level security;
alter table public.chat_messages      enable row level security;
alter table public.logs               enable row level security;
alter table public.server_info        enable row level security;

-- production policies (verbatim shapes from pg_policies)
create policy profiles_select_own_or_owner on public.profiles for select to public
  using ((auth.uid() = id) or (current_user_role() = any (array['owner','admin'])));
create policy profiles_update_owner_only on public.profiles for update to public
  using (current_user_role() = any (array['owner','admin']))
  with check (current_user_role() = any (array['owner','admin']));
create policy chat_insert_web on public.chat_messages for insert to authenticated
  with check (kind = any (array['web','system']));
create policy chat_select_authed on public.chat_messages for select to authenticated using (true);
create policy logs_select_authenticated on public.logs for select to authenticated using (true);
create policy server_info_select_authenticated on public.server_info for select to authenticated using (true);

create function public.prevent_non_owner_role_change() returns trigger
  language plpgsql security definer as $$
begin
  if new.role is distinct from old.role then
    if current_user_role() <> 'owner' then
      raise exception 'Only the owner can change roles.';
    end if;
  end if;
  return new;
end $$;
create trigger trg_prevent_non_owner_role_change before update on public.profiles
  for each row execute function public.prevent_non_owner_role_change();

create function public.handle_new_user() returns trigger
  language plpgsql security definer set search_path = public as $$
begin
  insert into public.profiles (id, username, role)
  values (new.id, new.raw_user_meta_data ->> 'username',
          coalesce(new.raw_app_meta_data ->> 'role', 'user'))
  on conflict (id) do update
    set role = coalesce(new.raw_app_meta_data ->> 'role', public.profiles.role)
    where new.raw_app_meta_data ->> 'role' is not null
      and public.profiles.role is distinct from (new.raw_app_meta_data ->> 'role');
  return new;
end $$;
create trigger on_auth_user_created after insert on auth.users
  for each row execute function public.handle_new_user();

create function public.list_account_usernames() returns setof text
  language sql stable security definer set search_path = public
  as $$ select username from public.profiles where username is not null $$;

create function public.merge_player(old_id uuid, new_id uuid) returns void
  language plpgsql as $$ begin null; end $$;
