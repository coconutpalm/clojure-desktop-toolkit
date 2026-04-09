# SWT UI Rules

Rules for Claude Code to follow when writing SWT UI code in this project.

## Theme and Design Guidelines — Search First, Always

**Before creating any `Color`, `Font`, icon, spacing value, or other visual element, ALWAYS search the project for a theme or design guidelines document.** Do not invent visual values from scratch when a specification may exist.

Search for files such as:
- `THEME.md`, `DESIGN.md`, `design-guidelines.md`, `style-guide.md`
- `theme.clj`, `colors.clj`, `fonts.clj`, `palette.clj`
- Any file whose name contains `theme`, `design`, `style`, `brand`, `color`, or `palette`

If a theme document is found, **read it completely before writing any visual code** and treat it as the authoritative source for every colour, typeface, size, spacing, and icon decision in the application. Never deviate from the project's established visual design — do not substitute your own colour choices, invent new font sizes, or add spacing values not present in the specification.

If no theme document exists, fall back to sensible platform defaults (system colors via `Display.getSystemColor`, standard font stacks) and note the absence so the developer can decide whether to create one.

## SWT Snippets Reference

Eclipse maintains an official directory of small, focused SWT examples in Java called **Snippets**, indexed by widget type:

**https://eclipse.dev/eclipse/swt/snippets/**

When you need to know how to use an SWT widget or feature correctly, look for a relevant snippet there first. Then translate the Java code into idiomatic CDT Clojure — replacing direct interop with CDT init functions, `on` for event handlers, `grid-layout`/`grid-data` helpers for layout, etc.

Snippets are the authoritative reference for correct SWT usage patterns, particularly for complex widgets like `Table`, `Tree`, `StyledText`, `CTabFolder`, drag-and-drop, and custom drawing.

## Browser Widget

When executing JavaScript via `Browser.evaluate`, SWT wraps your code inside an anonymous JavaScript function. You must explicitly `return` the result — otherwise the browser returns `nil` regardless of what the script computes:

```clojure
;; BAD: no return — always yields nil
(.evaluate browser "document.getElementById('output').innerHTML")

;; GOOD: explicit return
(.evaluate browser "return document.getElementById('output').innerHTML")
```

This applies to any value you want back: strings, numbers, booleans, and arrays.

## Forward Declarations

Use `(declare ...)` to handle mutual recursion between functions. A common pattern in SWT UIs is an event handler that calls a helper function which in turn references the widget-building function that contains the handler:

```clojure
(declare build-tab)   ; forward-declare to break the cycle

(defn on-tab-close [props parent event]
  (async-exec!
    #(do (.dispose parent)
         (build-tab props some-folder))))   ; references build-tab

(defn build-tab [props folder]
  (child-of folder props
    (defchildren
      (composite (id! :ui/tab-content))
      (c-tab-item
        :text "My Tab"
        (control :ui/tab-content)
        (on e/close [props parent event]
          (on-tab-close props parent event))))))   ; references on-tab-close
```

Without the `(declare build-tab)` at the top, the compiler will reject `on-tab-close` because `build-tab` isn't defined yet.

## UI Testing at the REPL

When the application is already running (its event loop is active), you can build and inspect UI components interactively from the REPL without touching the live application. The pattern:

1. **Create a temporary shell** using `shell` and `child-of`
2. **Screenshot the shell** with `screenshot-widget!` into a temp file
3. **Read the image** to verify the UI looks correct
4. **Dispose the shell** when done

If the application is already running, `process-events` is unnecessary — the application's own event loop will render the new shell automatically. `process-events` is only needed if no event loop is running.

Since all UI work must be on the UI thread, wrap calls in `(ui ...)`. The REPL thread is already separate from the UI thread, so `(ui ...)` dispatches correctly without any additional wrapping:

```clojure
;; 1. Build a temporary shell with the UI under test
(def test-result
  (ui (child-of (Display/getDefault) (atom {})
        (shell SWT/SHELL_TRIM "Test"
          :layout (FillLayout.)
          (label "Does this look right?")
          (button SWT/PUSH "OK")))))

;; 2. Screenshot it to a temp file
(ui (screenshot-widget! (:child test-result) "/tmp/test-ui.png"))

;; 3. Read /tmp/test-ui.png with the Read tool to visually inspect it

;; 4. Dispose the test shell when done
(ui (.dispose (:child test-result)))
```

For shells containing multiple widgets, pass them as additional init functions — `shell` takes `& inits` and composes them directly, so `defchildren` is not needed:

```clojure
(def test-result
  (ui (child-of (Display/getDefault) (atom {})
        (shell SWT/SHELL_TRIM "Test"
          (grid-layout :numColumns 2)
          (label "Name:")
          (text SWT/BORDER
            (hgrab))
          (label "Email:")
          (text SWT/BORDER
            (hgrab))))))
```

**macOS caveat**: This workflow only works on macOS if the running application itself embeds and exposes an nREPL server. A standalone REPL process cannot work because it cannot acquire the first thread — which macOS requires for all UI work — since the application already owns it.

**HiDPI tip**: On HiDPI/Retina screens, screenshotting an entire shell can produce a very large bitmap that is hard to read and analyze. If that happens, use `(id! :ui/focus-area)` on the specific widget or composite you want to examine, retrieve it from props, and screenshot just that widget instead:

```clojure
(def test-result
  (ui (child-of (Display/getDefault) (atom {})
        (shell SWT/SHELL_TRIM "Test"
          (grid-layout :numColumns 2)
          (composite (id! :ui/focus-area)
            (grid-layout :numColumns 2)
            (label "Name:")
            (text SWT/BORDER
              (hgrab)))))))

(ui (screenshot-widget! (:ui/focus-area (:props test-result)) "/tmp/test-ui.png"))
```

