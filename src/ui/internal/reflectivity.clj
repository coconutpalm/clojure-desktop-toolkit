(remove-ns 'ui.internal.reflectivity)

(ns ui.internal.reflectivity
  (:require [ui.internal.SWT-deps :refer [swt-libs-loaded?]]
            [clojure.string :as str]
            [clojure.pprint :refer [pprint]]
            [righttypes.nothing :refer [something]]
            [righttypes.conversions :refer :all]
            [righttypes.util.names :refer [->kebab-case]]
            [righttypes.util.interop :refer [array]])
  (:import [java.lang.reflect Modifier Field]
           [clojure.lang Symbol]
           [org.reflections Reflections]
           [org.reflections.scanners SubTypesScanner]
           [org.eclipse.swt.custom SashFormLayout ScrolledCompositeLayout CTabFolderLayout]
           [org.eclipse.swt.widgets Shell Composite Widget Layout
            Tray TaskBar TaskItem ScrollBar Item Control]
           [org.eclipse.swt.opengl GLCanvas]))

swt-libs-loaded?

(def swt-index
  (-> (Reflections. (to-array [(SubTypesScanner.)]))))

(def swt-composites (->> (.getSubTypesOf swt-index Composite)
                         (seq)
                         (remove #{Shell GLCanvas})
                         (remove #(.endsWith (.getName %) "OleClientSite"))
                         (remove #(.endsWith (.getName %) "OleControlSite"))
                         (remove #(.endsWith (.getName %) "WebSite"))
                         (#(conj % Composite))))

(def swt-widgets (->> (.getSubTypesOf swt-index Widget)
                      (seq)
                      (remove #(.isAssignableFrom Composite %))
                      (remove #(.isAssignableFrom Item %))
                      (remove #(not (nil? (.getEnclosingClass %))))
                      (remove #{Control Tray TaskBar TaskItem ScrollBar})))

(def swt-items (->> (.getSubTypesOf swt-index Item)
                    (seq)
                    (remove #{TaskItem})
                    (remove #(not (nil? (.getEnclosingClass %))))
                    (sort-by #(.getSimpleName %))))

(def swt-layouts (->> (.getSubTypesOf swt-index Layout)
                      (seq)
                      (remove #{SashFormLayout ScrolledCompositeLayout CTabFolderLayout})))

(def swt-listeners (->> (.getSubTypesOf swt-index java.util.EventListener)
                        (filter #(.endsWith (.getSimpleName %) "Listener"))
                        (filter #(.contains (.getName %) "org.eclipse.swt."))
                        (filter #(> 0 (.indexOf (.getName %) "internal")))
                        (sort-by #(.getSimpleName %))
                        (map (fn [clazz] [clazz (->> (.getMethods clazz)
                                                     (remove #(.endsWith (str (.getDeclaringClass %)) "Object"))
                                                     (remove #(not= 0 (bit-and Modifier/STATIC (.getModifiers %)))))]))
                        (into {})))

;; TODO: Generate docstring for swt-events
(def widget-to-listener-methods
  (apply merge
         (->> (concat swt-composites swt-widgets swt-items)
              (map (fn [clazz] {clazz (->> (.getMethods clazz)
                                           (remove #(= "addListener" (.getName %)))
                                           (filter (fn [m] (let [name (.getName m)]
                                                             (and (.startsWith name "add")
                                                                  (.endsWith name "Listener")))))
                                           (map (fn [m]
                                                  (let [listener-type (first (.getParameterTypes m))]
                                                    [(.getName m) {:listener-class listener-type
                                                                   :add-method (symbol (str ".add" (.getSimpleName listener-type)))
                                                                   :listener-methods (get swt-listeners listener-type)}])))
                                           (into {}))})))))

(defn widget-event-info
  "Given a `widget-class` (as a java.lang.Class) and a `method-name` (as a string), returns a map
   with keys :listener-type and :listener-methods.  Returns nil if not found."
  [widget-class method-name]
  (get-in widget-to-listener-methods [widget-class method-name]))


(def swt-event-methods (mapcat (fn [[_ events]] events) swt-listeners))

(defn- event-method->possible-listeners
  "Finds possible listeners (in swt-listeners) corresponding to `event-method` (in camelCase, e.g.'modifyText')"
  [^String event-method]
  (filter
   (fn [[_ methods]]
     (some #(= event-method (.getName %)) methods))
   swt-listeners))


(defn matching-listener
  "A given add method may be defined on more than a single listener class.  This function searches
   the `parent` class's add methods to find the correct listener class to construct from all of the
   `possible-listeners`."
  [parent possible-add-method-names]
  (some something (map #(widget-event-info parent %) possible-add-method-names)))

(defn possible-add-methods-for-event
  [event-method-name]
  (->> (event-method->possible-listeners event-method-name)  ;; seq of [listener-class methods]
       (map (fn [[listener-class _]] (str "add" (.getSimpleName listener-class))))))


(comment "e.g.: Look up the info we need to create a listener for modifyText"
         (->> (possible-add-methods-for-event "modifyText")
              (matching-listener org.eclipse.swt.widgets.Text))

         (->> (possible-add-methods-for-event "menuDetected")
              (matching-listener org.eclipse.swt.widgets.TrayItem))

         (->> (possible-add-methods-for-event "shellClosed")
              (matching-listener org.eclipse.swt.widgets.Shell))

         (->> (possible-add-methods-for-event "modifyText")
              (matching-listener org.eclipse.swt.widgets.Text))

         (get widget-event-info "addShellListener")
         :eoc)

#_(defn listener-methods
  [event-method-to-define methods]
  (map #(let [method-name (.getName %)
              name-symbol (symbol method-name)]
          (if (= event-method-to-define method-name)
            (list name-symbol ['this 'e] (list 'delegate 'props 'e))
            (list name-symbol ['this 'e])))
       methods))

(defn method-bodies [event-method-to-define methods]
  (map (fn [m]
         (let [method-name (.getName m)
               name-symbol (symbol method-name)]
           (if (= event-method-to-define method-name)
             (list name-symbol ['this 'e] (list 'delegate 'props 'e))
             (list name-symbol ['this 'e]))))
       methods))

(defmacro listener
  [listener-class method-bodies]
  (let [listener-class-name (if (class? listener-class)
                              (symbol (.getName listener-class))
                              listener-class)]
    `(reify ~listener-class-name
       ~@method-bodies)))

(defn build-listener [event-method-to-define listener-class methods]
  (let [method-bodies (method-bodies event-method-to-define methods)]
    (macroexpand `(listener ~listener-class ~method-bodies))))

(comment
  (let [methods (:listener-methods (get-in widget-to-listener-methods [org.eclipse.swt.widgets.Text "addSelectionListener"]))]
    (method-bodies "widgetSelected" methods))

  (let [methods (:listener-methods (get-in widget-to-listener-methods [org.eclipse.swt.widgets.Text "addSelectionListener"]))
        method-bodies (method-bodies "widgetSelected" methods)]
    (macroexpand `(listener org.eclipse.swt.events.SelectionListener ~method-bodies)))

  (let [methods (:listener-methods (get-in widget-to-listener-methods [org.eclipse.swt.widgets.Text "addSelectionListener"]))]
    (macroexpand (build-listener "widgetSelected" org.eclipse.swt.events.SelectionListener methods)))

  :eoc)

(defn build-listener-proxy
  [parent event-method-to-define listener-class methods]
  (let [l (build-listener event-method-to-define listener-class methods)
        add-listener (symbol (str ".add" (.getSimpleName listener-class)))]
    `(~add-listener ~parent ~l)))

(defn init-for-event
  [^String event-method-to-define]
  (let [possible-add-methods (possible-add-methods-for-event event-method-to-define)]
    `(fn [props# parent#]
       (let [matching-listener# (matching-listener parent# ~possible-add-methods)
             listener-class# (:listener-class matching-listener#)
             listener-methods# (:listener-methods matching-listener#)]
         (build-listener-proxy parent# ~event-method-to-define listener-class# listener-methods#)))))

(comment
  (event-method->possible-listeners "modifyText")
  (init-for-event "modifyText")
  (init-for-event "widgetSelected")
  (macroexpand '(init-for-event "widgetSelected"))
  (macroexpand '(init-for-event "modifyText"))
  :eoc)

(defn delegator [forms inits]
  `(let ~['delegate (list 'clojure.core/fn ['props 'event] `(do ~@forms))]
    ~@inits))

(delegator [`(println "on-control-moved")] (init-for-event "widgetSelected"))

(defn on-event-name-macro
  [event-method]
  (let [macro-name (symbol (str "on-" (-> event-method (.getName) ->kebab-case)))
        event-method-name (.getName event-method)
        init-fn (init-for-event event-method-name)
        delegate (delegator 'forms init-fn)]
    `(defmacro ~macro-name
       [[props event] & forms]
       ~delegate)))


(defn swt-events []
  (->> swt-event-methods
       (map on-event-name-macro)
       (map eval)))

(comment
  (first swt-event-methods)
  (on-event-name-macro (first swt-event-methods))
  (eval (on-event-name-macro (first swt-event-methods)))

  (macroexpand (on-control-moved [props event] (println "on-control-moved")))

  (macroexpand '(swt-events))

  (swt-events)


  :eoc)

(defn types-in-package
  "Returns a seq of Class objects for all classes in the given package."
  [package]
  (->> (Reflections. (array [Object]
                            package
                            (SubTypesScanner. false)))
       (.getAllTypes)
       (seq)
       (sort)
       (map #(Class/forName %))))

(defn types-in-swt-package
  "Returns a seq of Class objects for all classes in the given SWT package."
  [swt-package]
  (types-in-package (str "org.eclipse.swt." swt-package)))

(def ^:private swt-layoutdata (types-in-swt-package "layout"))


;; =====================================================================================
;; Generate online docs from class metadata

(defn layoutdata-by-layout []
  (letfn [(layout-type [clazz]
            (-> (.getSimpleName clazz)
                ->kebab-case
                (.split "\\-")
                first))]
    (reduce (fn [cur layout-class]
              (let [key (layout-type layout-class)
                    layoutdata (filter #(= (layout-type %) key) swt-layoutdata)]
                (conj cur [layout-class layoutdata])))
            {}
            swt-layouts)))

(defn fn-names<- [classes]
  (letfn [(fn-name<- [clazz]
            (-> (.getSimpleName clazz) ->kebab-case))]
    (sort-by first (map (fn [c] [(fn-name<- c) c]) classes))))


(defn- extract-java-meta [xs]
  (->> xs
       (map (fn [x] [(symbol (str (.getName x)
                                  (if (instance? Field x)
                                    ""
                                    (str "("
                                         (str/join ", " (map #(symbol (.getSimpleName %)) (.getParameterTypes x)))
                                         ")"))))
                     {:type (if (instance? Field x) (.getType x) (.getReturnType x))
                      :declaring-class (.getDeclaringClass x)}]))
       (sort-by first)))


(defn fields [^Class clazz]
  (->> (.getFields clazz)
       (filter (fn [field] (not= 0 (bit-and (.getModifiers field) Modifier/PUBLIC))))
       (extract-java-meta)))

(defn setters [^Class clazz]
  (->> (.getMethods clazz)
       (filter (fn [method] (and (not= 0 (bit-and (.getModifiers method) Modifier/PUBLIC))
                                 (.startsWith (.getName method) "set"))))
       (extract-java-meta)))

(defn non-prop-methods [^Class clazz]
  (->> (.getMethods clazz)
       (filter (fn [method] (and (not= 0 (bit-and (.getModifiers method) Modifier/PUBLIC))
                                 (not= Object (.getDeclaringClass method))
                                 (not (.startsWith (.getName method) "get"))
                                 (not (.startsWith (.getName method) "set")))))
       (extract-java-meta)))

(defn sorted-publics
  "Like `ns-publics` but returns the results sorted by symbol name."
  [ns]
  (if (string? ns)
    (sorted-publics (symbol ns))
    (->> (ns-publics ns) vec (sort-by first))))
