(ns ui.barf
  "A placeholder for handling unexpected exceptions.  Can integrate with logging frameworks by
   binding *barf* or *barf-stream*."
  (:require [clojure.pprint :refer [pprint]]
            [hyperfiddle.rcf :refer [tests]]))

(def ^{:dynamic true
       :doc "The barf stream.  Defaults to *out*"}
  *barf-stream* *out*)

(def ^{:dynamic true
       :doc "A function to barf an error to the appropriate sink.  Defaults to pretty printing to *barf-stream*"} 
  *barf* 
  (fn [^String error]
    (binding [*out* *barf-stream*]
      (pprint error))))

(defmacro with-maybe-barf
  "A guard for code that has to keep executing / stay alive, even if an exception was
   `throw`n up the stack.  Why barf?  Because `slurp` and `spit` were feeling lonely.

   Executes `forms` inside a try/catch.

   If an exception occurs, creates an `ex-info` containing `context` and wrapping the exception.  Then
   barfs the full `ex-info` stack trace to `*barf-stream*` (which is bound to `*out*` by default).
   
   Returns the result of executing `forms` or the caught exception if one occurs."
  [context & forms]
  `(try
     ~@forms
     (catch Throwable t#
       (*barf* (Throwable->map 
                (ex-info "Unexpected error: Barfing!" {:context ~context} t#))) 
       t#)))

(tests
 #_(with-maybe-barf 42 
   (throw (ex-info "hello" {:world :goodbye})))
 
 (with-maybe-barf [:testing 123]
   42)
 :eot)