(ns cdt-smoke
  "One-off smoke test for the v1 Nebula widget bundle. Opens a Shell
   containing PShelf (Tier 3a) + LED (Tier 2), screenshots it, exits.

   Runs as the JVM's `-main` so the main thread is the UI thread on
   macOS (`-XstartOnFirstThread`). Requires `target/classes` on
   classpath (where the compiled Nebula bytecode lives) and an SWT jar
   pre-loaded as a `:local/root` dep so SWT-deps's pomegranate fallback
   never fires (AppClassLoader under `clojure -X`/`-M -m` isn't
   modifiable).

   Invoke:
     clojure -J-XstartOnFirstThread \\
       -Sdeps '{:paths [\"dev\" \"src\" \"resources\" \"target/classes\"]
                :deps {org.eclipse.swt/swt
                       {:local/root \"target/build-deps/swt/swt.jar\"}}}' \\
       -X cdt-smoke/run :path '\"/tmp/cdt-smoke.png\"'

   Not part of the published JAR — lives under dev/."
  (:require
   [ui.SWT :as swt]
   [ui.nebula])
  (:import
   [org.eclipse.swt SWT]
   [org.eclipse.swt.layout GridData GridLayout]
   [org.eclipse.swt.widgets Display Shell]
   [org.eclipse.nebula.widgets.pshelf PShelf PShelfItem]
   [org.eclipse.nebula.widgets.led LED LEDCharacter]))

(defn- grid-data-fill [^GridData gd]
  (set! (.horizontalAlignment gd) SWT/FILL)
  (set! (.verticalAlignment gd) SWT/FILL)
  (set! (.grabExcessHorizontalSpace gd) true)
  (set! (.grabExcessVerticalSpace gd) true)
  gd)

(defn -main [& [out-path]]
  (let [path (or out-path "/tmp/cdt-step7-smoke.png")
        d (Display/getDefault)
        s (Shell. d SWT/SHELL_TRIM)]
    (.setText s "CDT Step 7 smoke — PShelf + LED")
    (.setSize s 600 360)
    (.setLayout s (GridLayout. 1 false))

    ;; PShelf — Tier 3a (with pshelfviewer subpackage filtered out)
    (let [pshelf (PShelf. s SWT/BORDER)]
      (.setLayoutData pshelf (grid-data-fill (GridData.)))
      (doseq [t ["Inbox" "Drafts" "Sent"]]
        (.setText (PShelfItem. pshelf SWT/NONE) t)))

    ;; LED — Tier 2 (uses opal.commons)
    (let [led (LED. s SWT/NONE)
          gd (GridData.)]
      (set! (.horizontalAlignment gd) SWT/CENTER)
      (.setLayoutData led gd)
      (.setCharacter led (LEDCharacter/getByNumber 8)))

    (.open s)
    (.layout s)
    (reset! swt/display d)
    (.timerExec
     d 700
     (swt/runnable-fn
      (fn []
        (try
          (swt/screenshot-widget! s path)
          (println "Screenshot saved:" path)
          (finally
            (.dispose s))))))
    (while (not (.isDisposed s))
      (when-not (.readAndDispatch d)
        (.sleep d)))
    (.dispose d)
    (System/exit 0)))

(defn run
  "Exec-style entrypoint for `clojure -X cdt-smoke/run :path '\"/tmp/foo.png\"'`."
  [{:keys [path]}]
  (-main (or path "/tmp/cdt-step7-smoke.png")))
