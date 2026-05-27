# nebula-widget example

Idiomatic-CDT demo of the bundled Eclipse Nebula widgets. Renders a
PShelf with four populated sections (Inbox, Drafts, Sent, Archive)
built entirely from CDT init functions — no custom Java class, no
`(.new ...)` interop in the layout.

## Why this example exists

CDT's `ui.SWT` namespace scans the classpath at load time and uses
reflection to generate a kebab-case init function for every SWT widget
class it finds (Composites, Widgets, Items). With Option 4's design
(see `Plans/todo/compile-java-swt-widgets-context.md`), the bundled
Nebula bytecode is extracted onto the same runtime classloader as
SWT — so it gets the same treatment automatically:
`org.eclipse.nebula.widgets.pshelf.PShelf` becomes `ui.SWT/pshelf`,
`PShelfItem` becomes `pshelf-item`, etc.

The one twist: `PShelfItem` is an `Item`, not a `Composite`, so its
child widgets must be attached to `(.getBody item)` rather than the
item itself. The example demonstrates the canonical CDT pattern for
that kind of widget-specific quirk: a one-line helper init
(`body`) that wraps the body wiring and composes cleanly with the
rest of the CDT API. This mirrors how Winze handles CTabItem via
`(ctab-item ... (control :ui/key))`.

## Files

- `src/example/main.clj` — bootstrap (DynamicClassLoader + `*repl*`
  binding, then `require example.ui` and call `start`). Same pattern
  as `examples/starter/src/starter/main.clj`.
- `src/example/ui.clj` — the actual UI: requires `ui.nebula` first so
  the Nebula JAR extracts and lands on the same classloader as SWT
  before `ui.SWT` runs its reflective init-fn generator. Then uses
  `application`, `shell`, `pshelf`, `pshelf-item`, `label` directly.
- `dev/screenshot.clj` — one-shot variant that screenshots the
  rendered shell and exits. Useful for visual smoke-testing without a
  human at the keyboard.
- `deps.edn` — depends on CDT via `:mvn/version "0.7.0"`. Run
  `make install` in the parent CDT directory first to populate your
  local Maven cache. Downstream users target the published version.

## Run

```bash
make run
```

You should see a window with a PShelf accordion-style sidebar with
four sections, the Inbox section expanded showing a "Contents for:
Inbox" label.

## What this example proves

- The Option 4 runtime-extraction design works end-to-end: a consumer
  depending only on `clojure-desktop-toolkit` as a Maven coord (no
  explicit SWT dep, no `:local/root` workarounds) can use bundled
  Nebula widgets.
- The starter-pattern entry (`DynamicClassLoader` + `*repl*` binding)
  also works for Nebula apps now, because Nebula bytecode rides into
  the same loader as SWT via the inner `nebula.jar` resource.
- CDT's reflective init-fn generator handles Nebula widgets identically
  to SWT's own — no special-case registration code. The Java class
  taxonomy (Composites / Widgets / Items) determines the kebab-case
  fn name and the `widget*` macro handles the standard SWT-style
  `(parent, style)` constructor for free.
