--
-- PostgreSQL database dump
--

\restrict FV1wXX5NeFVyd3g4nMIfXOHfb9S3ANjj2wf67Dw9xhb7j1gW4esH1A8oHCORv4h

-- Dumped from database version 17.6
-- Dumped by pg_dump version 17.11 (Debian 17.11-1.pgdg13+2)

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET transaction_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- Name: public; Type: SCHEMA; Schema: -; Owner: -
--

CREATE SCHEMA public;


--
-- Name: SCHEMA public; Type: COMMENT; Schema: -; Owner: -
--

COMMENT ON SCHEMA public IS 'standard public schema';


--
-- Name: check_whitelist_command_cap(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.check_whitelist_command_cap() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
begin
  if (select count(*) from public.whitelist_commands
      where requested_by is not distinct from new.requested_by
        and status = 'pending') >= 3 then
    raise exception 'Too many pending whitelist requests. Wait for the server to process them.';
  end if;
  return new;
end $$;


--
-- Name: clear_display_image_on_gallery_delete(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.clear_display_image_on_gallery_delete() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO 'public'
    AS $$
begin
  update public.waypoints w
     set display_image_url = null
   where w.id = old.waypoint_id
     and w.display_image_url = old.url;
  return old;
end;
$$;


--
-- Name: current_user_role(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.current_user_role() RETURNS text
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path TO 'public'
    AS $$
  select role from public.profiles where id = auth.uid();
$$;


--
-- Name: delete_account_by_username(text); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.delete_account_by_username(_username text) RETURNS jsonb
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO ''
    AS $$
declare
  caller_id   uuid;
  caller_role text;
  target_id   uuid;
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

  delete from public.profiles where id = target_id;
  delete from auth.identities where user_id = target_id;
  delete from auth.users where id = target_id;

  return jsonb_build_object('ok', true);
end;
$$;


--
-- Name: distance_leaderboard(integer); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.distance_leaderboard(limit_count integer DEFAULT 10) RETURNS TABLE(player_id uuid, username text, stat_value numeric)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path TO 'public'
    AS $_$
  select ps.player_id, p.username, sum(ps.stat_value) as stat_value
  from player_stats ps
  join players p on p.id = ps.player_id
  where ps.stat_key ~ '_CM$'
    and p.hidden = false
  group by ps.player_id, p.username
  order by stat_value desc
  limit limit_count;
$_$;


--
-- Name: enforce_owner_change_owner(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.enforce_owner_change_owner() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO 'public'
    AS $$
begin
  if (new.owner_id is distinct from old.owner_id)
     or (new.created_by is distinct from old.created_by)
  then
    if not public.wp_is_owner(new.id, auth.uid()) then
      raise exception 'only the waypoint owner can change ownership'
        using errcode = '42501';
    end if;
  end if;
  return new;
end;
$$;


--
-- Name: enforce_visibility_owner(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.enforce_visibility_owner() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO 'public'
    AS $$
begin
  if new.visibility is distinct from old.visibility
     and not public.wp_is_owner(new.id, auth.uid()) then
    raise exception 'only the waypoint owner can change visibility'
      using errcode = '42501';
  end if;
  return new;
end;
$$;


--
-- Name: enforce_waypoint_min_distance(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.enforce_waypoint_min_distance() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO 'public'
    AS $$
declare
  too_close boolean;
begin
  select exists (
    select 1 from public.waypoints w
    where w.dimension = NEW.dimension
      and w.id is distinct from NEW.id
      and sqrt(power(w.x - NEW.x, 2) + power(w.z - NEW.z, 2)) < 50
  ) into too_close;

  if too_close then
    raise exception 'Too close to an existing waypoint in this dimension — must be at least 50 blocks apart.';
  end if;

  return NEW;
end;
$$;


--
-- Name: enforce_waypoint_rate_limit(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.enforce_waypoint_rate_limit() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO 'public'
    AS $$
declare
  actor_role text;
  recent_count integer;
begin
  select role into actor_role from public.profiles where id = NEW.created_by;
  if actor_role = 'owner' then
    return NEW;
  end if;

  select count(*) into recent_count
  from public.waypoints
  where created_by = NEW.created_by
    and created_at > now() - interval '5 minutes';

  if recent_count >= 10 then
    raise exception 'Slow down — too many waypoints created in the last few minutes. Try again shortly.';
  end if;

  return NEW;
end;
$$;


--
-- Name: get_top1_summary(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.get_top1_summary() RETURNS TABLE(username text, points bigint, top_in text)
    LANGUAGE sql STABLE
    AS $$
  WITH RECURSIVE player_merge AS (
      SELECT
          id AS original_id,
          id AS final_id,
          merged_into
      FROM players

      UNION ALL

      SELECT
          pm.original_id,
          p.id AS final_id,
          p.merged_into
      FROM player_merge pm
      JOIN players p
          ON p.id = pm.merged_into
  ),
  resolved_players AS (
      SELECT DISTINCT ON (original_id)
          original_id,
          final_id
      FROM player_merge
      WHERE merged_into IS NULL
      ORDER BY original_id
  ),
  combined_stats AS (
      SELECT
          rp.final_id AS player_id,
          ps.stat_key,
          MAX(ps.stat_value) AS stat_value
      FROM player_stats ps
      JOIN resolved_players rp
          ON rp.original_id = ps.player_id
      WHERE ps.stat_key NOT LIKE '%:%'
      GROUP BY
          rp.final_id,
          ps.stat_key
  ),
  ranked AS (
      SELECT
          player_id,
          stat_key,
          stat_value,
          ROW_NUMBER() OVER (
              PARTITION BY stat_key
              ORDER BY stat_value DESC
          ) AS rank
      FROM combined_stats
  ),
  winners AS (
      SELECT
          player_id,
          stat_key
      FROM ranked
      WHERE rank = 1
  )
  SELECT
      p.username,
      COUNT(*) AS points,
      STRING_AGG(w.stat_key, ', ' ORDER BY w.stat_key) AS top_in
  FROM winners w
  JOIN players p
      ON p.id = w.player_id
  GROUP BY p.id, p.username
  ORDER BY points DESC;
$$;


--
-- Name: get_top3_summary(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.get_top3_summary() RETURNS TABLE(username text, top1 text, top2 text, top3 text)
    LANGUAGE sql STABLE
    AS $$
  WITH RECURSIVE player_merge AS (
    SELECT
      id AS original_id,
      id AS final_id,
      merged_into
    FROM players

    UNION ALL

    SELECT
      pm.original_id,
      p.id AS final_id,
      p.merged_into
    FROM player_merge pm
    JOIN players p
      ON p.id = pm.merged_into
  ),
  resolved_players AS (
    SELECT DISTINCT ON (original_id)
      original_id,
      final_id
    FROM player_merge
    WHERE merged_into IS NULL
    ORDER BY original_id
  ),
  combined_stats AS (
    SELECT
      rp.final_id AS player_id,
      ps.stat_key,
      MAX(ps.stat_value) AS stat_value
    FROM player_stats ps
    JOIN resolved_players rp
      ON rp.original_id = ps.player_id
    WHERE ps.stat_key NOT LIKE '%:%'
    GROUP BY
      rp.final_id,
      ps.stat_key
  ),
  ranked AS (
    SELECT
      player_id,
      stat_key,
      stat_value,
      ROW_NUMBER() OVER (
        PARTITION BY stat_key
        ORDER BY stat_value DESC
      ) AS rank
    FROM combined_stats
  ),
  top3 AS (
    SELECT
      player_id,
      stat_key,
      rank
    FROM ranked
    WHERE rank <= 3
  )
  SELECT
    p.username,
    STRING_AGG(t.stat_key, ',' ORDER BY t.stat_key) FILTER (WHERE t.rank = 1),
    STRING_AGG(t.stat_key, ',' ORDER BY t.stat_key) FILTER (WHERE t.rank = 2),
    STRING_AGG(t.stat_key, ',' ORDER BY t.stat_key) FILTER (WHERE t.rank = 3)
  FROM top3 t
  JOIN players p
    ON p.id = t.player_id
  GROUP BY p.username
  ORDER BY p.username;
$$;


--
-- Name: handle_new_user(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.handle_new_user() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO 'public'
    AS $$
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


--
-- Name: list_account_usernames(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.list_account_usernames() RETURNS SETOF text
    LANGUAGE sql SECURITY DEFINER
    SET search_path TO 'public'
    AS $$
  select username from public.profiles;
$$;


--
-- Name: list_stat_keys(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.list_stat_keys() RETURNS TABLE(stat_key text)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path TO 'public'
    AS $$
  select distinct stat_key from public.player_stats order by stat_key;
$$;


--
-- Name: log_change(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.log_change() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO 'public'
    AS $$
declare
  actor uuid := auth.uid();
  actor_name text;
  entity text := TG_ARGV[0];
  act text;
  rec record;
  dim_val text;
  changes_val jsonb;
  logo_id uuid;
  logo_name text;
  v_private boolean := false;
begin
  select username into actor_name from profiles where id = actor;

  -- Private waypoints never appear in the shared activity log, except for the
  -- moment they become public (so the visibility change can be shown).
  if entity = 'waypoint' then
    v_private := lower(coalesce((case when TG_OP = 'DELETE' then OLD else NEW end).visibility::text, 'public')) = 'private';
  elsif entity = 'gallery' then
    rec := (case when TG_OP = 'DELETE' then OLD else NEW end);
    select w.id, w.name, w.dimension, lower(coalesce(w.visibility,'public')) = 'private'
      into logo_id, logo_name, dim_val, v_private
      from public.waypoints w
      where w.id = rec.waypoint_id;
    if logo_id is null then
      return rec; -- waypoint no longer exists; nothing meaningful to log
    end if;
  end if;

  if v_private then
    return rec;
  end if;

  if TG_OP = 'INSERT' then
    act := 'create';
    rec := NEW;
    changes_val := to_jsonb(NEW);
  elsif TG_OP = 'UPDATE' then
    rec := NEW;
    if entity = 'waypoint' then
      if (OLD.owner_id is distinct from NEW.owner_id)
         or (OLD.created_by is distinct from NEW.created_by)
      then
        act := 'transfer';
        changes_val := jsonb_build_object(
          'from', jsonb_build_object('id', OLD.owner_id, 'username', OLD.owner_username),
          'to',   jsonb_build_object('id', NEW.owner_id, 'username', NEW.owner_username));
      else
        act := 'update';
        changes_val := jsonb_build_object('before', to_jsonb(OLD), 'after', to_jsonb(NEW));
      end if;
    else
      act := 'update';
      changes_val := jsonb_build_object('before', to_jsonb(OLD), 'after', to_jsonb(NEW));
    end if;
  elsif TG_OP = 'DELETE' then
    act := 'delete';
    rec := OLD;
    changes_val := to_jsonb(OLD);
  end if;

  if entity = 'waypoint' or entity = 'category' then
    logo_id := rec.id;
    logo_name := rec.name;
    if entity = 'waypoint' then
      dim_val := rec.dimension;
    else
      dim_val := null;
    end if;
  end if;

  insert into public.logs (action, entity_type, entity_id, entity_name, dimension, user_id, username, changes)
  values (act, entity, logo_id, logo_name, dim_val, actor, actor_name, changes_val);

  return rec;
end;
$$;


--
-- Name: log_whitelist_command(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.log_whitelist_command() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO 'public'
    AS $$
declare
  actor_username text;
begin
  if NEW.status = 'done' and OLD.status is distinct from 'done' then
    -- The plugin (service_role) is what performs this UPDATE, so auth.uid()
    -- would resolve to nothing useful here — attribute the log entry to
    -- whoever originally made the request instead.
    select username into actor_username from public.profiles where id = NEW.requested_by;
    insert into public.logs (entity_type, action, entity_id, entity_name, user_id, username, created_at)
    values ('whitelist', case when NEW.action = 'remove' then 'delete' else 'create' end, NEW.id, NEW.username, NEW.requested_by, actor_username, now());
  end if;
  return NEW;
end;
$$;


--
-- Name: merge_player(uuid, uuid); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.merge_player(old_id uuid, new_id uuid) RETURNS void
    LANGUAGE plpgsql
    AS $$
begin
  if old_id = new_id then
    raise exception 'old_id and new_id must differ';
  end if;
 
  -- Sum stats that exist for both ids into new_id
  update player_stats ps_new
  set stat_value = ps_new.stat_value + ps_old.stat_value,
      updated_at = greatest(ps_new.updated_at, ps_old.updated_at)
  from player_stats ps_old
  where ps_old.player_id = old_id
    and ps_new.player_id = new_id
    and ps_new.stat_key = ps_old.stat_key;
 
  -- Re-point stats that only exist on old_id
  update player_stats
  set player_id = new_id
  where player_id = old_id
    and stat_key not in (
      select stat_key from player_stats where player_id = new_id
    );
 
  -- Delete the now-duplicate old rows that were summed above
  delete from player_stats
  where player_id = old_id;
 
  -- Tag the old player row instead of deleting it
  update players
  set merged_into = new_id,
      hidden = true
  where id = old_id;
end;
$$;


--
-- Name: prevent_non_owner_role_change(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.prevent_non_owner_role_change() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER
    AS $$
BEGIN
  IF NEW.role IS DISTINCT FROM OLD.role THEN
    IF current_user_role() <> 'owner' THEN
      RAISE EXCEPTION 'Only the owner can change roles.';
    END IF;
    IF lower(OLD.username) = 'xhvsh' THEN
      RAISE EXCEPTION 'This account''s role cannot be changed.';
    END IF;
  END IF;
  RETURN NEW;
END;
$$;


--
-- Name: restrict_self_player_update(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.restrict_self_player_update() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER
    AS $$
begin
  if auth.role() = 'service_role' then
    return new;
  end if;

  if to_jsonb(new) - 'live_tracking_enabled'
     <> to_jsonb(old) - 'live_tracking_enabled'
  then
    raise exception
      'Regular accounts may only change live_tracking_enabled on their own player row.';
  end if;

  return new;
end
$$;


--
-- Name: rls_auto_enable(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.rls_auto_enable() RETURNS event_trigger
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO 'pg_catalog'
    AS $$
DECLARE
  cmd record;
BEGIN
  FOR cmd IN
    SELECT *
    FROM pg_event_trigger_ddl_commands()
    WHERE command_tag IN ('CREATE TABLE', 'CREATE TABLE AS', 'SELECT INTO')
      AND object_type IN ('table','partitioned table')
  LOOP
     IF cmd.schema_name IS NOT NULL AND cmd.schema_name IN ('public') AND cmd.schema_name NOT IN ('pg_catalog','information_schema') AND cmd.schema_name NOT LIKE 'pg_toast%' AND cmd.schema_name NOT LIKE 'pg_temp%' THEN
      BEGIN
        EXECUTE format('alter table if exists %s enable row level security', cmd.object_identity);
        RAISE LOG 'rls_auto_enable: enabled RLS on %', cmd.object_identity;
      EXCEPTION
        WHEN OTHERS THEN
          RAISE LOG 'rls_auto_enable: failed to enable RLS on %', cmd.object_identity;
      END;
     ELSE
        RAISE LOG 'rls_auto_enable: skip % (either system schema or not in enforced list: %.)', cmd.object_identity, cmd.schema_name;
     END IF;
  END LOOP;
END;
$$;


--
-- Name: set_updated_at(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.set_updated_at() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
begin
  new.updated_at = now();
  return new;
end;
$$;


--
-- Name: sync_discord_to_profile(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.sync_discord_to_profile() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER
    AS $$
declare
  v_user uuid := coalesce(new.user_id, old.user_id);
  v_provider_id text;
  v_name text;
begin
  select i.provider_id,
         case
           when i.identity_data->>'name' like '%#0'
             then replace(i.identity_data->>'name', '#0', '')
           else i.identity_data->>'name'
         end
    into v_provider_id, v_name
  from auth.identities i
  where i.user_id = v_user
    and i.provider = 'discord'
  limit 1;

  if v_provider_id is not null then
    update public.profiles
      set discord_id = v_provider_id,
          discord_username = v_name
      where id = v_user;
  else
    update public.profiles
      set discord_id = null,
          discord_username = null
      where id = v_user;
  end if;

  return null;
end;
$$;


--
-- Name: update_username_by_profile(uuid, text); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.update_username_by_profile(_profile_id uuid, _new_username text) RETURNS void
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO ''
    AS $_$
declare
  is_owner boolean;
  target_role text;
begin
  if _new_username is null or _new_username !~ '^[A-Za-z0-9_]{3,30}$' then
    raise exception 'Usernames must be 3-30 characters using letters, numbers or underscores.';
  end if;

  select exists (
    select 1 from public.profiles
    where id = auth.uid() and role = 'owner'
  ) into is_owner;

  if not is_owner then
    raise exception 'Only owners can change usernames.';
  end if;

  select role into target_role
  from public.profiles
  where id = _profile_id;

  if target_role is null then
    raise exception 'Profile not found.';
  end if;

  if target_role = 'owner' then
    raise exception 'Owner accounts cannot be renamed.';
  end if;

  if exists (
    select 1 from public.profiles
    where lower(username) = lower(_new_username)
      and id <> _profile_id
  ) then
    raise exception 'That username is already taken.';
  end if;

  update public.profiles
  set username = _new_username
  where id = _profile_id;

  update public.waypoints
  set created_by_username = _new_username
  where created_by = _profile_id;
end;
$_$;


--
-- Name: waypoints_sync_owner(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.waypoints_sync_owner() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
  IF NEW.owner_id IS NOT NULL THEN
    SELECT username INTO NEW.owner_username FROM profiles WHERE id = NEW.owner_id;
  ELSE
    NEW.owner_username := NULL;
  END IF;
  RETURN NEW;
END $$;


--
-- Name: waypoints_touch_updated_at(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.waypoints_touch_updated_at() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
  NEW.updated_at := now();
  RETURN NEW;
END $$;


--
-- Name: wp_can_edit(uuid, uuid); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.wp_can_edit(p_waypoint uuid, p_user uuid) RETURNS boolean
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO 'public', 'pg_temp'
    AS $$
declare
  v_vis text;
begin
  select visibility into v_vis from public.waypoints where id = p_waypoint;
  if v_vis is null then
    return false;
  end if;
  if public.wp_is_owner(p_waypoint, p_user) then
    return true;
  end if;
  if public.wp_is_creator(p_waypoint, p_user) then
    return true;
  end if;
  if v_vis = 'public'::text then
    return current_user_role() = any ('{owner,admin}'::text[]) or public.wp_is_collab(p_waypoint, p_user);
  end if;
  return public.wp_is_collab(p_waypoint, p_user);
end;
$$;


--
-- Name: wp_can_view(uuid, uuid); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.wp_can_view(p_waypoint uuid, p_user uuid) RETURNS boolean
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO 'public'
    AS $$
declare
  v_vis text;
  v_creator uuid;
begin
  select visibility, created_by into v_vis, v_creator from public.waypoints where id = p_waypoint;
  if v_vis is null then
    return false;
  end if;
  if v_vis = 'public'::text then
    return true;
  end if;
  return v_creator = p_user or public.wp_is_collab(p_waypoint, p_user);
end;
$$;


--
-- Name: wp_is_collab(uuid, uuid); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.wp_is_collab(p_waypoint uuid, p_user uuid) RETURNS boolean
    LANGUAGE plpgsql STABLE SECURITY DEFINER
    SET search_path TO 'public', 'pg_temp'
    AS $$
begin return exists (select 1 from public.waypoint_collaborators
  where waypoint_id = p_waypoint and user_id = p_user);
end; $$;


--
-- Name: wp_is_creator(uuid, uuid); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.wp_is_creator(p_waypoint uuid, p_user uuid) RETURNS boolean
    LANGUAGE plpgsql STABLE SECURITY DEFINER
    SET search_path TO 'public', 'pg_temp'
    AS $$
begin return exists (select 1 from public.waypoints
  where id = p_waypoint and created_by = p_user);
end; $$;


--
-- Name: wp_is_owner(uuid, uuid); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.wp_is_owner(p_waypoint uuid, p_user uuid) RETURNS boolean
    LANGUAGE plpgsql STABLE SECURITY DEFINER
    SET search_path TO 'public', 'pg_temp'
    AS $$
begin return exists (select 1 from public.waypoints
  where id = p_waypoint and (owner_id = p_user or created_by = p_user));
end; $$;


--
-- Name: wp_sync_created_by(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.wp_sync_created_by() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO 'public'
    AS $$
begin
  new.created_by := new.owner_id;
  new.created_by_username := new.owner_username;
  return new;
end;
$$;


--
-- Name: wp_transfer_housekeeping(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.wp_transfer_housekeeping() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO 'public'
    AS $$
begin
  if not exists (select 1 from public.waypoint_collaborators
                 where waypoint_id = new.id and user_id = old.created_by) then
    insert into public.waypoint_collaborators (waypoint_id, user_id, username, role, added_by)
    values (new.id, old.created_by, old.created_by_username, 'collaborator', new.created_by);
  end if;
  delete from public.waypoint_collaborators where waypoint_id = new.id and user_id = new.created_by;
  return new;
end;
$$;


SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: waypoints; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.waypoints (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    dimension text NOT NULL,
    name text NOT NULL,
    description text,
    x integer NOT NULL,
    y integer,
    z integer NOT NULL,
    color text DEFAULT '#a78bfa'::text NOT NULL,
    created_by uuid,
    created_by_username text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    category_id uuid,
    visibility text DEFAULT 'public'::text NOT NULL,
    owner_id uuid DEFAULT auth.uid(),
    owner_username text,
    display_image_url text,
    CONSTRAINT waypoints_dimension_check CHECK ((dimension = ANY (ARRAY['overworld'::text, 'nether'::text, 'end'::text]))),
    CONSTRAINT waypoints_visibility_check CHECK ((visibility = ANY (ARRAY['public'::text, 'private'::text])))
);

ALTER TABLE ONLY public.waypoints REPLICA IDENTITY FULL;


--
-- Name: wp_transfer_waypoint(uuid, uuid, text); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.wp_transfer_waypoint(p_waypoint uuid, p_new_owner uuid, p_new_owner_username text) RETURNS public.waypoints
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO 'public'
    AS $$
declare
  r public.waypoints;
begin
  if p_new_owner_username is null or p_new_owner_username = '' then
    raise exception 'invalid transfer target' using errcode = '22000';
  end if;
  if not exists (select 1 from public.profiles where id = p_new_owner) then
    raise exception 'target user not found' using errcode = 'P0001';
  end if;
  if not exists (
    select 1 from public.waypoints
    where id = p_waypoint and public.wp_is_owner(id, auth.uid())
  ) then
    raise exception 'not owner' using errcode = '42501';
  end if;
  if p_new_owner = auth.uid() then
    raise exception 'cannot transfer to self' using errcode = '22000';
  end if;
  update public.waypoints
  set owner_id = p_new_owner,
      owner_username = p_new_owner_username,
      created_by = p_new_owner,
      created_by_username = p_new_owner_username
  where id = p_waypoint
  returning * into r;
  if r.id is null then
    raise exception 'waypoint not found' using errcode = 'P0001';
  end if;
  return r;
end;
$$;


--
-- Name: access_codes; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.access_codes (
    code text NOT NULL,
    role text NOT NULL,
    used boolean DEFAULT false NOT NULL,
    used_by uuid,
    created_by text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    used_at timestamp with time zone,
    expires_at timestamp with time zone,
    CONSTRAINT access_codes_role_check CHECK ((role = ANY (ARRAY['owner'::text, 'admin'::text, 'user'::text])))
);


--
-- Name: achievement_criteria; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.achievement_criteria (
    achievement_key text NOT NULL,
    criterion_key text NOT NULL
);


--
-- Name: achievements; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.achievements (
    key text NOT NULL,
    title text,
    description text,
    frame text,
    hidden boolean,
    icon text,
    total_criteria integer
);


--
-- Name: categories; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.categories (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    name text NOT NULL,
    color text DEFAULT '#a78bfa'::text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    icon text DEFAULT 'hashtag'::text NOT NULL
);


--
-- Name: chat_messages; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.chat_messages (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    kind text NOT NULL,
    username text,
    user_id uuid,
    message text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chat_messages_kind_check CHECK ((kind = ANY (ARRAY['web'::text, 'server'::text, 'system'::text])))
);

ALTER TABLE ONLY public.chat_messages REPLICA IDENTITY FULL;


--
-- Name: live_positions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.live_positions (
    player_id uuid NOT NULL,
    dimension text NOT NULL,
    x double precision NOT NULL,
    y double precision NOT NULL,
    z double precision NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT live_positions_dimension_check CHECK ((dimension = ANY (ARRAY['overworld'::text, 'nether'::text, 'end'::text])))
);


--
-- Name: logs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.logs (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    action text NOT NULL,
    entity_type text NOT NULL,
    entity_id uuid,
    entity_name text,
    dimension text,
    user_id uuid,
    username text,
    changes jsonb,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT logs_action_check CHECK ((action = ANY (ARRAY['create'::text, 'update'::text, 'delete'::text, 'transfer'::text]))),
    CONSTRAINT logs_entity_type_check CHECK ((entity_type = ANY (ARRAY['waypoint'::text, 'category'::text, 'whitelist'::text, 'gallery'::text])))
);


--
-- Name: player_achievement_criteria; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.player_achievement_criteria (
    player_id uuid NOT NULL,
    achievement_key text NOT NULL,
    criterion_key text NOT NULL,
    done boolean DEFAULT false NOT NULL,
    awarded_at timestamp with time zone,
    updated_at timestamp with time zone
);


--
-- Name: player_achievements; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.player_achievements (
    player_id uuid NOT NULL,
    achievement_key text NOT NULL,
    completed boolean DEFAULT false NOT NULL,
    criteria_done integer DEFAULT 0 NOT NULL,
    criteria_total integer DEFAULT 0 NOT NULL,
    completed_at timestamp with time zone,
    updated_at timestamp with time zone
);


--
-- Name: player_stats; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.player_stats (
    player_id uuid NOT NULL,
    stat_key text NOT NULL,
    stat_value bigint DEFAULT 0 NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: players; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.players (
    id uuid NOT NULL,
    username text NOT NULL,
    first_seen timestamp with time zone DEFAULT now() NOT NULL,
    last_seen timestamp with time zone DEFAULT now() NOT NULL,
    online boolean DEFAULT false NOT NULL,
    afk boolean DEFAULT false NOT NULL,
    last_moved timestamp with time zone,
    hidden boolean DEFAULT false NOT NULL,
    merged_into uuid,
    live_tracking_enabled boolean DEFAULT true NOT NULL
);


--
-- Name: profiles; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.profiles (
    id uuid NOT NULL,
    username text NOT NULL,
    role text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    discord_id text,
    discord_username text,
    CONSTRAINT profiles_role_check CHECK ((role = ANY (ARRAY['owner'::text, 'admin'::text, 'user'::text])))
);


--
-- Name: rate_limits; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.rate_limits (
    key text NOT NULL,
    count integer DEFAULT 1 NOT NULL,
    reset_at timestamp with time zone NOT NULL
);


--
-- Name: server_info; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.server_info (
    key text NOT NULL,
    value text NOT NULL
);


--
-- Name: server_status_public; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.server_status_public (
    id smallint DEFAULT 1 NOT NULL,
    tps_1m numeric,
    tps_5m numeric,
    tps_15m numeric,
    version text,
    started_at timestamp with time zone,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    days bigint DEFAULT 0 NOT NULL,
    CONSTRAINT server_status_public_single_row CHECK ((id = 1))
);


--
-- Name: waypoint_collaborators; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.waypoint_collaborators (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    waypoint_id uuid NOT NULL,
    user_id uuid NOT NULL,
    username text,
    role text DEFAULT 'viewer'::text NOT NULL,
    added_by uuid,
    added_at timestamp with time zone DEFAULT now() NOT NULL
);

ALTER TABLE ONLY public.waypoint_collaborators REPLICA IDENTITY FULL;


--
-- Name: waypoint_gallery; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.waypoint_gallery (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    waypoint_id uuid NOT NULL,
    url text NOT NULL,
    caption text,
    uploaded_by uuid,
    uploaded_by_username text,
    "position" integer DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

ALTER TABLE ONLY public.waypoint_gallery REPLICA IDENTITY FULL;


--
-- Name: whitelist; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.whitelist (
    id uuid NOT NULL,
    username text NOT NULL,
    synced_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: whitelist_commands; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.whitelist_commands (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    action text NOT NULL,
    username text NOT NULL,
    requested_by uuid,
    requested_at timestamp with time zone DEFAULT now() NOT NULL,
    status text DEFAULT 'pending'::text NOT NULL,
    error text,
    processed_at timestamp with time zone,
    CONSTRAINT whitelist_commands_action_check CHECK ((action = ANY (ARRAY['add'::text, 'remove'::text]))),
    CONSTRAINT whitelist_commands_status_check CHECK ((status = ANY (ARRAY['pending'::text, 'done'::text, 'failed'::text])))
);


--
-- Name: access_codes access_codes_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.access_codes
    ADD CONSTRAINT access_codes_pkey PRIMARY KEY (code);


--
-- Name: achievement_criteria achievement_criteria_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.achievement_criteria
    ADD CONSTRAINT achievement_criteria_pkey PRIMARY KEY (achievement_key, criterion_key);


--
-- Name: achievements achievements_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.achievements
    ADD CONSTRAINT achievements_pkey PRIMARY KEY (key);


--
-- Name: categories categories_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.categories
    ADD CONSTRAINT categories_pkey PRIMARY KEY (id);


--
-- Name: chat_messages chat_messages_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chat_messages
    ADD CONSTRAINT chat_messages_pkey PRIMARY KEY (id);


--
-- Name: live_positions live_positions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.live_positions
    ADD CONSTRAINT live_positions_pkey PRIMARY KEY (player_id);


--
-- Name: logs logs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.logs
    ADD CONSTRAINT logs_pkey PRIMARY KEY (id);


--
-- Name: player_achievement_criteria player_achievement_criteria_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.player_achievement_criteria
    ADD CONSTRAINT player_achievement_criteria_pkey PRIMARY KEY (player_id, achievement_key, criterion_key);


--
-- Name: player_achievements player_achievements_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.player_achievements
    ADD CONSTRAINT player_achievements_pkey PRIMARY KEY (player_id, achievement_key);


--
-- Name: player_stats player_stats_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.player_stats
    ADD CONSTRAINT player_stats_pkey PRIMARY KEY (player_id, stat_key);


--
-- Name: players players_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.players
    ADD CONSTRAINT players_pkey PRIMARY KEY (id);


--
-- Name: profiles profiles_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.profiles
    ADD CONSTRAINT profiles_pkey PRIMARY KEY (id);


--
-- Name: profiles profiles_username_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.profiles
    ADD CONSTRAINT profiles_username_key UNIQUE (username);


--
-- Name: rate_limits rate_limits_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.rate_limits
    ADD CONSTRAINT rate_limits_pkey PRIMARY KEY (key);


--
-- Name: server_info server_info_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.server_info
    ADD CONSTRAINT server_info_pkey PRIMARY KEY (key);


--
-- Name: server_status_public server_status_public_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.server_status_public
    ADD CONSTRAINT server_status_public_pkey PRIMARY KEY (id);


--
-- Name: waypoint_collaborators waypoint_collaborators_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.waypoint_collaborators
    ADD CONSTRAINT waypoint_collaborators_pkey PRIMARY KEY (id);


--
-- Name: waypoint_collaborators waypoint_collaborators_waypoint_id_user_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.waypoint_collaborators
    ADD CONSTRAINT waypoint_collaborators_waypoint_id_user_id_key UNIQUE (waypoint_id, user_id);


--
-- Name: waypoint_gallery waypoint_gallery_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.waypoint_gallery
    ADD CONSTRAINT waypoint_gallery_pkey PRIMARY KEY (id);


--
-- Name: waypoints waypoints_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.waypoints
    ADD CONSTRAINT waypoints_pkey PRIMARY KEY (id);


--
-- Name: whitelist_commands whitelist_commands_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.whitelist_commands
    ADD CONSTRAINT whitelist_commands_pkey PRIMARY KEY (id);


--
-- Name: whitelist whitelist_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.whitelist
    ADD CONSTRAINT whitelist_pkey PRIMARY KEY (id);


--
-- Name: chat_messages_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX chat_messages_created_at_idx ON public.chat_messages USING btree (created_at DESC);


--
-- Name: idx_player_stats_player_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_player_stats_player_id ON public.player_stats USING btree (player_id);


--
-- Name: logs_action_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX logs_action_idx ON public.logs USING btree (action);


--
-- Name: logs_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX logs_created_at_idx ON public.logs USING btree (created_at DESC);


--
-- Name: logs_dimension_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX logs_dimension_idx ON public.logs USING btree (dimension);


--
-- Name: logs_user_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX logs_user_id_idx ON public.logs USING btree (user_id);


--
-- Name: player_stats_stat_key_value_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX player_stats_stat_key_value_idx ON public.player_stats USING btree (stat_key, stat_value DESC);


--
-- Name: players_username_lower_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX players_username_lower_idx ON public.players USING btree (lower(username));


--
-- Name: waypoint_collaborators_user_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX waypoint_collaborators_user_idx ON public.waypoint_collaborators USING btree (user_id);


--
-- Name: waypoint_collaborators_waypoint_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX waypoint_collaborators_waypoint_idx ON public.waypoint_collaborators USING btree (waypoint_id);


--
-- Name: waypoint_gallery_waypoint_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX waypoint_gallery_waypoint_idx ON public.waypoint_gallery USING btree (waypoint_id, "position");


--
-- Name: waypoints_owner_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX waypoints_owner_id_idx ON public.waypoints USING btree (owner_id);


--
-- Name: waypoints_visibility_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX waypoints_visibility_idx ON public.waypoints USING btree (visibility);


--
-- Name: whitelist_commands_pending_unique; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX whitelist_commands_pending_unique ON public.whitelist_commands USING btree (username, action) WHERE (status = 'pending'::text);


--
-- Name: categories categories_log; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER categories_log AFTER INSERT OR DELETE OR UPDATE ON public.categories FOR EACH ROW EXECUTE FUNCTION public.log_change('category');


--
-- Name: whitelist_commands check_whitelist_command_cap; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER check_whitelist_command_cap BEFORE INSERT ON public.whitelist_commands FOR EACH ROW EXECUTE FUNCTION public.check_whitelist_command_cap();


--
-- Name: players restrict_self_player_update; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER restrict_self_player_update BEFORE UPDATE ON public.players FOR EACH ROW EXECUTE FUNCTION public.restrict_self_player_update();


--
-- Name: waypoint_gallery trg_clear_display_image; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_clear_display_image AFTER DELETE ON public.waypoint_gallery FOR EACH ROW EXECUTE FUNCTION public.clear_display_image_on_gallery_delete();


--
-- Name: waypoints trg_owner_change_owner_only; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_owner_change_owner_only BEFORE UPDATE ON public.waypoints FOR EACH ROW EXECUTE FUNCTION public.enforce_owner_change_owner();


--
-- Name: profiles trg_prevent_non_owner_role_change; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_prevent_non_owner_role_change BEFORE UPDATE ON public.profiles FOR EACH ROW EXECUTE FUNCTION public.prevent_non_owner_role_change();


--
-- Name: waypoints trg_visibility_owner_only; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_visibility_owner_only BEFORE UPDATE ON public.waypoints FOR EACH ROW EXECUTE FUNCTION public.enforce_visibility_owner();


--
-- Name: waypoint_gallery waypoint_gallery_log; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER waypoint_gallery_log AFTER INSERT OR DELETE OR UPDATE ON public.waypoint_gallery FOR EACH ROW EXECUTE FUNCTION public.log_change('gallery');


--
-- Name: waypoints waypoint_min_distance; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER waypoint_min_distance BEFORE INSERT OR UPDATE ON public.waypoints FOR EACH ROW EXECUTE FUNCTION public.enforce_waypoint_min_distance();


--
-- Name: waypoints waypoint_rate_limit; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER waypoint_rate_limit BEFORE INSERT ON public.waypoints FOR EACH ROW EXECUTE FUNCTION public.enforce_waypoint_rate_limit();


--
-- Name: waypoints waypoints_log; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER waypoints_log AFTER INSERT OR DELETE OR UPDATE ON public.waypoints FOR EACH ROW EXECUTE FUNCTION public.log_change('waypoint');


--
-- Name: waypoints waypoints_set_updated_at; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER waypoints_set_updated_at BEFORE UPDATE ON public.waypoints FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();


--
-- Name: waypoints waypoints_sync_owner_trigger; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER waypoints_sync_owner_trigger BEFORE INSERT OR UPDATE OF owner_id ON public.waypoints FOR EACH ROW EXECUTE FUNCTION public.waypoints_sync_owner();


--
-- Name: waypoints waypoints_touch_updated_at_trigger; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER waypoints_touch_updated_at_trigger BEFORE UPDATE ON public.waypoints FOR EACH ROW EXECUTE FUNCTION public.waypoints_touch_updated_at();


--
-- Name: whitelist_commands whitelist_command_log; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER whitelist_command_log AFTER UPDATE ON public.whitelist_commands FOR EACH ROW EXECUTE FUNCTION public.log_whitelist_command();


--
-- Name: waypoints wp_sync_created_by; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER wp_sync_created_by BEFORE UPDATE OF owner_id ON public.waypoints FOR EACH ROW EXECUTE FUNCTION public.wp_sync_created_by();


--
-- Name: waypoints wp_transfer_housekeeping; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER wp_transfer_housekeeping AFTER UPDATE OF owner_id ON public.waypoints FOR EACH ROW WHEN ((new.owner_id IS DISTINCT FROM old.owner_id)) EXECUTE FUNCTION public.wp_transfer_housekeeping();


--
-- Name: access_codes access_codes_used_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.access_codes
    ADD CONSTRAINT access_codes_used_by_fkey FOREIGN KEY (used_by) REFERENCES public.profiles(id);


--
-- Name: achievement_criteria achievement_criteria_achievement_key_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.achievement_criteria
    ADD CONSTRAINT achievement_criteria_achievement_key_fkey FOREIGN KEY (achievement_key) REFERENCES public.achievements(key);


--
-- Name: chat_messages chat_messages_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chat_messages
    ADD CONSTRAINT chat_messages_user_id_fkey FOREIGN KEY (user_id) REFERENCES auth.users(id) ON DELETE SET NULL;


--
-- Name: live_positions live_positions_player_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.live_positions
    ADD CONSTRAINT live_positions_player_id_fkey FOREIGN KEY (player_id) REFERENCES public.players(id) ON DELETE CASCADE;


--
-- Name: logs logs_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.logs
    ADD CONSTRAINT logs_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.profiles(id) ON DELETE SET NULL;


--
-- Name: player_achievement_criteria player_achievement_criteria_achievement_key_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.player_achievement_criteria
    ADD CONSTRAINT player_achievement_criteria_achievement_key_fkey FOREIGN KEY (achievement_key) REFERENCES public.achievements(key);


--
-- Name: player_achievement_criteria player_achievement_criteria_player_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.player_achievement_criteria
    ADD CONSTRAINT player_achievement_criteria_player_id_fkey FOREIGN KEY (player_id) REFERENCES public.players(id);


--
-- Name: player_achievements player_achievements_achievement_key_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.player_achievements
    ADD CONSTRAINT player_achievements_achievement_key_fkey FOREIGN KEY (achievement_key) REFERENCES public.achievements(key);


--
-- Name: player_achievements player_achievements_player_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.player_achievements
    ADD CONSTRAINT player_achievements_player_id_fkey FOREIGN KEY (player_id) REFERENCES public.players(id);


--
-- Name: player_stats player_stats_player_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.player_stats
    ADD CONSTRAINT player_stats_player_id_fkey FOREIGN KEY (player_id) REFERENCES public.players(id) ON DELETE CASCADE;


--
-- Name: players players_merged_into_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.players
    ADD CONSTRAINT players_merged_into_fkey FOREIGN KEY (merged_into) REFERENCES public.players(id);


--
-- Name: profiles profiles_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.profiles
    ADD CONSTRAINT profiles_id_fkey FOREIGN KEY (id) REFERENCES auth.users(id) ON DELETE CASCADE;


--
-- Name: waypoint_collaborators waypoint_collaborators_added_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.waypoint_collaborators
    ADD CONSTRAINT waypoint_collaborators_added_by_fkey FOREIGN KEY (added_by) REFERENCES public.profiles(id) ON DELETE SET NULL;


--
-- Name: waypoint_collaborators waypoint_collaborators_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.waypoint_collaborators
    ADD CONSTRAINT waypoint_collaborators_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.profiles(id) ON DELETE CASCADE;


--
-- Name: waypoint_collaborators waypoint_collaborators_waypoint_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.waypoint_collaborators
    ADD CONSTRAINT waypoint_collaborators_waypoint_id_fkey FOREIGN KEY (waypoint_id) REFERENCES public.waypoints(id) ON DELETE CASCADE;


--
-- Name: waypoint_gallery waypoint_gallery_uploaded_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.waypoint_gallery
    ADD CONSTRAINT waypoint_gallery_uploaded_by_fkey FOREIGN KEY (uploaded_by) REFERENCES public.profiles(id) ON DELETE SET NULL;


--
-- Name: waypoint_gallery waypoint_gallery_waypoint_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.waypoint_gallery
    ADD CONSTRAINT waypoint_gallery_waypoint_id_fkey FOREIGN KEY (waypoint_id) REFERENCES public.waypoints(id) ON DELETE CASCADE;


--
-- Name: waypoints waypoints_category_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.waypoints
    ADD CONSTRAINT waypoints_category_id_fkey FOREIGN KEY (category_id) REFERENCES public.categories(id) ON DELETE SET NULL;


--
-- Name: waypoints waypoints_created_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.waypoints
    ADD CONSTRAINT waypoints_created_by_fkey FOREIGN KEY (created_by) REFERENCES public.profiles(id) ON DELETE SET NULL;


--
-- Name: waypoints waypoints_owner_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.waypoints
    ADD CONSTRAINT waypoints_owner_id_fkey FOREIGN KEY (owner_id) REFERENCES public.profiles(id);


--
-- Name: whitelist_commands whitelist_commands_requested_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.whitelist_commands
    ADD CONSTRAINT whitelist_commands_requested_by_fkey FOREIGN KEY (requested_by) REFERENCES auth.users(id);


--
-- Name: achievement_criteria Public read access; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY "Public read access" ON public.achievement_criteria FOR SELECT USING (true);


--
-- Name: achievements Public read access; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY "Public read access" ON public.achievements FOR SELECT USING (true);


--
-- Name: player_achievement_criteria Public read access; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY "Public read access" ON public.player_achievement_criteria FOR SELECT USING (true);


--
-- Name: player_achievements Public read access; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY "Public read access" ON public.player_achievements FOR SELECT USING (true);


--
-- Name: access_codes; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.access_codes ENABLE ROW LEVEL SECURITY;

--
-- Name: access_codes access_codes_delete_unused_owner; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY access_codes_delete_unused_owner ON public.access_codes FOR DELETE TO authenticated USING (((used = false) AND (EXISTS ( SELECT 1
   FROM public.profiles p
  WHERE ((p.id = auth.uid()) AND (p.role = 'owner'::text))))));


--
-- Name: access_codes access_codes_insert_owner; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY access_codes_insert_owner ON public.access_codes FOR INSERT TO authenticated WITH CHECK ((EXISTS ( SELECT 1
   FROM public.profiles p
  WHERE ((p.id = auth.uid()) AND (p.role = 'owner'::text)))));


--
-- Name: access_codes access_codes_update_owner; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY access_codes_update_owner ON public.access_codes FOR UPDATE TO authenticated USING ((EXISTS ( SELECT 1
   FROM public.profiles p
  WHERE ((p.id = auth.uid()) AND (p.role = 'owner'::text))))) WITH CHECK ((EXISTS ( SELECT 1
   FROM public.profiles p
  WHERE ((p.id = auth.uid()) AND (p.role = 'owner'::text)))));


--
-- Name: access_codes access_codes_view_owner; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY access_codes_view_owner ON public.access_codes FOR SELECT TO authenticated USING ((EXISTS ( SELECT 1
   FROM public.profiles p
  WHERE ((p.id = auth.uid()) AND (p.role = 'owner'::text)))));


--
-- Name: achievement_criteria; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.achievement_criteria ENABLE ROW LEVEL SECURITY;

--
-- Name: achievements; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.achievements ENABLE ROW LEVEL SECURITY;

--
-- Name: categories; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.categories ENABLE ROW LEVEL SECURITY;

--
-- Name: categories categories_delete_owner; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY categories_delete_owner ON public.categories FOR DELETE USING ((public.current_user_role() = ANY (ARRAY['owner'::text, 'admin'::text])));


--
-- Name: categories categories_insert_owner; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY categories_insert_owner ON public.categories FOR INSERT WITH CHECK ((public.current_user_role() = ANY (ARRAY['owner'::text, 'admin'::text])));


--
-- Name: categories categories_select_public; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY categories_select_public ON public.categories FOR SELECT USING (true);


--
-- Name: categories categories_update_owner; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY categories_update_owner ON public.categories FOR UPDATE USING ((public.current_user_role() = ANY (ARRAY['owner'::text, 'admin'::text]))) WITH CHECK ((public.current_user_role() = ANY (ARRAY['owner'::text, 'admin'::text])));


--
-- Name: chat_messages chat_insert_web; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY chat_insert_web ON public.chat_messages FOR INSERT TO authenticated WITH CHECK ((kind = ANY (ARRAY['web'::text, 'system'::text])));


--
-- Name: chat_messages; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.chat_messages ENABLE ROW LEVEL SECURITY;

--
-- Name: chat_messages chat_select_authed; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY chat_select_authed ON public.chat_messages FOR SELECT TO authenticated USING (true);


--
-- Name: live_positions; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.live_positions ENABLE ROW LEVEL SECURITY;

--
-- Name: live_positions live_positions_public_read; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY live_positions_public_read ON public.live_positions FOR SELECT TO authenticated, anon USING ((EXISTS ( SELECT 1
   FROM public.players p
  WHERE ((p.id = live_positions.player_id) AND (p.hidden = false) AND COALESCE(p.live_tracking_enabled, true)))));


--
-- Name: logs; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.logs ENABLE ROW LEVEL SECURITY;

--
-- Name: logs logs_select_authenticated; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY logs_select_authenticated ON public.logs FOR SELECT TO authenticated USING (true);


--
-- Name: player_achievement_criteria; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.player_achievement_criteria ENABLE ROW LEVEL SECURITY;

--
-- Name: player_achievements; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.player_achievements ENABLE ROW LEVEL SECURITY;

--
-- Name: player_stats; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.player_stats ENABLE ROW LEVEL SECURITY;

--
-- Name: player_stats player_stats are publicly readable; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY "player_stats are publicly readable" ON public.player_stats FOR SELECT USING (true);


--
-- Name: players; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.players ENABLE ROW LEVEL SECURITY;

--
-- Name: players players_public_read; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY players_public_read ON public.players FOR SELECT TO authenticated, anon USING ((hidden = false));


--
-- Name: players players_update_own_tracking; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY players_update_own_tracking ON public.players FOR UPDATE USING ((lower(username) = lower(( SELECT profiles.username
   FROM public.profiles
  WHERE (profiles.id = auth.uid()))))) WITH CHECK ((lower(username) = lower(( SELECT profiles.username
   FROM public.profiles
  WHERE (profiles.id = auth.uid())))));


--
-- Name: profiles; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.profiles ENABLE ROW LEVEL SECURITY;

--
-- Name: profiles profiles_find_users; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY profiles_find_users ON public.profiles FOR SELECT TO authenticated USING ((public.current_user_role() = ANY ('{owner,admin,user}'::text[])));


--
-- Name: profiles profiles_select_own_or_owner; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY profiles_select_own_or_owner ON public.profiles FOR SELECT USING (((auth.uid() = id) OR (public.current_user_role() = ANY (ARRAY['owner'::text, 'admin'::text]))));


--
-- Name: profiles profiles_update_owner_only; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY profiles_update_owner_only ON public.profiles FOR UPDATE USING ((public.current_user_role() = ANY (ARRAY['owner'::text, 'admin'::text]))) WITH CHECK ((public.current_user_role() = ANY (ARRAY['owner'::text, 'admin'::text])));


--
-- Name: rate_limits; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.rate_limits ENABLE ROW LEVEL SECURITY;

--
-- Name: server_info; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.server_info ENABLE ROW LEVEL SECURITY;

--
-- Name: server_info server_info_delete_owner; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY server_info_delete_owner ON public.server_info FOR DELETE USING ((public.current_user_role() = ANY (ARRAY['owner'::text, 'admin'::text])));


--
-- Name: server_info server_info_insert_owner; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY server_info_insert_owner ON public.server_info FOR INSERT WITH CHECK ((public.current_user_role() = ANY (ARRAY['owner'::text, 'admin'::text])));


--
-- Name: server_info server_info_select_authenticated; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY server_info_select_authenticated ON public.server_info FOR SELECT TO authenticated USING (true);


--
-- Name: server_info server_info_update_owner; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY server_info_update_owner ON public.server_info FOR UPDATE USING ((public.current_user_role() = ANY (ARRAY['owner'::text, 'admin'::text]))) WITH CHECK ((public.current_user_role() = ANY (ARRAY['owner'::text, 'admin'::text])));


--
-- Name: server_status_public; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.server_status_public ENABLE ROW LEVEL SECURITY;

--
-- Name: server_status_public server_status_public_read; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY server_status_public_read ON public.server_status_public FOR SELECT TO authenticated, anon USING (true);


--
-- Name: waypoint_collaborators; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.waypoint_collaborators ENABLE ROW LEVEL SECURITY;

--
-- Name: waypoint_gallery; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.waypoint_gallery ENABLE ROW LEVEL SECURITY;

--
-- Name: waypoints; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.waypoints ENABLE ROW LEVEL SECURITY;

--
-- Name: whitelist; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.whitelist ENABLE ROW LEVEL SECURITY;

--
-- Name: whitelist_commands; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.whitelist_commands ENABLE ROW LEVEL SECURITY;

--
-- Name: whitelist_commands whitelist_commands_owner_delete; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY whitelist_commands_owner_delete ON public.whitelist_commands FOR DELETE TO authenticated USING (((status = 'pending'::text) AND (EXISTS ( SELECT 1
   FROM public.profiles p
  WHERE ((p.id = auth.uid()) AND (p.role = 'owner'::text))))));


--
-- Name: whitelist_commands whitelist_commands_owner_insert; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY whitelist_commands_owner_insert ON public.whitelist_commands FOR INSERT TO authenticated WITH CHECK ((EXISTS ( SELECT 1
   FROM public.profiles p
  WHERE ((p.id = auth.uid()) AND (p.role = 'owner'::text)))));


--
-- Name: whitelist_commands whitelist_commands_owner_select; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY whitelist_commands_owner_select ON public.whitelist_commands FOR SELECT TO authenticated USING ((EXISTS ( SELECT 1
   FROM public.profiles p
  WHERE ((p.id = auth.uid()) AND (p.role = 'owner'::text)))));


--
-- Name: whitelist whitelist_owner_select; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY whitelist_owner_select ON public.whitelist FOR SELECT TO authenticated USING ((EXISTS ( SELECT 1
   FROM public.profiles p
  WHERE ((p.id = auth.uid()) AND (p.role = 'owner'::text)))));


--
-- Name: waypoints wp_delete_owner; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY wp_delete_owner ON public.waypoints FOR DELETE USING ((public.wp_is_owner(id, auth.uid()) OR ((visibility = 'public'::text) AND (public.current_user_role() = ANY ('{owner,admin}'::text[])))));


--
-- Name: waypoints wp_insert_owner_user; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY wp_insert_owner_user ON public.waypoints FOR INSERT WITH CHECK (((public.current_user_role() = ANY ('{owner,admin,user}'::text[])) AND (created_by = auth.uid()) AND (owner_id = auth.uid())));


--
-- Name: waypoints wp_select_private_creator; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY wp_select_private_creator ON public.waypoints FOR SELECT USING (((visibility = 'private'::text) AND ((created_by = auth.uid()) OR public.wp_is_collab(id, auth.uid()))));


--
-- Name: waypoints wp_select_public; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY wp_select_public ON public.waypoints FOR SELECT USING ((visibility = 'public'::text));


--
-- Name: waypoints wp_transfer_owner; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY wp_transfer_owner ON public.waypoints FOR UPDATE USING ((public.wp_is_owner(id, auth.uid()) OR ((visibility = 'public'::text) AND (public.current_user_role() = ANY ('{owner,admin}'::text[]))))) WITH CHECK ((owner_id <> auth.uid()));


--
-- Name: waypoints wp_update_owner_collab; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY wp_update_owner_collab ON public.waypoints FOR UPDATE USING (public.wp_can_edit(id, auth.uid())) WITH CHECK ((public.wp_can_edit(id, auth.uid()) OR (owner_id IS DISTINCT FROM auth.uid())));


--
-- Name: waypoint_collaborators wpc_delete; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY wpc_delete ON public.waypoint_collaborators FOR DELETE USING (public.wp_is_owner(waypoint_id, auth.uid()));


--
-- Name: waypoint_collaborators wpc_insert; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY wpc_insert ON public.waypoint_collaborators FOR INSERT WITH CHECK (public.wp_is_owner(waypoint_id, auth.uid()));


--
-- Name: waypoint_collaborators wpc_select; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY wpc_select ON public.waypoint_collaborators FOR SELECT USING (((user_id = auth.uid()) OR public.wp_can_edit(waypoint_id, auth.uid())));


--
-- Name: waypoint_collaborators wpc_update; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY wpc_update ON public.waypoint_collaborators FOR UPDATE USING (public.wp_is_owner(waypoint_id, auth.uid())) WITH CHECK (public.wp_is_owner(waypoint_id, auth.uid()));


--
-- Name: waypoint_gallery wpg_delete; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY wpg_delete ON public.waypoint_gallery FOR DELETE USING (public.wp_can_edit(waypoint_id, auth.uid()));


--
-- Name: waypoint_gallery wpg_insert; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY wpg_insert ON public.waypoint_gallery FOR INSERT WITH CHECK (public.wp_can_edit(waypoint_id, auth.uid()));


--
-- Name: waypoint_gallery wpg_select; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY wpg_select ON public.waypoint_gallery FOR SELECT USING (public.wp_can_view(waypoint_id, auth.uid()));


--
-- Name: waypoint_gallery wpg_update; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY wpg_update ON public.waypoint_gallery FOR UPDATE USING (public.wp_can_edit(waypoint_id, auth.uid())) WITH CHECK (public.wp_can_edit(waypoint_id, auth.uid()));


--
-- Name: SCHEMA public; Type: ACL; Schema: -; Owner: -
--

GRANT USAGE ON SCHEMA public TO postgres;
GRANT USAGE ON SCHEMA public TO anon;
GRANT USAGE ON SCHEMA public TO authenticated;
GRANT USAGE ON SCHEMA public TO service_role;


--
-- Name: FUNCTION delete_account_by_username(_username text); Type: ACL; Schema: public; Owner: -
--

GRANT ALL ON FUNCTION public.delete_account_by_username(_username text) TO authenticated;


--
-- Name: FUNCTION distance_leaderboard(limit_count integer); Type: ACL; Schema: public; Owner: -
--

GRANT ALL ON FUNCTION public.distance_leaderboard(limit_count integer) TO anon;
GRANT ALL ON FUNCTION public.distance_leaderboard(limit_count integer) TO authenticated;


--
-- Name: FUNCTION get_top1_summary(); Type: ACL; Schema: public; Owner: -
--

GRANT ALL ON FUNCTION public.get_top1_summary() TO anon;
GRANT ALL ON FUNCTION public.get_top1_summary() TO authenticated;


--
-- Name: FUNCTION list_account_usernames(); Type: ACL; Schema: public; Owner: -
--

GRANT ALL ON FUNCTION public.list_account_usernames() TO anon;
GRANT ALL ON FUNCTION public.list_account_usernames() TO authenticated;


--
-- Name: FUNCTION list_stat_keys(); Type: ACL; Schema: public; Owner: -
--

GRANT ALL ON FUNCTION public.list_stat_keys() TO anon;
GRANT ALL ON FUNCTION public.list_stat_keys() TO authenticated;


--
-- Name: FUNCTION wp_can_edit(p_waypoint uuid, p_user uuid); Type: ACL; Schema: public; Owner: -
--

GRANT ALL ON FUNCTION public.wp_can_edit(p_waypoint uuid, p_user uuid) TO anon;
GRANT ALL ON FUNCTION public.wp_can_edit(p_waypoint uuid, p_user uuid) TO authenticated;


--
-- Name: FUNCTION wp_can_view(p_waypoint uuid, p_user uuid); Type: ACL; Schema: public; Owner: -
--

GRANT ALL ON FUNCTION public.wp_can_view(p_waypoint uuid, p_user uuid) TO anon;
GRANT ALL ON FUNCTION public.wp_can_view(p_waypoint uuid, p_user uuid) TO authenticated;


--
-- Name: FUNCTION wp_is_collab(p_waypoint uuid, p_user uuid); Type: ACL; Schema: public; Owner: -
--

GRANT ALL ON FUNCTION public.wp_is_collab(p_waypoint uuid, p_user uuid) TO anon;
GRANT ALL ON FUNCTION public.wp_is_collab(p_waypoint uuid, p_user uuid) TO authenticated;


--
-- Name: FUNCTION wp_is_creator(p_waypoint uuid, p_user uuid); Type: ACL; Schema: public; Owner: -
--

GRANT ALL ON FUNCTION public.wp_is_creator(p_waypoint uuid, p_user uuid) TO anon;
GRANT ALL ON FUNCTION public.wp_is_creator(p_waypoint uuid, p_user uuid) TO authenticated;


--
-- Name: FUNCTION wp_is_owner(p_waypoint uuid, p_user uuid); Type: ACL; Schema: public; Owner: -
--

GRANT ALL ON FUNCTION public.wp_is_owner(p_waypoint uuid, p_user uuid) TO anon;
GRANT ALL ON FUNCTION public.wp_is_owner(p_waypoint uuid, p_user uuid) TO authenticated;


--
-- Name: TABLE waypoints; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,REFERENCES,TRIGGER,TRUNCATE,MAINTAIN ON TABLE public.waypoints TO anon;
GRANT ALL ON TABLE public.waypoints TO authenticated;
GRANT REFERENCES,TRIGGER,TRUNCATE,MAINTAIN ON TABLE public.waypoints TO service_role;


--
-- Name: FUNCTION wp_transfer_waypoint(p_waypoint uuid, p_new_owner uuid, p_new_owner_username text); Type: ACL; Schema: public; Owner: -
--

GRANT ALL ON FUNCTION public.wp_transfer_waypoint(p_waypoint uuid, p_new_owner uuid, p_new_owner_username text) TO anon;
GRANT ALL ON FUNCTION public.wp_transfer_waypoint(p_waypoint uuid, p_new_owner uuid, p_new_owner_username text) TO authenticated;


--
-- Name: TABLE access_codes; Type: ACL; Schema: public; Owner: -
--

GRANT REFERENCES,TRIGGER,TRUNCATE,MAINTAIN ON TABLE public.access_codes TO anon;
GRANT ALL ON TABLE public.access_codes TO authenticated;
GRANT ALL ON TABLE public.access_codes TO service_role;


--
-- Name: TABLE achievement_criteria; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,REFERENCES,TRIGGER,TRUNCATE,MAINTAIN ON TABLE public.achievement_criteria TO anon;
GRANT SELECT,REFERENCES,TRIGGER,TRUNCATE,MAINTAIN ON TABLE public.achievement_criteria TO authenticated;
GRANT SELECT,INSERT,REFERENCES,TRIGGER,TRUNCATE,MAINTAIN,UPDATE ON TABLE public.achievement_criteria TO service_role;


--
-- Name: TABLE achievements; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,REFERENCES,TRIGGER,TRUNCATE,MAINTAIN ON TABLE public.achievements TO anon;
GRANT SELECT,REFERENCES,TRIGGER,TRUNCATE,MAINTAIN ON TABLE public.achievements TO authenticated;
GRANT SELECT,INSERT,REFERENCES,TRIGGER,TRUNCATE,MAINTAIN,UPDATE ON TABLE public.achievements TO service_role;


--
-- Name: TABLE categories; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,REFERENCES,TRIGGER,TRUNCATE,MAINTAIN ON TABLE public.categories TO anon;
GRANT ALL ON TABLE public.categories TO authenticated;
GRANT REFERENCES,TRIGGER,TRUNCATE,MAINTAIN ON TABLE public.categories TO service_role;


--
-- Name: TABLE chat_messages; Type: ACL; Schema: public; Owner: -
--

GRANT REFERENCES,TRIGGER,TRUNCATE,MAINTAIN ON TABLE public.chat_messages TO anon;
GRANT ALL ON TABLE public.chat_messages TO authenticated;
GRANT ALL ON TABLE public.chat_messages TO service_role;


--
-- Name: TABLE live_positions; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,REFERENCES,TRIGGER,TRUNCATE,MAINTAIN ON TABLE public.live_positions TO anon;
GRANT SELECT,REFERENCES,TRIGGER,TRUNCATE,MAINTAIN ON TABLE public.live_positions TO authenticated;
GRANT ALL ON TABLE public.live_positions TO service_role;


--
-- Name: TABLE logs; Type: ACL; Schema: public; Owner: -
--

GRANT REFERENCES,TRIGGER,TRUNCATE,MAINTAIN ON TABLE public.logs TO anon;
GRANT SELECT,REFERENCES,TRIGGER,TRUNCATE,MAINTAIN ON TABLE public.logs TO authenticated;
GRANT REFERENCES,TRIGGER,TRUNCATE,MAINTAIN ON TABLE public.logs TO service_role;


--
-- Name: TABLE player_achievement_criteria; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,REFERENCES,TRIGGER,TRUNCATE,MAINTAIN ON TABLE public.player_achievement_criteria TO anon;
GRANT SELECT,REFERENCES,TRIGGER,TRUNCATE,MAINTAIN ON TABLE public.player_achievement_criteria TO authenticated;
GRANT REFERENCES,TRIGGER,TRUNCATE,MAINTAIN ON TABLE public.player_achievement_criteria TO service_role;


--
-- Name: TABLE player_achievements; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,REFERENCES,TRIGGER,TRUNCATE,MAINTAIN ON TABLE public.player_achievements TO anon;
GRANT SELECT,REFERENCES,TRIGGER,TRUNCATE,MAINTAIN ON TABLE public.player_achievements TO authenticated;
GRANT REFERENCES,TRIGGER,TRUNCATE,MAINTAIN ON TABLE public.player_achievements TO service_role;


--
-- Name: TABLE player_stats; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,REFERENCES,TRIGGER,TRUNCATE,MAINTAIN ON TABLE public.player_stats TO anon;
GRANT SELECT,REFERENCES,TRIGGER,TRUNCATE,MAINTAIN ON TABLE public.player_stats TO authenticated;
GRANT ALL ON TABLE public.player_stats TO service_role;


--
-- Name: TABLE players; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,REFERENCES,TRIGGER,TRUNCATE,MAINTAIN ON TABLE public.players TO anon;
GRANT SELECT,REFERENCES,TRIGGER,TRUNCATE,MAINTAIN ON TABLE public.players TO authenticated;
GRANT SELECT,INSERT,REFERENCES,TRIGGER,TRUNCATE,MAINTAIN,UPDATE ON TABLE public.players TO service_role;


--
-- Name: COLUMN players.live_tracking_enabled; Type: ACL; Schema: public; Owner: -
--

GRANT UPDATE(live_tracking_enabled) ON TABLE public.players TO authenticated;


--
-- Name: TABLE profiles; Type: ACL; Schema: public; Owner: -
--

GRANT REFERENCES,TRIGGER,TRUNCATE,MAINTAIN ON TABLE public.profiles TO anon;
GRANT SELECT,REFERENCES,TRIGGER,TRUNCATE,MAINTAIN,UPDATE ON TABLE public.profiles TO authenticated;
GRANT SELECT,INSERT,REFERENCES,TRIGGER,TRUNCATE,MAINTAIN,UPDATE ON TABLE public.profiles TO service_role;


--
-- Name: TABLE rate_limits; Type: ACL; Schema: public; Owner: -
--

GRANT REFERENCES,TRIGGER,TRUNCATE,MAINTAIN ON TABLE public.rate_limits TO anon;
GRANT REFERENCES,TRIGGER,TRUNCATE,MAINTAIN ON TABLE public.rate_limits TO authenticated;
GRANT REFERENCES,TRIGGER,TRUNCATE,MAINTAIN ON TABLE public.rate_limits TO service_role;


--
-- Name: TABLE server_info; Type: ACL; Schema: public; Owner: -
--

GRANT REFERENCES,TRIGGER,TRUNCATE,MAINTAIN ON TABLE public.server_info TO anon;
GRANT ALL ON TABLE public.server_info TO authenticated;
GRANT REFERENCES,TRIGGER,TRUNCATE,MAINTAIN ON TABLE public.server_info TO service_role;


--
-- Name: TABLE server_status_public; Type: ACL; Schema: public; Owner: -
--

GRANT SELECT,REFERENCES,TRIGGER,TRUNCATE,MAINTAIN ON TABLE public.server_status_public TO anon;
GRANT SELECT,REFERENCES,TRIGGER,TRUNCATE,MAINTAIN ON TABLE public.server_status_public TO authenticated;
GRANT SELECT,INSERT,REFERENCES,TRIGGER,TRUNCATE,MAINTAIN,UPDATE ON TABLE public.server_status_public TO service_role;


--
-- Name: TABLE waypoint_collaborators; Type: ACL; Schema: public; Owner: -
--

GRANT ALL ON TABLE public.waypoint_collaborators TO anon;
GRANT ALL ON TABLE public.waypoint_collaborators TO authenticated;
GRANT REFERENCES,TRIGGER,TRUNCATE,MAINTAIN ON TABLE public.waypoint_collaborators TO service_role;


--
-- Name: TABLE waypoint_gallery; Type: ACL; Schema: public; Owner: -
--

GRANT ALL ON TABLE public.waypoint_gallery TO anon;
GRANT ALL ON TABLE public.waypoint_gallery TO authenticated;
GRANT REFERENCES,TRIGGER,TRUNCATE,MAINTAIN ON TABLE public.waypoint_gallery TO service_role;


--
-- Name: TABLE whitelist; Type: ACL; Schema: public; Owner: -
--

GRANT REFERENCES,TRIGGER,TRUNCATE,MAINTAIN ON TABLE public.whitelist TO anon;
GRANT SELECT,REFERENCES,TRIGGER,TRUNCATE,MAINTAIN ON TABLE public.whitelist TO authenticated;
GRANT ALL ON TABLE public.whitelist TO service_role;


--
-- Name: TABLE whitelist_commands; Type: ACL; Schema: public; Owner: -
--

GRANT REFERENCES,TRIGGER,TRUNCATE,MAINTAIN ON TABLE public.whitelist_commands TO anon;
GRANT SELECT,INSERT,REFERENCES,DELETE,TRIGGER,TRUNCATE,MAINTAIN ON TABLE public.whitelist_commands TO authenticated;
GRANT SELECT,REFERENCES,TRIGGER,TRUNCATE,MAINTAIN,UPDATE ON TABLE public.whitelist_commands TO service_role;


--
-- Name: DEFAULT PRIVILEGES FOR SEQUENCES; Type: DEFAULT ACL; Schema: public; Owner: -
--

ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public GRANT ALL ON SEQUENCES TO postgres;


--
-- Name: DEFAULT PRIVILEGES FOR SEQUENCES; Type: DEFAULT ACL; Schema: public; Owner: -
--

ALTER DEFAULT PRIVILEGES FOR ROLE supabase_admin IN SCHEMA public GRANT ALL ON SEQUENCES TO postgres;
ALTER DEFAULT PRIVILEGES FOR ROLE supabase_admin IN SCHEMA public GRANT ALL ON SEQUENCES TO anon;
ALTER DEFAULT PRIVILEGES FOR ROLE supabase_admin IN SCHEMA public GRANT ALL ON SEQUENCES TO authenticated;
ALTER DEFAULT PRIVILEGES FOR ROLE supabase_admin IN SCHEMA public GRANT ALL ON SEQUENCES TO service_role;


--
-- Name: DEFAULT PRIVILEGES FOR FUNCTIONS; Type: DEFAULT ACL; Schema: public; Owner: -
--

ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public GRANT ALL ON FUNCTIONS TO postgres;


--
-- Name: DEFAULT PRIVILEGES FOR FUNCTIONS; Type: DEFAULT ACL; Schema: public; Owner: -
--

ALTER DEFAULT PRIVILEGES FOR ROLE supabase_admin IN SCHEMA public GRANT ALL ON FUNCTIONS TO postgres;
ALTER DEFAULT PRIVILEGES FOR ROLE supabase_admin IN SCHEMA public GRANT ALL ON FUNCTIONS TO anon;
ALTER DEFAULT PRIVILEGES FOR ROLE supabase_admin IN SCHEMA public GRANT ALL ON FUNCTIONS TO authenticated;
ALTER DEFAULT PRIVILEGES FOR ROLE supabase_admin IN SCHEMA public GRANT ALL ON FUNCTIONS TO service_role;


--
-- Name: DEFAULT PRIVILEGES FOR TABLES; Type: DEFAULT ACL; Schema: public; Owner: -
--

ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public GRANT ALL ON TABLES TO postgres;
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public GRANT REFERENCES,TRIGGER,TRUNCATE,MAINTAIN ON TABLES TO anon;
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public GRANT REFERENCES,TRIGGER,TRUNCATE,MAINTAIN ON TABLES TO authenticated;
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public GRANT REFERENCES,TRIGGER,TRUNCATE,MAINTAIN ON TABLES TO service_role;


--
-- Name: DEFAULT PRIVILEGES FOR TABLES; Type: DEFAULT ACL; Schema: public; Owner: -
--

ALTER DEFAULT PRIVILEGES FOR ROLE supabase_admin IN SCHEMA public GRANT ALL ON TABLES TO postgres;
ALTER DEFAULT PRIVILEGES FOR ROLE supabase_admin IN SCHEMA public GRANT ALL ON TABLES TO anon;
ALTER DEFAULT PRIVILEGES FOR ROLE supabase_admin IN SCHEMA public GRANT ALL ON TABLES TO authenticated;
ALTER DEFAULT PRIVILEGES FOR ROLE supabase_admin IN SCHEMA public GRANT ALL ON TABLES TO service_role;


--
-- PostgreSQL database dump complete
--

\unrestrict FV1wXX5NeFVyd3g4nMIfXOHfb9S3ANjj2wf67Dw9xhb7j1gW4esH1A8oHCORv4h

