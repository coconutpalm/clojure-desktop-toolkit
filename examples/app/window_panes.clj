(ns app.window-panes
  (:require [ui.SWT :as ui :refer [shell id! sash-form browser text menu menu-item
                                   application ui defmain with-property child-of
                                   | swtdoc tray-item on]]
            [ui.events :as e])
  (:import [org.eclipse.swt SWT]
           [org.eclipse.swt.layout FillLayout]))


;; =====================================================================================
;; An example app to test/prove the library's features


(defonce state (atom nil))

#_{:clj-kondo/ignore [:unresolved-symbol]}
(defn example-app []
  (application ; The application hosts the display object and runs the event loop

   (tray-item ; Define a system tray item so we can minimize to the tray
    (on e/menu-detected [props parent event] (.setVisible (:ui/tray-menu @props) true))
    (on e/widget-selected [props parent event] (let [shell (:ui/shell @props)]
                                                 (.setVisible shell (not (.isVisible shell))))))

   (shell
    SWT/SHELL_TRIM (id! :ui/shell)
    "Browser"
    :layout (FillLayout.)

    (on e/shell-closed [props parent event] (when-not (:closing @props)
                                              (set! (. event doit) false)
                                              (.setVisible parent false)))

    (sash-form
     SWT/HORIZONTAL

     (sash-form
      SWT/VERTICAL
      ;; sudo apt install libwebkit2gtk-4.0-37 on ubuntu if needed
      (browser SWT/WEBKIT (id! :ui/editor)
               :javascript-enabled true
               :url "https://www.duckduckgo.com")

      (text (| SWT/MULTI SWT/V_SCROLL) (id! :ui/textpane)
            :text "This is the notes pane..."
            (on e/modify-text [props parent event] (println (.getText parent))))

      :weights [80 20])

     (browser SWT/WEBKIT (id! :ui/editor)
              :javascript-enabled true
              :url (-> (swtdoc :swt :program 'Program) :result :eclipsedoc))

     :weights [30 70])

    (menu SWT/POP_UP (id! :ui/tray-menu)
          (menu-item SWT/PUSH "&Quit"
                     (on e/widget-selected [parent props event] (swap! props #(assoc-in % [:closing] true))
                         (.close (:ui/shell @props))))))

   (defmain [props parent]
     ;; Bind data layer to UI or...
     (reset! state props)
     (println (str (:ui/editor @props) " " parent)))))


(defn -main
  "An example main method."
  [& _args]
  (example-app))


(comment
  "Start the app from the REPL"

  (def app (future (example-app)))

  (:editor @state)
  {:state @state}

  (ui
   (child-of @ui/display (atom {})
             (shell "Browser 2" (id! :ui/shell)
                    (with-property :layout (FillLayout.)
                      :margin-height 10
                      :margin-width 10)
                    (browser SWT/WEBKIT (id! :ui/editor)
                             :javascript-enabled true
                             :url "https://www.google.com"))))


  (ui
   (child-of @ui/display (atom {})
             (shell "Text editor" (id! :ui/textedit)
                    (with-property :layout (FillLayout.)
                      :margin-height 10
                      :margin-width 10)
                    (text (| SWT/MULTI SWT/V_SCROLL) (id! :ui/textedit)
                          (on e/modify-text [parent props event] (println (.getText (:ui/textedit @props))))))))

  :eoc
  )