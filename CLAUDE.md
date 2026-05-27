# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project type: Native desktop library (SWT/CDT)

This project is a **native desktop UI toolkit** built on Eclipse SWT. It has no web UI.
The following gstack skills do not apply and should not be invoked:
- `/qa`, `/qa-only`, `/browse`, `/canary`, `/benchmark` — require a browser; SWT windows are not web pages
- `/design-review`, `/design-html`, `/design-shotgun` — generate or inspect web/HTML UI, not SWT

For UI testing, use the REPL screenshot workflow documented in `SWT-UI-RULES.md` instead of `/qa`.
For all UI code, **always read `SWT-UI-RULES.md` first** (see section below).

## SWT UI Rules — REQUIRED READING

**ALWAYS read [`SWT-UI-RULES.md`](SWT-UI-RULES.md) before writing any SWT user interface code.** It contains mandatory rules on UI threading, init function composition, event handling, resource management, layout helpers, platform quirks, and REPL testing workflow.

## Project Overview

Clojure Desktop Toolkit is a library that wraps [Eclipse SWT](https://eclipse.dev/eclipse/swt/) with an idiomatic functional Clojure API for building cross-platform native desktop apps. A key feature is that SWT platform libraries are bundled as ZIP resources inside the JAR and loaded automatically at runtime — users do not install SWT separately.

## Clojure Development

### REPL-First Workflow (Non-Negotiable)

Use `clj-nrepl-eval` (installed on PATH) for all REPL evaluation. **Never edit code files when the REPL is unavailable** — stop and ask the user to restore REPL first.

Before ANY file modification:
1. Read the whole source file
2. Test current behavior in REPL with sample data
3. Develop fix interactively in REPL
4. Verify with multiple test cases
5. Only then write to files

After editing files, reload the namespace: `(require 'my.namespace :reload)`

### Data Structure Conventions

- **Flat structures**: Avoid deep nesting; use namespaced/synthetic keywords (`:foo/something`)
- **Destructure in parameter lists**: `[{:user/keys [id name] :config/keys [timeout]}]`
- **Avoid shadowing core fns**: Don't bind `map`, `name`, `type`, `count`, `set`, `str`, `get`, `filter`, `reduce`, `merge`, `update`, `key`, `first`, `rest`, `keyword`, `symbol`, `class`, `empty?`
- **Use `str` not `.toString`**: `(str val)` is idiomatic Clojure; never call `(.toString val)`

### Alignment Rule

Always align multi-line data structure elements vertically — the bracket balancer depends on consistent indentation:

```clojure
;; ✅ Correct
(select-keys m [:key-a
                :key-b
                :key-c])
```

### Debugging: Inline Def over Println

```clojure
(defn process [data]
  (def data data)   ; inline def — keeps value inspectable in REPL
  ...)
```

### Rich Comment Forms (RCF) and Inline Tests

This codebase uses [Hyperfiddle RCF](https://github.com/hyperfiddle/rcf) for inline tests co-located with source code. The `tests` macro is the standard way to document and verify function behavior — prefer it over plain `(comment ...)` blocks when you want executable examples. Tests are activated via `(rcf/enable!)` in `dev/user.clj`.

Inline test syntax:
```clojure
(ns example
  (:require [hyperfiddle.rcf :refer [tests tap %]]))

(tests
  (inc 1) := 2
  {:a :b} := {:a _}                    ; wildcard
  {:a :b, :b [2 :b]} := {:a ?x, ?x [2 ?x]}  ; unification
  (assert false) :throws AssertionError)
```

Async tests with `tap`/`%`:
```clojure
(tests
  (future (tap 1) (tap 2))
  % := 1
  % := 2)
```

Use plain `(comment ...)` only for exploratory scratch work that you don't intend to keep as verified examples.

### Architectural Integrity

- **Fail fast, fail clearly**: config/service failures → explicit errors, never `(or real-config fallback)`
- **No architectural violations**: functions must not call `swap!`/`reset!` on global atoms; business logic must be separate from side effects
- **Definition of Done**: architectural integrity verified + REPL testing done + zero compilation warnings + zero linting errors + all tests pass

### Dynamic Dependency Loading

```clojure
(require '[clojure.repl.deps :refer [add-libs]])
(add-libs '{some/library {:mvn/version "1.0.0"}})
```
Requires Clojure 1.12+.

## Build Commands

```bash
make jar                    # tools.build → target/clojure-desktop-toolkit-0.7.0.jar
make compile-java           # just stage + javac the Nebula sources (no JAR)
make install                # build and install to local Maven (~/.m2)
make update-vendored-nebula # re-vendor vendor/nebula/ at the pinned SHA
make deploy                 # ./deploy.sh → deploy to Clojars
make clean                  # tools.build delete target/
```

Version constant lives in `Makefile` as `VERSION = 0.7.0`. The pom
template at `pom.xml` carries both `<version>` and `<scm><tag>` —
both must be bumped together at release time.

**JDK 17+ required**: Nebula's `grid` and `chips` widgets demand
`JavaSE-17` per their MANIFEST.MF, so all bundled bytecode is compiled
with `--release 17` (class-file major version 61). The `ui.nebula`
namespace's top-level `when` runs a fail-fast check at require time
that throws a clear `IllegalStateException` on pre-17 JDKs — much
nicer than the cryptic `UnsupportedClassVersionError` you'd get deep
inside Class.forName otherwise.

**Vendored Nebula**: `vendor/nebula/` is a filtered upstream mirror.
Do NOT edit those files by hand — bump `nebula-sha` in `build.clj` and
run `make update-vendored-nebula` instead. The exclusion manifest is
`nebula-sources.edn` in the project root; `:exclude-pkgs` filters out
JFace adapter subpackages, `:exclude-files` filters individual JFace
helper files. Both fields take paths relative to a bundle's `src/`
directory. Source staging uses a manual walk in
`build/stage-nebula-sources` (NOT `b/copy-dir :ignores`, which matched
absolute paths and was brittle).

**Two bases, one published pom**: the build uses two tools.build bases.
`*compile-basis*` includes SWT via `:local/root` `:extra` so `b/javac`
can compile Nebula sources against it. `publish-basis` is a plain
`b/create-basis` with NO SWT, used for `b/write-pom` so the published
pom never declares an SWT dependency. The runtime SWT-loading contract
in `ui.internal.SWT-deps` is what consumers actually get — they "depend
on CDT, get SWT for free, never know about it."

**Inner Nebula JAR (Option 4 design, 2026-05-27)**: Nebula bytecode
does NOT ship as loose `.class` files in the CDT JAR. Instead, `jar`
produces `target/nebula-<v>.jar`, then copies it into `class-dir` as
the resource `nebula.jar`. The published CDT JAR layout is:
- Clojure source under `ui/`, `ui/internal/`, etc.
- Six platform SWT zips in resources root (`swt-4.38-<plat>.zip`).
- `nebula.jar` at the JAR root.

At runtime, `ui.nebula` extracts `nebula.jar` and pomegranate-adds it
to the same `DynamicClassLoader` that holds SWT. This is the workaround
for the classloader split that would otherwise prevent
`PShelf extends Canvas` from resolving when defined by the parent
AppClassLoader. See `Plans/todo/compile-java-swt-widgets-context.md`
("Runtime classloader problem") for the full diagnosis. **Verification
gate**: `jar tf target/clojure-desktop-toolkit-<v>.jar | grep '^org/eclipse/nebula/.*\.class$' | wc -l` must return `0`.

**No Equinox**: CDT must never require an OSGi/Equinox runtime. The 53
bundled Nebula widgets are curated for OSGi-free operation. Widgets
that hard-require `Activator.getDefault()` returning non-null, NLS
resource bundles, or Eclipse extension-registry lookups are excluded.

**Development REPL:**
```bash
clojure -M:dev   # starts REPL with src, resources, dev, and examples on classpath
```

For Nebula widgets at the REPL, the dev `:dev` alias does NOT auto-compile
Java sources. Two workflows:

1. **Recommended for active CDT development**: run `make compile-java`
   once, then start a REPL with `target/classes` on the classpath:
   ```bash
   clojure -M:dev -Sdeps '{:paths ["target/classes"]}'
   ```
   Edit `.java` sources, re-run `make compile-java`, restart the REPL.
2. **Mirrors downstream consumers**: run `make install`, then depend on
   the installed JAR from a separate scratch project.

For SWT UI testing from a headless nREPL on macOS, see SWT-UI-RULES.md
("UI Testing at the REPL"). The standalone-REPL workflow does NOT work
on macOS unless the application itself embeds an nREPL — the
`-XstartOnFirstThread` JVM main thread is what SWT requires for
`Display/getDefault`. For one-off smoke tests, write a `-main`
(see `dev/cdt_smoke.clj` for a working example).

**Tests** use [Hyperfiddle RCF](https://github.com/hyperfiddle/rcf) — tests are inline in source files and activated via `(rcf/enable!)` in `dev/user.clj`. Run them by evaluating the test forms in the REPL.

## Bundled Nebula widgets (0.7.0+)

53 Eclipse Nebula widgets ship as a runtime-extracted inner JAR
(`nebula.jar` inside the CDT JAR, NOT loose `.class` files). Consumers
`(:require [ui.nebula])` **before** `(:require [ui.SWT])` in any
namespace that uses a Nebula widget.

```clojure
(ns my.ui
  (:require [ui.nebula]                ; MUST come first — see below.
            [ui.SWT :as ui :refer [application shell pshelf pshelf-item label]]))
```

**Why the order matters.** `ui.nebula` does three things at load time,
in this exact sequence: (1) JDK 17 fail-fast check; (2) `ui.internal.SWT-deps`
loads, pomegranate-adding SWT to the DynamicClassLoader; (3) extract
`nebula.jar` resource + pomegranate-add it to the same loader. ONLY
THEN does ui.nebula's body call `(require '[ui.SWT])`, which transitively
runs `ui.internal.reflectivity`'s classpath scan. With both SWT and
Nebula on the loader at scan time, `define-inits` auto-generates
`pshelf`, `pshelf-item`, `led`, etc. as `ui.SWT/*` init fns alongside
the built-in SWT widgets. Requiring `ui.SWT` first means the scan runs
without Nebula, and the Nebula init fns never appear.

The `ui.build.swt` namespace is internal-only for v0.7.0 — exposing it
as a public client API for downstream-Java-widget compilation is
deferred. See `Plans/todo/compile-java-swt-widgets-context.md` for the
classloader-strategy rework that v0.8.0+ ships.

Widgets deferred to a future release (require JFace/Forms/Draw2D/core.runtime
or Windows-only SWT internals): `pagination`, `picture`, `richtext`,
`roundedtoolbar`, `segmentedbar`, `timeline`, `treemapper`, `xviewer`,
`bidilayout`, `datechooser`, `radiogroup`, `geomap`.

## Architecture

### Core Pattern: Init Functions

The central abstraction is an **init function**: `(fn [props parent] ...)`. All widget constructors accept a vararg list of init functions (plus some sugar forms) that are called in order on the constructed widget.

- **`props`** — an atom of shared mutable state threaded through the entire UI tree, used for data binding and cross-component references
- **`parent`** — the SWT widget being initialized

The `->init` multimethod in `ui.inits` converts various argument types into init functions:
- `IFn` → used as-is
- `String` → calls `.setText`
- `Keyword` + next-arg → calls the matching Java setter (`:text "foo"` → `.setText("foo")`)

### Widget API Generation via Reflection

`ui.SWT` calls `(i/define-inits meta/swt-composites|swt-widgets|swt-items)` at load time. This uses `ui.internal.reflectivity` to introspect SWT classes and **generate Clojure constructor functions** for every SWT widget. This means:
- All SWT widgets are automatically available with kebab-case names
- New SWT versions are supported without code changes
- Widget functions take an optional SWT style integer, then vararg init forms

### Key Namespaces

| Namespace | Role |
|---|---|
| `ui.SWT` | Primary public API — application, shell, widget fns, event macros, UI-thread helpers |
| `ui.inits` | Init function framework — `->init` dispatch, `run-inits`, `define-inits` |
| `ui.events` | SWT event constants as kebab-case keywords |
| `ui.SWT-conversions` | Type coercions (vectors → Point/Rectangle, etc.) |
| `ui.internal.SWT-deps` | Runtime OS/arch detection and SWT ZIP extraction |
| `ui.internal.reflectivity` | Java reflection → widget metadata used by `define-inits` |
| `ui.internal.docs` | `swtdoc` interactive API browser |

### UI Threading

SWT requires all UI work on the UI thread:
- `(ui ...)` macro — run forms on the UI thread, blocking
- `(sync-exec! f)` — synchronous execution on UI thread
- `(async-exec! f)` — asynchronous (queued after pending events)
- `(ui-thread?)` — predicate

### Component Identity / Props

- `(id! :keyword)` — init function that stores the constructed widget in `@props` under `:keyword`
- `(| SWT/FLAG1 SWT/FLAG2)` — bitwise OR helper for combining SWT style flags

### API Discovery at Runtime

```clojure
(swtdoc)                    ; browse all APIs
(swtdoc :swt :widgets)      ; list widget types
(swtdoc :swt :events)       ; list event types
(swtdoc :package 'ui.SWT)   ; explore this package's public API
```

## Platform Support

SWT 4.38 ZIPs are bundled in `resources/` for: Linux GTK (x86_64, aarch64), macOS Cocoa (x86_64, aarch64), Windows Win32 (x86_64, aarch64). Detection and extraction happen in `ui.internal.SWT-deps` at startup.

**GTK scaling** (must call before Display initializes):
```clojure
(ui-scale! 1.5)
;; or env vars: GDK_DPI_SCALE=1.5 GDK_SCALE=1.5
```

## Examples

- `examples/starter/` — complete standalone app with its own `deps.edn`, the canonical starting point
- `examples/app/` — `hello.clj`, `minimize_to_tray.clj`, `window_panes.clj`
- `examples/databinding/` — experimental/WIP
