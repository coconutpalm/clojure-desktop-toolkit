(ns ui.internal.swt-platform
  "Pure platform detection + SWT zip extraction. No side effects at load
   time. Shared between runtime (ui.internal.SWT-deps) and build-time
   (ui.build.swt)."
  (:require
   [clojure.java.io :as io]
   [babashka.fs :as fs])
  (:import
   [java.io File]))

(def ^{:dynamic true
       :doc "The prefix to use for the SWT platform-native zip filename resource."}
  *platform-zip-prefix* "swt-4.38")

(defn detect-os-arch
  "Read os.name + os.arch system properties and produce
   {:os-code <three-letter lowercase prefix> :arch <raw arch>}."
  []
  {:os-code (-> (System/getProperty "os.name") (.substring 0 3) .toLowerCase)
   :arch    (System/getProperty "os.arch")})

(def ^:private lib-suffixes
  {"lin" {"x86_64"  'gtk.linux.x86_64
          "amd64"   'gtk.linux.x86_64
          "aarch64" 'gtk.linux.aarch64
          "amd"     'gtk.linux.aarch64}
   "mac" {"x86_64"  'cocoa.macosx.x86_64
          "aarch64" 'cocoa.macosx.aarch64
          "arm64"   'cocoa.macosx.aarch64}
   "win" {"x86_64"  'win32.win32.x86_64
          "ia64"    'win32.win32.x86_64
          "amd64"   'win32.win32.x86_64}})

;; NOTE: zip suffixes differ from lib suffixes by hyphens instead of dots.
(def ^:private zip-suffixes
  {"lin" {"x86_64"  'gtk-linux-x86_64
          "amd64"   'gtk-linux-x86_64
          "aarch64" 'gtk-linux-aarch64
          "amd"     'gtk-linux-aarch64}
   "mac" {"x86_64"  'cocoa-macosx-x86_64
          "aarch64" 'cocoa-macosx-aarch64
          "arm64"   'cocoa-macosx-aarch64}
   "win" {"x86_64"  'win32-win32-x86_64
          "ia64"    'win32-win32-x86_64
          "amd64"   'win32-win32-x86_64}})

(defn platform-lib-suffix
  "Return the dotted platform-lib suffix for the given {:os-code :arch} map."
  [{:keys [os-code arch]}]
  (-> (get lib-suffixes os-code "-unexpected os-code-")
      (get arch "-unsupported-")))

(defn platform-zip-suffix
  "Return the hyphenated platform-zip suffix for the given {:os-code :arch} map."
  [{:keys [os-code arch]}]
  (-> (get zip-suffixes os-code "-unexpected os-code-")
      (get arch "-unsupported-")))

(defn platform-zip-resource-name
  "Resource name (e.g. \"swt-4.38-cocoa-macosx-aarch64.zip\") for the given
   {:os-code :arch} map, or for the host platform if no arg is supplied."
  ([] (platform-zip-resource-name (detect-os-arch)))
  ([os-arch]
   (str *platform-zip-prefix* "-" (platform-zip-suffix os-arch) ".zip")))

(defn extract-swt-zip!
  "Extract the SWT zip resource into `target-dir`. Returns
   {:dir File :jar File :src File}. Idempotent: if the target already
   contains swt.jar, returns the existing extraction without re-unzipping."
  [resource-name ^File target-dir]
  (.mkdirs target-dir)
  (let [jar (File. target-dir "swt.jar")
        src (File. target-dir "src.zip")]
    (when-not (.exists jar)
      (fs/unzip (-> resource-name io/resource io/input-stream) target-dir))
    {:dir target-dir :jar jar :src src}))
