(ns ui.widgets.editor
  (:refer-clojure :exclude [list])
  (:require [ui.SWT]
            [ui.inits :as i]
            [clojure.string :as str]
            [clj-foundation.data :refer [nothing->identity strip-margin ->js-string-literal]])
  (:import [org.eclipse.swt SWT]
           [org.eclipse.swt.layout FillLayout]
           [org.eclipse.swt.browser Browser BrowserFunction]
           [org.eclipse.swt.graphics Rectangle]
           [org.eclipse.swt.events ModifyListener VerifyListener]
           [org.eclipse.swt.widgets Composite Menu]
           [org.eclipse.swt.layout FillLayout]))



(comment
  (proxy [org.eclipse.swt.widgets.Composite Editor] [parent style]
    (setFocus [this]
      (.setFocus @browser))
    (getSelectionAtom [this]
      selection)
    (getTextAtom [this]
      text)
    (getText [this]
      (.execute @browser "editor.state.doc.toString();"))
    (setText [this content]
      (let [text-length (count (.getText this))
            transaction (strip-margin
                         "editor.dispatch({
                                         |  changes: {
                                         |    from: 0,
                                         |    to: " text-length ",
                                         |    insert: " (->js-string-literal content) "
                                         |  }
                                         |})")]
        (.execute @browser transaction)))
    (cut [this]
      (.execute @browser "editor.cut();"))
    (copy [this]
      (.execute @browser "editor.copy();"))
    (paste [this]
      (.execute @browser "editor.paste();")))


  (proxy [BrowserFunction] [b "clojure"]
    (function [arg-array]
      (let [args (vec arg-array)]
        (println args))))

  ,)

(defprotocol TextEditor
  :extend-via-metadata true
  "Functions that interact with text editors"
  (getSelectionAtom [editor])
  (getTextAtom [editor])
  (getText [editor])
  (setText [editor content])
  (cut [editor])
  (copy [editor])
  (paste [editor]))


(defn codemirror6
  "Returns Code Mirror 6 inside a SWT Composite.  Implements the Editor protocol"
  [& more]
  (let [[style
         args] (i/extract-style-from-args more)
        style (nothing->identity SWT/NULL style)
        args (or args [])]

    (fn [props ^Browser parent]

      (let [child (composite-editor parent style)
            inits (i/args->inits args)]
        (i/run-inits props child inits)
        child))))
