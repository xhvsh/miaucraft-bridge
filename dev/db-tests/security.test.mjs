import { test, before, beforeEach } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import { PGlite } from "@electric-sql/pglite";

const here = dirname(fileURLToPath(import.meta.url));
const baseline = readFileSync(join(here, "baseline.sql"), "utf8");
const migration = readFileSync(join(here, "../supabase/migrations/20260101000600_security_hardening.sql"), "utf8");
const rollback = readFileSync(join(here, "../supabase/rollbacks/20260101000600_security_hardening.down.sql"), "utf8");

const OWNER = "00000000-0000-0000-0000-00000000000a";
const ADMIN = "00000000-0000-0000-0000-00000000000b";
const MEMBER = "00000000-0000-0000-0000-00000000000c";
const DISCORD_ONLY = "00000000-0000-0000-0000-00000000000d";

let db;

/** Runs `fn` as an API role with a given JWT subject, then restores the superuser. */
async function as(role, uid, fn) {
  await db.exec(`select set_config('request.jwt.claim.sub', '${uid ?? ""}', false); set role ${role};`);
  try {
    return await fn();
  } finally {
    await db.exec("reset role;");
  }
}

async function rejects(promise, pattern) {
  await assert.rejects(promise, (err) => {
    assert.match(String(err.message), pattern, `unexpected error: ${err.message}`);
    return true;
  });
}

before(async () => {
  db = new PGlite();
  await db.exec(baseline);
  await db.exec(`
    insert into auth.users (id, raw_app_meta_data, raw_user_meta_data) values
      ('${OWNER}',  '{"provider":"email","role":"owner"}', '{"username":"boss"}'),
      ('${ADMIN}',  '{"provider":"email","role":"user"}',  '{"username":"mod"}'),
      ('${MEMBER}', '{"provider":"email","role":"user"}',  '{"username":"alex"}');
    update public.profiles set role = 'admin' where id = '${ADMIN}';
  `);
  await db.exec(migration);
  // A Discord sign-up that never redeemed an access code, created AFTER the fix
  // (production had no such account when the migration was written).
  await db.exec(`insert into auth.users (id, raw_app_meta_data, raw_user_meta_data)
                 values ('${DISCORD_ONLY}', '{"provider":"discord"}', '{"username":"intruder"}')`);
});

beforeEach(async () => {
  await db.exec("delete from public.chat_messages; delete from public.rate_limits;");
});

test("migration is idempotent", async () => {
  await db.exec(migration);
  await db.exec(migration);
});

// ---------------------------------------------------------------- chat

test("chat: a member can post as themselves", async () => {
  await as("authenticated", MEMBER, () =>
    db.query("insert into public.chat_messages (kind, user_id, username, message) values ('web', $1, 'alex', 'hi')", [MEMBER]));
  const { rows } = await db.query("select count(*)::int n from public.chat_messages");
  assert.equal(rows[0].n, 1);
});

test("chat: impersonating another username is rejected", async () => {
  await rejects(
    as("authenticated", MEMBER, () =>
      db.query("insert into public.chat_messages (kind, user_id, username, message) values ('web', $1, 'boss', 'hi')", [MEMBER])),
    /row-level security/);
});

test("chat: impersonating another user id is rejected", async () => {
  await rejects(
    as("authenticated", MEMBER, () =>
      db.query("insert into public.chat_messages (kind, user_id, username, message) values ('web', $1, 'alex', 'hi')", [OWNER])),
    /row-level security/);
});

test("chat: forging a system notice is rejected", async () => {
  await rejects(
    as("authenticated", MEMBER, () =>
      db.query("insert into public.chat_messages (kind, username, message) values ('system', null, 'Server offline')")),
    /row-level security/);
});

test("chat: forging a server (in-game) message is rejected", async () => {
  await rejects(
    as("authenticated", MEMBER, () =>
      db.query("insert into public.chat_messages (kind, user_id, username, message) values ('server', $1, 'alex', 'x')", [MEMBER])),
    /row-level security/);
});

test("chat: oversized and empty messages are rejected", async () => {
  await rejects(
    as("authenticated", MEMBER, () =>
      db.query("insert into public.chat_messages (kind, user_id, username, message) values ('web', $1, 'alex', $2)", [MEMBER, "x".repeat(501)])),
    /row-level security/);
  await rejects(
    as("authenticated", MEMBER, () =>
      db.query("insert into public.chat_messages (kind, user_id, username, message) values ('web', $1, 'alex', '')", [MEMBER])),
    /row-level security/);
});

