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
   audit. This goes into `build.clj` as `nebula-sha` (step 3). No
   sibling Nebula checkout is needed — the build vendors sources via
   `clojure -T:build update-vendored-nebula`.

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
            [babashka.fs :as fs]
            [clojure.repl.deps :refer [add-libs]])
  (:import [java.io File]))

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
   Always `(require '[ui.nebula])` in namespaces that import Nebula widgets."
  (:require [ui.internal.SWT-deps]))  ;; forces SWT-loading defonces

(defn ensure-loaded! []
  (Class/forName "org.eclipse.nebula.widgets.opal.commons.SWTGraphicUtil")
  :ok)
```

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

(def publish-basis (delay (b/create-basis {:project "deps.edn"})))
(def compile-basis (delay (swt-build/compile-basis {:project "deps.edn"})))

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

(defn compile-java [_]
  (let [manifest (edn/read-string (slurp "nebula-sources.edn"))]
    (stage-nebula-sources manifest)
    (swt-build/javac-with-swt
      {:basis     @compile-basis
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

(defn update-vendored-nebula
  "Bump the vendored Nebula sources to the SHA in `nebula-sha`. Clones
   EclipseNebula/nebula at the pinned SHA, filters to just the bundle
   src/ trees enumerated in nebula-sources.edn, and overwrites
   `vendor/nebula/`. Also vendors LICENSE and writes vendor/nebula/VERSION.

   Run this manually when bumping versions. Commit the resulting tree.
   This task is NOT part of `jar` — routine builds use the existing
   vendored sources."
  [_]
  (let [tmpdir   "target/build-deps/nebula-fresh"
        manifest (edn/read-string (slurp "nebula-sources.edn"))]
    (b/delete {:path tmpdir})
    (b/process {:command-args ["git" "clone" nebula-repo tmpdir]})
    (b/process {:command-args ["git" "-C" tmpdir "checkout" nebula-sha]})
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
matching rules. tools.build 0.10.x documents `:ignores` as a coll of
regexes, but the match target (full path? relative path? filename
only?) should be confirmed against the actual extracted Nebula source
paths. If `:ignores` doesn't behave as expected, replace
`stage-nebula-sources` with a manual `fs/walk-file-tree` filter — the
function is small enough to hand-roll without ceremony.

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
 :grid                {:src "widgets/grid/org.eclipse.nebula.widgets.grid/src"
                       :tier 3
                       :exclude-pkgs ["org/eclipse/nebula/jface/gridviewer"
                                      "org/eclipse/nebula/jface/gridviewer/internal"]}
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
# Update version number here
# `make`
# Push changes to Github
# Create version tag on Github

ALL: jar deploy

jar:
	clojure -T:build jar :version '"0.6.0"'

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

- `deps.edn` — declares `io.github.coconutpalm/clojure-desktop-toolkit`
  as a dep, plus `:build` alias mirroring CDT's.
- `build.clj` — uses `swt-build/javac-with-swt` to compile
  `java-src/`.
- `java-src/com/example/HelloLabel.java` — a trivial
  `class HelloLabel extends org.eclipse.swt.widgets.Composite`.
- `src/example/main.clj` — `(:require [ui.nebula])` to bootstrap SWT,
  then uses `HelloLabel`.
- `README.md` — 1-paragraph explanation of what the example
  demonstrates.

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
`update-vendored-nebula` task. Pin `nebula-sha` to a recent Nebula
release tag SHA (per pre-flight check 4). Add the `:build` alias to
`deps.edn`. Remove `:jar` and `:deploy` aliases. Update `Makefile`.

Run `make jar`. Confirm it produces a JAR functionally equivalent to
today's depstar output. Run verification steps 4 (no SWT classes in
JAR), 5 (pom is SWT-free), 6 (consumer dep tree is SWT-free) from
`compile-java-swt-widgets-context.md`. **If anything regressed, fix
before continuing.**

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

Update:
- `README.md`: add a "Using Nebula widgets" section listing the 57
  bundled widgets, a "Compiling your own Java/SWT widgets" section
  pointing at `ui.build.swt`, a "Vendored Nebula version" subsection
  noting `vendor/nebula/VERSION` and the bump workflow, a "JDK 17+
  required" note, and a "CDT does not require Equinox" callout.
- `CLAUDE.md`: add a "Building" section explaining the `:build`
  alias, the `update-vendored-nebula` task, the manifest schema
  (`:exclude-pkgs`/`:exclude-files`), and the JDK 17 floor. Add the
  `(:require [ui.nebula])` contract for namespaces importing Nebula
  widgets. Document that `vendor/nebula/` is a filtered upstream
  mirror — contributors should NOT edit those files by hand (use
  `update-vendored-nebula` instead).
- `NOTICE.md`: EPL-2.0 attribution as described above. Reference the
  pinned SHA from `vendor/nebula/VERSION`.

### Step 12. Release

Bump version. Confirm `deploy.sh` works against the new JAR layout.
Tag and push.

## Verification (full)

See the **Verification** section of
`/Users/dorme/.claude/plans/carefully-analyze-this-code-snoopy-planet.md`
for the full 10-step verification suite. Critical steps that must pass
before merging:

- **3a**: `target/build-deps/java-src/` contains no
  `org/eclipse/nebula/jface/` paths, no `*/jface*` subdirectories
  under widget packages, no `*/viewer` or `*/viewers` subdirectories
  under widget packages, and none of the explicitly listed
  `:exclude-files` paths (e.g., `DateChooser*CellEditor.java`,
  `FormattedText*ObservableValue.java`).
- **3b**: No compiled class in the JAR references
  `org/eclipse/jface/` or `org/eclipse/core/runtime/`.
- **5**: Published pom contains no SWT entry.
- **6**: Consumer dep tree contains no SWT entry.
- **7**: PShelf and at least one opal widget render in a SWT shell.
- **8**: `ui.nebula` ensures correct load order; bypass produces the
  documented NoClassDefFoundError. The JDK fail-fast check throws
  with a clear message on JDK <17.

## Risks (full)

See **Risks** in the source plan. Recap of the top items:

1. **JDK floor**. `--release 17` pins consumers to JDK 17+. Document
   in README, add a fail-fast check in `ui.nebula`. JDK 17 is forced
   by Nebula's `grid` and `chips` widgets (`Bundle-RequiredExecutionEnvironment: JavaSE-17`).
2. **OSGi-expecting runtime code paths** in opal.commons. Smoke-test
   each widget; drop offenders from the manifest.
3. **`b/copy-dir :ignores` semantics**. Verify empirically in step 4
   for BOTH `:exclude-pkgs` (package-level) and `:exclude-files`
   (file-level) patterns. Fall back to manual walk if needed.
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
