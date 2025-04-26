(ns ui.SWT-conversions
  (:require [righttypes.util.interop :refer [array]]
            [righttypes.conversions :refer [convert]]))

(defonce ^:private string-array-class (class (array [String])))

(defmethod convert [string-array-class clojure.lang.ASeq] [_ s]
  (apply array [String] s))

(defmethod convert [string-array-class clojure.lang.PersistentVector] [_ s]
  (apply array [String] s))


(defonce ^:private int-array-class (class (array [Integer/TYPE])))

(defmethod convert [int-array-class clojure.lang.PersistentVector] [_ xs]
  (apply array [Integer/TYPE] xs))
