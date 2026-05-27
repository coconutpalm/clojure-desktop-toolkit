# Plan: Compile Java/SWT widgets into the CDT JAR (v1)

**Read `compile-java-swt-widgets-context.md` first.** It explains the
"why" behind every decision below — including why source staging (not
post-javac filtering), why two bases, why pure-SWT in v1, why no P2
fetcher yet, and why the no-Equinox-runtime constraint is permanent.

This file is the step-by-step executable plan for **v1**: source
staging + 57 pure-SWT Nebula widgets via `:exclude-pkgs` and
`:exclude-files`, no JFace anywhere, Nebula sources vendored under
`vendor/nebula/`. v2 (JFace via P2 fetcher) is a separate future plan.

## Pre-flight checks

Before starting:

1. Confirm the REPL is available (the project's CLAUDE.md requires
   REPL-driven development — no file edits without REPL).
2. Read `SWT-UI-RULES.md` if you'll be doing any UI-touching work
   (verification step 7).
3. Read `src/ui/internal/SWT_deps.clj` end-to-end. The refactor in
   step 1 must preserve every observable behavior of this file.
4. Pick the Nebula SHA to pin. The default is
   `4a5ce620574a16644e6fc5b7533137b1ad7d8ed4` — this is the SHA the
   per-package audit ran against (see context file's "Nebula SHA
   audited for this plan" section). Use this unless you have a
   reason to pick a different SHA; deviating requires re-running the
   per-widget audit described in the inline notes added to
   `nebula-sources.edn` in Step 8. This goes into `build.clj` as
   `nebula-sha` (step 3). No sibling Nebula checkout is needed —
   the build vendors sources via
   `clojure -T:build update-vendored-nebula`.
5. Confirm `git` is on `PATH` and network access to
   `github.com/EclipseNebula/nebula.git` works. The
   `update-vendored-nebula` task `git clone`s the upstream repo into
   `target/build-deps/`. (Routine `make jar` builds do NOT need
   network — they use the vendored tree.)
6. Note pre-existing version skew in the repo: `Makefile` declares
   `0.6.0`, `pom.xml` declares `0.5.1`, the recent commit message says
   `Bump version to 0.6.0`. The version bump in Step 11 reconciles
   these. Until then, treat `0.6.0` as "current."

## File-by-file specification

### New: `src/ui/internal/swt_platform.clj`

Pure platform-detection. Extract from `SWT_deps.clj` lines 19–62 and
78–83. NO side effects at load time — no `pomegranate`, no `import`,
no `defonce swt`.

```clojure
(ns ui.internal.swt-platform
  "Pure platform detection + SWT zip extraction. No side effects at load
   time. Shared between runtime (ui.internal.SWT-deps) and build-time
   (ui.build.swt)."
  (:require [clojure.java.io :as io]
            [babashka.fs :as fs])
  (:import [java.io File]))

(def ^:dynamic *platform-zip-prefix* "swt-4.38")

(defn detect-os-arch []
  {:os-code (-> (System/getProperty "os.name") (.substring 0 3) .toLowerCase)
   :arch    (System/getProperty "os.arch")})

;; Move the platform-lib-suffix and platform-zip-suffix maps here as
;; functions taking an os-arch map. Keep the existing mappings exactly:
;;   "lin" {"x86_64" "gtk.linux.x86_64" "amd64" "gtk.linux.x86_64"
;;          "aarch64" "gtk.linux.aarch64" "amd" "gtk.linux.aarch64"}
;;   "mac" {"x86_64" "cocoa.macosx.x86_64" "aarch64" "cocoa.macosx.aarch64"
;;          "arm64" "cocoa.macosx.aarch64"}
;;   "win" {"x86_64" "win32.win32.x86_64" "ia64" "win32.win32.x86_64"
;;          "amd64" "win32.win32.x86_64"}
;; The zip suffix is identical but with hyphens instead of dots.

(defn platform-lib-suffix [os-arch] ...)
(defn platform-zip-suffix [os-arch] ...)

(defn platform-zip-resource-name
  ([] (platform-zip-resource-name (detect-os-arch)))
  ([os-arch] (str *platform-zip-prefix* "-" (platform-zip-suffix os-arch) ".zip")))

(defn extract-swt-zip!
  "Extract the SWT zip resource into `target-dir`. Returns
   {:dir File :jar File :src File}. Idempotent: if the target already
   contains swt.jar, returns the existing extraction without re-unzipping."
  [resource-name ^File target-dir]
  ;; Implementation:
  ;; - .mkdirs target-dir
  ;; - if (File. target-dir "swt.jar") already exists, return the map directly
  ;; - else: (fs/unzip (-> resource-name io/resource io/input-stream) target-dir)
  ;; - return {:dir target-dir :jar (File. target-dir "swt.jar")
  ;;           :src (File. target-dir "src.zip")}
  ...)
```

### Modified: `src/ui/internal/SWT_deps.clj`

Strip the platform-detection code. Delegate to `ui.internal.swt-platform`.
**Preserve `defonce swt`, `defonce swt-libs-loaded?`, `load-swt-libs`,
and the try/catch-import fallback exactly** — runtime semantics must
not change.

```clojure
(ns ui.internal.SWT-deps
  "Dynamically resolve/load SWT subsystem dependencies when this namespace
   is required. Platform detection lives in ui.internal.swt-platform; this
   namespace adds the runtime classpath-injection side effects."
  (:require [ui.internal.swt-platform :as plat]
            [cemerick.pomegranate :as pom]
            [babashka.fs :as fs])
  (:import [java.io File]))

;; The original file referenced `[ui.repositories :refer [*repositories*]]`
;; and `[clojure.repl.deps :refer [add-libs]]` but neither symbol was used
;; in the body. Both are dropped here. If anything outside this namespace
;; still expects them re-exported from SWT-deps, restore the require.

(defn swt-platform []
  (let [tmpdir (File. (str (fs/create-temp-dir)))]
    (.deleteOnExit tmpdir)
    (plat/extract-swt-zip! (plat/platform-zip-resource-name) tmpdir)))

(defonce swt (swt-platform))

(defn load-swt-libs []
  (pom/add-classpath (:jar swt))
  (when (.exists (:src swt))
    (pom/add-classpath (:src swt))))

(defonce
  ^{:doc "Result of loading SWT subsystem dependencies."}
  swt-libs-loaded?
  (try (import '[org.eclipse.swt SWT])
       (catch ClassNotFoundException _e
         (load-swt-libs)
         (import '[org.eclipse.swt SWT]))))

;; Preserve the Chromium and databinding scaffolding (lines 101–127 of
;; the original) unchanged.
```

### New: `src/ui/build/swt.clj`

The public reusable helper. Clients of CDT `:require` this in their
own `build.clj`.

```clojure
(ns ui.build.swt
  "Build-time helpers for compiling Java/SWT sources into a JAR.

   Use from a tools.build `build.clj`. Provides functions to extract the
   CDT-bundled SWT JAR, build a basis with SWT on the classpath, and
   invoke `b/javac` against Java source roots that depend on SWT to
   compile."
  (:require [clojure.tools.build.api :as b]
            [ui.internal.swt-platform :as plat])
  (:import [java.io File]))

(defn extract-bundled-swt
  "Extract the CDT-bundled SWT zip (host platform) into target-dir.
   Returns {:dir File :jar File :src File}."
  ([] (extract-bundled-swt {}))
  ([{:keys [target-dir] :or {target-dir "target/build-deps/swt"}}]
   (let [td (File. target-dir)]
     (.mkdirs td)
     (plat/extract-swt-zip! (plat/platform-zip-resource-name) td))))

(defn swt-extra-deps
  "Returns a `{dep-symbol coord}` map for `(b/create-basis {:extra {:deps ...}})`.
   The coord is :local/root pointing at the extracted swt.jar."
  ([] (swt-extra-deps {}))
  ([opts] {'org.eclipse.swt/swt {:local/root (str (:jar (extract-bundled-swt opts)))}}))

(defn compile-basis
  "Create a tools.build basis with SWT (and any `:extra-deps`) on the
   classpath. Suitable for `b/javac` and `b/compile-clj`. NOT suitable
   for `b/write-pom` (use a separate SWT-free basis for that)."
  [{:keys [project extra-deps] :or {project "deps.edn"}}]
  (b/create-basis
    {:project project
     :extra   {:deps (merge (swt-extra-deps) extra-deps)}}))

(defn javac-with-swt
  "Compile Java sources that depend on SWT.
   Required: :src-dirs, :class-dir.
   Optional: :basis (defaults to compile-basis), :javac-opts
   (defaults to [\"--release\" \"17\"]), :extra-deps."
  [{:keys [src-dirs class-dir basis javac-opts extra-deps]
    :or   {javac-opts ["--release" "17"]}}]
  (let [basis (or basis (compile-basis {:extra-deps extra-deps}))]
    (b/javac {:src-dirs   src-dirs
              :class-dir  class-dir
              :basis      basis
              :javac-opts javac-opts})))
```

### New: `src/ui/nebula.clj`

Load-order safety. Consumers `(:require [ui.nebula])` to guarantee SWT
loads before any `org.eclipse.nebula.*` class is touched.

```clojure
(ns ui.nebula
  "Ensures SWT is loaded before any org.eclipse.nebula.* class is touched.
   Always `(require '[ui.nebula])` in namespaces that import Nebula widgets.

   At load time this namespace also enforces the JDK 17+ floor — Nebula's
   `grid` and `chips` widgets are compiled with `--release 17` and the
   shipped bytecode is class-file major version 61. A pre-17 JVM would
   otherwise produce a cryptic `UnsupportedClassVersionError` deep inside
   widget loading; the fail-fast check turns that into a clear error at
   namespace require time."
  (:require [ui.internal.SWT-deps]))  ;; forces SWT-loading defonces

(defn- jdk-major-version
  "Parses `java.version` (e.g., \"17.0.10\", \"11.0.21\", \"1.8.0_392\")
   into an integer major version. Returns 17 for \"17.x\", 11 for \"11.x\",
   8 for \"1.8.x\", etc."
  []
  (let [v (System/getProperty "java.version")
        [a b] (clojure.string/split v #"\.")
        a' (Integer/parseInt a)]
    (if (= a' 1) (Integer/parseInt b) a')))

(when (< (jdk-major-version) 17)
  (throw (IllegalStateException.
           (str "Clojure Desktop Toolkit's Nebula widgets require JDK 17 "
                "or later (bytecode major version 61). Detected JDK: "
                (System/getProperty "java.version")
                ". Upgrade your JDK before requiring ui.nebula."))))

(defn ensure-loaded! []
  (Class/forName "org.eclipse.nebula.widgets.opal.commons.SWTGraphicUtil")
  :ok)
```

Note: the top-level `when` runs at namespace-require time. The
`jdk-major-version` helper handles both modern (`17.0.10` → 17) and
legacy (`1.8.0_392` → 8) version strings, so the error path is
robust on any JVM that still tries to load the namespace.

### New: `build.clj`

The top-level tools.build script. Replaces `clojure -X:jar` (depstar).

```clojure
(ns build
  (:require [clojure.tools.build.api :as b]
            [ui.build.swt :as swt-build]
            [clojure.edn :as edn]
            [babashka.fs :as fs]))

(def lib 'io.github.coconutpalm/clojure-desktop-toolkit)
(def class-dir "target/classes")
(def java-staging "target/build-deps/java-src")
(def vendor-dir "vendor/nebula")
(defn jar-file [v] (format "target/clojure-desktop-toolkit-%s.jar" v))

;; Both bases are delays so callers pay the cost once per build invocation.
;; Renamed from `compile-basis` to `*compile-basis*` to avoid shadowing
;; `swt-build/compile-basis` (a function) — bare `compile-basis` in this
;; namespace must unambiguously deref the local delay.
(def publish-basis  (delay (b/create-basis {:project "deps.edn"})))
(def *compile-basis* (delay (swt-build/compile-basis {:project "deps.edn"})))

;; Pinned upstream version of Eclipse Nebula. Bumping this constant +
;; running `clojure -T:build update-vendored-nebula` + committing the
;; result is the full version-bump workflow. The initial SHA matches
;; what the per-package audit ran against — see the context file's
;; "Nebula SHA audited for this plan" section.
(def nebula-sha "4a5ce620574a16644e6fc5b7533137b1ad7d8ed4")
(def nebula-repo "https://github.com/EclipseNebula/nebula.git")

(defn- stage-nebula-sources
  "Copy only included Nebula packages into target/build-deps/java-src/.
   Files under any :exclude-pkgs path are skipped; specific files listed
   in :exclude-files are also skipped. The staged tree is what
   b/javac compiles — so any source file that would import JFace,
   core.runtime, or any other non-SWT class never enters javac's view.

   Reads sources from the vendored Nebula tree at vendor-dir/<src>, NOT
   from a sibling Nebula checkout."
  [manifest]
  (fs/delete-tree java-staging)
  (fs/create-dirs java-staging)
  (doseq [{:keys [src exclude-pkgs exclude-files]} (vals manifest)]
    (let [src-dir (str vendor-dir "/" src)
          pkg-ignores  (mapv #(re-pattern (str ".*/" % "/.*")) exclude-pkgs)
          file-ignores (mapv #(re-pattern (str ".*/" % "$")) exclude-files)
          ignores      (vec (concat pkg-ignores file-ignores))]
      (b/copy-dir {:src-dirs   [src-dir]
                   :target-dir java-staging
                   :ignores    ignores
                   :include    "**/*.java"}))))

(defn clean [_] (b/delete {:path "target"}))

(defn compile-java
  "Stage Nebula sources (per nebula-sources.edn) and compile them.
   Cleans `class-dir` and `java-staging` first so stale .class files
   from previous, broader manifests don't sneak into the JAR."
  [_]
  (b/delete {:path class-dir})
  (b/delete {:path java-staging})
  (let [manifest (edn/read-string (slurp "nebula-sources.edn"))]
    (stage-nebula-sources manifest)
    (swt-build/javac-with-swt
      {:basis     @*compile-basis*
       :src-dirs  [java-staging]
       :class-dir class-dir})))

(defn jar [{:keys [version]}]
  (clean nil)
  (compile-java nil)
  (b/copy-dir {:src-dirs ["src" "resources"] :target-dir class-dir})
  (b/write-pom {:class-dir class-dir
                :lib       lib
                :version   version
                :basis     @publish-basis      ;; SWT-free
                :src-pom   "pom.xml"})
  (b/jar {:class-dir class-dir :jar-file (jar-file version)}))

(defn install [{:keys [version] :as opts}]
  (jar opts)
  (b/install {:basis @publish-basis :lib lib :version version
              :class-dir class-dir :jar-file (jar-file version)}))

(defn- run!
  "Wrap b/process so a non-zero exit aborts the build with a clear
   message. b/process returns {:exit n ...}; without this, a failed
   `git checkout` would be silently swallowed and we'd vendor whatever
   HEAD the clone landed on."
  [args]
  (let [{:keys [exit]} (b/process {:command-args args})]
    (when-not (zero? exit)
      (throw (ex-info (str "Command failed (exit " exit "): "
                           (clojure.string/join " " args))
                      {:args args :exit exit})))))

(defn update-vendored-nebula
  "Bump the vendored Nebula sources to the SHA in `nebula-sha`. Clones
   EclipseNebula/nebula at the pinned SHA, filters to just the bundle
   src/ trees enumerated in nebula-sources.edn, and overwrites
   `vendor/nebula/`. Also vendors LICENSE and writes vendor/nebula/VERSION.

   Requires `git` on PATH and network access to nebula-repo. Run this
   manually when bumping versions. Commit the resulting tree. This task
   is NOT part of `jar` — routine builds use the existing vendored
   sources.

   Cannot be invoked until `nebula-sources.edn` exists (it's slurped to
   know which bundles to vendor)."
  [_]
  (let [tmpdir   "target/build-deps/nebula-fresh"
        manifest (edn/read-string (slurp "nebula-sources.edn"))]
    (b/delete {:path tmpdir})
    (run! ["git" "clone" nebula-repo tmpdir])
    (run! ["git" "-C" tmpdir "checkout" nebula-sha])
    (b/delete {:path vendor-dir})
    (fs/create-dirs vendor-dir)
    (doseq [{:keys [src]} (vals manifest)]
      (let [from (str tmpdir "/" src)
            to   (str vendor-dir "/" src)]
        (fs/create-dirs (fs/parent to))
        (b/copy-dir {:src-dirs [from] :target-dir to})))
    (b/copy-file {:src    (str tmpdir "/LICENSE")
                  :target (str vendor-dir "/LICENSE")})
    (spit (str vendor-dir "/VERSION")
          (str "Eclipse Nebula vendored at SHA " nebula-sha "\n"
               "Upstream: " nebula-repo "\n"))
    (b/delete {:path tmpdir})
    (println "Vendored Nebula at SHA" nebula-sha
             "to" vendor-dir
             "— review and commit.")))

(defn deploy [opts]
  ;; Adapt the existing deploy.sh flow to invoke b/install or a Clojars
  ;; pusher (slipset/deps-deploy is still usable). Confirm with the user
  ;; before touching deploy.sh — that's their release pipeline.
  ...)
```

Each bundle's `src/` tree is mirrored into `vendor/nebula/` in full —
excluded packages/files are filtered at staging time, not at vendor
time. This lets contributors `grep` the actual sources we ship,
including the excluded subpackages, to understand what's deliberately
omitted and why.

**Open detail to verify during step 4**: `b/copy-dir`'s `:ignores`
AND `:include` matching rules. tools.build 0.10.x documents `:ignores`
as a coll of regexes and `:include` as a glob string, but the match
targets (full path? relative path? filename only? glob semantics?)
should be confirmed against the actual staged Nebula source paths. If
either doesn't behave as expected, replace `stage-nebula-sources` with
a manual `fs/walk-file-tree` filter — the function is small enough to
hand-roll without ceremony.

### New: `nebula-sources.edn`

All 57 widgets enumerated. Paths relative to `vendor/nebula/`. `:tier`
is informational only — the build does not branch on it. `:exclude-pkgs`
and `:exclude-files` are the only fields that affect build behavior.

**Audit instruction for the implementing Claude:** Before running
`make jar`, walk each entry. For each widget:

1. Confirm `vendor/nebula/<:src>` exists (after running
   `clojure -T:build update-vendored-nebula` if the vendor tree isn't
   yet populated).
2. Run `grep -rhE '^import org\.eclipse\.' vendor/nebula/<:src> | sort -u`
   and confirm the non-SWT imports are limited to:
   - For **Tier 1**: zero non-SWT imports.
   - For **Tier 2**: only `org.eclipse.nebula.widgets.opal.commons.*`.
   - For **Tier 3 (package exclusion)**: imports outside SWT/opal.commons
     exist only inside the listed `:exclude-pkgs` directories.
   - For **Tier 3 (file exclusion)**: imports outside SWT/opal.commons
     exist only inside the listed `:exclude-files`.
3. If any widget fails the check (Nebula upstream renamed a package,
   moved an adapter file, added a transitive Eclipse import, etc.),
   **remove it from the manifest with a code comment explaining why**
   rather than silently expanding `:exclude-pkgs`/`:exclude-files`.
   Surface the discrepancy to the user.

```clojure
{;; Foundation (required by all Tier 2 widgets)
 :opal.commons        {:src "widgets/opal/commons/org.eclipse.nebula.widgets.opal.commons/src"
                       :tier 2}

 ;; Tier 1 — pure SWT, no other Nebula deps
 :bidilayout          {:src "widgets/bidilayout/org.eclipse.nebula.widgets.bidilayout/src" :tier 1}
 :calendarcombo       {:src "widgets/calendarcombo/org.eclipse.nebula.widgets.calendarcombo/src" :tier 1}
 :cdatetime           {:src "widgets/cdatetime/org.eclipse.nebula.widgets.cdatetime/src" :tier 1}
 :collapsiblebuttons  {:src "widgets/collapsiblebuttons/org.eclipse.nebula.widgets.collapsiblebuttons/src" :tier 1}
 :commons             {:src "widgets/commons/org.eclipse.nebula.widgets.commons/src" :tier 1}
 :ctree               {:src "widgets/ctree/org.eclipse.nebula.widgets.ctree/src" :tier 1}
 :floatingtext        {:src "widgets/floatingtext/org.eclipse.nebula.widgets.floatingtext/src" :tier 1}
 :fontawesome         {:src "widgets/fontawesome/org.eclipse.nebula.widgets.fontawesome/src" :tier 1}
 :ganttchart          {:src "widgets/ganttchart/org.eclipse.nebula.widgets.ganttchart/src" :tier 1}
 :nebulatoolbar       {:src "widgets/nebulatoolbar/org.eclipse.nebula.widgets.nebulatoolbar/src" :tier 1}
 :oscilloscope        {:src "widgets/oscilloscope/org.eclipse.nebula.widgets.oscilloscope/src" :tier 1}
 :pgroup              {:src "widgets/pgroup/org.eclipse.nebula.widgets.pgroup/src" :tier 1}
 :progresscircle      {:src "widgets/progresscircle/org.eclipse.nebula.widgets.progresscircle/src" :tier 1}
 :splitbutton         {:src "widgets/splitbutton/org.eclipse.nebula.widgets.splitbutton/src" :tier 1}
 :tiles               {:src "widgets/tiles/org.eclipse.nebula.widgets.tiles/src" :tier 1}

 ;; Tier 2 — pure SWT + opal.commons
 :badgedlabel         {:src "widgets/opal/badgedlabel/org.eclipse.nebula.widgets.opal.badgedlabel/src" :tier 2}
 :breadcrumb          {:src "widgets/opal/breadcrumb/org.eclipse.nebula.widgets.opal.breadcrumb/src" :tier 2}
 :calculator          {:src "widgets/opal/calculator/org.eclipse.nebula.widgets.opal.calculator/src" :tier 2}
 :carousel            {:src "widgets/opal/carousel/org.eclipse.nebula.widgets.opal.carousel/src" :tier 2}
 :checkboxgroup       {:src "widgets/opal/checkboxgroup/org.eclipse.nebula.widgets.opal.checkboxgroup/src" :tier 2}
 :chips               {:src "widgets/opal/chips/org.eclipse.nebula.widgets.opal.chips/src" :tier 2}
 :columnbrowser       {:src "widgets/opal/columnbrowser/org.eclipse.nebula.widgets.opal.columnbrowser/src" :tier 2}
 :dialog              {:src "widgets/opal/dialog/org.eclipse.nebula.widgets.opal.dialog/src" :tier 2}
 :duallist            {:src "widgets/opal/duallist/org.eclipse.nebula.widgets.opal.duallist/src" :tier 2}
 :header              {:src "widgets/opal/header/org.eclipse.nebula.widgets.opal.header/src" :tier 2}
 :heapmanager         {:src "widgets/opal/heapmanager/org.eclipse.nebula.widgets.opal.heapmanager/src" :tier 2}
 :horizontalspinner   {:src "widgets/opal/horizontalspinner/org.eclipse.nebula.widgets.opal.horizontalspinner/src" :tier 2}
 :launcher            {:src "widgets/opal/launcher/org.eclipse.nebula.widgets.opal.launcher/src" :tier 2}
 :led                 {:src "widgets/opal/led/org.eclipse.nebula.widgets.opal.led/src" :tier 2}
 :logindialog         {:src "widgets/opal/logindialog/org.eclipse.nebula.widgets.opal.logindialog/src" :tier 2}
 :multichoice         {:src "widgets/opal/multichoice/org.eclipse.nebula.widgets.opal.multichoice/src" :tier 2}
 :nebulaslider        {:src "widgets/opal/nebulaslider/org.eclipse.nebula.widgets.opal.nebulaslider/src" :tier 2}
 :notifier            {:src "widgets/opal/notifier/org.eclipse.nebula.widgets.opal.notifier/src" :tier 2}
 :panels              {:src "widgets/opal/panels/org.eclipse.nebula.widgets.opal.panels/src" :tier 2}
 :passwordrevealer    {:src "widgets/opal/passwordrevealer/org.eclipse.nebula.widgets.opal.passwordrevealer/src" :tier 2}
 :preferencewindow    {:src "widgets/opal/preferencewindow/org.eclipse.nebula.widgets.opal.preferencewindow/src" :tier 2}
 :promptsupport       {:src "widgets/opal/promptsupport/org.eclipse.nebula.widgets.opal.promptsupport/src" :tier 2}
 :propertytable       {:src "widgets/opal/propertytable/org.eclipse.nebula.widgets.opal.propertytable/src" :tier 2}
 :rangeslider         {:src "widgets/opal/rangeslider/org.eclipse.nebula.widgets.opal.rangeslider/src" :tier 2}
 :roundedcheckbox     {:src "widgets/opal/roundedcheckbox/org.eclipse.nebula.widgets.opal.roundedcheckbox/src" :tier 2}
 :roundedswitch       {:src "widgets/opal/roundedswitch/org.eclipse.nebula.widgets.opal.roundedswitch/src" :tier 2}
 :starrating          {:src "widgets/opal/starrating/org.eclipse.nebula.widgets.opal.starrating/src" :tier 2}
 :stepbar             {:src "widgets/opal/stepbar/org.eclipse.nebula.widgets.opal.stepbar/src" :tier 2}
 :switchbutton        {:src "widgets/opal/switchbutton/org.eclipse.nebula.widgets.opal.switchbutton/src" :tier 2}
 :textassist          {:src "widgets/opal/textassist/org.eclipse.nebula.widgets.opal.textassist/src" :tier 2}
 :tipoftheday         {:src "widgets/opal/tipoftheday/org.eclipse.nebula.widgets.opal.tipoftheday/src" :tier 2}
 :titledseparator     {:src "widgets/opal/titledseparator/org.eclipse.nebula.widgets.opal.titledseparator/src" :tier 2}

 ;; Tier 3a — pure SWT after excluding optional JFace adapter subpackage(s)
 :pshelf              {:src "widgets/pshelf/org.eclipse.nebula.widgets.pshelf/src"
                       :tier 3
                       :exclude-pkgs ["org/eclipse/nebula/jface/pshelfviewer"]}
 :compositetable      {:src "widgets/compositetable/org.eclipse.nebula.widgets.compositetable/src"
                       :tier 3
                       :exclude-pkgs ["org/eclipse/nebula/widgets/compositetable/viewers"]}
 :ctreecombo          {:src "widgets/ctreecombo/org.eclipse.nebula.widgets.ctreecombo/src"
                       :tier 3
                       :exclude-pkgs ["org/eclipse/nebula/widgets/ctreecombo/viewer"]}
 :gallery             {:src "widgets/gallery/org.eclipse.nebula.widgets.gallery/src"
                       :tier 3
                       :exclude-pkgs ["org/eclipse/nebula/jface/galleryviewer"]}
 :geomap              {:src "widgets/geomap/org.eclipse.nebula.widgets.geomap/src"
                       :tier 3
                       :exclude-pkgs ["org/eclipse/nebula/widgets/geomap/jface"]}
 ;; grid: the regex for the first :exclude-pkgs entry already matches
 ;; everything under gridviewer/, including the internal/ subdirectory,
 ;; so a second entry is unnecessary. Keep one path.
 :grid                {:src "widgets/grid/org.eclipse.nebula.widgets.grid/src"
                       :tier 3
                       :exclude-pkgs ["org/eclipse/nebula/jface/gridviewer"]}
 :radiogroup          {:src "widgets/radiogroup/org.eclipse.nebula.widgets.radiogroup/src"
                       :tier 3
                       :exclude-pkgs ["org/eclipse/nebula/jface/viewer/radiogroup"]}
 :tablecombo          {:src "widgets/tablecombo/org.eclipse.nebula.widgets.tablecombo/src"
                       :tier 3
                       :exclude-pkgs ["org/eclipse/nebula/jface/tablecomboviewer"
                                      "org/eclipse/nebula/jface/checktablecomboviewer"]}

 ;; Tier 3b — pure SWT after excluding specific JFace helper files
 :datechooser         {:src "widgets/datechooser/org.eclipse.nebula.widgets.datechooser/src"
                       :tier 3
                       :exclude-files ["org/eclipse/nebula/widgets/datechooser/DateChooserComboCellEditor.java"
                                       "org/eclipse/nebula/widgets/datechooser/DateChooserComboObservableValue.java"
                                       "org/eclipse/nebula/widgets/datechooser/DateChooserObservableValue.java"]}
 :formattedtext       {:src "widgets/formattedtext/org.eclipse.nebula.widgets.formattedtext/src"
                       :tier 3
                       :exclude-files ["org/eclipse/nebula/widgets/formattedtext/FormattedTextCellEditor.java"
                                       "org/eclipse/nebula/widgets/formattedtext/FormattedTextObservableValue.java"]}}
```

Tier 4 widgets (NOT in v1, candidates for v2 via P2 fetcher):
pagination, picture, richtext, roundedtoolbar, segmentedbar, timeline,
treemapper, xviewer. **picture** is borderline (one JFace file is
cross-referenced from `AbstractPictureControl`); verify during step 8
audit and promote to Tier 3 if salvageable.

### Modified: `deps.edn`

Add `:build` alias. Remove `:jar` and `:deploy` (depstar). Other
aliases unchanged.

```clojure
{:paths ["src" "resources"]

 :mvn/repos {"clojure-desktop-toolkit"
             {:url "https://coconutpalm.github.io/clojure-desktop-toolkit/maven"}}

 :deps {org.clojure/clojure {:mvn/version "1.12.0"}
        io.github.coconutpalm/righttypes {:mvn/version "0.7.3"}
        babashka/fs {:mvn/version "0.5.30"}
        org.reflections/reflections {:mvn/version "0.10.2"}
        clj-commons/pomegranate {:mvn/version "1.2.25"}
        com.hyperfiddle/rcf {:mvn/version "20220926-202227"}}

 :aliases {:dev {:paths ["src" "resources" "dev" "examples"]}

           :mcp {:extra-deps {org.hugoduncan/mcp-clj
                              {:git/url   "https://github.com/hugoduncan/mcp-clj"
                               :git/sha   "08ec63baeed813bc09673e61c20df8884961c11a"
                               :deps/root "projects/server"}}
                 :main-opts ["-m" "mcp-clj.stdio-server.main"]}

           :build {:extra-paths ["src" "resources"]
                   :extra-deps  {io.github.clojure/tools.build {:mvn/version "0.10.9"}}
                   :ns-default  build}}}
```

`:extra-paths ["src" "resources"]` is **essential** — the build needs
`ui.build.swt` and `ui.internal.swt-platform` on its classpath, plus
`resources/` so the bundled SWT ZIPs are reachable via `io/resource`.

### Modified: `Makefile`

```makefile
# Release Checklist:
#
# Update version number here AND in pom.xml (<version> and <scm><tag>)
# `make`
# Push changes to Github
# Create version tag on Github

ALL: jar deploy

# Use the version chosen in Step 11. Placeholder shown — replace with
# the actual version string (e.g., "0.7.0" or "1.0.0") before running.
jar:
	clojure -T:build jar :version '"<X.Y.Z>"'

compile-java:
	clojure -T:build compile-java

update-vendored-nebula:
	clojure -T:build update-vendored-nebula

deploy:
	./deploy.sh

clean:
	clojure -T:build clean
```

The `update-vendored-nebula` target is intentionally NOT in `ALL`. It
runs manually when bumping Nebula versions; the contributor edits
`nebula-sha` in `build.clj`, runs `make update-vendored-nebula`,
reviews the diff under `vendor/nebula/`, and commits.

Note: keep `./deploy.sh` invocation — that's the user's existing
release pipeline. Confirm with user whether `deploy.sh` needs updating
to point at the new JAR path (`target/clojure-desktop-toolkit-0.6.0.jar`
vs. the old `clojure-desktop-toolkit.jar`).

### New: `vendor/nebula/`

Filtered copy of Eclipse Nebula sources for the 57 bundles in v1.
Mirrors upstream layout exactly: e.g.
`vendor/nebula/widgets/pshelf/org.eclipse.nebula.widgets.pshelf/src/...`.
Populated by `clojure -T:build update-vendored-nebula`. The
bundle src/ trees are mirrored in full — excluded packages/files are
filtered at staging time so contributors can see what's omitted.

Also under `vendor/nebula/`:
- `LICENSE` — upstream EPL-2.0 text, copied from the Nebula repo.
- `VERSION` — two lines: pinned SHA and upstream repo URL. Generated
  by the `update-vendored-nebula` task.

Commit this tree to the CDT repo. Total size ~5–10 MB of source files.

### New: `NOTICE.md`

EPL-2.0 attribution. Lists bundled third-party code: SWT (Eclipse
Platform), Nebula widgets (Eclipse Nebula project). Reference the
Nebula commit hash from `vendor/nebula/VERSION` — single source of
truth for "which upstream version is currently vendored." Update
NOTICE.md whenever bumping `nebula-sha`.

### New: `examples/java-widget/` (the reusability demonstration)

A tiny example project showing a client compiling their own Java/SWT
widget using `ui.build.swt`. Minimal contents:

- `deps.edn` — declares CDT as a `:local/root` dep pointing at the
  parent CDT checkout (`{:local/root "../.."}`). Using `:local/root`
  instead of a versioned Maven coord avoids re-pinning every CDT
  release and lets the example track CDT-in-development without an
  intermediate `b/install` step. The example also adds a `:build`
  alias mirroring CDT's.
- `build.clj` — uses `swt-build/javac-with-swt` to compile
  `java-src/`.
- `java-src/com/example/HelloLabel.java` — a trivial
  `class HelloLabel extends org.eclipse.swt.widgets.Composite`.
- `src/example/main.clj` — `(:require [ui.nebula])` to bootstrap SWT,
  then uses `HelloLabel`.
- `README.md` — 1-paragraph explanation of what the example
  demonstrates, including a note that a published-version variant
  (using `:mvn/version` instead of `:local/root`) is the form
  downstream users would write.

## Implementation sequence

Execute in order. Each step has a verification gate.

### Step 1. Extract `ui.internal.swt-platform`

Create `src/ui/internal/swt_platform.clj` per the spec above. Refactor
`SWT_deps.clj` to delegate. **Do NOT change observable behavior.**

Verify in REPL:
```clojure
(require 'ui.SWT :reload-all)
(import '[org.eclipse.swt SWT])
(SWT/getVersion)   ;; should return an integer like 4956
```

Confirm `@ui.internal.SWT-deps/swt` returns a map with `:jar` pointing
at an extracted swt.jar.

### Step 2. Add `ui.build.swt`

Create `src/ui/build/swt.clj` per the spec. Test in isolation in a
REPL (use a temporary `:build` alias to load tools.build):

```clojure
(require '[ui.build.swt :as sb])
(def basis (sb/compile-basis {:project "deps.edn"}))
(map str (:classpath-roots basis))
;; Should include a path ending in /swt.jar from target/build-deps/swt/
```

### Step 3. Write minimal `build.clj` and switch the build

Implement `build.clj` with `clean`, `jar` (Nebula-free for now — skip
the `compile-java` call inside `jar` temporarily), and the
`update-vendored-nebula` task. Pin `nebula-sha` to the audited SHA
(per pre-flight check 4). Add the `:build` alias to `deps.edn`. Remove
`:jar` and `:deploy` aliases. Update `Makefile`.

Do NOT invoke `update-vendored-nebula` yet — it slurps
`nebula-sources.edn` which doesn't exist until Step 3a.

Run `make jar`. Confirm it produces a JAR functionally equivalent to
today's depstar output. Run verification steps 3 (no SWT classes in
JAR), 5 (pom is SWT-free), 6 (consumer dep tree is SWT-free). **If
anything regressed, fix before continuing.**

### Step 3a. Vendor a seed manifest

Create `nebula-sources.edn` with just three widgets that exercise all
exclusion patterns: `opal.commons` (no exclusions), `pshelf`
(`:exclude-pkgs`), and `datechooser` (`:exclude-files`).

Run `clojure -T:build update-vendored-nebula`. Verify:
- `vendor/nebula/widgets/opal/commons/.../src/` exists.
- `vendor/nebula/widgets/pshelf/.../src/` exists. **The
  `org/eclipse/nebula/jface/pshelfviewer/` subdirectory is also
  present** — vendoring mirrors upstream; filtering happens at staging.
- `vendor/nebula/widgets/datechooser/.../src/` exists, including the
  three files we plan to exclude at staging time.
- `vendor/nebula/VERSION` contains the SHA.
- `vendor/nebula/LICENSE` is the upstream EPL-2.0 text.

Commit the seed vendor tree.

### Step 4. Implement `stage-nebula-sources` against the seed manifest

Add the `stage-nebula-sources` function to `build.clj`. With the
three-widget seed manifest from step 3a, run staging only (not full
build):
```
clojure -T:build compile-java   # invokes stage-nebula-sources internally
```

Confirm via verification step 3a (from context doc) — staging
filtered out everything we expected:
```
find target/build-deps/java-src -path "*org/eclipse/nebula/jface/pshelfviewer*"
find target/build-deps/java-src -name "DateChooser*CellEditor.java" -o \
                                -name "DateChooser*ObservableValue.java"
```
Expected: all empty. If anything matches, the `:ignores` regex didn't
fire — **this is a P0 bug**. Empirically determine the correct regex
form for the pinned tools.build version. If `b/copy-dir :ignores`
is fundamentally unsuitable for either pattern, replace with
`fs/walk-file-tree`.

### Step 5. Wire `compile-java` into `jar`, validate small manifest

With the 3-widget seed manifest, run full `make jar`. Run verification
steps 3, 3a, 3b. Especially **3b** (no JFace class references in any
compiled CDT class) — this is the critical gate proving source staging
works as designed for both `:exclude-pkgs` AND `:exclude-files`.

### Step 6. Add `ui.nebula` and smoke-test load-order

Create `src/ui/nebula.clj`. Run verification step 8 (load-order
failure path) to confirm the contract.

### Step 7. Smoke-test in REPL with screenshot

Verification step 7: build the JAR, run a REPL with the JAR on
classpath, instantiate PShelf and an opal widget (e.g. LED), use the
SWT-UI-RULES.md screenshot workflow to capture the window. Confirm
widgets render correctly.

### Step 8. Expand manifest to all 57 widgets

Add the remaining 54 entries to `nebula-sources.edn`. Re-run
`clojure -T:build update-vendored-nebula` to bring the full vendor
tree in (the seed step 3a vendored only 3 bundles; this step expands
to all 57). Commit the expanded vendor tree.

**Run the audit instruction** (the inline notes in
`nebula-sources.edn`): for each entry, verify the `:src` path exists
under `vendor/nebula/` and the imports match the declared tier.
Specifically:
- For Tier 3a entries, confirm the `:exclude-pkgs` paths actually
  exist under `vendor/nebula/<:src>/`.
- For Tier 3b entries, confirm the `:exclude-files` paths actually
  exist under `vendor/nebula/<:src>/`.
- If `AbstractPictureControl.java` (in widgets/picture/) turns out
  to use the forms subpackage only optionally, consider promoting
  picture from Tier 4 to Tier 3b with appropriate `:exclude-files`.

Surface any discrepancies to the user before silently modifying the
manifest.

Run full `make jar` again. Re-verify steps 3, 3a, 3b. Smoke-test a
representative sample: one Tier 1, one Tier 2, one Tier 3a (e.g.
`grid` or `pshelf`), one Tier 3b (e.g. `datechooser`).

### Step 9. Cross-platform spot check

Build on the host platform. Copy the JAR to a different-platform
machine (or Docker container with the appropriate SWT runtime
support). Run the smoke test there. Confirms bytecode portability.

If a different-platform machine isn't accessible: at minimum, inspect
the bytecode `.class` files in the JAR with `javap -v` and confirm
they reference SWT classes by name (not by file path), and that the
bytecode major version matches `--release 17` (major 61).

### Step 10. Add `examples/java-widget/` demonstration

Implement the example project per the spec. Run `clojure -T:build
jar` inside `examples/java-widget/` (after installing the new CDT
JAR locally via `clojure -T:build install`). Confirm the example
JAR contains the compiled Java class. Verification step 10.

### Step 11. Documentation

This release is the **first user-visible breaking change in CDT's
history** — the JDK floor moves from 11 to 17. Signal it loudly and in
the project's established convention.

#### Version bump

Pick the new version BEFORE editing docs (the version string appears in
multiple places). Options:

- **0.7.0** — conservative pre-1.0 minor bump. Signals "new
  capability + breaking environment change" while keeping the 0.x
  status quo. Recommend this unless the user wants to graduate.
- **1.0.0** — graduation to stable. Justified by the JDK floor
  bump being a deliberate breaking change AND by the major new
  capability (Nebula widgets + reusable Java compilation helper).

Confirm the choice with the user before editing version strings.
Bumps land in:

- `Makefile` (the `:version` arg to `clojure -T:build jar`, replacing
  the `<X.Y.Z>` placeholder).
- `pom.xml` **both** `<version>` and `<scm><tag>` elements (currently
  `0.5.1` and `v0.5.1` respectively — pre-existing skew from
  `Makefile` 0.6.0, see pre-flight check 6).
- The `:version` field in any generated artifacts.
- The new "New and Noteworthy" doc filename.
- The README's "New and Noteworthy" bullet link target.

There is no automation to keep `Makefile` and `pom.xml` versions in
sync. Both must be edited by hand. Consider adding a `make
check-versions` target in a follow-up to lint for drift, but that's
out of scope for this plan.

#### New and Noteworthy page (follows existing project convention)

Create `docs/new-and-noteworthy/version-<X.Y.Z>.md` matching the
existing pattern (see `docs/new-and-noteworthy/version-0.4.4.md`).
Required contents:

1. **Breaking changes** section at the top — **JDK 17+ now required**.
   Briefly explain why (Nebula `grid`/`chips` widgets demand
   `JavaSE-17`), what consumers need to do (upgrade JDK; no Clojure
   code changes), and what error they'll see if they don't (a clear
   `UnsupportedClassVersionError` at first import).
2. **New features** section — 57 Eclipse Nebula widgets bundled in the
   CDT JAR (briefly enumerate by category: opal family, charting,
   navigation, etc.); new `ui.build.swt` helper for compiling
   custom Java/SWT widgets in client projects; the `vendor/nebula/`
   tree pinned at a specific SHA.
3. **How to use Nebula widgets** — show the `(:require [ui.nebula])`
   contract with a minimal example (one `org.eclipse.nebula.*` widget
   in a `shell`).
4. **Vendored Nebula version** — note the pinned SHA and the
   `update-vendored-nebula` workflow.
5. **CDT does not require Equinox** — preserve this contract as a
   prominent callout; it's now more relevant than ever since we're
   bundling Nebula widgets that some assume need Equinox.

#### README.md updates

- **Top of README**: update the "New and Noteworthy" bullet to point
  at the new `docs/new-and-noteworthy/version-<X.Y.Z>.md` (currently
  it points at 0.4.4 — replace, don't append).
- **JDK 17+ required** note prominent near the top, with a brief
  explanation. This is the breaking change banner — readers must see
  it before they `clojure -X:deps prep` or pull the new version.
- **Using Nebula widgets** section listing the 57 bundled widgets
  (consider grouping by tier or category for readability), with a
  minimal code example using PShelf or LED.
- **Compiling your own Java/SWT widgets** section pointing at
  `ui.build.swt`, with a minimal client `build.clj` snippet.
- **Vendored Nebula version** subsection noting
  `vendor/nebula/VERSION` and the bump workflow.
- **CDT does not require Equinox** callout — same content as in the
  New and Noteworthy page, but persists in the README for ongoing
  discoverability.

#### CLAUDE.md updates

Add a "Building" section explaining the `:build` alias, the
`update-vendored-nebula` task, the manifest schema
(`:exclude-pkgs`/`:exclude-files`), and the JDK 17 floor. Add the
`(:require [ui.nebula])` contract for namespaces importing Nebula
widgets. Document that `vendor/nebula/` is a filtered upstream
mirror — contributors should NOT edit those files by hand (use
`update-vendored-nebula` instead).

Add a **"Dev REPL workflow for Nebula widgets"** subsection. Nebula
classes only land in `target/classes` after `make compile-java` (or
`make jar`). The `:dev` alias does NOT auto-compile Java sources, so
to use Nebula at the REPL you must either:

1. Run `make compile-java` once, then start the REPL with
   `clojure -M:dev -Sdeps '{:paths ["target/classes"]}'` (extends
   classpath without re-running tools.build), OR
2. Install the JAR locally via `clojure -T:build install` and depend
   on it from a separate scratch project.

Option 1 is the recommended workflow for active CDT development —
edit `.java` sources, re-run `make compile-java`, restart REPL.
Option 2 better mirrors what downstream consumers will do.

#### NOTICE.md

EPL-2.0 attribution as described above. Reference the pinned SHA
from `vendor/nebula/VERSION`.

### Step 12. Release

Version is already chosen and propagated through code/docs in Step 11.
At this point:

1. Confirm `deploy.sh` works against the new JAR file name
   (`clojure-desktop-toolkit-<X.Y.Z>.jar` rather than the old
   `clojure-desktop-toolkit.jar`). If it doesn't, surface to user
   before editing the release script.
2. Smoke-test the new JAR locally one more time with the full
   verification suite.
3. Tag `v<X.Y.Z>` and push (tag + commits).
4. `make deploy` to push to Clojars.
5. **Verify the README's "New and Noteworthy" link resolves** on
   GitHub (point at `docs/new-and-noteworthy/version-<X.Y.Z>.md`) —
   broken links here are the most likely user-visible regression
   from this release.

## Verification (full)

Ten steps, run in order during the implementation sequence. Steps
marked **(gate)** must pass before merging — they are the contract
this v1 plan promises to honor. Other steps are confidence checks.

### Step 1. JAR is produced

```
ls -lh target/clojure-desktop-toolkit-<X.Y.Z>.jar
```

Expected: file exists. Size is at least a few MB once Nebula classes
land (the six SWT zips ride along as resources and dominate size).

### Step 2. Published pom has correct metadata

```
unzip -p target/clojure-desktop-toolkit-<X.Y.Z>.jar \
  META-INF/maven/io.github.coconutpalm/clojure-desktop-toolkit/pom.xml \
  | grep -E "<(groupId|artifactId|version)>" | head -6
```

Expected: top-level `<groupId>io.github.coconutpalm</groupId>`,
`<artifactId>clojure-desktop-toolkit</artifactId>`, and `<version>`
matching the build argument.

### Step 3. JAR does not bundle loose SWT classes

```
jar tf target/clojure-desktop-toolkit-<X.Y.Z>.jar \
  | grep -E '^org/eclipse/swt/' | wc -l
```

Expected: `0`. The SWT zips are resources; no `.class` from SWT
should be loose in the JAR. Without this guarantee a consumer might
get classpath collisions between the bundled SWT and their host
platform's SWT JAR.

### Step 3a. Source staging excluded everything we asked it to **(gate)**

After `clojure -T:build compile-java` runs `stage-nebula-sources`:

```
find target/build-deps/java-src -path "*/org/eclipse/nebula/jface/*"
find target/build-deps/java-src -path "*/viewer/*"
find target/build-deps/java-src -path "*/viewers/*"
find target/build-deps/java-src \
   -name "DateChooser*CellEditor.java" -o \
   -name "DateChooser*ObservableValue.java" -o \
   -name "FormattedTextCellEditor.java" -o \
   -name "FormattedTextObservableValue.java"
```

Expected: all four commands print nothing. If any matches, the
`:ignores` regex (or `:include` glob) didn't fire for that exclusion.
**P0 — fix before continuing**. Empirically determine the correct
regex/glob form for the pinned tools.build version. If
`b/copy-dir :ignores`/`:include` is fundamentally unsuitable, replace
`stage-nebula-sources` with `fs/walk-file-tree`.

### Step 3b. No JFace / core.runtime references in compiled classes **(gate)**

```
for f in $(find target/classes -name "*.class"); do
  javap -c -p "$f" 2>/dev/null
done | grep -E 'org/eclipse/(jface|core/runtime)' | wc -l
```

Expected: `0`. Any non-zero count means source staging let through
a file with disallowed imports. **Stop and investigate before adding
more widgets.** The critical correctness invariant of v1 is that the
shipped JAR contains zero non-SWT Eclipse class references.

### Step 4. Bytecode targets JDK 17

```
javap -v target/classes/org/eclipse/nebula/widgets/opal/commons/SWTGraphicUtil.class \
  | grep "major version"
```

Expected: `major version: 61` (JDK 17 class-file format). A different
number means `--release 17` wasn't applied, or javac picked the wrong
target.

### Step 5. Published pom is SWT-free **(gate)**

```
unzip -p target/clojure-desktop-toolkit-<X.Y.Z>.jar \
  META-INF/maven/io.github.coconutpalm/clojure-desktop-toolkit/pom.xml \
  | grep -A1 "org.eclipse.swt"
```

Expected: no output. SWT must not appear as a dependency in the
published pom. If it does, the publish-basis leaked SWT into
`b/write-pom` — verify `publish-basis` is a plain
`b/create-basis {:project "deps.edn"}` call with no `:extra`.

### Step 6. Consumer dep tree is SWT-free **(gate)**

From a sibling project depending on CDT:

```
clojure -X:deps tree | grep -i swt
```

Expected: no output. CDT's contract — "depend on CDT, you get SWT
for free, you never know about it" — fails the moment a consumer
sees SWT in their dep tree.

### Step 7. Smoke test — widgets render **(gate)**

In a REPL with the new JAR on classpath:

```clojure
(require '[ui.nebula])
(ui.nebula/ensure-loaded!)       ;; => :ok
(require '[ui.SWT :as ui])
;; Build a shell containing PShelf (Tier 3a) and one opal widget
;; (e.g., LED — Tier 2). Use the SWT-UI-RULES.md screenshot workflow
;; to capture the window.
```

Expected: widgets render correctly with no exceptions. Save the
screenshot as evidence. Smoke at minimum: one Tier 1, one Tier 2,
one Tier 3a, one Tier 3b.

### Step 8. Load-order contract **(gate)**

Three sub-checks:

1. **With** `(:require [ui.nebula])` first, then
   `(Class/forName "org.eclipse.nebula.widgets.pshelf.PShelf")`
   succeeds.
2. **Without** the require (cold REPL), the same `Class/forName`
   fails with a `NoClassDefFoundError` naming an SWT class. This
   proves `ui.nebula` is the load-order gate.
3. **JDK fail-fast**: on a JDK 16 (or older) JVM, `(require
   '[ui.nebula])` throws `IllegalStateException` with a message
   naming the JDK 17 floor — NOT a cryptic
   `UnsupportedClassVersionError` from class loading.

### Step 9. Cross-platform spot check

Build on the host platform. Copy the JAR to a different-platform
machine (or a Docker container with the matching native SWT support)
and re-run Step 7. Confirms bytecode portability.

Fallback if no different-platform machine is accessible:

```
javap -v target/classes/org/eclipse/nebula/widgets/pshelf/PShelf.class \
  | grep -E '(major version|org/eclipse/swt)' | head
```

Expected: `major version: 61` plus constant-pool entries referencing
SWT classes by symbolic name (e.g., `org/eclipse/swt/widgets/Composite`).
Combined with Step 4, this gives high confidence the bytecode is
truly platform-independent.

### Step 10. Reusability example builds

```
cd examples/java-widget
clojure -T:build jar
jar tf target/java-widget.jar | grep HelloLabel
```

Expected: `com/example/HelloLabel.class` listed. Proves
`ui.build.swt/javac-with-swt` works for downstream clients — the
core promise of "reusable by client projects."

### Gate items summary

The following must pass before merging:

- **3a**: staged tree contains no `org/eclipse/nebula/jface/` paths,
  no `*/viewer*` subdirectories, and none of the listed
  `:exclude-files` paths (`DateChooser*CellEditor.java`,
  `FormattedText*ObservableValue.java`, etc.).
- **3b**: no compiled class references
  `org/eclipse/jface/` or `org/eclipse/core/runtime/`.
- **5**: published pom contains no SWT entry.
- **6**: consumer dep tree contains no SWT entry.
- **7**: PShelf, an opal widget, plus a Tier 1 and Tier 3b widget
  render in a SWT shell.
- **8**: `ui.nebula` enforces load order; bypass produces the
  documented `NoClassDefFoundError`; JDK fail-fast check throws a
  clear message on JDK <17.

## Risks (full)

See **Risks** in the source plan. Recap of the top items:

1. **JDK floor**. `--release 17` pins consumers to JDK 17+. Document
   in README, add a fail-fast check in `ui.nebula`. JDK 17 is forced
   by Nebula's `grid` and `chips` widgets (`Bundle-RequiredExecutionEnvironment: JavaSE-17`).
2. **OSGi-expecting runtime code paths** in opal.commons. Smoke-test
   each widget; drop offenders from the manifest.
3. **`b/copy-dir :ignores` and `:include` semantics**. Verify
   empirically in step 4 that `:ignores` regexes fire for BOTH
   `:exclude-pkgs` (package-level) and `:exclude-files` (file-level)
   patterns, AND that the `:include "**/*.java"` glob picks up the
   expected Java sources without false negatives. Fall back to manual
   `fs/walk-file-tree` if either misbehaves.
4. **License compliance**. Add `NOTICE.md` with Nebula/SWT
   attribution referencing the pinned SHA from
   `vendor/nebula/VERSION`.
5. **Vendored Nebula staleness**. `nebula-sha` is pinned in
   `build.clj`. Bumping = edit the constant, run
   `clojure -T:build update-vendored-nebula`, commit the result.
   This is intentional friction; do NOT add automatic
   nebula-fetching to the `jar` target.
6. **`grid`/`chips` JDK target**. Their MANIFEST.MF declares
   `JavaSE-17`. Other widgets target JDK 11. Compiling everything
   with `--release 17` is simpler than two compile passes; this
   propagates the JDK 17+ requirement to consumers.

## When to STOP and ask the user

- Audit finds a widget whose imports don't match its declared tier
  (per the `nebula-sources.edn` audit instructions). Surface, don't
  silently expand `:exclude-pkgs` or `:exclude-files`.
- `b/copy-dir :ignores` doesn't match expected paths for EITHER the
  package-level or file-level exclusion pattern, and the fallback
  isn't obvious. Confirm the manual-walk approach before committing.
- `deploy.sh` needs changes to support the new JAR file naming
  scheme. Get user approval before editing the release pipeline.
- The version-bump choice in Step 11. Recommend 0.7.0 to the user
  but confirm before propagating the version string into `Makefile`,
  `pom.xml`, the new-and-noteworthy filename, and the README link.
  Do NOT silently pick 1.0.0 — graduation to stable is the user's
  call, not the implementing Claude's.
- Verification step 3b finds ANY JFace class reference in the
  compiled JAR. This means source staging let something through —
  stop and investigate before adding more widgets.
- A smoke test reveals an OSGi-dependent widget in v1 (one we
  expected to work without Equinox). Drop the widget from the
  manifest with a comment; surface to user.
- `picture` audit reveals `AbstractPictureControl`'s forms import
  is genuinely required, NOT just an optional cross-reference.
  Confirm with the user whether to defer to v2 or attempt a more
  invasive workaround.
- Nebula upstream has restructured paths in a way that breaks the
  manifest's `:src`/`:exclude-pkgs`/`:exclude-files` assumptions
  between bumps. Surface the breaking changes and ask whether to
  pin to an older SHA or update the manifest.

## Out of scope for v1

The following are explicitly deferred. **Do NOT attempt them as part
of this plan**:

- JFace bundling (any mechanism).
- The P2 fetcher (v2 work).
- Tier 4 widgets.
- Chromium / databinding compilation (related but separate from
  Nebula).
- Replacing or modifying CDT's runtime SWT-loading mechanism.
- Changing CDT's public API surface in `ui.SWT.clj`.
- AOT compilation of Clojure namespaces (CDT is REPL-driven, no AOT
  by design).

---

## Option 4 addendum (2026-05-27): bundle Nebula as a separate inner JAR

Read the **"The runtime classloader problem"** section of the context
file first. The summary: shipping loose Nebula `.class` files inside the
CDT JAR doesn't survive `ClassNotFoundException` at runtime because the
CDT JAR sits on the parent `AppClassLoader` while pomegranate-added SWT
sits on a child `DynamicClassLoader`, and parent loaders can't see
child loaders. Option 4 fixes this by extracting Nebula at runtime onto
the same classloader as SWT.

### File-by-file changes

#### Modified: `build.clj`

- Add a new path constant: `(def nebula-class-dir "target/nebula-classes")`.
- `compile-java` outputs to `nebula-class-dir` (NOT `class-dir`). The
  staging step is unchanged.
- New task `nebula-jar`:
  ```clojure
  (defn nebula-jar [{:keys [version]}]
    (b/jar {:class-dir nebula-class-dir
            :jar-file  (format "target/nebula-%s.jar" version)}))
  ```
- `jar` task sequence:
  1. `clean`
  2. `compile-java` → populates `target/nebula-classes/`
  3. `nebula-jar` → produces `target/nebula-<version>.jar`
  4. Copy `target/nebula-<version>.jar` to `class-dir/nebula.jar`
     (single canonical name inside the published JAR — version-less so
     `ui.nebula`'s extractor doesn't need to know the version).
  5. `b/copy-dir {:src-dirs ["src" "resources"] :target-dir class-dir}`
  6. `b/write-pom` (unchanged; still SWT-free)
  7. `b/jar {:class-dir class-dir :jar-file (jar-file version)}`
- The published CDT JAR layout becomes:
  ```
  org/                          ← only ui/* clojure source classes
  ui/SWT.clj, ui/nebula.clj, ...
  swt-4.38-<six>.zip            ← SWT platform bundles
  nebula.jar                    ← NEW: the compiled Nebula bytecode
  META-INF/maven/.../pom.xml
  ```
- **Key contract**: no `org/eclipse/nebula/**` `.class` files appear
  directly in the JAR. Verification step 3b's `jar tf | grep ^org/eclipse/nebula`
  should return 0.

#### Modified: `src/ui/nebula.clj`

This namespace gains the runtime extractor analogous to
`ui.internal.SWT-deps`. Body order:

```clojure
(ns ui.nebula
  "..."
  (:require [babashka.fs :as fs]
            [cemerick.pomegranate :as pom]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [ui.internal.SWT-deps])     ; forces SWT to be on the loader first
  (:import [java.io File]))

;; JDK 17 fail-fast — unchanged
(defn- jdk-major-version [] ...)
(when (< (jdk-major-version) 17) (throw ...))

(defn- extract-nebula!
  "Extract the bundled `nebula.jar` resource to a tmp dir, then add it
   to the classloader via pomegranate. Idempotent via defonce. Returns
   the extracted java.io.File for the jar."
  []
  (let [tmpdir (File. (str (fs/create-temp-dir)))
        target (File. tmpdir "nebula.jar")]
    (.deleteOnExit tmpdir)
    (with-open [in  (-> "nebula.jar" io/resource io/input-stream)
                out (io/output-stream target)]
      (io/copy in out))
    (pom/add-classpath target)
    target))

(defonce nebula-jar (extract-nebula!))

;; Now that Nebula classes are on the classloader, load ui.SWT — its
;; reflectivity scan will pick up both SWT and Nebula widgets, and
;; `define-inits` will auto-generate init fns for both. Consumers that
;; require ui.nebula get `pshelf`, `pshelf-item`, etc. in ui.SWT.
(require '[ui.SWT])

(defn ensure-loaded! [] ...)  ; unchanged
```

Critical detail: the `require '[ui.SWT]` is in the **body** of the
namespace, not in the `(ns ...)` form's `:require`. This lets the
extraction run first.

#### Modified: `src/ui/internal/reflectivity.clj`

The 2026-05-27 fix to `classpath-urls` (explicit `java.class.path`
URLs + SWT jar URL) is retained. **Add** the Nebula jar to the URL
set the same way:

```clojure
(defn- classpath-urls []
  (let [from-cp     (->> (str/split (System/getProperty "java.class.path") ...) ...)
        swt-jar-url (-> swt-deps/swt :jar .toURI .toURL)
        nebula-url  (when-let [nj (try (resolve 'ui.nebula/nebula-jar) (catch Throwable _ nil))]
                      (some-> @nj .toURI .toURL))]
    (cond-> (conj from-cp swt-jar-url)
      nebula-url (conj nebula-url))))
```

The `try/resolve` dance is so reflectivity doesn't have a hard
dependency on `ui.nebula` — consumers that only use SWT shouldn't be
forced to load Nebula. (`ui.nebula` itself transitively loads
reflectivity via the `(require '[ui.SWT])` call, after the Nebula JAR
is extracted, so the resolve succeeds.)

#### Deferred to v0.8.0: `examples/java-widget/`

Rename the directory to `examples/java-widget.deferred/` and replace
its README with a short note explaining the classloader-split issue
that blocks downstream-client Java widget compilation in v1. Reference
the context doc's "Runtime classloader problem" section. Keep the
source so the future PR has a starting point.

#### Modified: `examples/nebula-widget/`

- `deps.edn` returns to the plan's original `:local/root "../.."` for
  CDT in dev (or `:mvn/version "0.7.0"` for downstream-facing
  documentation), with NO explicit `org.eclipse.swt/swt` dep.
- `src/example/main.clj` uses the canonical starter pattern: install
  a `DynamicClassLoader` as context loader, bind `*repl* true`,
  require `example.ui`, and `eval`-call into it.
- `src/example/ui.clj` requires `ui.nebula` (which transitively loads
  ui.SWT after extracting the Nebula JAR), then uses `pshelf`,
  `pshelf-item`, `label`, etc. directly.
- The `body` helper (PShelfItem's `.getBody`-injection wrapper) stays.

#### Modified: README.md / CLAUDE.md / version-0.7.0.md

- Remove the "Compiling your own Java/SWT widgets" sections — that
  story is deferred to v0.8.0. Replace with a short pointer to the
  deferred example explaining the classloader trade-off.
- Update the "Using bundled Eclipse Nebula widgets" section to note
  the load-order contract: `(:require [ui.nebula])` BEFORE `ui.SWT`
  is necessary so the reflective `define-inits` sees Nebula
  widgets when it runs.

### Implementation sequence (Option 4)

13. Edit `build.clj`:
    - Split `compile-java` to output to `target/nebula-classes/`.
    - Add `nebula-jar` task producing `target/nebula-<version>.jar`.
    - Update `jar` task to call `nebula-jar`, then copy the result to
      `target/classes/nebula.jar`, then do the existing source+pom+jar
      steps.
    - Verify with `make jar` then `jar tf` that the published JAR
      contains `nebula.jar` and NO `org/eclipse/nebula/**` `.class`
      files.

14. Edit `src/ui/nebula.clj`:
    - Add `extract-nebula!` + `defonce nebula-jar`.
    - Move `(require '[ui.SWT])` into the namespace body, after
      extraction.
    - Keep JDK 17 fail-fast at the top (before extraction so the error
      message is clear).

15. Edit `src/ui/internal/reflectivity.clj`:
    - Add Nebula JAR URL to `classpath-urls` via a soft resolve of
      `ui.nebula/nebula-jar` so reflectivity doesn't hard-depend on
      ui.nebula.

16. `make install` and verify in a fresh JVM (no `:local/root` SWT):
    ```
    cd examples/nebula-widget && make run
    ```
    The PShelf window must render. Capture a screenshot
    via the dev/screenshot.clj variant for evidence.

17. Move `examples/java-widget/` → `examples/java-widget.deferred/`.
    Replace its README with a 10-line note pointing at the context
    doc's "Runtime classloader problem" section.

18. Update README.md, CLAUDE.md, `docs/new-and-noteworthy/version-0.7.0.md`
    to reflect the deferral and the new load-order contract.

### Verification gates (Option 4 additions)

- **G-O4-A** (replaces gate 3b): `jar tf target/clojure-desktop-toolkit-<v>.jar | grep '^org/eclipse/nebula/.*\.class$' | wc -l` returns `0`. The published JAR contains `nebula.jar` (as a resource) — NOT loose Nebula `.class` files.
- **G-O4-B**: `jar tf target/clojure-desktop-toolkit-<v>.jar | grep nebula.jar` returns `nebula.jar` (the inner JAR is present).
- **G-O4-C**: From a fresh JVM with only `clojure-desktop-toolkit {:mvn/version "0.7.0"}` on classpath (no `:local/root` SWT), `(require '[ui.nebula] '[ui.SWT])` followed by `(resolve 'ui.SWT/pshelf)` returns a non-nil Var.
- **G-O4-D**: `cd examples/nebula-widget && make run` renders the PShelf window. Screenshot in the verification artifacts.

### Risks

- **Resource extraction performance**: the Nebula JAR is read from
  inside the CDT JAR and re-copied to a temp dir at every JVM start.
  This is fine for typical applications (a few MB, done once). If
  startup latency becomes a concern, switch `pom/add-classpath` to
  load the JAR via a JarFile-backed URL pointing into the CDT JAR
  directly (avoids the copy) — but `add-classpath` expects
  filesystem paths, so the copy is the simplest correct path.

- **Reflectivity's optional Nebula scan via `try/resolve`**: if any
  load-order regression causes `ui.nebula/nebula-jar` to exist before
  `nebula-jar` itself is extracted, reflectivity will get a nil URL
  and silently skip Nebula. Mitigated by the `defonce` ordering in
  `ui.nebula` and a small `pre-load-test` in dev/ that asserts
  PShelf shows up in `swt-composites` after `(require '[ui.nebula])`.
