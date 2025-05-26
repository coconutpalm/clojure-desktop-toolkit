(ns app.minimize-to-tray
  (:require
   [ui.events :as e]
   [ui.SWT :refer [application id! label menu menu-item on shell tray-item]])
  (:import
   [org.eclipse.swt SWT]
   [org.eclipse.swt.layout FillLayout]))

  (defn minimize-to-tray
    "An example application that minimizes the main window to the system tray when it's closed."
    []
    (application
     (tray-item
      ;; System tray right-click handler
      (on e/menu-detected [props parent event] (.setVisible (:ui/tray-menu @props) true))

      ;; System tray click handler toggles visibility
      (on e/widget-selected [props parent event] (let [shell (:ui/shell @props)]
                                                   (.setVisible shell (not (.isVisible shell))))))

     (shell
      SWT/SHELL_TRIM (id! :ui/shell)
      :layout (FillLayout.)
      "Close minimizes to Tray"

      (label SWT/WRAP "This program minimizes to the system tray and remains running when its shell is closed.")

      (on e/shell-closed [props parent event] (when-not (:closing @props)
                                                (set! (. event doit) false)
                                                (.setVisible parent false)))

      (menu SWT/POP_UP (id! :ui/tray-menu)
            (menu-item SWT/PUSH "&Quit"
                       (on e/widget-selected [parent props event] (swap! props assoc :closing true)
                           (.close (:ui/shell @props))))))))

(comment

  (def app (future (minimize-to-tray)))


  :eoc
  )