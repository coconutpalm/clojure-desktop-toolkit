(ns screenshot
  "Bootstrap + screenshot variant of example.main. Same DynamicClassLoader
   + `*repl*` setup as example.main, but calls `example.ui/start-with-screenshot`
   so the shell screenshots itself and the JVM exits.

   Invoke via -X with the :dev path on classpath:
     clojure -J-XstartOnFirstThread \\
       -Sdeps '{:paths [\"src\" \"resources\" \"dev\"]}' \\
       -X screenshot/run :path '\"/tmp/cdt-nebula-widget.png\"'"
  (:gen-class))

(defn run [{:keys [path]}]
  (let [path (or path "/tmp/cdt-nebula-widget.png")
        cl   (.getContextClassLoader (Thread/currentThread))]
    (.setContextClassLoader (Thread/currentThread)
                            (clojure.lang.DynamicClassLoader. cl))
    (binding [*repl* true]
      (require '[example.ui])
      (eval `(example.ui/start-with-screenshot ~path))
      (System/exit 0))))
