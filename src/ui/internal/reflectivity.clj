(remove-ns 'ui.internal.reflectivity)

(ns ui.internal.reflectivity
  (:require [clojure.string :as str]
            [clj-foundation.conversions :refer :all]
            [clj-foundation.data :refer [->kebab-case]]
            [clj-foundation.interop :refer [array]])
  (:import [java.lang.reflect Modifier Field]
           [clojure.lang Symbol]
           [org.reflections Reflections]
           [org.reflections.scanners SubTypesScanner]
           [org.eclipse.swt.custom SashFormLayout ScrolledCompositeLayout CTabFolderLayout]
           [org.eclipse.swt.widgets Shell Composite Widget Layout
            Tray TaskBar TaskItem ScrollBar Item Control]
           [org.eclipse.swt.opengl GLCanvas]))

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

(def swt-listeners (->> (.getSubTypesOf swt-index org.eclipse.swt.internal.SWTEventListener)
                      (filter #(.endsWith (.getSimpleName %) "Listener"))
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
                                               {:add-method (.getName m)
                                                :listener-type listener-type
                                                :listener-methods (get swt-listeners listener-type)}))))})))))

(def swt-event-methods (mapcat (fn [[_ events]] events) swt-listeners))


(defn- event-method->listener
  "Finds the listener information in swt-listeners corresponding to `event-method` (in camelCase)"
  [^String event-method]
  (first (filter
          (fn [[_ methods]]
            (some #(= event-method (.getName %)) methods))
          swt-listeners)))


(defn- listener-bodies-delegating [delegate-method-name methods]
  (map
   (fn [m]
     (let [name-symbol (symbol (.getName m))]
       (if (= delegate-method-name (.getName m))
         (list name-symbol ['this 'e] '(delegate props e))
         (list name-symbol ['this 'e]))))
   methods))


(defn init-for-event
  ""
  [^String delegate-method-name]
  (let [[listener-class
         methods] (event-method->listener delegate-method-name)

        listener-class-name (symbol (.getName listener-class))
        add-method (symbol (str ".add" (.getSimpleName listener-class)))
        method-bodies (listener-bodies-delegating delegate-method-name methods)]

    (list 'fn ['props 'parent]
          (list add-method 'parent
           `(reify ~listener-class-name
             ~@method-bodies)))))

(defn on* [^String event-method-name [props-name e-name] forms]
  (let [delegate-fn (symbol "delegate")
        initfn (init-for-event event-method-name)]
    `(let [~delegate-fn (fn [~props-name ~e-name] ~@forms)]
      ~initfn)))

(defn on-event-name-macro
  [event-method]
  (let [macro-name (symbol (str "on-" (-> event-method (.getName) ->kebab-case)))
        event-method-name (.getName event-method)]
    `(defmacro ~macro-name
       [[props# event#] & forms#]
       (let [delegate-name# (symbol "delegate")
             init-proxy# (init-for-event ~event-method-name)]
         `(letfn [(~delegate-name# [~props# ~event#] ~@forms#)]
            ~init-proxy#)))))


(defmacro swt-events []
  `(let []
     ~@(map on-event-name-macro swt-event-methods)))



(defn types-in-package [swt-package]
  (->> (Reflections. (array [Object]
                          (str "org.eclipse.swt." swt-package)
                          (SubTypesScanner. false)))
     (.getAllTypes)
     (seq)
     (sort)
     (map #(Class/forName %))))

(def ^:private swt-layoutdata (types-in-package "layout"))


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
