# piko fork (pmaxhogan/piko) - notes for agents

This is a fork of crimera/piko. Upstream owns almost everything; the fork adds the
Vantage-only X patches ("Hide verified users", "Daily time limit") and the Postbox
(locked-down Instagram) patches, and builds the `.mpp` that `pmaxhogan/vantage`
consumes. Every line changed in an upstream-owned file is a future merge conflict for
the nightly `sync-upstream.yml`, so add new files and new directories, and touch
upstream files only when there is no other way.

## Hygiene

- Add files by name; `gradlew.bat` shows as modified in the working tree and is not
  part of any change here. Leave it alone.
- `gradle.properties` stays byte-identical to upstream (the Vantage version suffix is
  applied in `build-mpp.yml` at build time).
- ASCII `-` only, no em/en-dashes. LF endings.

## Where the Postbox code lives

Only in two fork-owned directories, plus tests and one build block:

- Patches: `patches/src/main/kotlin/app/crimera/patches/instagram/postbox/*.kt`
  (`PostboxCorePatch.kt` shared helpers + the internal init patch, and one file per
  patch: `PostboxSettingsLockPatch.kt`, `PostboxShortcutLockPatch.kt`,
  `PostboxLinkRouterPatch.kt`, `PostboxNetworkBlockPatch.kt`, `PostboxProfileGridPatch.kt`).
- Runtime: `extensions/instagram/src/main/java/app/morphe/extension/instagram/patches/postbox/*.java`
  (`Postbox` lifecycle hub, `PostboxBuild` switches, `PostboxNav`, `PostboxSettings`,
  `PostboxShortcuts`, `PostboxLinks` + `PostboxLinkRouter`, `PostboxNet`,
  `PostboxProfile` + `PostboxProfileNet`).
- Tests: `extensions/instagram/src/test/java/app/morphe/extension/instagram/patches/postbox/`.
- `extensions/instagram/build.gradle.kts` carries a fork-only block adding
  `testImplementation("junit:junit:4.13.2")` (the Instagram extension had no tests
  upstream). Keep it a separate block at the end so upstream edits above do not collide.

The five patches are `Postbox settings lock`, `Postbox shortcut lock`,
`Postbox link router`, `Postbox network block` and `Postbox profile grid`, all `default = false`, so a plain
piko user never gets them. Their header comments explain each one; read those before
editing.

## The no-fingerprint technique

The Postbox patches carry no `Fingerprint` for obfuscated Instagram code. They edit
piko's OWN extension classes inside the patched dex, found by class and method name,
at patch time. The one Instagram method touched is the `InstagramAppShell.onCreate`
that piko's own `instagramInitHook` already fingerprints (the core patch adds the
`Postbox.init(Context)` call right after `super.onCreate()`, in the same spot and
register piko uses).

- Every lookup `throw`s a `PatchException` naming what moved (class missing, method
  count != 1, signature changed, no `IOException` in the `throws` annotation, call
  landed inside a try block). An upstream rename must fail the BUILD, never ship an
  unlocked app. Preserve that: when you add a hook, resolve all targets first, edit
  second, then re-read the result and assert its shape.
- Methods are edited IN PLACE (`addInstructions(0, ...)` on the `MutableMethod`
  object), never replaced with a rebuilt method. `MutableClass` keeps `methods` and
  `directMethods` as separate lazily built sets and the dex writer reads the latter, so
  swapping the object is only safe while nobody has materialised that set. An in-place
  edit has no ordering hazard. `forceConstantReturn` prepends `const` + `return` and
  leaves the old body as unreachable code, like Morphe's `returnEarly`.
- Hooks are inserted with the `invoke-static/range` form so any register number works;
  `addInstructionsAtControlFlowLabel` is used where a branch could otherwise jump past
  the new code (the `transformNavigationTabs` returns).
- Hooks that throw on purpose (`Links.interceptUri` -> `IOException` to fail a request)
  must sit OUTSIDE piko's try block: that block catches `Exception` and would swallow
  the block. `hookInterceptUri` checks this and fails the build otherwise.