This workflow lets you iterate on layout and styling quickly without restarting the application.

## Application Entry Point (`-main`)

CDT bundles platform-specific SWT native libraries inside its JAR and unpacks them at runtime. This dynamic loading requires specific setup in your `-main` function **before** any UI namespace is touched. The pattern has three steps:

### Step 1 — Enable dynamic classloading

Replace the current thread's classloader with a `DynamicClassLoader` so that Clojure can load the SWT native library at runtime:

```clojure
(let [cl (.getContextClassLoader (Thread/currentThread))]
  (.setContextClassLoader (Thread/currentThread)
    (clojure.lang.DynamicClassLoader. cl)))
```

### Step 2 — Require `ui.SWT` in code, not in the `ns` form, under `*repl* true`

The `ns` form runs at compile time, before the classloader override is in place. Requiring `ui.SWT` (or your UI namespace that transitively requires it) at compile time means SWT's native library extraction fails. Instead, require it at runtime inside a `(binding [*repl* true] ...)` block — the `*repl* true` binding enables Clojure 1.12's `add-libs` pathway that CDT relies on for dynamic dependency resolution:

```clojure
(binding [*repl* true]
  (require '[my-app.ui]))   ; triggers SWT native-lib extraction and API generation
  ...)
```

### Step 3 — Start the UI with `eval`

Because the UI namespace was `require`d in code rather than in the `ns` form, the Clojure compiler doesn't know about its vars at compile time and will reject direct calls to them. Use `eval` to invoke the entry point after the `require` has run:

```clojure
(eval `(my-app.ui/hello ~@args))
```

### Complete `-main` template

```clojure
(ns my-app.main
  (:gen-class))   ; do NOT require ui.SWT or any UI namespace here

