(remove-ns 'ui.internal.reflectivity)

(ns ui.internal.reflectivity
  (:require [ui.internal.SWT-deps :refer [swt-libs-loaded?]]
            [clojure.string :as str]
            [righttypes.util.lets :refer [let-map]]
            [righttypes.nothing :refer [translate-nothingness]]
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


(defn- listener-bodies-delegating [delegate-method-name methods]
  (map
   (fn [m]
     (let [name-symbol (symbol (.getName m))]
       (if (= delegate-method-name (.getName m))
         (list name-symbol ['this 'e] '(delegate props e))
         (list name-symbol ['this 'e]))))
   methods))

(defn matching-listener
  "A given add method may be defined on more than a single listener class.  This function searches
   the `parent` class's add methods to find the correct listener class to construct from all of the
   `possible-listeners`."
  [parent possible-add-method-names]

  (translate-nothingness

   (loop [add-method-names possible-add-method-names]
     (when-not (nil? add-method-names)
       (let [maybe-parent-listener (widget-event-info parent (first add-method-names))]
         (if (nil? maybe-parent-listener)
           (recur (rest add-method-names))
           maybe-parent-listener))))

   #(throw (ex-info "No matching listener in parent for possible listeners."
                    {:parent parent
                     :parent-listeners (get parent widget-to-listener-methods)
                     :possible-listener-classes possible-add-method-names}))))

(defn possible-listeners-for-event
  [event-method-name]
  (->> (event-method->possible-listeners event-method-name)  ;; seq of [listener-class methods]
       (map (fn [[listener-class methods]] 
              #_{:clj-kondo/ignore [:unused-binding]}
              (let [handler-info (let-map [listener-class listener-class
                                           listener-class-name (.getName listener-class)
                                           add-method-name (str "add" (.getSimpleName listener-class))
                                           add-method-call (symbol (str "." add-method-name)) 
                                           add-listener-fn (list 'fn ['parent]
                                                                 (list add-method-call 'parent
                                                                       `(reify ~listener-class
                                                                          ~@(listener-bodies-delegating event-method-name methods))))])]
                [(:listener-class handler-info) handler-info])))
       (into {})))

(defn init-for-event
  [^String delegate-method-name]
  (list 'fn ['props 'parent]
        (list 'let ['possible-listeners (list 'ui.internal.reflectivity/possible-listeners-for-event delegate-method-name)
                    'matching-listener (list 'ui.internal.reflectivity/matching-listener 'parent (list 'map :add-method-name 'possible-listeners))
                    'add-event-listener (list '-> 'possible-listeners (list :listener-class 'matching-listener) :add-listener-fn)]
          (list 'add-event-listener 'parent))))

(defn on-event-name-macro
  [event-method]
  (let [macro-name (symbol (str "on-" (-> event-method (.getName) ->kebab-case)))
        event-method-name (.getName event-method)
        add-handler-sourcecode (init-for-event event-method-name)]
    `(defmacro ~macro-name
       [[props# event#] & forms#]
       (let [delegate-name# (symbol "delegate")
             add-event-handler# ~add-handler-sourcecode]
         `(letfn [(~delegate-name# [~props# ~event#] ~@forms#)]
            ~add-event-handler#)))))


(defmacro swt-events []
  `(let []
     ~@(map on-event-name-macro swt-event-methods)))

(comment
  (first swt-event-methods)
  (on-event-name-macro (first swt-event-methods))

  (do
    (defmacro first-event []
      `(let []
         ~(on-event-name-macro (first swt-event-methods))))
    (first-event))

  (event-method->possible-listeners "modifyText")
  (init-for-event "modifyText")
  (init-for-event "widgetSelected")
  (init-for-event "changed")

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
