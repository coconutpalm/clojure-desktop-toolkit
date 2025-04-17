(ns ui.gridlayout
  "Helpers for using SWT GridaLayout and GridData."
  (:require [ui.inits :refer [run-inits args->inits]]
            [clj-foundation.interop :refer [set-fields!]])
  (:import [org.eclipse.swt SWT]
           [org.eclipse.swt.layout GridLayout GridData]))

(defn grid-layout
  "An init function for org.eclipse.swt.layout.GridLayout. Arguments in the form
  :field1 val1 :field2 val2, etc., set the layout object's properties."
  [& more]
  (let [inits (args->inits more)]
    (fn [props parent]
      (let [l (GridLayout.)]
        (run-inits props l inits)
        (.setLayout parent l)))))


(defn- grid-data*
  [props control inits]
  (let [parent        (.getParent control)
        parent-layout (.getLayout parent)
        layout-data   (or (.getLayoutData control) (GridData.))]
    (when-not (instance? GridLayout parent-layout)
      (throw (ex-info (str (.getSimpleName parent) ".layout = "
                           (if parent-layout
                             (.getSimpleName (class parent-layout))
                             "nil")
                           " but must be GridLayout."))))
    (run-inits props layout-data inits)
    (.setLayoutData control layout-data)
    layout-data))

(defn grid-data
  "Construct and initialize a GridData on the specified control.  A GridLayout must
  have previously been set on the control's parent."
  [& more]
  (fn [props parent]
    (grid-data* props parent (args->inits more))))

(defn hgrab [& more]
  (fn [props control]
    (let [layout-data (grid-data* props control [])]
      (set-fields!
       layout-data
       horizontalAlignment SWT/FILL
       verticalAlignment SWT/CENTER
       grabExcessHorizontalSpace true
       grabExcessVerticalSpace false)
      (run-inits props layout-data (args->inits more)))))

(defn align-left-hgrab [& more]
  (fn [props control]
    (let [layout-data (grid-data* props control [])]
      (set-fields!
       layout-data
       horizontalAlignment SWT/LEFT
       verticalAlignment SWT/CENTER
       grabExcessHorizontalSpace true
       grabExcessVerticalSpace false)
      (run-inits props layout-data (args->inits more)))))

(defn align-center-hgrab [& more]
  (fn [props control]
    (let [layout-data (grid-data* props control [])]
      (set-fields!
       layout-data
       horizontalAlignment SWT/CENTER
       verticalAlignment SWT/CENTER
       grabExcessHorizontalSpace true
       grabExcessVerticalSpace false)
      (run-inits props layout-data (args->inits more)))))

(defn align-right-hgrab [& more]
  (fn [props control]
    (let [layout-data (grid-data* props control [])]
      (set-fields!
       layout-data
       horizontalAlignment SWT/RIGHT
       verticalAlignment SWT/CENTER
       grabExcessHorizontalSpace true
       grabExcessVerticalSpace false)
      (run-inits props layout-data (args->inits more)))))

(defn vgrab [& more]
  (fn [props control]
    (let [layout-data (grid-data* props control [])]
      (set-fields!
       layout-data
       horizontalAlignment SWT/FILL
       verticalAlignment SWT/FILL
       grabExcessHorizontalSpace false
       grabExcessVerticalSpace true)
      (run-inits props layout-data (args->inits more)))))

(defn grab-both [& more]
  (fn [props control]
    (let [layout-data (grid-data* props control [])]
      (set-fields!
       layout-data
       horizontalAlignment SWT/FILL
       verticalAlignment SWT/FILL
       grabExcessHorizontalSpace true
       grabExcessVerticalSpace true)
      (run-inits props layout-data (args->inits more)))))

(defn align-left [& more]
  (fn [props control]
    (let [layout-data (grid-data* props control [])]
      (set-fields!
       layout-data
       horizontalAlignment SWT/LEFT
       verticalAlignment SWT/FILL
       grabExcessHorizontalSpace false
       grabExcessVerticalSpace false)
      (run-inits props layout-data (args->inits more)))))

(defn align-center [& more]
  (fn [props control]
    (let [layout-data (grid-data* props control [])]
      (set-fields!
       layout-data
       horizontalAlignment SWT/CENTER
       verticalAlignment SWT/FILL
       grabExcessHorizontalSpace false
       grabExcessVerticalSpace false)
      (run-inits props layout-data (args->inits more)))))

(defn align-right [& more]
  (fn [props control]
    (let [layout-data (grid-data* props control [])]
      (set-fields!
       layout-data
       horizontalAlignment SWT/RIGHT
       verticalAlignment SWT/FILL
       grabExcessHorizontalSpace false
       grabExcessVerticalSpace false)
      (run-inits props layout-data (args->inits more)))))
