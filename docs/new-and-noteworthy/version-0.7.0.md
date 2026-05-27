# ![Logo](../images/icon32x32.png) Version 0.7.0 New and Noteworthy

## ⚠️ Breaking changes — JDK 17 is now required

CDT now requires **JDK 17 or later** at runtime.

The Nebula `grid` and `chips` widgets declare
`Bundle-RequiredExecutionEnvironment: JavaSE-17` upstream, so all Java
now compiled with `--release 17` (class-file major version 61).

(If you forget, `ui.nebula`'s top-level fail-fast check turns that into
a clear `IllegalStateException` at namespace require time.)

No Clojure code changes are required in consumer apps — purely an
environment bump.  From an API perspective, this release is purely additive.

## 53 Eclipse Nebula widgets bundled in the JAR

The CDT JAR now ships compiled bytecode for **53 widgets** from the
[Eclipse Nebula Widgets](https://github.com/EclipseNebula/nebula) project:

- **Foundation**: `opal.commons`, `cwt` (intra-Nebula dependencies, pure-SWT).
- **Layout & navigation**: `pshelf`, `compositetable`, `pgroup`,
  `collapsiblebuttons`, `breadcrumb`, `header`, `nebulatoolbar`,
  `titledseparator`, `floatingtext`, `panels`, `tiles`.
- **Pickers & input**: `calendarcombo`, `cdatetime`, `formattedtext`,
  `textassist`, `passwordrevealer`, `multichoice`, `duallist`,
  `ctreecombo`, `tablecombo`, `columnbrowser`, `propertytable`,
  `preferencewindow`, `calculator`, `horizontalspinner`,
  `nebulaslider`, `rangeslider`, `roundedcheckbox`, `roundedswitch`,
  `switchbutton`.
- **Display & status**: `led`, `progresscircle`, `starrating`, `chips`,
  `badgedlabel`, `stepbar`, `commons`, `ctree`, `grid`, `gallery`,
  `oscilloscope`, `ganttchart`, `heapmanager`, `splitbutton`,
  `fontawesome`, `notifier`, `dialog`, `logindialog`, `tipoftheday`,
  `promptsupport`, `carousel`, `checkboxgroup`.

To use a Nebula widget, `(:require [ui.nebula])` **before**
`(:require [ui.SWT])` in the consuming namespace. `ui.nebula` does
three things in order: JDK 17 check; SWT extraction (via
`ui.internal.SWT-deps`); Nebula JAR extraction onto the same
runtime `DynamicClassLoader`. Only after both are on the loader does
this namespace require `ui.SWT`, so `ui.internal.reflectivity`'s
classpath scan sees both layers and `define-inits` auto-generates
init fns (`pshelf`, `pshelf-item`, `led`, ...) for the bundled widgets.

```clojure
(ns my.ui
  (:require [ui.nebula]                                     ; MUST come first
            [ui.SWT :as ui :refer [application shell pshelf pshelf-item label]])
  (:import  [org.eclipse.swt SWT]
            [org.eclipse.swt.layout FillLayout]))
```

Running the bundled example:

```bash
cd examples/nebula-widget && make run
```

## Build helper `ui.build.swt` — internal-only for v0.7.0

CDT itself uses a new `ui.build.swt` namespace to compile the bundled
Nebula sources against an extracted SWT jar at build time. **Exposing
it as a public client API is deferred** to a future release.

The reason: the runtime classloader split (the CDT JAR lives on the
JVM startup classpath while bundled SWT lives on a runtime
`DynamicClassLoader` after pomegranate extraction) means a custom
Java widget compiled by the consumer would land on the parent
AppClassLoader, while SWT lives on the child loader — and parent
loaders can't see child loaders. The result is `ClassNotFoundException:
org/eclipse/swt/widgets/Composite` the first time the custom widget is
used. The bundled Nebula widgets sidestep this by traveling INSIDE the
CDT JAR as `nebula.jar`, which gets extracted onto the same loader as
SWT at runtime — but that trick doesn't generalize to client-compiled
widgets without an upstream loader-strategy change.

See `Plans/todo/compile-java-swt-widgets-context.md` (specifically the
"Runtime classloader problem" section) for the full diagnosis and the
four options that were considered. A future v0.8.0+ release ships
either a child-first classloader inside CDT or an equivalent client-
loader strategy and reopens the downstream `ui.build.swt` story.

## Vendored Nebula sources at a pinned upstream SHA

Nebula sources are vendored under [`vendor/nebula/`](../../vendor/nebula/)
at a pinned upstream SHA (see `vendor/nebula/VERSION`). The build is
self-contained: no env vars, no sibling directories, no network at
build time. Bumping to a newer Nebula version is a single step:

```bash
# edit `nebula-sha` in build.clj, then:
make update-vendored-nebula
# review the diff under vendor/nebula/, commit.
```

## CDT does **not** require an Equinox runtime

The bundled Nebula widgets are curated to work without an Eclipse
Equinox container: any widget that hard-requires
`Activator.getDefault()` returning non-null, NLS resource bundles, or
the Eclipse extension registry is **not** in this release.

(Eight widgets — `pagination`, `picture`, `richtext`, `roundedtoolbar`,
`segmentedbar`, `timeline`, `treemapper`, `xviewer` — were deferred to
a future release that introduces a minimal P2 fetcher for compile-time
JFace + supporting bundles. Four more — `bidilayout`, `datechooser`,
`radiogroup`, `geomap` — were reclassified during audit and likewise
deferred.)

[Prior N&N](version-0.5.0.md)
