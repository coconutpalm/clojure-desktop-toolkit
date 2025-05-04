(ns ui.SWT-conversions
  (:require [righttypes.util.interop :refer [array]]
            [righttypes.conversions :refer [convert]]
            [hyperfiddle.rcf :refer [tests]])
  (:import [clojure.lang PersistentVector ExceptionInfo]
           [org.eclipse.swt.graphics Point Rectangle RGB RGBA]))

(defn convert-error
  ([from to message inner-ex]
   (throw (ex-info message
                   {:op :convert
                    :to to
                    :from from}
                   inner-ex)))
  ([from to message]
   (throw (ex-info message
                   {:op :convert
                    :to to
                    :from from}))))

(defonce ^:private string-array-class (class (array [String])))

(defmethod convert [string-array-class clojure.lang.ASeq] [_ s]
  (apply array [String] s))

(defmethod convert [string-array-class clojure.lang.PersistentVector] [_ s]
  (apply array [String] s))


(defonce ^:private int-array-class (class (array [Integer/TYPE])))

(defmethod convert [int-array-class clojure.lang.PersistentVector] [_ xs]
  (apply array [Integer/TYPE] xs))

(def point-format "A point must have exactly an 'x' and a 'y' value; found: ")
(defmethod convert [Point PersistentVector] [_ xs]
  (when-not (= 2 (count xs))
    (convert-error xs Point (str point-format xs)))
  (try
    (Point. (first xs) (second xs))
    (catch Throwable t
      (convert-error xs Point (str point-format xs) t))))

(def rect-format  "A rectangle must have exactly an [x, y, width, height]; found: ")
(defmethod convert [Rectangle PersistentVector] [_ xs]
  (when-not (= 4 (count xs))
    (convert-error xs Rectangle (str rect-format xs)))
  (try
    (let [[x y width height] xs]
      (Rectangle. x y width height))
    (catch Throwable t
      (convert-error xs Rectangle (str rect-format xs)) t)))

(def rgb-format "An RGB must be in the form [r g b] where (<= 0 x 255) or (<= 0.0 x 1.0); found:")
(defmethod convert [RGB PersistentVector] [_ xs]
  (when-not (= 3 (count xs))
    (convert-error xs RGB (str rgb-format xs)))
  (try
    (let [[r g b] xs]
      (RGB. r g b))
    (catch Throwable t
      (convert-error xs RGB (str rgb-format xs) t))))

(def rgba-format "An RGBA must be in the form [r g b alpha] where (<= 0 x 255) or (<= 0.0 x 1.0); found:")
(defmethod convert [RGBA PersistentVector] [_ xs]
  (when-not (= 4 (count xs))
    (convert-error xs RGBA (str rgba-format xs)))
  (try
    (let [[r g b a] xs]
      (RGBA. r g b a))
    (catch Throwable t
      (convert-error xs RGBA (str rgba-format xs) t))))

(tests
 (convert Point [50 50]) := (Point. 50 50)
 (convert Rectangle [50 50 1024 768]) := (Rectangle. 50 50 1024 768)
 (convert RGB [255 255 255]) := (RGB. 255 255 255)
 (convert RGBA [255 255 255 100]) := (RGBA. 255 255 255 100)

 (try
   (convert Point [30 20 100])
   (catch ExceptionInfo ex (ex-data ex))) := {:op :convert, :to Point, :from [30 20 100]}

 :eot)