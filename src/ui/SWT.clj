(remove-ns 'ui.SWT)

(ns ui.SWT
  (:refer-clojure :exclude [list])
  (:require [ui.internal.SWT-deps]
            [ui.internal.docs :as docs]
            [ui.internal.reflectivity :as meta]
            [ui.inits :as i]
            [ui.events :as e]
            [clojure.pprint :refer [pprint]]
            [righttypes.util.names :refer [->camelCase ->PascalCase]]
            [righttypes.nothing :refer [nothing something nothing->identity]])
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
  "Names `parent` control using `kw` inside the props.

   (swap! props assoc kw parent)"
  [kw]
  (fn [props parent]
    (swap! props assoc kw parent)))

(defn reset-prop!
  "Define a prop entry; assigns `v` to the `k` entry in the props atom/map."
  [k v]
  (fn [props _]
    (swap! props assoc k v)))

(defn update-in-prop!
  "Like `update-in`, but swaps the result of invoking `f` on the `ks` path in the props atom/map."
  [ks f]
  (fn [props _]
    (swap! props update-in ks f)))

(defn assoc-in-prop!
  "Like `assoc-in`, but updates the `ks` path in the props atom/map with v."
  [ks v]
  (fn [props _]
    (swap! props assoc-in ks v)))

(defmacro with-set-parent-property!
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
  "org.eclipse.swt.widgets.Shell"
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
  "Return the props atom associated with each open shell."
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
;; `result` and `exception` atoms respectively.  Construct using `runnable-fn`

(defrecord RunnableFn [f result exception]
  IFn Runnable
  (run [this] (.invoke this))

  (invoke [_]
    (try
      (reset! result (f))
      @result
      (catch Throwable t
        (reset! exception t)
        t))))

(defn runnable-fn
  "Returns a RunnableFn w wrapping f.  Usage: (w), (:result w), or (:exception w)"
  [f]
  (RunnableFn. f (atom nil) (atom nil)))


;; =====================================================================================
;; Event processing must happen on the UI thread.  Some helpers...

(defn ui-thread?
  "Nullary form: Returns true if the current thread is the UI thread and false otherwise.
   Unary form: Returns true if the specified thread is the UI thread and false otherwise."
  ([^Thread t]
   (let [dt (and (something @display)
                 (.getThread @display))]
     (= t dt)))
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
  [& more]
  (cond
    (coll? (first more)) `(with-ui* (fn [] ~@more))
    :else                `(with-ui* (fn [] (~@more)))))


(defn sync-exec!
  "Synonym for `Display.getDefault().syncExec( () -> f() );` except that the result of executing
  `f()` is captured/returned and any uncaught exception thrown by `f` is rethrown."
  [f]
  (with-ui* f))

(defn async-exec!
  "Synonym for Display.getDefault().asyncExec( () -> f() ); "
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
  "Run the event loop while the specified `init` shell-or-fn is not disposed."
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


;; =====================================================================================
;; Event handling helper

(defn- reify-listener [listener methods event-method-to-define]
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


;; =====================================================================================
;; An example app to test/prove the library's features


(defonce state (atom nil))

#_{:clj-kondo/ignore [:unresolved-symbol]}
(defn example-app []
  (application ; The application hosts the display object and runs the event loop

   (tray-item ; Define a system tray item so we can minimize to the tray
    (on e/menu-detected [props parent event] (.setVisible (:ui/tray-menu @props) true))
    (on e/widget-selected [props parent event] (let [shell (:ui/shell @props)]
                                                 (.setVisible shell (not (.isVisible shell))))))

   (shell
    SWT/SHELL_TRIM (id! :ui/shell)
    "Browser"
    :layout (FillLayout.)

    (on e/shell-closed [props parent event] (when-not (:closing @props)
                                              (set! (. event doit) false)
                                              (.setVisible parent false)))

    (sash-form
     SWT/HORIZONTAL

     (sash-form
      SWT/VERTICAL
      ;; sudo apt install libwebkit2gtk-4.0-37 on ubuntu if needed
      (browser SWT/WEBKIT (id! :ui/editor)
               :javascript-enabled true
               :url "https://www.duckduckgo.com")

      (text (| SWT/MULTI SWT/V_SCROLL) (id! :ui/textpane)
            :text "This is the notes pane..."
            (on e/modify-text [props parent event] (println (.getText parent))))

      :weights [80 20])

     (browser SWT/WEBKIT (id! :ui/editor)
              :javascript-enabled true
              :url (-> (swtdoc :swt :program 'Program) :result :eclipsedoc))

     :weights [30 70])

    (menu SWT/POP_UP (id! :ui/tray-menu)
          (menu-item SWT/PUSH "&Quit"
                     (on e/widget-selected [parent props event] (swap! props #(assoc-in % [:closing] true))
                         (.close (:ui/shell @props))))))

   (defmain [props parent]
     ;; Bind data layer to UI or...
     (reset! state props)
     (println (str (:ui/editor @props) " " parent)))))


(defn hello []
  (application
   (shell SWT/SHELL_TRIM
          "Hello application"

          :layout (FillLayout.)

          (label "Hello, world"))))

(defn hello-desugared []
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
  "Add a child widget to specified parent and run the functions in its arglist"
  [clazz style-bits & initfns]
  `(fn [props# parent#]
     (let [child# (new ~clazz parent# ~style-bits)]
       (doall (map (fn [initfn#] (initfn# props# child#)) [~@initfns]))
       child#)))

(defn hello-desugared2 []
  (application
   (widget Shell SWT/SHELL_TRIM
           (fn [_props parent] (.setText parent "Hello application"))
           (fn [_props parent] (.setLayout parent (FillLayout.)))
           (widget Label SWT/NONE
                   (fn [_props parent] (.setText parent "Hello, world")))
           (fn [_props parent] (.open parent)))))


(defn hd2 []
  (ui
   (child-of
    @display (atom {})
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
  (def app (future (example-app)))

  (def app (future (hello)))

  (def app (future (hello-desugared2)))

  (defn minimize-to-tray []
    (application
     (tray-item
      ;; System tray right-click handler
      (on e/menu-detected [props parent event] (.setVisible (:ui/tray-menu @props) true))

      ;; System tray click handler toggles visibility
      (on e/widget-selected [props parent event] (let [shell (:ui/shell @props)]
                                                   (.setVisible shell (not (.isVisible shell))))))

     (shell
      SWT/SHELL_TRIM (id! :ui/shell)
      :layout (FillLayout.)
      "Close minimizes to Tray"

      (label SWT/WRAP "This program minimizes to the system tray and remains running when its shell is closed.")

      (on e/shell-closed [props parent event] (when-not (:closing @props)
                                                (set! (. event doit) false)
                                                (.setVisible parent false)))

      (menu SWT/POP_UP (id! :ui/tray-menu)
            (menu-item SWT/PUSH "&Quit"
                       (on e/widget-selected [parent props event] (swap! props assoc :closing true)
                           (.close (:ui/shell @props))))))))

  (def app (future (minimize-to-tray)))


  {:app app}
  (:editor @state)
  {:state @state}
  {:display @display}

  (ui
   (child-of @display (atom {})
             (shell "Browser 2" (id! :ui/shell)
                    (with-set-parent-property! :layout (FillLayout.)
                      :margin-height 10
                      :margin-width 10)
                    (browser SWT/WEBKIT (id! :ui/editor)
                             :javascript-enabled true
                             :url "https://www.google.com"))))


  (ui
   (child-of @display (atom {})
             (shell "Text editor" (id! :ui/textedit)
                    (with-set-parent-property! :layout (FillLayout.)
                      :margin-height 10
                      :margin-width 10)
                    (text (| SWT/MULTI SWT/V_SCROLL) (id! :ui/textedit)
                          (on e/modify-text [parent props event] (println (.getText (:ui/textedit @props))))))))

  (reset! state {})

  (ui
   (root-props))

  (ui (.dispose @display))

  :eoc)