(defn -main [& args]
  ;; 1. Enable dynamic classloading for SWT native lib extraction
  (let [cl (.getContextClassLoader (Thread/currentThread))]
    (.setContextClassLoader (Thread/currentThread)
      (clojure.lang.DynamicClassLoader. cl)))

  (binding [*repl* true]
    ;; 2. Require the UI namespace at runtime (not in the ns form)
    (require '[my-app.ui])

    ;; 3. Start the UI via eval (compiler doesn't know about my-app.ui at compile time)
    (eval `(my-app.ui/hello ~@args))))
```

**Splash screen note**: If your app uses a JAR manifest splash screen (`SplashScreen-Image`), close it from a separate thread with a short delay — on macOS, closing the splash screen on the main thread before the UI is ready causes a hang:

```clojure
(future
  (Thread/sleep 1000)
  (when-let [splash (java.awt.SplashScreen/getSplashScreen)]
    (.close splash)))
```

## REPL Workflow Rules

- **Never use `:reload-all`** — it reloads the entire dependency graph, which will almost certainly hit a namespace containing a global atom and reset it to its initial value, destroying live state and forcing a REPL restart.
- **Prefer `:reload` over `:reload-all`**, but only when you are certain the namespace being reloaded does not define or initialise a global atom. `(require '[my.ns] :reload)` reloads only that namespace.
- **Prefer re-evaluating only the changed forms** at the REPL over reloading entire namespaces. Evaluate just the `defn` or `def` that changed, then evaluate its callers if needed. This is the safest and fastest workflow.
- **Never use `:reload` or `:reload-all` on any namespace that defines or initialises a global atom** — this includes namespaces that hold UI state (`display`, `ui-state`, etc.) or application configuration. Reloading these will destroy live state and require a full REPL restart to recover.

## UI Thread Rules

1. **The thread that calls `(Display/getDefault)` becomes the SWT UI thread** — all SWT event processing happens on that thread for the lifetime of the app. In practice, `application` calls `(Display/getDefault)` internally, so the thread calling `application` becomes the UI thread. When starting from the REPL, this means you must invoke `application` (or `hello`, etc.) from a `future` or other background thread — e.g. `(def app (future (hello)))` — so the REPL thread is not captured as the UI thread.

   **macOS app name**: Call `(Display/setAppName "My App")` before `(Display/getDefault)` is called, so the correct name appears in the macOS system menu bar. Once the `Display` is created it is too late to set this.

   **macOS additional requirement**: On macOS, Cocoa requires all UI work to happen on the JVM's first thread. The JVM must be launched with `-XstartOnFirstThread`, and that first thread must be the one that calls `(Display/getDefault)` to claim itself as the SWT UI thread. When running an uberjar: `java -XstartOnFirstThread -jar target/your-app.jar`. Once the UI thread is established, other threads can safely perform UI work by routing through `(ui ...)`, `sync-exec!`, or `async-exec!` — these dispatch back to the UI thread on all platforms including macOS.

   **macOS exit**: After `application` returns on macOS the JVM process hangs. Always call `(System/exit 0)` immediately after `application` in your `-main` or top-level entry point:

   ```clojure
   (defn -main [& args]
     (application ...)
     (System/exit 0))   ; required on macOS; harmless on other platforms
   ```

2. **Never call `application` from the REPL directly** — the event loop will block the REPL. Always run it from a separate thread. Also, never call `application` if it is already running — `application` creates a fresh `props` atom each time, so any references the application has stored to the previous `props` atom will be clobbered.

3. **All UI mutations must use `async-exec!`** — it queues work after all pending events have finished processing. Never mutate UI state directly from background threads or from within event handlers (see rule 6).

4. **Use `sync-exec!` (or the `ui` macro) only for reading UI state**, not mutating it. It blocks the calling thread and runs before pending events, which can corrupt UI state if used for writes.

5. **If you're already on the UI thread**, `with-ui*` (which backs both `sync-exec!` and `ui`) calls `f` directly without going through `syncExec` — so `(ui ...)` is safe to call from event handlers without deadlocking.

6. **Event handlers must only READ UI state, never mutate it.** When a user action occurs (keypress, mouse click, traversal), the platform spawns a sequence of related events — for example: `KeyDown → Traverse → FocusOut → FocusIn → KeyUp`, or a similar chain for mouse events. Writing UI state from inside an event handler inserts foreign events into the middle of this platform-managed transaction, which can cause undefined behavior — especially across platforms. Always wrap UI mutations in `async-exec!`, even from within an event handler on the UI thread.

   **Exception — vetoing an event**: Setting `(set! (. event doit) false)` to cancel or veto an event *must* be done synchronously inside the handler — it has no effect if deferred to `async-exec!`. This is the only legitimate direct mutation inside an event handler. Any follow-on UI work (e.g. hiding the widget) should still use `async-exec!`:

   ```clojure
   (on e/shell-closed [props parent event]
     (when-not (:closing @props)
       (set! (. event doit) false)           ; must be synchronous — vetoes the close
       (async-exec! #(.setVisible parent false))))  ; follow-on mutation deferred
   ```

7. **`ui-thread?`** is available to check whether you're on the UI thread if you need conditional dispatch.

8. **`kill-application!`** itself uses `(ui ...)` to dispose the display — reinforcing that even disposal must happen on the UI thread.

## Init Functions and Composition

Every CDT widget constructor returns an init function with the signature `(fn [props parent] ...)`:

- **`props`** is a shared `atom` (a plain Clojure map) threaded through the entire UI tree. It is created fresh by `application` and flows into every init function automatically.
- **`parent`** is the SWT widget that this init function should attach itself to. At the top level, `parent` is the SWT `Display`; for nested widgets it is the enclosing composite/shell.

This uniform signature is what makes composition work. Passing child init functions to a parent is just passing functions — the parent calls each child fn with `props` and itself as `parent`, building the widget tree recursively:

```clojure
(shell "My App"               ; shell receives Display as parent
  :layout (GridLayout.)
  (composite                  ; composite receives Shell as parent
    (label "Name:")           ; label receives Composite as parent
    (text SWT/BORDER)))       ; text receives Composite as parent
```

### Init functions always create — never retrieve

**Never call an init function to get a reference to an existing widget.** Init functions are constructors — calling one always creates a new widget. For example, calling `(main-window)` will create a second main window, not return the existing one.

To get a reference to an existing widget, always look it up by its assigned id in the props map:

```clojure
;; BAD: calling the init function again — creates a NEW window
(let [w (main-window)]
  (.setVisible w false))

;; GOOD: retrieve the existing widget from props by id
(let [w (:ui/main-window @ui-state)]
  (async-exec! #(.setVisible w false)))
```

### Naming widgets with `id!`

Use `(id! :ui/name)` as a child init function to store a named reference to the enclosing widget in `props`:

```clojure
(text SWT/BORDER
  (id! :ui/name-field))
```

This executes `(swap! props assoc :ui/name-field parent)`, so `:ui/name-field` in `@props` points to the Text widget. **By convention, widget identity keys use the `ui` namespace** (e.g. `:ui/name-field`, `:ui/submit-button`).

To retrieve a widget reference later — e.g. in an event handler or `defmain` block:

```clojure
(defmain [props parent]
  (let [field (:ui/name-field @props)]
    (.setText field "default value")))
```

### Props manipulation helpers

These init function factories in `ui.SWT` let you store arbitrary state in `props` alongside widget references:

| Helper | Behaviour |
|--------|-----------|
| `(id! :kw)` | Stores the current widget under `:kw` in props |
| `(reset-prop! :kw v)` | Sets `(:kw @props)` to `v` |
| `(assoc-in-prop! [:k1 :k2] v)` | Like `assoc-in` on props |
| `(update-in-prop! [:k1 :k2] f)` | Like `update-in` on props |

`definit` and `defmain` are syntactic sugar for writing inline init functions when the above helpers aren't enough:

```clojure
;; These two are functionally identical; defmain signals "this runs after the UI is built"
(definit [props parent] (swap! props assoc :ui/ready? true))
(defmain [props parent] (.setText (:ui/title @props) "Loaded"))
```

By convention, `defmain` is placed last in the UI tree and used to publish the fully-constructed props into a global atom, giving the rest of the application access to the UI:

```clojure
(def ui-state (atom nil))

(application
  (shell "My App"
    (text SWT/BORDER
      (id! :ui/name-field))
    (button SWT/PUSH "Submit"
      (id! :ui/submit-btn)))
  (defmain [props _]
    (reset! ui-state @props)))  ; all widget refs now accessible via @ui-state

;; Elsewhere in the application:
(.getText (:ui/name-field @ui-state))
```

## Icons and Images on macOS

**NEVER use PNG images with partial transparency (0 < alpha < 255) in SWT applications.** On macOS, SWT's Cocoa image renderer does not correctly render semi-transparent pixels — they render as an opaque blob. Only fully transparent (alpha=0) and fully opaque (alpha=255) pixels are safe. Note that drawing commands that use anti-aliasing *do* work correctly — the restriction applies only to pre-rendered pixel data with intermediate alpha values.

### Workaround: Draw icons programmatically

Once a human has approved the SVG design for an icon, do not export it to PNG. Instead, write a Clojure function that draws the icon programmatically using a `GC`. This avoids semi-transparent pixels entirely while still producing smooth, anti-aliased output.

The function must:
1. Accept a `size` argument (the icon's pixel dimension)
2. Create a new `Image` of that size
3. Set the background to `SWT/COLOR_WIDGET_BACKGROUND` as the first drawing step, so the icon blends correctly with the platform UI
4. Draw the icon using `GC` drawing commands
5. Dispose the `GC` and return the `Image`

```clojure
(defn draw-my-icon [display size]
  (let [img (Image. display size size)]
    (doto-gc-on img
      (.setBackground (.getSystemColor display SWT/COLOR_WIDGET_BACKGROUND))
      (.fillRectangle 0 0 size size)
      (.setAntialias SWT/ON)
      (.setForeground (.getSystemColor display SWT/COLOR_DARK_BLUE))
      (.drawOval 2 2 (- size 4) (- size 4)))
    img))
```

### HiDPI / Retina support

On platforms that support double-resolution icons (macOS Retina, HiDPI Linux), the icon function must be called **twice** — once at 1x and once at 2x — and the results wrapped in an `ImageDataProvider` so SWT can select the correct resolution automatically:

```clojure
(defn my-icon [display size]
  (reify org.eclipse.swt.graphics.ImageDataProvider
    (getImageData [_ zoom]
      (let [scaled (int (* size (/ zoom 100)))
            img    (draw-my-icon display scaled)]
        (try
          (.getImageData img)
          (finally (.dispose img)))))))

;; Usage
(image-label :image (Image. display (my-icon display 16)))
```

Always follow this pattern for any icon that will appear in toolbars, tabs, tray items, or other UI chrome where Retina rendering matters.

### Icon registry

The best practice is to maintain an **icon registry** — a global map from `[role size]` to `Image` — so each icon is drawn and wrapped once and reused across the UI. This is especially important for programmatically drawn icons, where re-creating the `Image` on every use would be both wasteful and a resource-leak risk.

Always construct registry `Image` objects using an `ImageDataProvider` rather than calling `draw-my-icon` directly. According to the `ImageDataProvider` Javadoc, returning `null` for any zoom level other than 100 is explicitly allowed — SWT will fall back to scaling the 100% image. This means an `ImageDataProvider` is safe on all platforms: non-HiDPI displays only ever request zoom=100, and HiDPI displays can request zoom=200 (or 150) and get the crisply-drawn result. Using `ImageDataProvider` everywhere is therefore the correct universal approach.

```clojure
(def icon-registry (atom {}))

(defn- make-icon-provider [display draw-fn size]
  (reify org.eclipse.swt.graphics.ImageDataProvider
    (getImageData [_ zoom]
      (let [scaled (int (* size (/ zoom 100)))
            img    (draw-fn display scaled)]
        (try
          (.getImageData img)
          (finally (.dispose img)))))))

(defn icon
  "Return a cached Image for the given role keyword and logical pixel size.
   Constructed via ImageDataProvider so HiDPI and standard displays both work.
   Creates and registers the icon on first use."
  [display role size]
  (or (get @icon-registry [role size])
      (let [draw-fn (case role
                      :save   draw-save-icon
                      :delete draw-delete-icon
                      (throw (ex-info "Unknown icon role" {:role role :size size})))
            img (Image. display (make-icon-provider display draw-fn size))]
        (swap! icon-registry assoc [role size] img)
        img)))
```

Dispose all registry images on application exit:

```clojure
(defmain [props parent]
  (on e/shell-closed [props parent event]
    (doseq [[_ img] @icon-registry]
      (.dispose img))
    (reset! icon-registry {})))
```

## Resource Management

OS resources — `Image`, `GC`, `Color`, `Font`, `Region`, `Cursor`, etc. — are **not garbage collected**. The rule is: **if you created it, you must dispose it.**

For short-lived resources, always use `try/finally` to guarantee disposal:

```clojure
(let [img (Image. display 100 100)]
  (try
    (do-something-with img)
    (finally (.dispose img))))
```

CDT provides `with-gc-on` and `doto-gc-on` helpers that handle this for `GC` objects:

```clojure
(doto-gc-on canvas
  (.setForeground blue)
  (.drawRect 0 0 100 100))
```

For long-lived resources that are tied to a widget's lifetime, dispose them in an `(on e/widget-disposed ...)` handler on the owning widget:

```clojure
(label
  (definit [props parent]
    (let [font (Font. display (FontData. "Arial" 14 SWT/BOLD))]
      (.setFont parent font)
      ; Dispose when the widget is disposed
      ((on e/widget-disposed [props parent event]
         (.dispose font)) props parent))))
```

For resources tied to a window's lifetime, dispose in `(on e/shell-closed ...)`:

```clojure
(shell "My App"
  (definit [props parent]
    (let [img (Image. display "/path/to/image.png")]
      (swap! props assoc :ui/header-image img)
      ((on e/shell-closed [props parent event]
         (.dispose img)) props parent))))
```

**Exception**: Resources that live in an application-global registry (e.g. a shared image cache or color palette) should be disposed when the application exits, not when individual widgets are disposed.

### Theme and UI guidelines

See **Theme and Design Guidelines — Search First, Always** at the top of this file. That rule applies to every `Color`, `Font`, icon, and spacing decision made here.

### Color management

**Prefer system colors over constructed ones.** SWT provides a palette of platform-appropriate colors via `Display.getSystemColor(SWT/COLOR_*)` — these are pre-allocated, never need to be disposed, and automatically adapt to the platform's color scheme (light/dark mode, high-contrast, etc.). Use them wherever the design permits:

```clojure
(.getSystemColor display SWT/COLOR_WIDGET_BACKGROUND)
(.getSystemColor display SWT/COLOR_LIST_FOREGROUND)
(.getSystemColor display SWT/COLOR_TITLE_BACKGROUND)
```

When a theme document specifies custom colors that differ from the system palette, maintain a **color registry** — a global map from a role keyword to `Color` — so each custom color is constructed once and reused. Colors are OS resources and must be disposed; a registry makes that tractable.

```clojure
(def color-registry (atom {}))

(defn color
  "Return a cached Color for the given role keyword.
   Falls back to the system color for the given SWT constant if no custom
   color is registered for the role."
  [display role]
  (or (get @color-registry role)
      (throw (ex-info "Unknown color role — check theme document" {:role role}))))

(defn register-colors!
  "Construct and register all custom colors from the theme palette.
   Call once at application startup, after reading the theme document."
  [display]
  (reset! color-registry
    {:brand-primary   (Color. display 0x1A 0x73 0xE8)
     :brand-secondary (Color. display 0x34 0xA8 0x53)
     :surface         (Color. display 0xF8 0xF9 0xFA)
     :on-surface      (Color. display 0x20 0x21 0x24)}))
```

Dispose all registry colors on application exit:

```clojure
(defmain [props parent]
  (on e/shell-closed [props parent event]
    (doseq [[_ c] @color-registry]
      (.dispose c))
    (reset! color-registry {})))
```

Colors should be requested by **semantic role** (`:brand-primary`, `:surface`, `:error`, etc.) rather than by raw RGB values. This mirrors how the font registry uses `:sans`/`:serif`/`:mono` — the role name conveys intent; the value comes from the theme.

**Do not construct ad-hoc `Color` objects inline** (e.g. `(Color. display 255 0 0)`) in widget init functions. Every such call allocates an OS resource that is almost certainly never disposed. Go through the registry or `getSystemColor` instead.

### Font management

The best practice is to maintain a **font registry** — a global map from `[typeface size style]` to `Font` — so fonts are constructed once and reused across the UI. Dispose all registry fonts on application exit.

Fonts should be requested by role (`:sans`, `:serif`, `:mono`) rather than by name, resolved through a **typeface fallback stack** that renders well across platforms. Construct the stack carefully, particularly if the application uses:
- **Box drawing characters** (U+2500–U+257F) — not all system fonts cover these
- **Nerd Font symbols** — require a patched font to be installed (e.g. `JetBrainsMono Nerd Font`, `FiraCode Nerd Font`)

Example fallback stacks:

```clojure
(def font-stacks
  {:sans  ["Inter" "Noto Sans"                             ; cross-platform
           "SF Pro Text" "Helvetica Neue"                  ; macOS
           "Segoe UI" "Tahoma" "Arial"                     ; Windows
           "Ubuntu" "DejaVu Sans" "sans-serif"]            ; Linux / fallback
   :serif ["Noto Serif"                                    ; cross-platform
           "New York" "Georgia" "Palatino"                 ; macOS
           "Constantia" "Times New Roman"                  ; Windows
           "DejaVu Serif" "serif"]                         ; Linux / fallback
   :mono  ["JetBrainsMono Nerd Font" "FiraCode Nerd Font"  ; Nerd Font variants
           "JetBrains Mono" "Fira Code" "Noto Sans Mono"   ; cross-platform
           "SF Mono" "Menlo"                               ; macOS
           "Cascadia Code" "Consolas" "Courier New"        ; Windows
           "DejaVu Sans Mono" "monospace"]})               ; Linux / fallback
```

Resolve the stack at startup by checking which fonts are actually installed on the running platform, then cache the winning name. For monospace/box-drawing use, verify coverage by checking `FontData` availability before committing to a choice.

## Adding Children Dynamically with `child-of`

**Only use `child-of` for adding widgets to an already-constructed UI at runtime.** A typical use case is adding a new `CTabItem` and its associated control to a `CTabFolder` in response to a user action. Do not use it during initial UI construction — nesting `child-of` calls inside the layout tree just obscures the hierarchy without any benefit.

`child-of` takes the parent widget, the props atom, and a single init function. It returns a map of `{:child <the new widget> :props <resulting props map>}`.

```clojure
;; Adding a single widget dynamically at runtime
(child-of tab-folder props
  (c-tab-item
    :text "New Tab"))
```

### `defchildren` — mounting multiple children into an existing composite

`child-of` accepts exactly one init function, but real use cases often require mounting several widgets together into an already-existing parent composite. `defchildren` bundles multiple init functions into one, making this idiomatic. Use it whenever you need to add more than one widget via `child-of`.

**Creating a `control`/`CTabItem` pair dynamically inside a `CTabFolder`** — the most common pattern. The content composite must be a sibling of the item, both added in the same `child-of` call so the `:ui/...` id is in props before `(control ...)` runs:

```clojure
;; Production code: open a new tab in response to a user action
(defn open-tab! [props tab-folder title content-fn]
  (child-of tab-folder props
    (defchildren
      (composite (id! :ui/new-tab-content)
        (grid-layout)
        (content-fn))
      (c-tab-item
        :text title
        (control :ui/new-tab-content)))))
```

**Mounting toolbar buttons into a `CTabFolder`'s toolbar composite** — the toolbar composite already exists; `defchildren` mounts the buttons as a group:

```clojure
(child-of (:ui/tab-toolbar @props) props
  (defchildren
    (button SWT/PUSH "+"
      (on e/widget-selected [props parent event]
        (async-exec! #(open-tab! props ...))))
    (button SWT/PUSH "⚙"
      (on e/widget-selected [props parent event]
        (async-exec! #(open-settings! props))))))
```

**REPL testing** — same pattern; `defchildren` lets you mount a realistic group of children into a temporary test composite:

```clojure
(def test-result
  (ui (child-of (Display/getDefault) (atom {})
        (shell SWT/SHELL_TRIM "Test"
          (let [folder (c-tab-folder SWT/BORDER (id! :ui/folder))])))))

;; Mount a tab pair into the already-constructed folder
(child-of (:ui/folder @(:props test-result)) (:props test-result)
  (defchildren
    (composite (id! :ui/tab-content)
      (grid-layout)
      (label "Hello"))
    (c-tab-item
      :text "Tab 1"
      (control :ui/tab-content))))
```

`child-of` also accepts a `[parent props]` vector as its first argument, which allows chaining:

```clojure
(-> [tab-folder props]
    (child-of (composite (id! :ui/tab-content) ...))
    (child-of (c-tab-item
                :text "New Tab"
                (control :ui/tab-content))))
```

## Modal Dialogs

Use `shell-modal` to create a modal dialog that blocks the caller until the dialog is dismissed, then returns the dialog's props. This is the correct way to run a nested SWT event loop — it handles the platform-specific details of blocking until the shell is disposed.

```clojure
(defn confirm-dialog
  "Show a modal confirmation dialog. Returns true if the user clicked OK."
  [parent-shell message]
  (let [dialog-props
        (shell-modal parent-shell
          (| SWT/APPLICATION_MODAL SWT/DIALOG_TRIM)
          :text "Confirm"
          (grid-layout)
          (label SWT/WRAP
            :text message
            (hgrab))
          (composite
            (grid-layout :numColumns 2 :makeColumnsEqualWidth true)
            (hgrab)
            (button SWT/PUSH "OK"
              (on e/widget-selected [props parent event]
                (swap! props assoc :ui/result true)
                (async-exec! #(.dispose (.getShell parent)))))
            (button SWT/PUSH "Cancel"
              (on e/widget-selected [props parent event]
                (async-exec! #(.dispose (.getShell parent)))))))]
    (:ui/result @dialog-props)))   ; returns true or nil
```

Key points:
- `shell-modal` takes the **parent shell** as its first argument, then init functions exactly like `shell`. It opens the dialog and **blocks the calling thread** until the shell is disposed.
- `shell-modal` creates a **fresh `props` atom** for the dialog, independent of the parent's props. Results must be stored into that dialog's props (via `swap!`) before disposing the shell.
- After `shell-modal` returns, dereference the returned props atom to retrieve results.
- **Dispose the shell from within `async-exec!`** — per the UI threading rules, disposing (a UI mutation) from inside an event handler must be deferred to avoid interrupting the event transaction.

## Event Handling

Events are registered using the `on` macro as a child init function inside a widget:

```clojure
(on event-keyword [props parent event] body...)
```

- **`event-keyword`** — a kebab-case keyword (or `e/` constant) naming the listener method to handle
- **`props`** — the shared props atom (same one threaded through the whole UI tree)
- **`parent`** — the widget the listener is attached to
- **`event`** — the raw SWT `TypedEvent` subclass instance (e.g. `SelectionEvent`, `ModifyEvent`)

```clojure
(button SWT/PUSH "Save"
  (on e/widget-selected [props parent event]
    (async-exec! #(save! (:ui/name-field @props)))))
```

### How event keywords map to SWT

CDT derives event keywords from SWT listener interface method names by converting `camelCase` to `kebab-case`:

| SWT listener interface | SWT method | CDT keyword |
|------------------------|------------|-------------|
| `SelectionListener` | `widgetSelected` | `:widget-selected` / `e/widget-selected` |
| `ModifyListener` | `modifyText` | `:modify-text` / `e/modify-text` |
| `ShellListener` | `shellClosed` | `:shell-closed` / `e/shell-closed` |
| `VerifyListener` | `verifyText` | `:verify-text` / `e/verify-text` |

**Always prefer `e/constant` over raw kebab-case keywords.** Both work, but `e/widget-selected` is navigable by tooling, catches typos at compile time, and makes it clear the value comes from the CDT event vocabulary. Raw keywords like `:widget-selected` are opaque strings with no such guarantees. All available constants are defined as vars in the `ui.events` namespace, accessed via the `e/` alias.

### Finding the right event

Because the event bindings are generated from SWT's listener interfaces via reflection, the source code won't tell you what's available. Use the REPL:

```clojure
(swtdoc :swt :listeners)   ; all listener interfaces and their methods
(swtdoc :swt :events)      ; all TypedEvent subclasses and their fields
```

To find events for a specific widget, look up the SWT Javadoc for that widget class and read its `add*Listener` methods. Each `addXxxListener(XxxListener)` method corresponds to an interface whose methods become `e/` constants. For example, `Text.addModifyListener(ModifyListener)` has one method `modifyText` → `e/modify-text`.

The `event` object's fields (e.g. `(.text event)`, `(.doit event)`, `(.keyCode event)`) come directly from the SWT `TypedEvent` subclass. Use `(swtdoc :swt :events)` or the Eclipse Javadoc to see what fields are available for a given event type.

### Multiple methods from the same listener interface

When you need to handle more than one method from the same SWT listener interface, **do not use multiple `on` forms** — some SWT controls (e.g. `CTabFolder` minimize and maximize handlers) do not correctly dispatch to multiple registered adapters for the same interface. Instead, drop to Java interop and `reify` the listener adapter class directly, implementing all required methods in one place:

```clojure
;; BAD: two separate `on` handlers for the same listener interface
(c-tab-folder
  (on e/minimize [props parent event] (async-exec! #(.setMinimized parent true)))
  (on e/maximize [props parent event] (async-exec! #(.setMaximized parent false))))

;; GOOD: reify the adapter directly, handling both methods together
(definit [props parent]
  (.addCTabFolder2Listener parent
    (reify org.eclipse.swt.custom.CTabFolder2Adapter
      (minimize [this event]
        (async-exec! #(.setMinimized parent true)))
      (maximize [this event]
        (async-exec! #(.setMaximized parent false))))))
```

Use `(swtdoc :swt :listeners)` to find the correct adapter class name for a given listener interface.

## System Tray

`tray-item` creates an OS system tray entry. It must be a **direct child of `application`**, placed at the same level as `shell` — not inside a shell:

```clojure
(application
  (tray-item                              ; sibling of shell, not inside it
    (on e/menu-detected [props parent event]
      (async-exec! #(.setVisible (:ui/tray-menu @props) true)))
    (on e/widget-selected [props parent event]
      (let [shell (:ui/shell @props)]
        (async-exec! #(.setVisible shell (not (.isVisible shell)))))))

  (shell SWT/SHELL_TRIM (id! :ui/shell)
    ...
    (menu SWT/POP_UP (id! :ui/tray-menu)
      (menu-item SWT/PUSH "&Quit"
        (on e/widget-selected [props parent event]
          (swap! props assoc :closing true)
          (async-exec! #(.close (:ui/shell @props))))))))
```

The canonical "minimize to tray" pattern intercepts `e/shell-closed` on the shell to hide instead of close, using a `:closing` flag in props to distinguish a real quit from a regular window close:

```clojure
(on e/shell-closed [props parent event]
  (when-not (:closing @props)
    (set! (. event doit) false)               ; veto the close (must be synchronous)
    (async-exec! #(.setVisible parent false)))) ; hide instead
```

## Built-in Custom Widgets

CDT ships a work-in-progress higher-level widget in the `ui.widgets` namespace:

### `ui.widgets.console`

Turns a `Browser` widget into a read-only ANSI terminal with a black background. Useful for embedding log output or REPL output in a UI.

**This is currently an alpha-quality release**

```clojure
(require '[ui.widgets.console :as console])

;; In the UI tree: create a browser and initialise it as a console
(browser SWT/WEBKIT (id! :ui/log-browser))

;; Later, from any thread (via async-exec!):
(async-exec! #(console/append (:ui/log-browser @props) "Hello \033[1;32mworld\033[0m\n"))
(async-exec! #(console/clear   (:ui/log-browser @props) ""))
```

**Note**: the console loads `ansi_up` from a CDN at runtime (`cdn.jsdelivr.net`). It requires network access on first use.

## API Style Rules

9. **The `widget` macro is an escape hatch — use it only for custom SWT controls that have no CDT-generated init function.** When a CDT init function exists for a widget, use it instead.

   Because CDT's widget init functions are generated via reflection at compile time, they don't appear in source code and can't be discovered by reading it. Use the REPL and call `swtdoc` to find what's available.

   Here are some representative examples:

   ```clojure
   (swtdoc :swt :composites)   ; Composite subclasses (Group, TabFolder, SashForm, ...)
   (swtdoc :swt :widgets)      ; Widget subclasses (Button, Text, Label, Tree, ...)
   (swtdoc :swt :items)        ; Item subclasses (TableItem, TreeItem, MenuItem, ...)
   ```

   Check these before reaching for `widget` or raw Java interop.

10. **Build custom controls as plain functions that return init functions — no subclassing required.** Because widget constructors are just functions that return init functions, you can compose them into reusable custom controls using `defn`. Extra init functions passed by the caller are appended, giving the caller the same configuration flexibility as any built-in widget:

    ```clojure
    (defn custom-browser [& extra-inits]
      (apply browser SWT/WEBKIT
        :javascript-enabled true
        (on e/changing [props parent event] ...)
        extra-inits))

    ;; Called exactly like any other CDT widget
    (custom-browser
      (id! :ui/results)
      :text initial-html)
    ```

11. **`Item` subclasses are not composites — don't add controls as their children.** Items (e.g. `TabItem`, `TableItem`, `TreeItem`, `CTabItem`) are not containers. When an item needs to reference a control (e.g. the content panel inside a `TabItem`), construct the control separately as a sibling and link it via `.setControl`:

    ```clojure
    ;; BAD: trying to nest a Composite inside a TabItem as a child
    (tab-item "Settings"
      (composite   ; won't work — TabItem is not a parent
        (label "Foo")))

    ;; GOOD: construct the control as a sibling, then link with `control`
    (tab-folder
      (tab-item "Settings"
        (control :ui/settings-panel))   ; links once :ui/settings-panel is in props
      (composite (id! :ui/settings-panel)
        (label "Foo")))
    ```

12. **Keep widget constructor lines clean.** At most one item from the following precedence order may appear on the same line as a widget constructor — everything else goes on subsequent lines:

    1. Style bits (e.g. `SWT/BORDER`, `(| SWT/PUSH SWT/BORDER)`)
    2. `(id! :kw)`
    3. A bare string label/title (shorthand for `:text` — see rule 13a)

    Only one of these may appear on the constructor line. Everything else (layout, event handlers, child widgets, other property keywords) always goes on subsequent lines.

    ```clojure
    ;; BAD: grid-layout on the constructor line
    (composite (grid-layout :numColumns 2) ...)

    ;; BAD: both style bits and id! on the constructor line
    (text SWT/BORDER (id! :ui/name-field) ...)

    ;; GOOD: style bits on the constructor line, everything else below
    (text SWT/BORDER
      (id! :ui/name-field)
      (hgrab))

    ;; GOOD: id! alone on the constructor line
    (composite (id! :ui/client-area)
      ...)

    ;; GOOD: bare string label on the constructor line
    (label "Address:")

    ;; GOOD: style bits + bare string — only one item, no extras
    (button SWT/PUSH "Save"
      (on e/widget-selected [props parent event] ...))

    ;; GOOD: no inline item at all
    (composite
      (grid-layout :numColumns 2)
      ...)
    ```

13. **Omit `:text` when passing a string label — use a bare string instead.** CDT converts bare strings to `.setText` calls automatically, so `:text "Address:"` and `"Address:"` are equivalent. The bare string is more idiomatic and reads more naturally, especially when there are no style bits or `id!`:

    ```clojure
    ;; BAD
    (label :text "Address:")
    (button SWT/PUSH :text "Save")

    ;; GOOD
    (label "Address:")
    (button SWT/PUSH "Save")
    ```

14. **Never assign a property to its default value.** Only set properties that differ from the SWT defaults. This keeps code concise and signals intent clearly — if a property is set, it means something. The positional Java constructors force you to supply every argument regardless; this is another reason to prefer CDT keyword sub-inits, which let you omit defaults entirely.

15. **Never pass `SWT/NONE` explicitly** — CDT supplies it as the default style when no style argument is given. `(composite)` and `(composite SWT/NONE)` are equivalent; the former is preferred. This is a specific instance of rule 14.

16. **Use `|` to compose SWT style bits, not `bit-or`.** The `|` helper is defined in `ui.SWT` specifically for this purpose and mirrors the `|` operator used in SWT Java code, making style expressions easier to read and recognise:

    ```clojure
    ;; BAD
    (shell (bit-or SWT/APPLICATION_MODAL SWT/DIALOG_TRIM))

    ;; GOOD
    (shell (| SWT/APPLICATION_MODAL SWT/DIALOG_TRIM))
    ```

17. **Prefer CDT declarative init functions over Java interop.** If you find yourself writing `.setText`, `.addListener`, or `(new Widget ...)` in bulk, step back and look for a CDT equivalent. Only drop to interop for things CDT genuinely doesn't cover — e.g. centering a dialog, running a modal event loop, or one-off widget configuration with no CDT wrapper.

   **Corollary — GridData**: Use the `ui.gridlayout` helpers (`hgrab`, `vgrab`, `grab-both`, `align-left-hgrab`, `align-right`, `grid-data`, etc.) instead of constructing and mutating `GridData` objects directly.

   ```clojure
   ;; BAD: raw Java interop
   (fn [props parent]
     (let [gd (GridData.)]
       (set! (.horizontalAlignment gd) SWT/FILL)
       (set! (.grabExcessHorizontalSpace gd) true)
       (set! (.verticalAlignment gd) SWT/CENTER)
       (.setLayoutData parent gd)))

   ;; GOOD: CDT helper as an init function in the widget tree
   (text SWT/BORDER
     (hgrab))
   ```

   **Corollary — GridLayout**: Use the `grid-layout` init function with keyword sub-inits rather than constructing `GridLayout` directly and mutating its fields.

   ```clojure
   ;; BAD: Java interop — positional constructor args and field mutation
   (fn [props parent]
     (let [l (GridLayout. 2 false)]
       (set! (.marginWidth l) 10)
       (set! (.horizontalSpacing l) 5)
       (.setLayout parent l)))

   ;; GOOD: grid-layout with keyword sub-inits
   (composite
     (grid-layout
       :numColumns        2
       :marginWidth       10
       :horizontalSpacing 5))
   ```

   **Corollary — GridData constructors**: The named helpers `hgrab`, `vgrab`, `grab-both`, `align-left-hgrab`, `align-right`, `align-center`, etc. cover all common layout cases and should be your first choice. For the rare case they don't cover, use `grid-data` with keyword sub-inits rather than calling `GridData` constructors directly — the Java constructors take positional arguments whose meaning is opaque; keyword sub-inits are self-documenting.

   ```clojure
   ;; BAD: positional GridData constructor — what does true false 2 1 mean?
   ;; Also forces you to supply :verticalSpan 1 even though 1 is the default.
   (fn [props parent]
     (.setLayoutData parent (GridData. SWT/FILL SWT/CENTER true false 2 1)))

   ;; GOOD: grid-data with keyword sub-inits — intent is clear,
   ;; and :verticalSpan is omitted because 1 is the default.
   (text SWT/BORDER
     (grid-data
       :horizontalAlignment SWT/FILL
       :verticalAlignment   SWT/CENTER
       :grabExcessHorizontalSpace true
       :horizontalSpan 2))
   ```

   Note that the positional Java constructors force you to supply every argument including defaults — yet another reason to prefer keyword sub-inits (see rule 14).

   **Corollary — `with-property`**: When you need to set multiple sub-properties on a layout or other property value, use `with-property` instead of local `let`-binding and Java field mutation. `with-property` assigns the named property to its parent widget *and* applies keyword sub-inits to the value itself, all in one expression:

   ```clojure
   ;; BAD: let-binding and field mutation to configure a FillLayout
   (fn [props parent]
     (let [l (FillLayout.)]
       (set! (.marginHeight l) 10)
       (set! (.marginWidth l) 10)
       (.setLayout parent l)))

   ;; GOOD: with-property sets sub-properties and assigns in one step
   (with-property :layout (FillLayout.)
     :margin-height 10
     :margin-width  10)
   ```

   **Corollary — automatic type coercion**: CDT automatically converts Clojure vectors to the Java types SWT expects. Never construct `Point`, `Rectangle`, `RGB`, `RGBA`, `int[]`, or `String[]` by hand — pass a plain vector and CDT will coerce it:

   | Vector | Converts to |
   |--------|-------------|
   | `[x y]` | `Point` |
   | `[x y w h]` | `Rectangle` |
   | `[r g b]` | `RGB` |
   | `[r g b a]` | `RGBA` |
   | `[n1 n2 ...]` | `int[]` |
   | `["a" "b" ...]` | `String[]` |

   ```clojure
   ;; BAD: manual construction
   :weights  (into-array Integer/TYPE [25 75])
   :location (Point. 100 200)
   :bounds   (Rectangle. 0 0 800 600)
   :foreground (RGB. 255 0 0)

   ;; GOOD: plain vectors, CDT coerces automatically
   :weights  [25 75]
   :location [100 200]
   :bounds   [0 0 800 600]
   :foreground [255 0 0]
   ```
