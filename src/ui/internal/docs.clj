(ns ui.internal.docs
  (:require [ui.internal.reflectivity :as meta]
            [clj-foundation.conversions :refer :all]))


(def ^:private eclipse-help-url-prefix
  "https://help.eclipse.org/latest/index.jsp?topic=%2Forg.eclipse.platform.doc.isv%2Freference%2Fapi%2F")

(defn eclipsedoc-url
  "Return the URL for the specified class's documentation at Eclipse."
  [clazz]
  (cond
    (instance? Class clazz) (eclipsedoc-url (.getName clazz))
    (string? clazz)         (str eclipse-help-url-prefix
                                 (.replaceAll clazz "\\." "%2F")
                                 ".html")
    :else                   (throw (IllegalArgumentException. (str "Invalid class name: " clazz)))))


(defn- doc-for-node [node]
  (cond
    (class? node) {:class node
                   :fields (meta/fields node)
                   :properties (meta/setters node)
                   :methods (meta/non-prop-methods node)
                   :eclipsedoc (eclipsedoc-url node)}
    (and (map? node)
         (not (empty? node))
         (keyword? (first (first node)))) {:subtopics (sort (keys node))}
    (var? node) (or (:doc (meta node)) node)
    :else node))


(defn- name-str [x] (name (convert clojure.lang.Named x)))


(defn- traverse [current-doc topic]
  (cond
    (map? current-doc) (get current-doc topic)
    (sequential? current-doc) (if (sequential? (first current-doc))
                                (second (first (filter (fn [x] (>= (.indexOf (name-str (first x)) (name-str topic)) 0)) current-doc)))
                                (first (filter (fn [x] (>= (.indexOf (name-str x) (name-str topic)) 0)) current-doc)))
    :default nil))


(defn swtdoc* [breadcrumb current-doc query]
  (if-let [topic (first query)]
    (if-let [next-doc (traverse current-doc topic)]
      (swtdoc* (conj breadcrumb topic) next-doc (rest query))
      (ex-info (str "Couldn't find documentation node: " topic) {:breadcrumb breadcrumb
                                                                 :next-topic topic
                                                                 :rest-of-query (rest query)}))
    {:breadcrumb breadcrumb
     :result (doc-for-node current-doc)}))
