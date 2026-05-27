(ns ui.build.swt
  "Build-time helpers for compiling Java/SWT sources into a JAR.

   Use from a tools.build `build.clj`. Provides functions to extract the
   CDT-bundled SWT JAR, build a basis with SWT on the classpath, and
   invoke `b/javac` against Java source roots that depend on SWT to
   compile.

   The CDT-published pom intentionally declares no SWT dependency
   (consumers inherit SWT via CDT's runtime classpath-injection in
   `ui.internal.SWT-deps`). To compile Java sources against SWT at build
   time without leaking SWT into the published pom, use `compile-basis`
   here for `b/javac`/`b/compile-clj`, and a separate plain
   `b/create-basis` call for `b/write-pom`."
  (:require
   [clojure.tools.build.api :as b]
   [ui.internal.swt-platform :as plat])
  (:import
   [java.io File]))

(defn extract-bundled-swt
  "Extract the CDT-bundled SWT zip (host platform) into target-dir.
   Returns {:dir File :jar File :src File}. Idempotent — see
   `ui.internal.swt-platform/extract-swt-zip!`."
  ([] (extract-bundled-swt {}))
  ([{:keys [target-dir] :or {target-dir "target/build-deps/swt"}}]
   (let [td (File. ^String target-dir)]
     (.mkdirs td)
     (plat/extract-swt-zip! (plat/platform-zip-resource-name) td))))

(defn swt-extra-deps
  "Returns a `{dep-symbol coord}` map for use as
   `(b/create-basis {:extra {:deps ...}})`. The coord is `:local/root`
   pointing at the extracted swt.jar."
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
