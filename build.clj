(ns build
  "Top-level tools.build script for Clojure Desktop Toolkit.

   Produces `target/clojure-desktop-toolkit-<version>.jar`. The published
   pom intentionally declares NO SWT dependency — consumers inherit SWT
   via CDT's runtime classpath-injection in `ui.internal.SWT-deps`. To
   compile bundled Java/SWT sources (e.g. Nebula widgets) against SWT
   without leaking SWT into the published pom, the build uses two bases:
   `publish-basis` (no SWT) for `b/write-pom`, and `*compile-basis*`
   (SWT via `:local/root` `:extra`) for `b/javac`."
  (:require
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [clojure.tools.build.api :as b]
   [ui.build.swt :as swt-build]))

(def lib 'io.github.coconutpalm/clojure-desktop-toolkit)
(def class-dir         "target/classes")
(def nebula-class-dir  "target/nebula-classes")
(def java-staging      "target/build-deps/java-src")
(def vendor-dir        "vendor/nebula")
(defn jar-file        [v] (format "target/clojure-desktop-toolkit-%s.jar" v))
(defn nebula-jar-file [v] (format "target/nebula-%s.jar" v))

;; Inside the published CDT JAR, the bundled Nebula bytecode lives at
;; this fixed resource name. `ui.nebula` extracts it at runtime — the
;; version-less name keeps the runtime extractor decoupled from the
;; build version.
(def bundled-nebula-jar-name "nebula.jar")

;; Two bases as delays so callers pay the cost once per build invocation.
;; `*compile-basis*` is named with earmuffs to avoid shadowing
;; `swt-build/compile-basis` (a function); bare `compile-basis` in this
;; namespace must unambiguously deref the local delay.
(def publish-basis   (delay (b/create-basis {:project "deps.edn"})))
(def ^:dynamic *compile-basis* (delay (swt-build/compile-basis {:project "deps.edn"})))

;; Pinned upstream version of Eclipse Nebula. Bumping = edit this constant,
;; run `clojure -T:build update-vendored-nebula`, commit the result.
;; Matches the SHA the per-package audit ran against — see the context
;; file's "Nebula SHA audited for this plan" section.
(def nebula-sha "4a5ce620574a16644e6fc5b7533137b1ad7d8ed4")
(def nebula-repo "https://github.com/EclipseNebula/nebula.git")

(defn- excluded?
  "True if `rel-path` (relative to a bundle's src/ dir, slash-separated)
   matches any :exclude-pkgs prefix or :exclude-files equality. Both are
   anchored at the package root — no leading slash, no `.*/` prefix —
   because we walk relative paths."
  [rel-path exclude-pkgs exclude-files]
  (or (some (fn [pkg] (or (= rel-path pkg)
                          (.startsWith ^String rel-path (str pkg "/"))))
            exclude-pkgs)
      (some #(= rel-path %) exclude-files)))

(defn- stage-nebula-sources
  "Copy only included Nebula packages into target/build-deps/java-src/.
   Files under any :exclude-pkgs path are skipped; specific files listed
   in :exclude-files are also skipped. The staged tree is what
   b/javac compiles — so any source file that would import JFace,
   core.runtime, or any other non-SWT class never enters javac's view.

   Reads sources from the vendored Nebula tree at vendor-dir/<src>, NOT
   from a sibling Nebula checkout.

   Manual walk (not `b/copy-dir :ignores`) because tools.build's
   `:ignores` regex matches the absolute path; anchoring patterns to
   relative paths is brittle. The walk here matches rel-paths directly
   against the manifest's package/file strings — same shape as the
   manifest data — which is easier to reason about and verify."
  [manifest]
  (fs/delete-tree java-staging)
  (fs/create-dirs java-staging)
  (doseq [{:keys [src exclude-pkgs exclude-files]} (vals manifest)]
    (let [src-dir (str vendor-dir "/" src)
          src-path (.toPath (java.io.File. ^String src-dir))]
      (doseq [^java.io.File f (file-seq (java.io.File. ^String src-dir))
              :when (and (.isFile f)
                         (.endsWith (.getName f) ".java"))
              :let [rel (str (.relativize src-path (.toPath f)))]
              :when (not (excluded? rel exclude-pkgs exclude-files))]
        (let [target (java.io.File. ^String java-staging ^String rel)]
          (.mkdirs (.getParentFile target))
          (java.nio.file.Files/copy
           (.toPath f)
           (.toPath target)
           ^"[Ljava.nio.file.CopyOption;"
           (into-array java.nio.file.CopyOption
                       [java.nio.file.StandardCopyOption/REPLACE_EXISTING]))))))
  (println "Staged Nebula sources to" java-staging))

(defn clean [_] (b/delete {:path "target"}))

(defn compile-java
  "Stage Nebula sources (per nebula-sources.edn) and compile them to
   `nebula-class-dir` (NOT `class-dir`). The CDT JAR ships a separate
   inner `nebula.jar` built from this output; no loose Nebula `.class`
   files end up in the published CDT JAR. See Option 4 in the plan."
  [_]
  (b/delete {:path nebula-class-dir})
  (b/delete {:path java-staging})
  (let [manifest (edn/read-string (slurp "nebula-sources.edn"))]
    (stage-nebula-sources manifest)
    (swt-build/javac-with-swt
     {:basis     @*compile-basis*
      :src-dirs  [java-staging]
      :class-dir nebula-class-dir})))

(defn nebula-jar
  "Bundle the compiled Nebula classes into `target/nebula-<v>.jar`.
   The result is what `ui.nebula` extracts and pomegranate-adds at
   runtime so Nebula classes share a classloader with SWT."
  [{:keys [version]}]
  (b/jar {:class-dir nebula-class-dir
          :jar-file  (nebula-jar-file version)}))

(defn jar
  "Build the CDT JAR.

   Pipeline:
     1. Clean.
     2. Compile Nebula sources to `target/nebula-classes/`.
     3. JAR them into `target/nebula-<v>.jar`.
     4. Copy that JAR into the class-dir as the resource
        `nebula.jar` (version-less inside the CDT JAR so the runtime
        extractor doesn't need to know the build version).
     5. Copy Clojure src + resources alongside.
     6. Write pom (SWT-free, Nebula-free).
     7. JAR everything.

   The published CDT JAR contains:
     - Clojure source under ui/, ui/internal/, etc.
     - The six platform SWT zips in resources root.
     - `nebula.jar` at the JAR root.
   NO loose `org/eclipse/nebula/**` `.class` files. See Option 4
   in the plan + the Runtime classloader problem in the context."
  [{:keys [version] :as opts}]
  (clean nil)
  (compile-java nil)
  (nebula-jar opts)
  (b/copy-dir {:src-dirs ["src" "resources"] :target-dir class-dir})
  (b/copy-file {:src    (nebula-jar-file version)
                :target (str class-dir "/" bundled-nebula-jar-name)})
  (b/write-pom {:class-dir class-dir
                :lib       lib
                :version   version
                :basis     @publish-basis    ;; SWT-free
                :src-pom   "pom.xml"})
  (b/jar {:class-dir class-dir :jar-file (jar-file version)}))

(defn install [{:keys [version] :as opts}]
  (jar opts)
  (b/install {:basis     @publish-basis
              :lib       lib
              :version   version
              :class-dir class-dir
              :jar-file  (jar-file version)}))

(defn- run-cmd!
  "Wrap b/process so a non-zero exit aborts the build with a clear
   message. b/process returns {:exit n ...}; without this, a failed
   `git checkout` would be silently swallowed and we'd vendor whatever
   HEAD the clone landed on."
  [args]
  (let [{:keys [exit]} (b/process {:command-args args})]
    (when-not (zero? exit)
      (throw (ex-info (str "Command failed (exit " exit "): "
                           (str/join " " args))
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
    (run-cmd! ["git" "clone" nebula-repo tmpdir])
    (run-cmd! ["git" "-C" tmpdir "checkout" nebula-sha])
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
