(ns example.main
  "Bootstrap entry: set up the DynamicClassLoader and `*repl*` binding
   that CDT's runtime SWT loader needs, then hand off to `example.ui`.

   Mirrors `examples/starter/src/starter/main.clj` — the canonical CDT
   entry pattern. Without the DynamicClassLoader, `cemerick.pomegranate`
   (used by `ui.internal.SWT-deps` to inject the bundled SWT jar onto
   the classpath at runtime) fails with `AppClassLoader` not being
   modifiable under `clojure -M -m` / `clojure -X`."
  (:gen-class))

(defn -main [& args]
  (let [cl (.getContextClassLoader (Thread/currentThread))]
    (.setContextClassLoader (Thread/currentThread)
                            (clojure.lang.DynamicClassLoader. cl)))
  (binding [*repl* true]                ; Allow add-libs from Clojure 1.12 to work.
    (require '[example.ui])             ; Triggers SWT load + reflective init-fn generation.
    (eval `(example.ui/start ~@args)))) ; eval because require isn't in ns form.
