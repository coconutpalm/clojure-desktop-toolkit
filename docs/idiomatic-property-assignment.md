# ![Logo](images/icon32x32.png) Idiomatic Java property assignment

## To build a user interface

In the [prior chapter](hello-world-no-sugar.md), we explained how to extend Clojure Desktop Toolkit by writing functions with the signature:

```clojure
(fn [props parent] ,,,,)
```

Adding hierarchical or other complex behavior simply involves writing factory functions or macros that return functions having this signature.

Property assignment is another area where utilizing Java interop alone often doesn't result in satisfying, idiomatic Clojure or Lisp code, but Clojure Desktop Toolkit addresses this in a way that is both general and extensible.

## Property assignment

We already saw how we can assign to JavaBeans style properties simply by naming the property, in `kebab-case`, as a keyword and following this with the value we wish to assign.  For example:

```clojure
(composite
  :layout (FillLayout.)

  (text (| SWT/MULTI SWT/SCROLL_VERTICL)))
```

Further, if you want to set subsequent properties of the child object, you can write:

```clojure
(shell "Browser 2"
  (with-property :layout (FillLayout.)
    :margin-height 10
    :margin-width 10)
  (browser SWT/WEBKIT (id! :ui/editor)
    :javascript-enabled true
    :url "https://www.google.com"))
```

In these cases, setting the property using Java interop to construct a `FillLayout` isn't too bad, but other property types don't work as well with Java interop.

For example...

## Property assignment and type mismatch

Let's set the weights of a window split (of two resizeable window panes):

```clojure
(sash-form SWT/HORIZONTAL
  (label "pane 1")
  (label "pane 2")
  :weights (into-array Integer/TYPE [25 75]))
```

SWT's `weights` property requires a Java `int[]`.  Clojure already requires us to call a conversion function to get this.  One could supply a Clojure function to make Java array creation prettier like the following function taken from the *RightTypes* library:

```clojure
(sash-form SWT/HORIZONTAL
  (label "pane 1")
  (label "pane 2")
  :weights (array [Integer/TYPE] 25 75))
```

This is arguably easier on the eyes.

## Idiomatic property assignment

While the code above isn't terrible, Lispers would normally prefer to write:

```clojure
(sash-form SWT/HORIZONTAL
  (label "pane 1")
  (label "pane 2")
  :weights [25 75]
```

As seen in the code example in the [README](../README.md), Clojure Desktop Toolkit already allows this, but how?

## Automatic type conversion

The *RightTypes* library on which Clojure Desktop Toolkit is built already supplies a `convert` multimethod for defining polymorphic type conversions.  Clojure Desktop Toolkit supplies an implementation for converting Clojure vectors to Java `int[]` arrays:

```clojure
(defonce ^:private int-array-class (class (array [Integer/TYPE])))

(defmethod convert [int-array-class clojure.lang.PersistentVector] [_ xs]
  (apply array [Integer/TYPE] xs))
```

Whenever Clojure Desktop Toolkit attempts to set a property value, it first applies the `convert` multimethod.  If the types are already assignment-compatible, this multimethod behaves like identity.  Otherwise, if an appropriate conversion is defined for the specified pair of types, `convert` automatically coerces the supplied value into the expected type.

### You can extend Clojure Desktop Toolkit this way, too!

If you encounter a situation where Clojure Desktop Toolkit doesn't already convert types automatically, simply supply your own `convert` multimethod implementation for the desired pair of types.  Of course, pull requests for the `SWT_conversions.clj` namespace are appreciated too!


[Return to documentation index](index.md)
