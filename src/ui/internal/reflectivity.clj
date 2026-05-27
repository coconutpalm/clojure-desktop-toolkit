(remove-ns 'ui.internal.reflectivity)

(ns ui.internal.reflectivity
  (:require [ui.internal.SWT-deps :as swt-deps :refer [swt-libs-loaded?]]
            [clojure.string :as str]
            [clojure.pprint :refer [pprint]]
            [righttypes.nothing :refer [something]]
            [righttypes.util.lets :refer [let-map]]
            [righttypes.conversions :refer :all]
            [righttypes.util.names :refer [->kebab-case ->camelCase]]
            [righttypes.util.interop :refer [array]])
  (:import [java.io File]
           [java.lang.reflect Modifier Field]
           [clojure.lang Symbol]
           [org.reflections Reflections]
           [org.reflections.scanners SubTypesScanner]
           [org.eclipse.swt.custom SashFormLayout ScrolledCompositeLayout CTabFolderLayout]
           [org.eclipse.swt.widgets Shell Composite Widget Layout
            Tray TaskBar TaskItem ScrollBar Item Control]
           [org.eclipse.swt.opengl GLCanvas]))

swt-libs-loaded?

(defn- classpath-urls
  "Return a vector of URLs covering every entry on the JVM's classpath,
   plus the runtime-extracted SWT jar (which is added to the classloader
   by `ui.internal.SWT-deps` via pomegranate, not to `java.class.path`).
   We pass these to `Reflections` explicitly because the library's
   default URL discovery (`ClasspathHelper.forJavaClassPath`) depends
   on `javax.servlet.ServletContext` on some classpaths, which makes
   it fail under modern -M/-X launches that don't include the Servlet
   API. Passing URLs explicitly is both portable and reproducible.

   The SWT jar URL matters: without it, Reflections can't trace
   `PShelf extends Canvas extends Composite` (Canvas and Composite live
   in the runtime-extracted swt.jar, not in `java.class.path`), so
   transitive subtype enumeration misses most bundled Nebula widgets."
  []
  (let [from-cp (->> (str/split (System/getProperty "java.class.path") (re-pattern File/pathSeparator))
                     (remove str/blank?)
                     (mapv #(-> ^String % File. .toURI .toURL)))
        swt-jar-url    (-> swt-deps/swt :jar .toURI .toURL)
        ;; Soft-resolve `ui.nebula/nebula-jar` WITHOUT triggering load.
        ;; ui.nebula loads ui.SWT in its body, which transitively loads
        ;; this namespace — so when classpath-urls runs, ui.nebula is
        ;; mid-load and its `nebula-jar` defonce has already produced
        ;; the extracted File. `find-ns` checks for the partially-loaded
        ;; namespace without re-triggering load (which would loop).
        ;; Consumers who only require ui.SWT (no Nebula) skip this
        ;; branch — `find-ns` returns nil.
        nebula-jar-url (when-let [ns-obj (find-ns 'ui.nebula)]
                         (when-let [v (ns-resolve ns-obj 'nebula-jar)]
                           (when-let [f (try (deref v) (catch Throwable _ nil))]
                             (-> ^File f .toURI .toURL))))]
    (cond-> (conj from-cp swt-jar-url)
      nebula-jar-url (conj nebula-jar-url))))

(def swt-index
  (let [urls (classpath-urls)
        args (cons (SubTypesScanner.) urls)]
    (Reflections. (to-array args))))

(defn- swt-style-ctor?
  "True if `clazz` has at least one public 2-arg constructor whose
   second arg is `int` (i.e. the SWT-style `(parent, style)` pattern).
   `define-inits` assumes this shape; classes without it would throw
   `IllegalArgumentException: No matching ctor` at namespace load.
   Skipping them here is the difference between v0.7.0 picking up
   ~50 extra Nebula widgets cleanly and CDT failing to load at all
   for downstream consumers.

   FOLLOW-UP: A handful of bundled Nebula classes don't conform —
   most are internal helpers (CalendarComposite, MonthPick,
   CustomButton, GanttComposite, GridToolTip) instantiated by their
   wrapping widget's own setup, so excluding them is fine. The one
   user-facing exclusion is `org.eclipse.nebula.widgets.oscilloscope.
   multichannel.Plotter`, whose ctor is `(int channelCount, Composite
   parent, int style)` — channel count first, then SWT pair. A future
   release could expose it via a hand-written `plotter` wrapper init
   (~10 lines)."
  [^Class clazz]
  (->> (.getConstructors clazz)
       (some (fn [^java.lang.reflect.Constructor c]
               (let [ts (.getParameterTypes c)]
                 (and (= 2 (alength ts))
                      (= Integer/TYPE (aget ts 1))))))))

(defn- non-abstract? [^Class c]
  (zero? (bit-and Modifier/ABSTRACT (.getModifiers c))))

(def swt-composites (->> (.getSubTypesOf swt-index Composite)
                         (seq)
                         #_(remove #{Shell GLCanvas})
                         (remove #{GLCanvas})
                         (remove #(.endsWith (.getName %) "OleClientSite"))
                         (remove #(.endsWith (.getName %) "OleControlSite"))
                         (remove #(.endsWith (.getName %) "WebSite"))
                         (filter non-abstract?)
                         (filter swt-style-ctor?)
                         (#(conj % Composite))))

(def swt-widgets (->> (.getSubTypesOf swt-index Widget)
                      (seq)
                      (remove #(.isAssignableFrom Composite %))
                      (remove #(.isAssignableFrom Item %))
                      (remove #(not (nil? (.getEnclosingClass %))))
                      (remove #{Control Tray TaskBar TaskItem ScrollBar})
                      (filter non-abstract?)
                      (filter swt-style-ctor?)))

(def swt-items (->> (.getSubTypesOf swt-index Item)
                    (seq)
                    (remove #{TaskItem})
                    (remove #(not (nil? (.getEnclosingClass %))))
                    (filter non-abstract?)
                    (filter swt-style-ctor?)
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

;; =====================================================================================
;; Parent-child relationship discovery via constructor reflection

(def widget-constructor-parent-types
  "Map: widget-class → set of types accepted as parent by any public constructor.
   Derived by inspecting each constructor's first parameter type."
  (->> (concat swt-composites swt-widgets swt-items)
       (map (fn [^Class clazz]
              [clazz (->> (.getConstructors clazz)
                          (filter #(>= (alength (.getParameterTypes %)) 2))
                          (map #(aget (.getParameterTypes %) 0))
                          (filter #(or (.isAssignableFrom Widget %)
                                       (= org.eclipse.swt.widgets.Display %)))
                          set)]))
       (filter #(seq (second %)))
       (into {})))

(defn- custom-control?
  "Returns true if `clazz` is a custom SWT control — class name starts with 'C'
   followed by an uppercase letter (e.g. CTabFolder, CCombo, CBanner)."
  [^Class clazz]
  (let [name (.getSimpleName clazz)]
    (and (>= (.length name) 2)
         (= \C (.charAt name 0))
         (Character/isUpperCase (.charAt name 1)))))

(defn valid-children-of
  "Returns `[[kebab-name class] ...]` of widget classes whose constructors accept
   `parent-class` (or a supertype of it) as the first parameter."
  [^Class parent-class]
  (->> widget-constructor-parent-types
       (filter (fn [[_ parent-types]]
                 (some #(.isAssignableFrom % parent-class) parent-types)))
       (map first)
       (fn-names<-)))

(defn valid-parents-of
  "Returns `[[kebab-name class] ...]` of concrete widget classes that `child-class`
   can be constructed inside.  Custom controls (CTabFolder, CCombo, etc.) are only
   included when the child's constructor explicitly names them as a parameter type."
  [^Class child-class]
  (let [declared-parent-types (get widget-constructor-parent-types child-class)]
    (->> (concat swt-composites swt-widgets swt-items)
         (filter (fn [candidate]
                   (and (some #(.isAssignableFrom % candidate) declared-parent-types)
                        (or (not (custom-control? candidate))
                            (contains? declared-parent-types candidate)))))
         (fn-names<-))))

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
