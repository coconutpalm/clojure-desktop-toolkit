(ns ui.internal.SWT-deps
  "Dynamically resolve/load SWT subsystem dependencies when this namespace is required.

   If you package SWT yourself, use a Maven repository directory layout and modify
   the dynamic `ui.repositories/*repositories*` map to point to your repository (as a
   file URL) instead of the default ones.

   Alternatively, ensure the correct SWT is already on the classpath at launch.
   Then this code will automatically detect it and won't try to dynamically resolve SWT."
  (:require
   [clojure.java.io :as io]
   [babashka.fs :as fs]
   [cemerick.pomegranate :as pom]
   [ui.repositories :refer [*repositories*]]
   [clojure.repl.deps :refer [add-libs]])
  (:import
   [java.io File]))

#_(def platform-lib-suffix
  (let [suffixes {"lin" {"x86_64" 'gtk.linux.x86_64
                         "amd64" 'gtk.linux.x86_64
                         "aarch64" 'gtk.linux.aarch64
                         "amd" 'gtk.linux.aarch64}
                  "mac" {"x86_64" 'cocoa.macosx.x86_64
                         "aarch64" 'cocoa.macosx.aarch64
                         "arm64" 'cocoa.macosx.aarch64}
                  "win" {"x86_64" 'win32.win32.x86_64
                         "ia64" 'win32.win32.x86_64
                         "amd64" 'win32.win32.x86_64}}
        arch (System/getProperty "os.arch")
        os-code (-> (System/getProperty "os.name")
                    (.substring 0 3)
                    (.toLowerCase))]
    (-> (get suffixes os-code "-unexpected os-code-")
        (get arch "-unsupported-"))))


(def ^{:dynamic true
       :doc "The prefix to use for the SWT platform-native zip filename resource."}
  *platform-zip-prefix* "swt-4.38")

(def platform-zip-suffix
  (let [suffixes {"lin" {"x86_64" 'gtk-linux-x86_64
                         "amd64" 'gtk-linux-x86_64
                         "aarch64" 'gtk-linux-aarch64
                         "amd" 'gtk-linux-aarch64}
                  "mac" {"x86_64" 'cocoa-macosx-x86_64
                         "aarch64" 'cocoa-macosx-aarch64
                         "arm64" 'cocoa-macosx-aarch64}
                  "win" {"x86_64" 'win32-win32-x86_64
                         "ia64" 'win32-win32-x86_64
                         "amd64" 'win32-win32-x86_64}}
        arch (System/getProperty "os.arch")
        os-code (-> (System/getProperty "os.name")
                    (.substring 0 3)
                    (.toLowerCase))]
    (-> (get suffixes os-code "-unexpected os-code-")
        (get arch "-unsupported-"))))

(defn platform-zip []
  (str *platform-zip-prefix* "-" platform-zip-suffix ".zip"))

#_(defn ->platform-lib
  "Returns the full library dependency given a qualified group/archive symbol"
  [ga-symbol]
  (symbol (namespace ga-symbol) (str (name ga-symbol) "." platform-lib-suffix)))

#_(defn ->platform-resource-jar
  "Returns the full library dependency given a qualified group/archive symbol"
  [ga-symbol version]
  (io/resource
    (str (namespace ga-symbol) "/" (str (name ga-symbol) "." platform-lib-suffix "_" version ".jar"))))


;; SWT and dependencies ------------------------------------------------------------

(defn swt-platform
  []
  (let [tmpdir (File. (str (fs/create-temp-dir)))]
    (.deleteOnExit tmpdir)
    (fs/unzip (-> (platform-zip) io/resource io/input-stream) tmpdir)
    {:dir tmpdir :jar (File. tmpdir "swt.jar") :src (File. tmpdir "src.zip")}))

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
  (symbol "org.eclipse.platform" (str "org.eclipse.core.databinding" (when subproject (str "." subproject))) ))

(databinding-lib nil)