### `PostboxBuild`: the constant getters

`PostboxBuild.shortcutLock() / linkRouter() / networkBlock() / profileGrid() / traceNetwork()` are the
build switches. In source each is a bare `return false;`, which compiles to exactly
`const/4 vX, 0x0; return vX`. A patch turns its feature on by rewriting that constant
to `0x1` in the dex (`enableBuildFlag`). Rules:

- Keep every getter a bare `return false`. `requireBuildFlag` refuses any other shape
  (logic added, renamed, already on) and fails the build; `PostboxBuildTest` pins the
  names and the false defaults.
- One getter per patch, set once. Nothing at runtime (a pref, a system property, an
  intent) may be able to flip one.
- A new feature gets its own getter, its own `FLAG_*` constant in `PostboxCorePatch.kt`,
  a `Log.i` line in `Postbox.init`, and an entry in the `PostboxBuildTest` expected set.

### `Postbox.init` runs before `Utils.setContext`

The core patch calls `Postbox.init(Context)` from `Application.onCreate`, and it runs
before the shared extension has been handed its context (`Utils.setContext`), so nothing
reachable from `init` (or from `PostboxShortcuts.enforce` called by it) may call
`Utils.getContext()`. Use the `Application`/`Context` that `init` was handed. The Postbox
classes do not reference `Utils` at all today; keep it that way. (No code comment states
this ordering; it follows from the patch graph: `postboxCorePatch` depends on
`sharedExtensionPatch`, whose hook inserts the `Utils.setContext` call at
`super.onCreate()` + 1 first, and `hookApplicationInit` then inserts at the same index, in
front of it, pushing `setContext` down. Check `hookApplicationInit` and piko's
`instagramInitHook` if you change either.)

### `PostboxNav.NAV_CONFIG` is read out of the dex

`PostboxNav.NAV_CONFIG` (`v2|home:1,reels:0,messages:1,search:0,profile:1,create:0,notifications:0|messages`)
is the single source of the bottom-bar layout. `Postbox settings lock` reads the field's
static value out of the extension dex (`readNavConfig`) and bakes it into
`Pref.navigationTabs()`. It must stay a compile-time `static final String` literal (one
literal, no method call) and in piko's canonical encoding (every tab listed, default
order); `PostboxNavConfigTest` runs it through piko's real parser.

### Tests fail on purpose

`PostboxNavConfigTest` reaches piko's PRIVATE `NavigationBarPatch.resolveConfig`,
`encodeConfig` and `startupChoices` by reflection. When upstream renames or reshapes
them the test fails, and that is the point: re-verify the fixed layout against the new
parser before a Postbox build ships. Do not "fix" it by loosening the reflection;
re-point it and re-read what the method now does. The same goes for `PostboxNavTest`,
which references `NavigationBarPatch.Tab`.

## Adding to the network block or the link router

- Network block: add the path substring to `BLOCKED_PATHS` in `PostboxNet.java` (a
  trailing slash is deliberate where a longer sibling must stay reachable; the stories
  tray is `/feed/reels_tray/` and must never match), or a parser key to
  `DROPPED_PARSER_KEYS`, and add a row to `PostboxNetTest`, including a near-miss that
  must NOT be blocked.
- Link router: edit the tables in `PostboxLinks.java` (it is pure Java, no Android
  types) and add a row to `PostboxLinksTest`. Unknown `instagram://` hosts and unknown
  instagram.com paths bounce by design; a wrongly bounced one logs a `bounce` line in
  logcat naming the host to add.
- Finding endpoints: build a verification bundle with the `Postbox network block`
  option `traceNetwork` = true (options file or Morphe Manager, locally), install it,
  and read `adb logcat -s PostboxNet PostboxNetBlock` while using a surface. Path only is
  logged. NEVER ship a trace build as a release: `build-postbox.sh` in vantage refuses
  to build when any enabled option whose key matches test/debug/trace is on, and do not
  commit an options file with it on.

## Build, test, release

- Local: Temurin 21 (`export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home`);
  CI uses Java 17.
