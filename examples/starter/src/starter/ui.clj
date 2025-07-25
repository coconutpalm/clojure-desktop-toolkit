(ns starter.ui
  "Minimal starter application"
  (:require
   [ui.SWT :as ui :refer [application shell label]])  ; clj-kondo can't resolve `label` because it's generated by a macro :-(
  (:import 
   [org.eclipse.swt SWT]
   [org.eclipse.swt.layout FillLayout]))

(defn hello
  "Display the main application user interface."
  [& _args]

  ;; The `application` function runs as long as there is at least one open shell.
  (application
   (shell "Hello, world"
          :layout (FillLayout.)

          (label SWT/WRAP "Hello, world is the traditional message used to demonstrate a new language or user interface toolkit.")))
  
  ;; MacOS needs an explicit exit call to close the program.
  (System/exit 0))



(comment
  "How to start a SWT application from the REPL."

  (def app (future (hello)))

  :eoc)
