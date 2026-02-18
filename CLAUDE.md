# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Clojure Desktop Toolkit is a library that wraps [Eclipse SWT](https://eclipse.dev/eclipse/swt/) with an idiomatic functional Clojure API for building cross-platform native desktop apps. A key feature is that SWT platform libraries are bundled as ZIP resources inside the JAR and loaded automatically at runtime — users do not install SWT separately.

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
