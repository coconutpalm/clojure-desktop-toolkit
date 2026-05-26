# Context: Compiling Java/SWT widgets into the CDT JAR

This document captures everything a future Claude needs to understand BEFORE
executing `compile-java-swt-widgets-plan.md`. Read this first.

## The problem

Clojure Desktop Toolkit (CDT) is a Clojure library that wraps Eclipse SWT
in an idiomatic functional API. Today CDT ships as a **pure Clojure** JAR
built with depstar. It has no Java sources, no AOT, no javac step.

The user wants to add the ability to **compile Java-based SWT widgets**
(initially the Eclipse Nebula widget collection) into CDT's JAR. The same
mechanism must be **reusable by downstream client projects** that depend
on CDT and want to compile their own Java/SWT widgets.

Nebula sources are **vendored** under `vendor/nebula/` in the CDT repo,
filtered to just the bundle source trees we actually compile. The
upstream Git SHA is pinned in `build.clj` and documented in
`vendor/nebula/VERSION`. A dedicated `update-vendored-nebula` build
task fetches a new SHA when bumping versions. This makes the build
self-contained (no env vars, no sibling directories, no network at
build time), reproducible across environments, and easy for engineers
to introspect — they can `ls vendor/nebula/widgets/` instead of
spinning up a REPL.

## Background: how CDT loads SWT today

SWT has **platform-native artifacts** (cocoa/macOS, gtk/Linux, win32/Windows
× x86_64/aarch64). CDT solves this at runtime, not at deploy time:

1. `resources/swt-4.38-<platform>.zip` — six bundled ZIPs (one per
   supported platform), each containing a `swt.jar` plus an optional
   `src.zip`.
2. `src/ui/internal/SWT_deps.clj` — at load time:
   - Reads `os.name` and `os.arch` system properties.
   - Maps them to a platform suffix (e.g. `cocoa-macosx-aarch64`).
   - Attempts `(import '[org.eclipse.swt SWT])`. If it succeeds (SWT is
     already on classpath), it's done.
   - On `ClassNotFoundException`: locates the matching ZIP via
     `io/resource`, extracts it to a temp dir, and calls
     `cemerick.pomegranate/add-classpath` on the extracted `swt.jar`.
     Then retries the import.
3. **CDT's published `pom.xml` declares NO SWT dependency.** This is by
   design — consumers transparently inherit CDT's dynamic-loading
   behavior. The contract: "depend on CDT, you get SWT for free, you
   never know about it."
4. The current build uses depstar (`clojure -X:jar`) and produces
   `clojure-desktop-toolkit.jar` containing Clojure source + the six SWT
   ZIPs as resources. No AOT. No javac.

The `SWT_deps.clj` file (lines 101–127) also has commented-out scaffolding
for **Chromium** and **Eclipse databinding** — both platform-native. This
problem will recur for those libraries; the chosen solution should
generalize.

## The architectural insight that makes this tractable

**SWT's Java API is uniform across all platforms.** Only the native
bindings differ. So:

- A Java widget compiled against `swt.jar` from any platform produces
  **platform-independent bytecode** that runs on every platform CDT
  supports.
