(ns starter.main
  "We dynamically load the correct platform-specific SWT libraries for the running platform.  In order to do this,
   we have to configure Clojure to allow dynamic loading at runtime before we `require` anything that involves
   the UI.  This `main` namespace shows how to do this."
  (:gen-class))

(defn -main
  "Start the app."
  [& args]

  ;; Allow the app to dynamically load the platform-specific SWT libraries
  (let [cl (.getContextClassLoader (Thread/currentThread))]
    (.setContextClassLoader (Thread/currentThread) (clojure.lang.DynamicClassLoader. cl)))

  (binding [*repl* true]           ; Allow add-libs from Clojure 1.12 to work.
    (require '[starter.ui])        ; Actually initialize SWT with the correct platform-specific lib and generate the SWT Clojure API.
    (eval                          ; Start the user interface.
     `(starter.ui/hello ~@args)))) ; We have to `eval` because the `require` isn't in the `ns` form; the compiler hates us otherwise.  LOL.

