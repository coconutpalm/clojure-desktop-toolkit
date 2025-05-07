# ![Logo](images/icon32x32.png) Disposable resources

Operating system user interfaces don't have garbage collectors to automatically dispose of resources.  SWT respects this.

The method you need for this is called `dispose`.  The rule is, "If you created it, you dispose it, unless you set it on a widget and the widget now owns it."

## Common things that need to be disposed

This means that, if you create a graphics resource, you have to dispose of it to avoid leaking these resources.  What are common examples?

* Color objects
* Fonts
* Graphics contexts for drawing on a canvas (or a `Drawable`).

You can avoid a lot of this headache by using fonts, colors, etc., that are already defined by the operating system.  See `getSystemColor` on the `Display` class, for example.

Consult the SWT documentation for more.

## Drawing on a graphics context

Regarding this latter case, Clojure Desktop Toolkit supplies a macro to help.  `doto-gc-on` works like Clojure's `doto`, but crates a graphics context, executes your drawing commands on that graphics context, and then automatically disposes it for you.  Here's an example:

```clojure
(doto-gc-on image
            (. setBackground (.getSystemColor display SWT/COLOR_DARK_BLUE))
            (. fillRectangle (.getBounds image)))
```


[Return to documentation index](000-index.md)