test("chat: the service role (plugin) can still write any kind, long server rows included", async () => {
  await as("service_role", null, async () => {
    await db.query("insert into public.chat_messages (kind, username, message) values ('server', 'Steve', 'hello')");
    await db.query("insert into public.chat_messages (kind, message) values ('system', 'Server online')");
  });
  await rejects(
    db.query("insert into public.chat_messages (kind, message) values ('system', $1)", ["y".repeat(2001)]),
    /chat_messages_message_len/);
});

test("chat: bursts are rate limited per user", async () => {
  await as("authenticated", MEMBER, async () => {
    for (let i = 0; i < 5; i++) {
      await db.query("insert into public.chat_messages (kind, user_id, username, message) values ('web', $1, 'alex', $2)", [MEMBER, `m${i}`]);
    }
    await rejects(
      db.query("insert into public.chat_messages (kind, user_id, username, message) values ('web', $1, 'alex', 'spam')", [MEMBER]),
      /too fast/);
  });
});

test("chat: a session without a profile cannot read chat, logs or server info", async () => {
  await db.exec("insert into public.chat_messages (kind, message) values ('system', 'secret'); insert into public.server_info values ('ip', '1.2.3.4');");
  for (const uid of [DISCORD_ONLY]) {
    const chat = await as("authenticated", uid, () => db.query("select * from public.chat_messages"));
    assert.equal(chat.rows.length, 0);
    const info = await as("authenticated", uid, () => db.query("select * from public.server_info"));
    assert.equal(info.rows.length, 0);
  }
  const member = await as("authenticated", MEMBER, () => db.query("select * from public.server_info"));
  assert.equal(member.rows.length, 1);
});

// ------------------------------------------------------------ profiles

test("profiles: an admin can no longer rewrite another account's username", async () => {
  await rejects(
    as("authenticated", ADMIN, () => db.query("update public.profiles set username = 'hacked' where id = $1", [MEMBER])),
    /permission denied/);
});

test("profiles: an admin cannot promote anyone (role trigger still applies)", async () => {
  await rejects(
    as("authenticated", ADMIN, () => db.query("update public.profiles set role = 'owner' where id = $1", [ADMIN])),
    /Only the owner can change roles/);
});

test("profiles: the owner can still change a role", async () => {
  await as("authenticated", OWNER, () => db.query("update public.profiles set role = 'admin' where id = $1", [MEMBER]));
  const { rows } = await db.query("select role from public.profiles where id = $1", [MEMBER]);
  assert.equal(rows[0].role, "admin");
  await db.query("update public.profiles set role = 'user' where id = $1", [MEMBER]);
});

test("profiles: a plain member cannot touch any profile", async () => {
  const res = await as("authenticated", MEMBER, () => db.query("update public.profiles set role = 'owner' where id = $1", [MEMBER]));
  assert.equal(res.affectedRows, 0);
});

// ------------------------------------------------------ OAuth sign-ups

test("handle_new_user: email sign-ups still get a profile, OAuth sign-ups do not", async () => {
  await db.exec(`insert into auth.users (id, raw_app_meta_data, raw_user_meta_data) values
    ('00000000-0000-0000-0000-0000000000e1', '{"provider":"email","role":"user"}', '{"username":"newbie"}'),
    ('00000000-0000-0000-0000-0000000000e2', '{"provider":"discord"}', '{"username":"stranger"}')`);
  const { rows } = await db.query("select id from public.profiles where id in ('00000000-0000-0000-0000-0000000000e1','00000000-0000-0000-0000-0000000000e2')");
  assert.deepEqual(rows.map((r) => r.id), ["00000000-0000-0000-0000-0000000000e1"]);
});

// ------------------------------------------------------ grants / RPC

test("grants: API roles lost TRUNCATE; anon lost writes", async () => {
  await rejects(as("authenticated", MEMBER, () => db.query("truncate public.chat_messages")), /permission denied/);
  await rejects(as("anon", null, () => db.query("insert into public.server_info values ('a','b')")), /permission denied/);
});

