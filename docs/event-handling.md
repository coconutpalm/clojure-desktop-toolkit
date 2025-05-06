# ![Logo](images/icon32x32.png) Event handling

Like most user interface toolkits, Eclipse SWT responds to user interaction within a user interface via events, or functions that the toolkit invokes when given user interface actions occur.

While it is possible to use Java interop APIs to add listeners and implement event handlers using custom Clojure Desktop Toolkit init functions, Clojure Desktop Toolkit provides a macro, the `on` macro, that eliminates the boilerplate for this common use-case.

In this chapter, we will:

* Examine this macro
* Explain how it works

> *Note*: This is the the area of Clojure Desktop Toolkit that is newest and most subject to change.  You should consider this part of the API to be provisional.  That said, I will attempt to maintain backward compatibility as much as possible in future versions.

## The `on` macro

Since I anticipate readers are already familiar with event handling generally, let's start with a specific code example as an illustration.

This program:

1. When closed, hides itself and keeps runnin in the system tray.
2. Toggles its visibility when the system tray icon is clicked.
3. Only quits, when the system tray menu's "Quit" option is selected.

```clojure
(application
 (tray-item
   (on e/menu-detected [props parent event] (.setVisible (:ui/tray-menu @props) true))  ; Right-click handler
   (on e/widget-selected [props parent event] (let [shell (:ui/shell @props)]           ; Left-click handler
                                                (.setVisible shell (not (.isVisible shell))))))

 (shell SWT/SHELL_TRIM (id! :ui/shell)
   "Close minimizes to Tray"

   :layout (FillLayout.)

   (label SWT/WRAP "This program minimizes to the system tray and remains running when its shell is closed.")

   (on e/shell-closed [props parent event] (when-not (:closing @props)
                                               (set! (. event doit) false)
                                               (.setVisible parent false)))

   (menu SWT/POP_UP (id! :ui/tray-menu)
      (menu-item SWT/PUSH "&Quit"
        (on e/widget-selected [parent props event] (swap! props assoc :closing true)
        (.close (:ui/shell @props)))))))
```

Many techniques are illustrated above, but we have covered enough up to this point in the tutorial that you should be able to follow the code without any further explanation.

But how does the `on` macro work?

In the code above, the `on` macro expands to an init function that

* Selects the listener interface containing the requested event method from the set of listeners available on the parent widget.
* Implements this listener interface so that it delegates to the code supplied in the body of the `on` macro.
* Registers the new listener with the parent widget.

### A concrete example: SelectionListener

How would you use this?

If a widget supplies an `.addSelectionListener(SelectionListener)` method, you can look at `SelectionListener` and learn that it supports two event methods named `widgetSelected` and `widgetDefaultSelected`.

To listen to these events using Clojure Desktop Toolkit, you would use one of the following forms:

* `(on e/widget-selected ,,,)`
* `(on e/widget-default-selected ,,,)`

### One last detail

The initial parameter to `on` is actually a keyword.

The `ui.events` namespace defines constants for all of the keywords that name events in the SWT API, so they are easier to find when typing in an IDE.

So, `(on e/shell-closed ,,,)` actually means `(on :shell-closed ,,,)`.

You can use either form when writing code, but you get spellchecking and IDE lookup support if you use the predefined constants.

## Conclusion

To write event listeners, use the `on` macro.  The list of possible events is in the `ui.events` namespace, and you can consult the SWT API for a given SWT widget to learn what listeners and listener methods that SWT widget supports.

[Return to documentation index](index.md)
