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
  `PostboxLinkRouterPatch.kt`, `PostboxNetworkBlockPatch.kt`, `PostboxProfileGridPatch.kt`,
  `PostboxSurfaceLockPatch.kt`).
- Runtime: `extensions/instagram/src/main/java/app/morphe/extension/instagram/patches/postbox/*.java`
  (`Postbox` lifecycle hub, `PostboxBuild` switches, `PostboxNav`, `PostboxSettings`,
  `PostboxShortcuts`, `PostboxLinks` + `PostboxLinkRouter`, `PostboxNet`,
  `PostboxProfile` + `PostboxProfileNet`, `PostboxSurface`).
- Tests: `extensions/instagram/src/test/java/app/morphe/extension/instagram/patches/postbox/`.
- `extensions/instagram/build.gradle.kts` carries a fork-only block adding
  `testImplementation("junit:junit:4.13.2")` (the Instagram extension had no tests
  upstream). Keep it a separate block at the end so upstream edits above do not collide.

The six patches are `Postbox settings lock`, `Postbox shortcut lock`,
`Postbox link router`, `Postbox network block`, `Postbox profile grid` and
`Postbox surface lock`, all `default = false`, so a plain piko user never gets them. Their
header comments explain each one; read those before editing.

What each layer does, for the surfaces that have more than one:

- Launcher shortcuts (`Postbox shortcut lock`): static ones are stripped from the manifest.
  Dynamic ones are (1) never published: every call in Instagram's code to
  `ShortcutManager.setDynamicShortcuts` / `addDynamicShortcuts` / `pushDynamicShortcut` is
  redirected to `PostboxShortcuts`, which goes by `PostboxShortcuts.keeps` (returns false:
  nothing is kept, the conversation targets included); (2) cleared when the UI goes to the
  background (last activity stopped, or `TRIM_MEMORY_UI_HIDDEN`), at once and after about
  1 s, 5 s and 15 s; (3) cleared at process start and from `onActivityResumed`, at most once
  a minute. Instagram publishes on backgrounding, which is why (3) alone was not enough.
- Forbidden pages (search results, audio, hashtag, place): `Postbox link router` bounces a
  LINK to one that comes from outside; `Postbox surface lock` bounces the page itself when a
  tap inside the app opens it (found by a view id that only that page has); `Postbox network
  block` leaves it empty if both were missed.
- Home: `Postbox network block` empties the feed; `Postbox surface lock` takes the click off
  the title (the Following / Favorites menu) and hides the endless loading placeholder.
- A link to a single reel (`Postbox link router`): Instagram routes `/reel/<code>` to
  `ClipsUrlHandlerActivity`, whose viewer loads even its first item through
  `clips/discover/stream/`, which the network block fails ("something went wrong"). That
  endpoint stays blocked. Instead `PostboxLinks.reelAsPostLink` turns `/reel/<code>`,
  `/reels/<code>` and `/<user>/reel/<code>` into `https://www.instagram.com/p/<code>/`
  (shortcode only, no query), which `MediaExternalUrlHandlerActivity` plays as a single post.
  `PostboxLinkRouter.openAsPost` swaps the link in place when the activity is exported (the
  entry point `UrlHandlerLauncherActivity`, before it picks a handler), and otherwise, for a
  VIEW intent only, sends the post link to the app again and finishes the handler. Not
  rewritten, for want of a shortcode: `/share/reel/<id>` and `instagram://reel` /
  `instagram://reels_share`. A reel opened from a chat carries no link and is untouched.

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

