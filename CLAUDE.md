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
make jar      # clojure -X:jar :version '"0.4.4"' → clojure-desktop-toolkit.jar
make deploy   # ./deploy.sh → deploy to Clojars
make clean    # rm -f *.jar
```

**Development REPL:**
```bash
clojure -M:dev   # starts REPL with src, resources, dev, and examples on classpath
```

**Tests** use [Hyperfiddle RCF](https://github.com/hyperfiddle/rcf) — tests are inline in source files and activated via `(rcf/enable!)` in `dev/user.clj`. Run them by evaluating the test forms in the REPL.

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
