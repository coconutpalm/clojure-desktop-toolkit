(ns ui.internal.SWT-deps
  "Dynamically resolve/load SWT subsystem dependencies when this namespace is required"
  (:require
   [clojure.java.io :as io]
   [clojure.repl.deps :refer [add-libs]]))


(def platform-lib-suffix
  (let [suffixes {"lin" 'gtk.linux.x86_64
                  "mac" 'cocoa.macosx.x86_64
                  "win" 'win32.win32.x86_64}
        os-code (-> (System/getProperty "os.name")
                   (.substring 0 3)
                   (.toLowerCase))]
    (str (get suffixes os-code "-unsupported-"))))

(defn ->platform-lib
  "Returns the full library dependency given a qualified group/archive symbol"
  [ga-symbol]
  (symbol (namespace ga-symbol) (str (name ga-symbol) "." platform-lib-suffix)))

(defn ->platform-resource-jar
  "Returns the full library dependency given a qualified group/archive symbol"
  [ga-symbol version]
  (io/resource
    (str (namespace ga-symbol) "/" (str (name ga-symbol) "." platform-lib-suffix "_" version ".jar"))))


;; SWT and dependencies ------------------------------------------------------------

(def ^:dynamic *swt-version* "4.35")

(def swt-libs {(->platform-lib 'org.eclipse.swt/org.eclipse.swt) {:mvn/version *swt-version*}})

(defonce
  ^{:doc "Result of loading SWT subsystem dependencies."}
  swt-libs-loaded? (try (import '[org.eclipse.swt SWT])
                        (catch ClassNotFoundException _e
                          (add-libs swt-libs))))

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
