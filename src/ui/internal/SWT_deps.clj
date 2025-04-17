(remove-ns 'ui.internal.SWT-deps)

(ns ui.internal.SWT-deps
  "Dynamically resolve/load SWT subsystem dependencies when this namespace is required"
  (:require
   [clojure.java.io :as io]
   [insideout.dynamo :as dynamo]))


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

;; Migrate to Equodev Chromium
;;  https://github.com/equodev/chromium

(def ^:dynamic *swt-version* "3.116.0")

(def swt-lib      [(->platform-lib 'org.eclipse.platform/org.eclipse.swt)
                   *swt-version*])
(def swt-chromium [(->platform-lib 'org.eclipse.platform/org.eclipse.swt.browser.chromium)
                   *swt-version*])
(def chromium-jar (->platform-resource-jar 'chromium/com.make.chromium.cef "0.4.0.202005172227"))

(def reflections-lib '[org.reflections/reflections "0.9.12"])


(defonce lib-resolutions
  (do
    (dynamo/resolve-libs [swt-lib swt-chromium reflections-lib])
    (when chromium-jar
      (dynamo/add-urls-to-classpath [chromium-jar]))))


;; org.eclipse.swt.browser.chromium.
;; Original jar locations
;;
;; http://dl.maketechnology.io/chromium-cef/rls/repository/plugins/com.make.chromium.cef.gtk.linux.x86_64_0.4.0.202005172227.jar
;; http://dl.maketechnology.io/chromium-cef/rls/repository/plugins/com.make.chromium.cef.cocoa.macosx.x86_64_0.4.0.202005172227.jar
;; http://dl.maketechnology.io/chromium-cef/rls/repository/plugins/com.make.chromium.cef.win32.win32.x86_64_0.4.0.202005172227.jar


;; com.make.chromium.cef.feature_0.4.0.202005172227
