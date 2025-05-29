(ns ui.SWT
  (:refer-clojure :exclude [list])
  (:require [ui.internal.SWT-deps]
            [ui.internal.docs :as docs]
            [ui.internal.reflectivity :as meta]
            [ui.inits :as i]
            [ui.events :as e]
            [clojure.pprint :refer [pprint]]
            [righttypes.util.names :refer [->camelCase ->PascalCase]]
            [righttypes.nothing :refer [nothing something nothing->identity NO-RESULT-ERROR]])
  (:import [clojure.lang IFn]
           [org.eclipse.swt SWT]
           [org.eclipse.swt.graphics GC Image]
           [org.eclipse.swt.layout FillLayout]
           [org.eclipse.swt.events TypedEvent]
           [org.eclipse.swt.widgets Display Shell TrayItem Listener Label]))

(defn ui-scale!
  "Scale the user interface by `factor` on GTK-based window systems.  Must be called before
  the Display is initialized."
  [factor]
  (let [multiplier (str factor)]
    (System/setProperty "sun.java2d.uiScale" multiplier)
    (System/setProperty "glass.gtk.uiScale" multiplier)))

(def display
  "The default SWT Display object or `nothing`"
  (atom nothing))

(defn with-gc-on
  "Create a graphics context on `drawable`, run `f`, and ensure the `gc` is disposed."
  [drawable f]
  (let [gc (GC. drawable)]
    (try
      (f gc)
      (finally (.dispose gc)))))

(defmacro doto-gc-on
  "Like with-gc-on, but executes `forms` inside a `doto` block on the `gc`."
  [drawable & forms]
  `(with-gc-on ~drawable
     (fn [gc#]
       (doto gc# ~@forms))))


;; =====================================================================================
;; Aaaaaand, here's the API!
;; =====================================================================================

(i/define-inits meta/swt-composites)
(i/define-inits meta/swt-widgets)
(i/define-inits meta/swt-items)


;; =====================================================================================
;; Props manipulation

(defmacro definit
  "Defines a special-purpose initialization node within a user interface tree.

   Syntactic sugar for (initfn (fn [props parent] forms))."
  [[props parent] & forms]
  `(fn [~props ~parent] ~@forms))


