(ns starter.main
  "We dynamically load the correct platform-specific SWT libraries for the running platform.  In order to do this,
   we have to configure Clojure to allow dynamic loading at runtime before we `require` anything that involves
   the UI.  This `main` namespace shows how to do this."
  (:gen-class)
  (:import
   [java.awt SplashScreen]))

(defn -main
  "Start the app."
  [& args]

  ;; Allow the app to dynamically load the platform-specific SWT libraries
  (let [cl (.getContextClassLoader (Thread/currentThread))]
    (.setContextClassLoader (Thread/currentThread) (clojure.lang.DynamicClassLoader. cl)))

  (binding [*repl* true]           ; Allow add-libs from Clojure 1.12 to work.
    (require '[starter.ui])        ; Actually initialize SWT with the correct platform-specific lib and generate the SWT Clojure API.

    ;; Close the splash screen if it's being displayed; on MacOS this must happen on a separate thread
    (future
      (Thread/sleep 1000)  ; Wait a second to allow the splash screen to be displayed on MacOS
      (when-let [splash-screen (SplashScreen/getSplashScreen)]
        (.close splash-screen)))

    (eval                          ; Start the user interface.
     `(starter.ui/hello ~@args)))) ; We have to `eval` because the `require` isn't in the `ns` form; the compiler hates us otherwise.  LOL.

