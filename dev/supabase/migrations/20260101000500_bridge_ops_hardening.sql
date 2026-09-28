-- 20260101000500_bridge_ops_hardening.sql
--
-- Reduces the attack surface of the /bridge command queue.
--
-- 1. INSERT on bridge_commands is revoked from authenticated and the
--    "bridge_commands owner insert" policy is dropped. The ONLY way a website
--    session can queue work is request_bridge_command(), which now runs as
--    SECURITY DEFINER (activates RLS from within) and enforces the owner
--    check itself, so a hijacked owner session cannot forge a request that
--    bypasses the function.
-- 2. The dead p_args parameter and args column are removed end to end: the
--    plugin never read args (poll selects id/command/requested_by_username),
--    and the site sent an empty object. Both old signatures are dropped so a
--    stale call site fails loudly instead of side-stepping the new one.
-- 3. EXECUTE is revoked from public; only authenticated may call it.

drop policy if exists "bridge_commands owner insert" on public.bridge_commands;
revoke insert on public.bridge_commands from authenticated;

drop function if exists public.request_bridge_command(text, jsonb);
drop function if exists public.request_bridge_command(text);

create or replace function public.request_bridge_command(p_command text)
returns public.bridge_commands
language plpgsql
security definer
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

  insert into public.bridge_commands (command, requested_by, requested_by_username)
  values (
    p_command,
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

revoke all on function public.request_bridge_command(text) from public;
grant execute on function public.request_bridge_command(text) to authenticated;

alter table public.bridge_commands drop column if exists args;