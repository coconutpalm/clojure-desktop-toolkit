(ns example.ui
  "Builds the example UI. Loaded lazily by `example.main` so the SWT
   and Nebula extractors run under the DynamicClassLoader the bootstrap
   installs.

   `CTabItem`'s analogue in Winze is `(ctab-item ... (control :ui/key))`
   — the content is a sibling widget assigned via `setControl`.
   `PShelfItem` has no `setBody`; its body Composite is auto-created
   and accessed via `.getBody`. The `body` helper below mirrors the
   `control` init shape: a small wrapper that runs nested inits against
   the right parent. Use it as the content init of a `pshelf-item`."
  (:require
   [ui.nebula]                                  ;; extract Nebula JAR + JDK 17 check
   [ui.inits :as inits]
   [ui.SWT :as ui :refer [application shell pshelf pshelf-item label]])
  (:import
   [org.eclipse.swt SWT]
   [org.eclipse.swt.layout FillLayout]
   [org.eclipse.swt.widgets Composite]))

(defn body
  "Init that runs `body-inits` against the PShelfItem parent's body
   Composite (`.getBody`). PShelfItem has no `setBody` — the body is
   auto-created by PShelfItem's constructor and is the actual parent
   for child widgets. Analogous to `ui.SWT/control` for CTabItem, but
   pushes children into the body instead of binding via setControl."
  [& body-inits]
  (fn [_props parent]
    (let [body ^Composite (.getBody parent)]
      (inits/run-inits _props body (inits/args->inits body-inits)))))

(defn- pshelf-with-sections
  "Builds the example shell content: a PShelf with four populated
   sections. Same UI as `examples/java-widget/` rendered."
  []
  (pshelf SWT/BORDER
          (pshelf-item "Inbox"   (body :layout (FillLayout.) (label "Contents for: Inbox")))
          (pshelf-item "Drafts"  (body :layout (FillLayout.) (label "Contents for: Drafts")))
          (pshelf-item "Sent"    (body :layout (FillLayout.) (label "Contents for: Sent")))
          (pshelf-item "Archive" (body :layout (FillLayout.) (label "Contents for: Archive")))))

(defn start [& _]
  (application
   (shell SWT/SHELL_TRIM
          "CDT nebula-widget example"
          :layout (FillLayout.)
          (pshelf-with-sections))))

(defn start-with-screenshot
  "Variant of `start` that screenshots the shell to `path` after a short
   delay, then disposes. Used by `dev/screenshot.clj` to verify the
   example end-to-end without a human at the keyboard."
  [path]
  (application
   (shell SWT/SHELL_TRIM
          "CDT nebula-widget screenshot"
          :layout (FillLayout.)
          (pshelf-with-sections)
          (fn [_props shell-w]
            (.timerExec
             @ui/display 800
             (ui/runnable-fn
              (fn []
                (try
                  (ui/screenshot-widget! shell-w path)
                  (println "Screenshot saved:" path)
                  (finally
                    (.dispose shell-w))))))))))
