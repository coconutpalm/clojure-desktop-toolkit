(ns app.hello
  "Various kinds of 'hello-world' applications ranging from idiomatic to desugared.

   The desugared versions illustrate how Clojure Desktop Toolkit works under the hood,
   which provides the background one needs to extend Clojure Desktop Toolkit the same way
   it is written to begin with."
  (:require
   [ui.SWT :as ui :refer [application ui child-of shell label]])
  (:import
   [org.eclipse.swt SWT]
   [org.eclipse.swt.widgets Shell Label]
   [org.eclipse.swt.layout FillLayout]))


(defn hello
  "Idiomatic Hello, world."
  []
  (application
   (shell SWT/SHELL_TRIM
          "Hello application"

          :layout (FillLayout.)

          (label "Hello, world"))))

(defn hello-desugared
  "Hello, world--but without using any predefined 'init' functions in order to illustrate
   the function signature init functions require."
  []
  (application
   (fn [props parent]
     (let [child (Shell. parent SWT/SHELL_TRIM)]
       (doall
        (map #(apply % props child [])
             [(fn [_props parent] (.setText parent "Hello application"))
              (fn [_props parent] (.setLayout parent (FillLayout.)))
              (fn [props parent]
                (let [child (Label. parent SWT/NONE)]
                  (doall
                   (map #(apply % props child [])
                        [(fn [_props parent] (.setText parent "Hello, world"))]))
                  child))]))
       (.open child)
       child))))

(defmacro widget
  "Example macro to partially resugar the above example: Add a child widget to specified parent
   and run the init functions in its arglist.  Automates away the manual  `map` step in the desugared 'hello'.

   This could also be used to instantiate a custom SWT control that isn't automatically detected
   by Clojure Desktop Toolkit's init function generator macros."
  [clazz style-bits & initfns]
  `(fn [props# parent#]
     (let [child# (new ~clazz parent# ~style-bits)]
       (doall (map (fn [initfn#] (initfn# props# child#)) [~@initfns]))
       child#)))

(defn hello-desugared2
  "Use the `widget` macro to partially resugar the desugared 'hello world' application."
  []
  (application
   (widget Shell SWT/SHELL_TRIM
           (fn [_props parent] (.setText parent "Hello application"))
           (fn [_props parent] (.setLayout parent (FillLayout.)))
           (widget Label SWT/NONE
                   (fn [_props parent] (.setText parent "Hello, world")))
           (fn [_props parent] (.open parent)))))

(defn mount-new-top-level-shell
  "Mount a new top level shell inside the existing running application.  Illustrates the `ui` macro,
   that works like `do`, but ensuring that its body runs on the UI thread."
  []
  (ui
   (child-of
    @ui/display (atom {})
    (fn [props parent]
      (let [child (Shell. parent SWT/SHELL_TRIM)]
        (doall
         (map #(apply % props child [])
              [(fn [_props parent] (.setText parent "Hello application"))
               (fn [_props parent] (.setLayout parent (FillLayout.)))
               (fn [props parent]
                 (let [child (Label. parent SWT/NONE)]
                   (doall
                    (map #(apply % props child [])
                         [(fn [_props parent] (.setText parent "Hello, world"))]))))])))))))



(comment
  "Examples showing how to start an application from the REPL."

  (def app (future (hello)))

  (def app (future (hello-desugared2)))

  :eoc)