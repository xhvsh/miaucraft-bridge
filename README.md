# miaucraft-bridge

Paper plugin (Java 21, Paper 1.21.x) that mirrors a Minecraft server into Supabase for the [Miaucraft website](https://github.com/xhvsh/miaucraft): online players, stats, achievements, live positions, TPS, status, whitelist, in-game ⇄ web chat, an optional live map, plus an owner-facing diagnostics/action queue and a self-updater.

## How it works

- **Writes** go through `SinkManager` queues (coalesced per conflict key, flushed in foreign-key order every 5 s, bounded, per-sink exponential backoff) using the **service-role key** from the server's local `config.yml`. Never commit that file.
- **Reads/polls**: `whitelist_commands`, `bridge_commands` (owner actions — a fixed set of symbols, never a console string), `chat_messages` (kind `web`, treated as untrusted and sanitised before being broadcast).
- **Behaviour** comes from `remote-config.json` in this repo (polled; `update.*` and credentials are deliberately local-only).
- **Threading**: HTTP is `sendAsync`; Bukkit API calls are scheduled onto the main thread; nothing slow runs on it. A transient database outage only grows the bounded queues and backs sinks off (max 60 s).

## Build and test

```bash
mvn test          # 79 unit tests
mvn package       # target/miaucraft-bridge-plugin.jar
cd dev/db-tests && npm install && npm test    # SQL regression tests for the migrations (in-process Postgres, no Docker)
```

## Database migrations

`dev/supabase/migrations/` is the ordered chain. `20260101000000_init` … `0500` are the local-dev reconstruction plus the production fixes; **`0600_security_hardening` is written against the real production schema** and is guarded so the local chain still runs. Rollbacks live in `dev/supabase/rollbacks/` (outside the chain on purpose). The SQL tests replay a prod-shaped baseline (`dev/db-tests/baseline.sql`), the migration, and the rollback.

Compatibility: every deployed plugin version (≤ 2.6.1) keeps working after `0600` — the plugin only uses the service role (bypasses RLS), and the migration keeps its grants.

## Releasing

Servers update themselves from `plugin-update.json` on `main`. **Each release must be signed**; servers on 2.7.0+ refuse an unsigned or wrongly signed manifest.

One-time setup (already done on the maintainer's machine; the public key is compiled into `UpdateSignature.RELEASE_PUBLIC_KEY`):

```bash
java dev/ReleaseSigner.java gen %USERPROFILE%\.miaucraft\release-signing.key
```

Back that file up and keep it out of the repository. To rotate the key, release a build signed with the old key that contains the new public key.

Release:

```powershell
powershell -File publish-release.ps1 -Version 2.7.0 -SiteRepo C:\path\to\miaucraft
```

The script bumps `pom.xml`, runs the tests, builds, checks `plugin.yml`, copies the jar to `releases/`, signs `(version, sha256)` and writes the manifest. Then commit `plugin-update.json` and `releases/*.jar` (and the mirrored `bridge/` in the site repo) and push. Old servers ignore the extra `signature` field.

Local dev servers: `dev/publish-update.ps1` writes an **unsigned** manifest, so set `update.require-signature: false` in that dev server's `config.yml` (never in production).

## Configuration

See the comments in `src/main/resources/config.yml`. Notable keys: `enabled` (master kill switch), `update.require-signature` / `update.public-key`, `update.auto-apply` (default `false`), `bridge.retention.purge-hours`.

## Security notes

See the site repository's `docs/SECURITY-AUDIT.md` for the combined audit. Rules this plugin relies on: the service-role key stays in `config.yml` only; rows written by website users are validated by database policies *and* sanitised here; updates are verified (https, size cap, sha256, Ed25519 signature, `plugin.yml` version match, atomic install).