One exception to "only piko's own classes are edited": `Postbox shortcut lock` rewrites
Instagram methods, the ones that publish dynamic shortcuts. It still has no fingerprint. The
calls name a framework class (`Landroid/content/pm/ShortcutManager;`), which is never
obfuscated, so `classDefForEach` finds them in whatever class they live in (five calls in
four methods of `LX/0Mp;` and `LX/6eN;` on 447.0.0.55.81). Each `invoke-virtual` becomes an
`invoke-static` to the `PostboxShortcuts` method of the same name on the same registers (one
instruction for one, nothing moves). The extension package is skipped in the walk, because
`PostboxShortcuts` makes those calls itself. Zero calls found fails the build, and so does a
`PostboxShortcuts` that is not `public` (Instagram's classes call it). The patcher log line
`Postbox shortcut lock: redirected N shortcut publish call(s) in M method(s): ...` names
every site. morphe-cli only prints loggers named `app.morphe.*`; a patch logs through
`Logger.getLogger(this::class.java.name)` inside `execute`, any other name is dropped.

### `PostboxBuild`: the constant getters

`PostboxBuild.shortcutLock() / linkRouter() / networkBlock() / profileGrid() / surfaceLock() / traceNetwork()` are the
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

## Adding to the network block, the link router or the surface lock

- Network block: add the path substring to `BLOCKED_PATHS` in `PostboxNet.java` (a
  trailing slash is deliberate where a longer sibling must stay reachable; the stories
  tray is `/feed/reels_tray/` and must never match), a `{first part, later part}` row to
  `BLOCKED_PAIRS` when an id sits in the middle of the path (`/media/<id>/chaining`,
  `/locations/<id>/sections/`), or a parser key to `DROPPED_PARSER_KEYS`, and add a row to
  `PostboxNetTest`, including a near-miss that must NOT be blocked. Typeahead
  (`fbsearch/ig_typeahead/`, `keyword_typeahead/`, `typeahead_stream/`, `places/`,
  `tags/search/`, `location_search/`) and single reels (`clips/item/`, `clips/items/`,
  `clips/direct_thread_clips/`, `clips/user/`) must stay reachable; the test pins them.
- Surface lock: a forbidden page is a row in `PostboxSurface.FORBIDDEN_VIEWS`, the name of
  a view that ONLY that page has plus a label for logcat. Take the name from a
  `uiautomator dump` of the page and check it is absent from dumps of the allowed screens
  (inbox search, a single reel, a single post, profiles, the friend map); add it to
  `PostboxSurfaceTest`. A match only counts while the view is really on screen, like a
  dump. The first pass logs `surface: watching, ids[...]` once (tag `Postbox`); an
  `UNRESOLVED` there is a name this build does not have. Views are looked at after layout,
  at most once per 150 ms per activity window; dialogs and popups are separate windows and
  are not looked at.
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

## What was checked on a device, and what was not

The 3.10.0-vantage.3 bundle was applied to Instagram 447.0.0.55.81 by the vantage build
and the release APK was driven by hand on a Pixel 6 (Android 16), logged in, on
2026-10-12. Observed, each with a logcat line or a `dumpsys` result (the list a user
reads is in the vantage README, "Checked on a device"):

- the fixed bar (Home, Message, Profile), the cold start on Messages and the startup-tab
  guard for the Reels, Search and Home extras;
- `dumpsys shortcut`: no dynamic shortcut in the foreground or 1, 3, 10 and 30 seconds
  after HOME, with `shortcuts: dropped N of N at publish` for every publish;
- the link bounce for `/explore/`, `/reels/`, `/`, `/explore/tags/<tag>/`,
  `instagram://explore` and `instagram://reels`; `/p/`, `/reel/`, `/reels/` and profile
  links opening as single items (`reel link -> post viewer, in place
  (UrlHandlerLauncherActivity)`, cold and with the app running);
- the network block: Home empty under the tray, Following and Favorites empty, a reel or
  post opened from a chat with nothing after it (`Postbox: blocked` on
  `feed/timeline/` and `clips/discover/stream/`);
- `Postbox profile grid`: `bind verdict=SELF` leaves the own grid, `verdict=OTHER`
  hides `profile_tabs_container` and `profile_viewpager`; every id name resolved;
- `Postbox surface lock`: every id name resolved; `surface: bounce serp|audio|place`
  for a hashtag, "View song details" and a place's "See more"; no bounce on the friend
  map or the inbox search; `home title locked` and `home placeholder hidden`, re-applied
  after leaving and re-entering Home;
- piko's settings page: reachable from the own profile, the locked rows gone.
- still working with all of it on: a story from the tray (opens, advances, returns),
  and the people search of New Message and of the inbox (`account_serp` is blocked,
  the typeahead endpoints are not).

NOT observed, do not describe as tested:

- push notifications on the renamed package (permission was not granted, no message was
  sent to the phone);
- posting a story, sending a message, logout and login with 2FA;
- the background clearing passes of the shortcut lock ever being needed (the redirect
  dropped every publish, so `shortcuts: removed N dynamic` never appeared after the
  first launch);
- the router's backstop path (`sent again`), `m.instagram.com` and `instagr.am` reel
  links, `/share/reel/<id>` and `instagram://reel?...` (not rewritten: no shortcode);
- pages nobody tapped into: an effect or template page (probably `clips_pivot_page_fragment`
  too), a keyword search opened from the inbox search, a hashtag opened from inside the
  main activity rather than a modal one;
- the network paths added for search results, audio, hashtag and place pages: the
  surface lock bounces those pages before they load, so the paths themselves were not
  seen failing. They come from `strings` on the stock dex, not from a trace.

The placeholder on Home is a row of the Home list made `INVISIBLE`; if Instagram shows
it again without a layout pass it stays visible until the next one.