(defmacro defmain
  "Defines an init function node in a user interface tree.  By convention, this is used after the
   entire user interface is constructed to bind data into the user interface elements.

   Functionally identical to `definit`, but aids in program readability.

   Syntactic sugar for (initfn (fn [props parent] forms))"
  [[props parent] & forms]
  `(fn [~props ~parent] ~@forms))


(defn id!
  "Init function factory that names `parent` control using `kw` inside the props.

   (swap! props assoc kw parent)"
  [kw]
  (fn [props parent]
    (swap! props assoc kw parent)))

(defn reset-prop!
  "Init function factory that defines a prop entry; assigns `v` to the `k` entry in the props atom/map."
  [k v]
  (fn [props _]
    (swap! props assoc k v)))

(defn update-in-prop!
  "Init function factory that is like `update-in`, but swaps the result of invoking `f` on the `ks` path in the props atom/map."
  [ks f]
  (fn [props _]
    (swap! props update-in ks f)))

(defn assoc-in-prop!
  "Like `assoc-in`, but updates the `ks` path in the props atom/map with v."
  [ks v]
  (fn [props _]
    (swap! props assoc-in ks v)))

(defmacro with-property
  "Set `new-value` on `property` of parent and run `initfns` on `new-value`.

   An alternative to keyword syntax for setting property values where the value
   to set into the property has its own properties that need to be set."
  [property new-value & initfns]
  (let [inits (vec initfns)
        set-method (symbol (str ".set" (->PascalCase property)))]
    `(fn [props# parent#]
       (let [child# ~new-value
             inits# (i/args->inits ~inits)]
         (i/run-inits props# child# inits#)
         (~set-method parent# child#)
         child#))))

;; =====================================================================================
;; Hand-coded APIs

(defn |
  "Like `bit-or`, but for Integers.  Intended for combining SWT style bits for the \"style\"
  widget constructor parameter."
  [& styles]
  (int (apply bit-or styles)))

(defn tray-item
  "Define a system tray item.  Must be a child of the application node.  The :image
  and :highlight-image should be 16x16 SWT Image objects.  `on-widget-selected` is
  fired on clicks and `on-menu-detected` to request the right-click menu be displayed."
  [& inits]
  (let [[style
         inits] (i/extract-style-from-args inits)
        style   (nothing->identity SWT/NULL style)]
    (fn [props display]
      (when-let [tray (.getSystemTray display)]
        (let [tray-item (TrayItem. tray style)
              image (Image. display 16 16)
              highlight-image (Image. display 16 16)]

          ;; Make some default/stub images
          (doto-gc-on image
                      (. setBackground (.getSystemColor display SWT/COLOR_DARK_BLUE))
                      (. fillRectangle (.getBounds image)))

          (doto-gc-on highlight-image
                      (. setBackground (.getSystemColor display SWT/COLOR_BLUE))
                      (. fillRectangle (.getBounds image)))

          (doto tray-item
            (. setImage image)
            (. setHighlightImage highlight-image))

          (i/run-inits props tray-item (or inits []))

          (.addListener display SWT/Dispose (reify Listener
                                              (handleEvent [_this _event] (.dispose tray-item))))

          tray-item)))))


(defn shell
  "Define an org.eclipse.swt.widgets.Shell and open it.  Accepts additional init functions to
   execute against the new Shell.  Doesn't run an event loop against the new Shell."
  [& inits]
  (let [[style
         inits] (i/extract-style-from-args inits)
        style   (nothing->identity SWT/SHELL_TRIM style)
        style   (if (= style SWT/DEFAULT)
                  SWT/SHELL_TRIM
                  style)
        init    (i/widget* Shell style (or inits []))]

    (fn [props disp]
      (let [sh (init props disp)]
        (.open sh)
        sh))))


(defn root-props
  "Return the props atom associated with each non-disposed shell."
  []
  (->> (Display/getDefault)
       (.getShells)
       (mapcat #((when-not (.isDisposed %) [(.getData %)])))))

(defn defchildren
  "Define children to add to specified parent"
  [& args]
  (let [inits (i/args->inits args)]
    (fn [props parent]
      (i/run-inits props parent inits))))


(defmacro widget
  "Construct a widget given its Java Class object, its style bits and optional init functions.
   The purpose is to support arbitrary SWT customn widgets that follow SWT's naming conventions
   that aren't already mapped to the Clojure Desktop Toolkit API.

   Provisional API.  This API may change or move."
  [clazz style-bits & initfns]
  `(fn [props# parent#]
     (let [child# (new ~clazz parent# ~style-bits)]
       (doall (map (fn [initfn#] (initfn# props# child#)) [~@initfns]))
       child#)))


;; =====================================================================================
;; Specialized online docs

(require '[ui.gridlayout :as layout])

(def ^:private documentation
  {:package {:ui.SWT (meta/sorted-publics 'ui.SWT)
             :ui.gridlayout (meta/sorted-publics 'ui.gridlayout)}
   :swt {:SWT {SWT (meta/fields SWT)}
         :composites (meta/fn-names<- (conj meta/swt-composites Shell))
         :widgets (meta/fn-names<- meta/swt-widgets)
         :items (meta/fn-names<- meta/swt-items)
         :events (->> (.getSubTypesOf meta/swt-index TypedEvent) (seq) (sort-by #(.getSimpleName %)))
         :listeners meta/listener-methods
         :graphics (meta/types-in-swt-package "graphics")
         :program (meta/types-in-swt-package "program")
         :layout-managers (meta/layoutdata-by-layout)}})

(defn swtdoc
  "Print documentation on the SWT library support."
  [& query]
  (docs/swtdoc* [] documentation query))


;; =====================================================================================
;; A wrapped nullary function that captures its result or thrown exception in the
;; `result` and `exception` atoms respectively.  Construct using `runnable-fn` factory.

(defrecord RunnableFn [f result exception]
  IFn Runnable
  (run [this] (.invoke this))

  (invoke [_]
    (try
      (reset! result (f))
      @result
      (catch Throwable t
        (reset! result NO-RESULT-ERROR)
        (reset! exception t)
        t))))

(defn runnable-fn
  "Returns a RunnableFn `w` wrapping nullary function `f`.

   Usage: `(w)`, which returns the result or the thrown exception, if any.  Also captures
   the result of a successful run or exception so you can `(:result w)`, or `(:exception w)`
   to retrieve these results.  On exception, `(:result w)` is set to `righttypes.nothing/NO-RESULT-ERROR`"
  [f]
  (RunnableFn. f (atom nil) (atom nil)))


;; =====================================================================================
;; Event processing must happen on the UI thread.  Some helpers...

(defn ui-thread?
  "Nullary form: Returns true if the current thread is the UI thread and false otherwise.
   Unary form: Returns true if the specified thread is the UI thread and false otherwise."
  ([^Thread t]
   (let [ui-thread (and (something @display)
                        (.getThread @display))]
     (= t ui-thread)))
  ([]
   (ui-thread? (Thread/currentThread))))


(defn with-ui*
  "Implementation detail: Use `sync-exec!` or `ui` instead.  Public because it's called
  from the `ui` macro."
  [f]
  (if (ui-thread?)
    (f)
    (let [r (runnable-fn f)]
      (.syncExec (Display/getDefault) r)

      (when @(:exception r)
        (throw @(:exception r)))
      @(:result r))))

(defmacro ui
  "Run the specified code on the UI thread and return its results or rethrow exceptions."
  {:clj-kondo/lint-as 'clojure.core/do
   :clj-kondo/ignore [:redundant-do]}
  [& more]
  (cond
    (coll? (first more)) `(with-ui* (fn [] ~@more))
    :else                `(with-ui* (fn [] (~@more)))))


(defn sync-exec!
  "Synonym for `Display.getDefault().syncExec( () -> f() );` except that the result of executing
  `f()` is captured/returned and any uncaught exception thrown by `f` is rethrown.  This form
   blocks the current thread until `f` is done executing.

   Use with care, preferably only for reading user interface state.  If you mutate user
   interface state, your code will execute before pending events have completed processing.  This
   can cause unpredictable and platform-specific behavior.

   The best practice is to only mutate user interface state from within `async-exec!`."
  [f]
  (with-ui* f))

(defn async-exec!
  "Synonym for Display.getDefault().asyncExec( () -> f() ).  This form doesn't block the calling
   thread, but adds `f` to the end of the event queue.  This is useful for executing code after
   all pending events have completed processing.

   Use this form when you need to mutate user interface state."
  [f]
  (.asyncExec @display (runnable-fn f)))

(defn- process-event
  "Process a single event iff all shells aren't disposed.  Returns a pair
  [shells-disposed? event-queue-not-empty?]"
  [d]
  (let [disposed (empty? (.getShells d))]
    [disposed
     (and (not disposed)
          (.readAndDispatch d))]))

(defn process-pending-events!
  "Process events until the event queue is exhausted."
  ([]
   (process-pending-events! @display))
  ([display]
   (while (.readAndDispatch display))))


;; =====================================================================================
;; The main application and her children

(defn child-of
  "Mount the child specified by child-init-fn inside parent passing initial-props-value inside the props atom.
  Returns a map containing the :child and resulting :props"
  ([parent props child-init-fn]
   (reset! display (Display/getDefault))
   {:child (child-init-fn props (if parent parent @display))
    :props @props})

  ([[parent props] child-init-fn]
   (child-of parent props child-init-fn)))


(defn application
  "The application creates/hosts the display object and runs the UI event loop until all SWT `Shell` (window) objects
   are closed or `(.dispose s)`'d.

   When `application` runs its init functions, the `Display` object is passed as the `parent`.  The thread
   that invokes `application` becomes the user interface thread (or `Display` thread) for the entire application.

   When invoking this function from a REPL, be sure to invoke it from a separate thread, otherwise the event loop will
   block the REPL."
  [& more]
  (let [props (atom {})
        disp (Display/getDefault)
        inits (i/args->inits more)]

    (try
      (reset! display disp)

      (let [init-results (i/run-inits props disp inits)
            maybe-shell  (first (filter #(instance? Shell %) init-results))
            s            (if (instance? Shell maybe-shell)
                           maybe-shell
                           (throw (ex-info "Couldn't make shell from args" {:args more})))]

        (loop [[disposed busy] (process-event disp)]
          (when (not busy)
            (.sleep disp))
          (when (not disposed)
            (recur (process-event disp)))))

      (process-pending-events!)

      (catch Throwable t
        t)
      (finally
        (.dispose disp)))))

(defn kill-application!
  "Semi-violently and immediately kill the application by disposing its display object."
  []
  (ui (.dispose @display))
  (reset! display nil))


;; =====================================================================================
;; Event handling

(defn- reify-listener
  "A helper function for the `on` macro that generates code to reify a specified `Listener` class."
  [listener methods event-method-to-define]
  (let [l (symbol (.getName listener))
        ms (map (fn [m]
                  (let [method-name (.getName m)
                        name-symbol (symbol method-name)]
                    (if (= event-method-to-define method-name)
                      (clojure.core/list name-symbol ['this 'e] (clojure.core/list 'delegate 'e))
                      (clojure.core/list name-symbol ['this 'e]))))
                methods)
        add-listener (symbol (str ".add" (.getSimpleName listener)))]
    `(fn [parent#] (~add-listener parent# (reify ~l ~@ms)))))


(comment
  (let [event-method "modifyText"]
    (->> (meta/event-method->possible-listeners event-method)
         (map (fn [[l methods]] [l (reify-listener l methods event-method)]))
         (into {})))

  (let [event-method "widgetSelected"]
    (->> (meta/event-method->possible-listeners event-method)
         (map (fn [[l methods]] [l (reify-listener l methods event-method)]))))

  :eoc)


(defmacro on
  "Register an event handler for the specified `event-name`, `handler-parameters`, and `body`.

   Synopsis:
   (on :widget-selected [props parent event] (println \"I was selected!\"))

   `event-name` is a keyword named after a listener method, but in kebab-case.
   `handler-parameters` is the argument list naming your props, parent, and event local bindings.
   `body` is the code to execute when the event is triggered."
  {:clj-kondo/ignore [:unresolved-symbol]}
  [event-name handler-parameters & body]

  (when (not= 3 (count handler-parameters))
    (throw (ex-info "`handler-parameters` must have 3 elements designating the [props parent event] names."
                    {:event-name event-name
                     :handler-parameters handler-parameters
                     :meta (meta &form)})))

  (let [event-method (->camelCase event-name)
        reify-fns (->> (meta/event-method->possible-listeners event-method)
                       (map (fn [[listener methods]] [listener (reify-listener listener methods event-method)]))
                       (into {}))

        delegate-fn 'delegate]
    `(fn [props# parent#]
       (let [parent-class# (class parent#)
             listener-class# (->> (meta/possible-listeners ~event-method)
                                  (meta/matching-listener parent-class#)
                                  :listener-class)

             effect# (fn ~handler-parameters ~@body)
             ~delegate-fn (fn [e#] (effect# props# parent# e#))]

         (let [reify-fns# ~reify-fns
               reify-fn# (get reify-fns# listener-class#)]
           (reify-fn# parent#))))))

(comment
  (macroexpand '(on :shell-closed [props event] (println event)))

  (let [f (on e/shell-closed [props parent event] (println event))]
    (f (atom {}) (Shell.)))

  (let [f (on e/shell-closed [props parent event] (when-not (:closing @props)
                                                    (set! (. event doit) false)
                                                    (.setVisible parent false)))]
    (f (atom {}) (Shell.)))

  :eoc)


(comment

  {:display @display}

  (ui
   (root-props))

  :eoc
  )
