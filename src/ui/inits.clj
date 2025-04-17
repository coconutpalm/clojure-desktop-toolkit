(remove-ns 'ui.inits)

(ns ui.inits
  "Defines API for defining and manipulating init functions.  An init function is a function
  where the initial argument is the object to init and subsequent arguments (if any)
  define the values used for initialization."
  (:require [ui.SWT-conversions :refer :all]
            [clj-foundation.errors :refer [maybe-barf]]
            [clj-foundation.patterns :refer [nothing]]
            [clj-foundation.interop :refer [array set-property!]]
            [clj-foundation.data :refer [->camelCase ->kebab-case setter nothing->identity]])
  (:import [clojure.lang IFn Keyword Reflector]
           [java.lang.reflect Modifier]
           [org.eclipse.swt.widgets Widget Composite]))


(defn run-inits
  "Initialize the specified control using the functions in the `inits` seq."
  [props control inits]
  (doall (map #(maybe-barf @props (apply % props control [])) inits)))


(defmulti ->init
  "Convert first and second arguments (from front of vararg list) into an init function.  Returns the
  function and the number of arguments consumed from the arglist."
  (fn [arg1 _]
    (class arg1)))

(defmethod ->init
  IFn [arg1 _]
  [arg1 1])

(defmethod ->init
  String [arg1 _]
  (letfn [(set-text-on [_ control]
            (.setText control arg1))]
    [set-text-on 1]))

(defn- get-prop [maybe-keyword props]
  (if (keyword? maybe-keyword)
    (get @props maybe-keyword maybe-keyword)
    maybe-keyword))

(defmethod ->init
  Keyword [arg1 arg2]
  (letfn [(set-property [props o]
            (let [field-name  (->camelCase arg1)
                  arg2        (get-prop arg2 props)
                  field       (->> (.getClass o)
                                 (.getDeclaredFields)
                                 (filter (fn [field]
                                           (and (= field-name (.getName field))
                                                (not= 0 (bit-and (.getModifiers field) Modifier/PUBLIC))
                                                (Reflector/paramArgTypeMatch (.getType field) (class arg2)))))
                                 (first))]
              (if field
                (Reflector/setInstanceField o field-name arg2)
                (set-property! o (name arg1) arg2))))]
    [set-property 2]))

(defn args->inits
  "Convert a vararg parameter list into a seq of init functions."
  [args]
  (letfn [(process-args [inits args]
            (let [arg1        (first args)
                  arg2        (second args)
                  [next-init
                   args-used] (->init arg1 arg2)]
              (cond
                (nil? arg2)                 (conj inits next-init)
                (= args-used (count args)) (conj inits next-init)
                (= 1 args-used)            (process-args (conj inits next-init) (rest args))
                (= 2 args-used)            (process-args (conj inits next-init) (rest (rest args))))))]
    (when (first args)
      (process-args [] args))))

(defn extract-style-from-args
  [args]
  (let [maybe-style (first args)]
    (if (instance? Integer maybe-style)
      [maybe-style (rest args)]
      [nothing args])))

(defmacro widget*
  "Meta-construct the specified SWT class derived from Widget."
  [^Class clazz style args]
  `(fn [props# ^Composite parent#]
     (let [child# (maybe-barf (deref props#) (new ~clazz parent# ~style))
           inits# (maybe-barf (deref props#) (args->inits ~args))]
       (when (instance? Widget child#)
         (.setData child# props#))
       (swap! props# update-in [:breadcrumb] #(vec (conj % (.getSimpleName ~clazz))))
       (run-inits props# child# inits#)
       (swap! props# update-in [:breadcrumb] pop)
       child#)))


(require 'ui.internal.docs)

(defn class->init [clazz]
  (let [name (.getName clazz)
               doc (str "Construct a " name "\n\n" (ui.internal.docs/eclipsedoc-url name))
               name-sym (symbol name)
               fn-name (symbol (-> (.getSimpleName clazz) ->kebab-case))]
           `(defn ~fn-name
              ~doc
              [& inits#]
              (let [[style#
                     inits#] (extract-style-from-args inits#)
                    style# (nothing->identity SWT/NULL style#)]
                (widget* ~name-sym style# (or inits# []))))))

(defn widget-classes->inits [classes]
  (map class->init classes))


(defmacro define-inits
  "Define init functions for the specified classes.  The classes must follow the SWT
  2-arg constructor convention [parent style]."
  [classes]
  (let [inits (widget-classes->inits (var-get (resolve classes)))]
    `(let []
       ~@inits)))
