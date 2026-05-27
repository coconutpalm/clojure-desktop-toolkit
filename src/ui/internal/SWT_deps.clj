(ns ui.internal.SWT-deps
  "Dynamically resolve/load SWT subsystem dependencies when this namespace
   is required. Platform detection lives in ui.internal.swt-platform; this
   namespace adds the runtime classpath-injection side effects.

   If you package SWT yourself, ensure the correct SWT is already on the
   classpath at launch. This code will detect it via `import` and skip the
   pomegranate fallback."
  (:require
   [babashka.fs :as fs]
   [cemerick.pomegranate :as pom]
   [ui.internal.swt-platform :as plat])
  (:import
   [java.io File]))

(defn ->platform-lib
  "Returns the full library dependency given a qualified group/archive symbol,
   suffixed with the current host platform's lib suffix (e.g.
   cocoa.macosx.aarch64)."
  [ga-symbol]
  (let [suffix (plat/platform-lib-suffix (plat/detect-os-arch))]
    (symbol (namespace ga-symbol) (str (name ga-symbol) "." suffix))))

;; SWT and dependencies ------------------------------------------------------------

(defn swt-platform
  []
  (let [tmpdir (File. (str (fs/create-temp-dir)))]
    (.deleteOnExit tmpdir)
    (plat/extract-swt-zip! (plat/platform-zip-resource-name) tmpdir)))

(defonce swt (swt-platform))

(defn load-swt-libs
  []
  (pom/add-classpath (:jar swt))
  (when (.exists (:src swt))
    (pom/add-classpath (:src swt))))

(defonce
  ^{:doc "Result of loading SWT subsystem dependencies."}
  swt-libs-loaded? (try (import '[org.eclipse.swt SWT])
                        (catch ClassNotFoundException _e
                          (load-swt-libs)
                          (import '[org.eclipse.swt SWT]))))

;; Chromium and dependencies --------------------------------------------------------

;; See:
;;
;; https://github.com/equodev/chromium and
;; https://storage.googleapis.com/equo-chromium-swt-ce/oss/mvn/index.html

; Oddly, these URLs resolve, but fetching via the Maven repository doesn't?
;https://dl.equo.dev/chromium-swt-ce/oss/mvn/com/equo/com.equo.chromium.cef.gtk.linux.x86_64/128.0.0/com.equo.chromium.cef.gtk.linux.x86_64-128.0.0.jar
;https://dl.equo.dev/chromium-swt-ce/oss/mvn/com/equo/com.equo.chromium.cef.cocoa.macosx.x86_64/128.0.0/com.equo.chromium.cef.cocoa.macosx.x86_64-128.0.0.jar

(def ^:dynamic *chromium-version* "128.0.0")

(def equo-libs {'com.equo/com.equo.chromium {:mvn/version *chromium-version*}
                (->platform-lib 'com.equo/com.equo.chromium.cef) {:mvn/version *chromium-version*}})

;; Eclipse databinding and dependencies ---------------------------------------------

;; Probably need to compile/package this myself because of dependencies
(def ^:dynamic *databinding-version* "1.13.300")

(defn databinding-lib
  [subproject]
  (symbol "org.eclipse.platform" (str "org.eclipse.core.databinding" (when subproject (str "." subproject)))))

(databinding-lib nil)