- The Morphe registry is GitHub Packages and needs `GITHUB_ACTOR` and `GITHUB_TOKEN`
  (a token with `read:packages`) in the environment.
- Tests: `./gradlew :extensions:instagram:testDebugUnitTest :extensions:twitter:testDebugUnitTest`
- Bundle: `./gradlew :patches:buildAndroid -Pversion=<x>` -> `patches/build/libs/patches-*.mpp`.
- `.github/workflows/build-mpp.yml` (push to main on `extensions/** patches/** gradle/**`
  etc., or dispatch): tests, then the bundle as `<upstream>-vantage.<VANTAGE_REV>`,
  then a release `v<that>`. Bump `VANTAGE_REV` in the workflow for a fork-only rebuild on
  the same upstream version (an upstream bump already gives a fresh tag).
- `MPP_PRERELEASE` (repo variable, "true"/"false") marks the release a prerelease.
  vantage reads `/releases/latest`, which skips prereleases, so `true` stages a change
  that vantage is not ready for. It also suppresses the vantage dispatch below.
- Dispatch step: the last step of `build-mpp.yml`, "Notify vantage (repository_dispatch)",
  sends `event_type=piko-release` to `pmaxhogan/vantage`, which triggers
  `build-postbox.yml` there. It needs the fork secret `VANTAGE_DISPATCH_TOKEN`: a
  fine-grained PAT for repository `pmaxhogan/vantage` only, Contents read+write,
  Metadata read (`GITHUB_TOKEN` cannot dispatch across repos). Absent secret = a warning
  and a skip, never a failed job. `sync-upstream.yml` dispatches `build-mpp.yml` after a
  clean upstream merge, so a clean sync ends in a Postbox build when the secret exists.
  How to prove the chain fired is in vantage's `.claude/CLAUDE.md`.
- A push to this fork that touches `patches/**` or `extensions/**` publishes a release
  immediately (unless `MPP_PRERELEASE=true`), and with the dispatch that is a Postbox
  release too. Land fork-side Postbox work in one push when you can.

## Fork-owned CI files

- `.github/dependabot.yml` is a FORK OVERRIDE of upstream's (upstream targets `dev`
  monthly with an npm entry; the fork is `main` only, weekly, github-actions + gradle).
  It is an accepted sync conflict: if `sync-upstream` leaves a PR open over it, resolve by
  keeping the fork's version. Dependabot version updates are off by default on a fork;
  enable them once in the fork's Settings > Advanced Security.
- `.github/workflows/vantage-ci.yml` (name `Vantage CI`) is the PR gate (extension tests
  plus the bundle build, no secrets). `.github/workflows/dependabot-auto-merge.yml`
  listens for it with `workflow_run` and squash-merges minor/patch bot PRs once green,
  holding majors with a comment. Do not rename `Vantage CI` without updating the
  listener. No ruleset on main: the sync PR is opened with `GITHUB_TOKEN`, which never
  triggers `pull_request` workflows, so a required check would strand it. A merge made
  with the workflow token does not trigger push workflows, so a bot merge publishes no
  release by itself.

## What is NOT verified

As of 2026-10-11 the Postbox patches have not been applied to a real Instagram APK and
nothing has been run on a device. The unit tests cover the pure logic and the Gradle
build only compiles the patches; the dex edits were checked in a scratch harness against
the built extension, outside the patcher. Designed but unobserved:
the fixed bar and startup guard, shortcut removal (what `dumpsys shortcut` shows), the
link bounce for each deep-link form, whether the blocked endpoints really empty the feed
and the post-chaining surfaces on 447.0.0.55.81, and whether piko's settings page is
still reachable and pruned. `Postbox profile grid` is the least certain of the five: its view id names
(`PostboxProfile.java`, the arrays marked UNCONFIRMED) and request paths
(`PostboxProfileNet.java`) come from public UI dumps and the long-standing private API,
not from the APK. Its first run logs which names resolved (`adb logcat -s Postbox`, lines
starting `profile grid:`); fix the arrays from that. One item of the original design has
no code in the fork yet: hiding the feed-switcher dropdown on Home. Do not describe any of this as tested.
