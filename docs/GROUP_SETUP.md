# İz 0.8.0 group setup

Groups require a dedicated Supabase project. Local maps, navigation and diary recording remain usable when configuration is empty. Only public project URL and publishable/legacy anon key enter the APK; never use a service-role or secret key in Android.

## Deploy

1. Create a dedicated Supabase project.
2. Enable **Authentication → Providers → Anonymous Sign-Ins**. Keep provider IP rate limits enabled. The anonymous device receives its own Auth user ID; no shared anonymous identity, email or Google sign-in is used. Captcha is not implemented in this client; enabling a mandatory captcha will make signup fail closed until a captcha flow is added.
3. Execute `supabase/migrations/202609110001_groups.sql` with SQL Editor, or link the project and run `supabase db push`. The migration installs metadata tables, server-only RPCs, restrictive private Realtime policies and a 15-minute `pg_cron` cleanup job. Use a dedicated project: the restrictive Realtime policy intentionally denies client broadcasts and unrelated authenticated receive topics.
4. Deploy `supabase/functions/group-api` using `supabase functions deploy group-api --project-ref YOUR_REF --no-verify-jwt`. `supabase/config.toml` also sets `verify_jwt=false`. The function itself verifies every bearer token with Auth `/user` and requires an anonymous user. The Edge function verifies current device access tokens itself; this is **not** an unauthenticated endpoint.
5. The Edge runtime supplies `SUPABASE_URL` and `SUPABASE_SERVICE_ROLE_KEY`; keep that server key only in Supabase. Do not copy it to Android, source control, screenshots, documentation or logs.
6. Set `GROUP_SUPABASE_URL` and `GROUP_SUPABASE_KEY` (the publishable or legacy anon key) as environment variables before running Gradle, or add them to the root `local.properties` file. The Android build reads those two sources directly; Gradle `-P` project properties are not build inputs. Empty values show a clear unconfigured state.
7. Use only private Realtime channels. If project settings expose the private-only channel setting, enable it as additional defense. No Postgres-change publication is needed.

## Behavior and retention

A group freezes common vias and destination at creation and expires within 24 hours. Its invite code/QR expires after 15 minutes; host renewal immediately replaces the old code. Host approval is required. Up to ten pending/approved members including the host reserve places; removed users cannot reuse that ride's invitation. Each person plans from their current position with their own transport choice.

Joining grants no location permission and starts no diary/session. Explicit consent requires an active real trip session. Consent enables both sending and receiving live positions for that ride. The app requests fresh consent after a process restart or lease expiry. Sharing can remain active with diary recording off. Stopping, leaving or ending stops local sends immediately; a failed remote stop cannot retract an already delivered/in-flight message, and the server consent lease expires within 60 seconds without renewal.

Positions exist only in memory and transit. Android publishes the latest fresh fix at most every 10 seconds moving or 30 seconds stationary. Failed position requests are dropped; there is no retry queue, coordinate database, historical replay or Realtime replay request. Markers become delayed after 30 seconds and disappear after 5 minutes, including during network failure. Offline users remain in the member list. The service checks sender and recipient current membership and consent before each recipient delivery. RLS subscription caching is not relied on to revoke access. Receivers subscribe to their own random private inbox, which rotates on consent changes/removal. Clients cannot publish directly to those private inboxes.

Only names, group route stops, membership, invite expiry, consent leases, rate counters and publish timestamps persist in the group database. Auth sessions are stored in Android's app-private `noBackupFilesDir`; no location is stored there. No health, diary, photo or other device data is uploaded. Keep Edge request bodies out of custom logs and tracing. Default code logs no requests or credentials. Cleanup removes ended/expired groups every 15 minutes and anonymous users inactive for seven days with no remaining group membership or recent authenticated group activity. If cleanup has deleted a device identity, a confirmed invalid refresh token creates a new anonymous identity; local membership and consent are cleared and the original action is not replayed. Network failures and rate limits never rotate identities. Review provider-level usage and limits in the dashboard.

## Verification

Install Node.js 24 or newer, then run the complete local backend suite from a clean dependency install:

```shell
cd supabase
npm ci
npm test
```

The package pins PGlite for reproducible SQL tests. The suite runs validation, consent/revocation relay and HTTP authentication tests against local fake servers, and executes the production migration against PGlite with minimal Auth/Realtime schema fixtures. It does not contact or modify a Supabase project. The harness omits only the provider's `pg_cron` installation and schedule; verify that job in the provider dashboard after deployment.

Set `GROUP_SUPABASE_URL` and `GROUP_SUPABASE_KEY` in the terminal and run `node supabase/tests/live-smoke.mjs` for a real provider test. It creates three synthetic anonymous devices and an ephemeral group, verifies a real two-client private WebSocket relay, rejects a stranger and direct client broadcast, removes the recipient while retaining its original socket, and confirms no later coordinates are delivered. It ends the test group in `finally`; anonymous cleanup removes the accounts later. Synthetic `(0,0)` coordinates are used, never a real person's location. Do not run against an unrelated existing application project.

Android group unit tests can be run from the repository root with `./gradlew :app:testDebugUnitTest --tests 'com.atay.iz.group.*'`. Device navigation/group sharing, process death, actual background GPS and two physical device UX need real hardware; a successful backend test does not claim a road test.

## Official references

- [Anonymous sign-ins](https://supabase.com/docs/guides/auth/auth-anonymous)
- [Realtime authorization](https://supabase.com/docs/guides/realtime/authorization)
- [Realtime protocol and private join](https://supabase.com/docs/guides/realtime/protocol)
- [Server REST broadcasts](https://supabase.com/docs/guides/realtime/broadcast)

