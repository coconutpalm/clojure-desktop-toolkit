(ns ui.nebula
  "Runtime gate for the bundled Eclipse Nebula widget set.

   `(:require [ui.nebula])` from a consumer namespace BEFORE
   `(:require [ui.SWT])` does three things, in order:

     1. Enforces the JDK 17+ floor (Nebula's `grid` and `chips` widgets
        target `JavaSE-17`; all bundled bytecode is class-file major
        version 61). A pre-17 JVM throws `IllegalStateException` with
        a clear message at require time, not a cryptic
        `UnsupportedClassVersionError` deep inside widget loading.

     2. Forces `ui.internal.SWT-deps` to load, which extracts the
        host-platform SWT zip and pomegranate-adds it to the
        DynamicClassLoader.

     3. Extracts the bundled `nebula.jar` (a resource inside the CDT
        JAR) to a tmp dir and pomegranate-adds it to the same
        DynamicClassLoader. This is the **load-order critical** step:
        Nebula classes (e.g. `PShelf extends Canvas`) must be defined
        by the same classloader that holds SWT, otherwise the JVM
        can't resolve their SWT superclasses. See the plan/context
        \"Runtime classloader problem\" section for the diagnosis.

   After the body runs, `(require '[ui.SWT])` is invoked from this
   namespace's body so `ui.internal.reflectivity` scans the classpath
   with BOTH SWT and Nebula on it — `define-inits` then auto-generates
   `pshelf`, `pshelf-item`, `led`, etc. as `ui.SWT/*` init fns
   alongside the built-in SWT widgets."
  (:require
   [babashka.fs :as fs]
   [cemerick.pomegranate :as pom]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [ui.internal.SWT-deps])              ; forces SWT extraction first.
  (:import
   [java.io File]))

(defn- jdk-major-version
  "Parses `java.version` (e.g., \"17.0.10\", \"11.0.21\", \"1.8.0_392\")
   into an integer major version. Returns 17 for \"17.x\", 11 for
   \"11.x\", 8 for \"1.8.x\", etc."
  []
  (let [v (System/getProperty "java.version")
        [a b] (str/split v #"\.")
        a' (Integer/parseInt a)]
    (if (= a' 1) (Integer/parseInt b) a')))

(when (< (jdk-major-version) 17)
  (throw (IllegalStateException.
          (str "Clojure Desktop Toolkit's Nebula widgets require JDK 17 "
               "or later (bytecode major version 61). Detected JDK: "
               (System/getProperty "java.version")
               ". Upgrade your JDK before requiring ui.nebula."))))

(def ^:private bundled-nebula-resource "nebula.jar")

(defn- extract-nebula!
  "Extract the bundled `nebula.jar` resource to a tmp dir, then add it
   to the runtime classloader via pomegranate. Returns the extracted
   `java.io.File`. Idempotent because `(defonce nebula-jar ...)` calls
   it once."
  []
  (if-let [res (io/resource bundled-nebula-resource)]
    (let [tmpdir (File. (str (fs/create-temp-dir)))
          target (File. tmpdir bundled-nebula-resource)]
      (.deleteOnExit tmpdir)
      (with-open [in  (io/input-stream res)
                  out (io/output-stream target)]
        (io/copy in out))
      (pom/add-classpath target)
      target)
    (throw (ex-info (str "ui.nebula: bundled resource '"
                         bundled-nebula-resource
                         "' not found on classpath. The CDT JAR must "
                         "include it (build.clj's `jar` task copies the "
                         "inner Nebula JAR alongside Clojure source).")
                    {:resource bundled-nebula-resource}))))

(defonce
  ^{:doc "The runtime-extracted bundled Nebula JAR (File). Deref'd by
          `ui.internal.reflectivity` via soft resolve so it can add the
          Nebula URL to its Reflections scan after extraction. Idempotent."}
  nebula-jar
  (extract-nebula!))

;; CRITICAL load-order step: require ui.SWT AFTER the Nebula JAR is on
;; the classloader. ui.SWT's transitive load of `ui.internal.reflectivity`
;; scans the classpath; if we required ui.SWT before extraction, no
;; Nebula init fns would be generated.
(require '[ui.SWT])

(defn ensure-loaded!
  "Forces resolution of a core Nebula class to confirm the runtime
   classpath holds both SWT and the bundled Nebula bytecode. Returns
   `:ok` or throws."
  []
  (Class/forName "org.eclipse.nebula.widgets.opal.commons.SWTGraphicUtil")
  :ok)