test("rpc: anonymous visitors cannot list account usernames or run merge_player", async () => {
  await rejects(as("anon", null, () => db.query("select * from public.list_account_usernames()")), /permission denied/);
  await rejects(as("authenticated", MEMBER, () => db.query("select public.merge_player(gen_random_uuid(), gen_random_uuid())")), /permission denied/);
});

test("rpc: trigger functions are not callable through the API", async () => {
  await rejects(as("authenticated", MEMBER, () => db.query("select public.handle_new_user()")), /permission denied/);
});

// -------------------------------------------------------- rate limits

test("rate limit: five failures lock the key, a clear resets it, the window expires", async () => {
  const allowed = () => db.query("select public.rate_limit_allowed('signin:user:alex', 5) ok").then((r) => r.rows[0].ok);
  assert.equal(await allowed(), true);
  for (let i = 0; i < 5; i++) await db.query("select public.rate_limit_fail('signin:user:alex', 3600)");
  assert.equal(await allowed(), false);
  await db.query("select public.rate_limit_clear('signin:user:alex')");
  assert.equal(await allowed(), true);

  await db.query("select public.rate_limit_fail('signin:user:bob', 3600)");
  await db.query("update public.rate_limits set reset_at = now() - interval '1 second', count = 99 where key = 'signin:user:bob'");
  assert.equal((await db.query("select public.rate_limit_allowed('signin:user:bob', 5) ok")).rows[0].ok, true);
  await db.query("select public.rate_limit_fail('signin:user:bob', 3600)");
  assert.equal((await db.query("select count from public.rate_limits where key = 'signin:user:bob'")).rows[0].count, 1);
});

test("rate limit: concurrent failures are all counted (atomic upsert)", async () => {
  await Promise.all(Array.from({ length: 8 }, () => db.query("select public.rate_limit_fail('register:ip:1.1.1.1', 3600)")));
  assert.equal((await db.query("select count from public.rate_limits where key = 'register:ip:1.1.1.1'")).rows[0].count, 8);
});

test("rate limit: the table and functions are unreachable for API roles", async () => {
  await rejects(as("anon", null, () => db.query("select * from public.rate_limits")), /permission denied/);
  await rejects(as("authenticated", MEMBER, () => db.query("select public.rate_limit_clear('x')")), /permission denied/);
});

// ------------------------------------------------------------ misc

test("storage + indexes", async () => {
  const b = (await db.query("select allowed_mime_types, file_size_limit from storage.buckets where id = 'waypoint-gallery'")).rows[0];
  assert.deepEqual(b.allowed_mime_types, ["image/jpeg", "image/png", "image/webp"]);
  assert.equal(Number(b.file_size_limit), 5242880);
  const idx = (await db.query("select indexname from pg_indexes where schemaname = 'public'")).rows.map((r) => r.indexname);
  assert.ok(!idx.includes("idx_player_stats_player_id"));
  assert.ok(!idx.includes("waypoint_collaborators_waypoint_idx"));
  assert.ok(idx.includes("chat_messages_user_created_idx"));
});

test("waypoints: name, description, coordinate and colour limits hold for new rows", async () => {
  const ins = (name, desc, x, z, color) =>
    db.query("insert into public.waypoints (name, description, x, z, color) values ($1, $2, $3, $4, $5)", [name, desc, x, z, color]);
  await ins("Home", "base", 100, -200, "#aabbcc");
  await ins("Edge", null, 30000000, -30000000, null);
  await rejects(ins("", null, 0, 0, null), /waypoints_name_len/);
  await rejects(ins("n".repeat(61), null, 0, 0, null), /waypoints_name_len/);
  await rejects(ins("ok", "d".repeat(161), 0, 0, null), /waypoints_description_len/);
  await rejects(ins("ok", null, 30000001, 0, null), /waypoints_xz_range/);
  await rejects(ins("ok", null, 0, -30000001, null), /waypoints_xz_range/);
  await rejects(ins("ok", null, 0, 0, "red"), /waypoints_color_format/);
  await rejects(db.query("insert into public.waypoint_gallery (caption) values ($1)", ["c".repeat(201)]), /waypoint_gallery_caption_len/);
});

test("rollback script runs cleanly on top of the migration", async () => {
  await db.exec(rollback);
  // the forged-notice hole is intentionally open again after a rollback
  await as("authenticated", MEMBER, () => db.query("insert into public.chat_messages (kind, message) values ('system', 'x')"));
});