- At build time, we can pick ANY one of the bundled SWT JARs (e.g., the
  host's matching one) and use it as the javac classpath.
- The resulting `.class` files reference `org.eclipse.swt.*` by name;
  the JVM resolves those references against whichever platform-specific
  `swt.jar` is on the classpath at runtime — which is whatever
  `SWT_deps.clj` injected via pomegranate.

This is why we can ship a single CDT JAR containing compiled Nebula
widgets that works on all platforms.

## The build problem that needs solving

`tools.build`'s `b/javac` reads its classpath from a `basis`, and
`b/create-basis` derives the basis from `deps.edn`. But CDT's `deps.edn`
**intentionally declares no SWT dependency** (so consumers don't get one
transitively). javac therefore has no SWT classes on its classpath and
can't compile any Java source that imports `org.eclipse.swt.*`.

The chosen solution: extract one of the bundled SWT ZIPs at build time
and inject the resulting `swt.jar` into the basis as a `:local/root`
dep via `:extra`. tools.deps treats `:local/root` as a fully-resolved
leaf (no POM lookup, no transitive resolution) — exactly the semantics
we want for hand-curated classpath additions.

## Confirmed technical facts about tools.build

These were verified via web search; treat them as ground truth:

- `b/create-basis` accepts `:extra` (a deps-edn map) to inject
  build-time-only deps that don't appear in `deps.edn`. They show up in
  `:classpath-roots` and are consumed by `b/javac` / `b/compile-clj`.
- `b/javac` takes `:src-dirs` (collection of source roots), `:class-dir`,
  `:basis`, `:javac-opts`. Classpath comes from the basis.
- `:local/root` coords work in `:extra` and point at on-disk JARs/dirs.
- `b/write-pom` writes basis deps into the published pom. **This is why
  we need TWO bases**: a compile-basis with SWT (for javac) and a
  publish-basis without SWT (for write-pom). Otherwise the published pom
  would declare SWT and break CDT's dynamic-loading contract.
- SWT 4.38 corresponds to SWT bundle version `3.132.0` on Maven Central
  under `org.eclipse.platform/org.eclipse.swt.<ws>.<os>.<arch>`. We are
  NOT using Maven Central in v1 — we extract from the bundled ZIPs.

## Why source staging, not post-javac class deletion

An earlier draft proposed compiling every `.java` file under each
widget's `src/` directory, then deleting unwanted output classes
afterwards. This is **broken**: javac compiles all source files, and
classes that import JFace (e.g., PShelf's optional `PShelfViewer.java`)
fail to compile because JFace isn't on the classpath. The build dies
before any deletion can occur.

The correct fix is **source staging**: copy only included packages from
each widget's `src/` into `target/build-deps/java-src/` BEFORE javac
runs. The staged tree contains only pure-SWT source files. javac
compiles cleanly against a SWT-only classpath. No JFace, core.runtime,
or other Eclipse platform deps are needed at compile time.

This has a strong side benefit: the resulting JAR contains **zero
non-SWT classes**. Consumers can never accidentally hit a JFace-related
NoClassDefFoundError, because there are no JFace references in the
shipped bytecode.

`b/copy-dir`'s `:ignores` option (a coll of regexes) is the staging
mechanism. **Verify its semantics empirically during step 4** — the
matching rules (against full path vs. relative path) should be
confirmed against the pinned tools.build version. If `:ignores`
doesn't behave as expected, fall back to a manual `fs/walk-file-tree`
filter — the staging step is small enough to hand-roll.

## Nebula widget taxonomy (the 57 widgets)

A two-pass survey of all 66 Nebula widgets classified them by what they
need on the classpath beyond SWT. The first pass grep'd at bundle
granularity (treating the whole `org.eclipse.nebula.widgets.<name>/src/`
tree as one unit). A follow-up audit at **package granularity** found
that the prior survey was too pessimistic — the PShelf pattern (pure-SWT
core package + separable JFace adapter in a sibling subpackage) is
actually common in Nebula, not rare. Audit findings:

- **Tier 1 (15 widgets)**: pure SWT. No other Nebula deps.
- **Tier 2 (32 widgets)**: pure SWT + `opal.commons` (a small utility
  bundle we also build in).
- **Tier 3a — package exclusion (8 widgets)**: core widget package is
  pure SWT, but the bundle also contains an optional sibling subpackage
  (typically named `viewer`, `viewers`, or `jface`) that imports JFace.
  Excludable via `:exclude-pkgs`. The 8 widgets are pshelf,
  compositetable, ctreecombo, gallery, geomap, grid, radiogroup,
  tablecombo.
- **Tier 3b — file exclusion (2 widgets)**: core widget package is
  pure SWT except for 1–3 specific helper files (typically `*CellEditor`
  and `*ObservableValue`) that import JFace. Excludable via the new
  `:exclude-files` manifest field. The 2 widgets are datechooser and
  formattedtext.
- **Tier 4 (8 widgets — DEFERRED to v2)**: pagination, picture,
  richtext, roundedtoolbar, segmentedbar, timeline, treemapper, xviewer.
  These import JFace, `org.eclipse.core.runtime`, or `org.eclipse.ui`
  in their core packages and cannot be salvaged by subpackage or file
  exclusion alone. **picture is borderline** — its JFace dep is one
  file but cross-referenced from `AbstractPictureControl`; verify during
  audit step and promote if salvageable.

**v1 ships the 57 widgets in Tiers 1+2+3 plus `opal.commons`.** That is
the complete v1 scope.

### Nebula SHA audited for this plan

The Tier classifications above were verified against this exact Nebula
upstream state:

- **SHA**: `4a5ce620574a16644e6fc5b7533137b1ad7d8ed4`
- **Short SHA**: `4a5ce620`
- **Tag descriptor**: `V3.3.0-9-g4a5ce620` (9 commits past the `V3.3.0`
  release tag)
- **HEAD commit date**: 2026-05-14
- **HEAD commit message**: "Add TriStateSwitchButton with two active
  states and off (#697)"
- **Upstream**: https://github.com/EclipseNebula/nebula

The implementing Claude SHOULD use this SHA as the initial value of
`nebula-sha` in `build.clj` so the vendored tree matches what was
audited. If a newer SHA is chosen instead, **re-run the
package-granularity audit** described below — Nebula upstream may
have moved files, added imports, or restructured subpackages between
this SHA and the chosen one. Do not assume the 57-widget classification
holds without verification.

If the build chooses to pin a tagged release (`V3.3.0`) rather than
the audited SHA, note that V3.3.0 is 9 commits BEFORE the audited
state. The TriStateSwitchButton widget added in commit #697 will not
be present in V3.3.0, but no Tier 1/2/3 classifications depend on
it.

### The audit lesson

The original survey was wrong about Tier 3. It grep'd
`'^import org\.eclipse\.'` across each bundle's full src/ tree and
classified bundles as "JFace required throughout" whenever ANY file
imported JFace. The PShelf pattern showed this was incorrect: ~50% of
the widgets the first survey flagged as Tier 4 are actually
**Tier 3-able** because the JFace imports are confined to
clearly-separable subpackages or specific helper files.

**Future-Claude lesson**: when classifying multi-package Java bundles
for "what compile-time deps are needed", always grep at **package**
granularity, not bundle granularity. A single `import` statement in
one file does not mean the whole bundle needs that dep — it means
that one file does. Cross-reference each suspicious package from the
core widget package to confirm it's truly separable.

## v1 vs v2 vs v3 phasing

- **v1** (this plan): source staging + 57 pure-SWT widgets via
  `:exclude-pkgs` and `:exclude-files`. No P2 fetcher. Zero non-SWT
  classes shipped. Establishes the build pipeline and the vendored
  Nebula tree under `vendor/nebula/`.
- **v2** (future, separate plan): add internal `ui.internal.p2`
  namespace — a minimal Eclipse P2 update-site fetcher (~200–400 LOC).
  Use it to fetch JFace and supporting bundles for compile-time.
  Extend the manifest to cover Tier 4 widgets. Per-widget OSGi-runtime
  smoke tests determine which are shippable.
- **v3** (future): once `ui.internal.p2` has shipped one or two
  releases and stabilized, promote to public `ui.build.p2` so clients
  can fetch arbitrary Eclipse-platform bundles.

The "internal API first" pattern (private → battle-tested → public)
gives empirical knowledge before committing to API shape.

## Permanent constraint: no Equinox runtime in CDT applications

CDT's identity is "lightweight SWT for Clojure, no OSGi runtime." This
is **non-negotiable** across all milestones. Both OSGi and Clojure are
highly opinionated about classloaders and they fight each other in
ways that defeat CDT's "minimal lightweight desktop app" value
proposition.

This constraint shapes v2 widget selection:

- Tier 4 widgets compile against JFace + core.runtime, BUT only ship if
  they actually work without an Equinox container running.
- Widgets that hard-require `Activator.getDefault()` returning
  non-null, NLS resource bundles, or extension-registry lookups are
  excluded.
- Smoke test = instantiate widget in a SWT shell with NO Equinox
  container; exercise its API surface; ship only if no NPEs from OSGi
  internals.

The README and CLAUDE.md must document this contract prominently:
"CDT does not require an Equinox runtime. JFace-bundled widgets are
curated for OSGi-free operation."

## Why a minimal P2 fetcher (v2) over alternatives

Considered alternatives and why each was rejected:

- **Maven Central for JFace + transitive deps**: works for many
  bundles but coverage is patchy. Some Eclipse artifacts aren't on
  Central, and published POMs have broken transitive references that
  resolution stumbles on.
- **Bundle JFace JARs as resources** (parallel to SWT): works, but
  doesn't generalize beyond JFace. Each new Eclipse dep (Chromium,
  databinding) needs its own bundled-JAR set. Significant JAR bloat.
- **Depend on the P2 Director at build time**: would pull Equinox into
  the build process. Ironic given runtime Equinox avoidance, and a
  slippery slope toward "we already have it, let's use it at runtime."
- **Tycho/Maven for the Java compilation step**: mixes build tools.
  Imports Tycho complexity into a Clojure project.

The minimal fetcher path simultaneously avoids Equinox-at-build-time,
gives full control over what bundles ship, generalizes beyond JFace,
and is genuinely tractable in pure Clojure.

## Key references in the existing codebase

- `src/ui/internal/SWT_deps.clj` — runtime SWT loading. Lines 19–62 are
  the platform-detection code to extract into `ui.internal.swt-platform`.
  Lines 78–99 are the runtime-side `defonce` machinery that must be
  preserved byte-for-byte.
- `src/ui/SWT.clj` — primary public API; requires `SWT_deps`
  transitively, triggering SWT loading.
- `deps.edn` — currently has `:dev`, `:mcp`, `:jar`, `:deploy` aliases.
  The `:jar` and `:deploy` aliases use depstar/deps-deploy; they will
  be removed and replaced with `:build`.
- `Makefile` — `make jar` currently runs `clojure -X:jar :version
  '"0.6.0"'`. Will be replaced with `clojure -T:build jar
  :version '"0.6.0"'`.
- `pom.xml` — manually-maintained pom template. `b/write-pom` will use
  it via `:src-pom`. Content unchanged.
- `examples/starter/build.clj` — existing example of tools.build in
  this repo (for the starter project, not CDT itself). Reference for
  tools.build idioms.
- `CLAUDE.md` — project-level instructions. Has a "Build Commands"
  section that must be updated.
- `SWT-UI-RULES.md` — required reading for UI work. Contains the REPL
  screenshot workflow used in verification step 7.

## Key references outside the repository

- **Upstream Nebula**: https://github.com/EclipseNebula/nebula. We
  vendor a filtered subset under `vendor/nebula/` in the CDT repo;
  the pinned SHA lives in `build.clj` as `nebula-sha` and in
  `vendor/nebula/VERSION`. Upstream widget sources live at
  `widgets/<name>/org.eclipse.nebula.widgets.<name>/src/` for top-level
  widgets, and `widgets/opal/<name>/org.eclipse.nebula.widgets.opal.<name>/src/`
  for opal-family widgets — the vendored tree mirrors this layout.
- Nebula is EPL-2.0, compatible with CDT (also EPL-2.0). Vendoring is
  EPL-redistribution-compliant: we ship the upstream `LICENSE` under
  `vendor/nebula/LICENSE` and attribute Eclipse Nebula in `NOTICE.md`.

## JDK target

v1 compiles all widgets with `--release 17`. CDT consumers therefore
need JDK 17+. This is the current LTS (released 2021) and is widely
available. The choice is forced by Nebula's `grid` and `chips` widgets,
which require `JavaSE-17` per their MANIFEST.MF. Compiling everything
with `--release 17` is simpler than maintaining two compile passes.

`ui.nebula` includes a fail-fast check: at namespace load, it inspects
`System.getProperty "java.version"` and throws a clear error if the
runtime JDK is < 17.

**This is the first user-visible breaking change in CDT's history.**
Existing consumers running JDK 11–16 hit `UnsupportedClassVersionError`
at first import after upgrading. No Clojure code changes are required
in consumer apps — purely an environment bump. The release must signal
this prominently:

- A CDT version bump (recommend 0.7.0 conservatively or 1.0.0 to
  graduate; confirm choice with the user before editing version
  strings).
- A new `docs/new-and-noteworthy/version-<X.Y.Z>.md` page leading
  with the breaking change, following the project's existing
  `version-0.4.4.md` convention.
- The README's top "New and Noteworthy" bullet redirected at the
  new release notes (currently points at 0.4.4 — replace it).
- A prominent JDK 17+ banner in the README near the top.

See Step 11 in the plan file for the concrete documentation tasks.

## Files that will be touched (preview — full details in plan)

**New**: `build.clj`, `src/ui/internal/swt_platform.clj`,
`src/ui/build/swt.clj`, `src/ui/nebula.clj`, `nebula-sources.edn`,
`vendor/nebula/` (vendored sources + LICENSE + VERSION), `NOTICE.md`,
`docs/new-and-noteworthy/version-<X.Y.Z>.md` (release notes —
follows the project's existing `version-0.4.4.md` convention; the
release notes flag the **JDK 17+ breaking change** prominently as
the first user-visible breaking change in CDT's history), plus an
`examples/java-widget/` example showing client reusability.

**Modified**: `deps.edn` (add `:build` alias, remove depstar aliases),
`Makefile` (point at build.clj, add `update-vendored-nebula` target,
bump version constant), `pom.xml` (used as `:src-pom` + bump
`<version>` element), `src/ui/internal/SWT_deps.clj` (delegate
platform detection), `CLAUDE.md` (document new build, the JDK 17
floor, the `(:require [ui.nebula])` contract, the vendored Nebula
layout and update workflow, the no-Equinox constraint),
`README.md` (redirect the top "New and Noteworthy" bullet at the
new release notes, add prominent JDK 17+ notice, add Using-Nebula
and Compiling-your-own-Java-widgets sections, add Vendored-Nebula
subsection, add no-Equinox callout).
