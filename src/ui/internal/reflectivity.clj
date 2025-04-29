(remove-ns 'ui.internal.reflectivity)

(ns ui.internal.reflectivity
  (:require [ui.internal.SWT-deps :refer [swt-libs-loaded?]]
            [clojure.string :as str]
            [clojure.pprint :refer [pprint]]
            [righttypes.nothing :refer [something]]
            [righttypes.util.lets :refer [let-map]]
            [righttypes.conversions :refer :all]
            [righttypes.util.names :refer [->kebab-case ->camelCase]]
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
                         #_(remove #{Shell GLCanvas})
                         (remove #{GLCanvas})
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

(defn- methods->event-names [ms]
  (->> ms
       (map #(.getName %))
       (map ->kebab-case)
       (map keyword)
       (sort)))

(def listener-methods (doall
                    (->> swt-listeners
                         (map (fn [[c ms]] [c (methods->event-names ms)]))
                         (sort-by #(.getName (first %)))
                         (into {}))))

(def swt-event-methods (methods->event-names
                        (mapcat (fn [[_ events]] events) swt-listeners)))


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


(defn event-method->possible-listeners
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

(defn possible-listeners
  [event-method-name]
  (->> (event-method->possible-listeners event-method-name)  ;; seq of [listener-class methods]
       (map (fn [[listener-class _]] (str "add" (.getSimpleName listener-class))))))


(comment "e.g.: Look up the info we need to create a listener for modifyText"
         (event-method->possible-listeners "modifyText")

         (->> (possible-listeners "modifyText")
              (matching-listener org.eclipse.swt.widgets.Text))

         (->> (possible-listeners "menuDetected")
              (matching-listener org.eclipse.swt.widgets.TrayItem))

         (->> (possible-listeners "shellClosed")
              (matching-listener org.eclipse.swt.widgets.Shell))

         (->> (possible-listeners "widgetSelected")
              (matching-listener org.eclipse.swt.widgets.TrayItem))

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
