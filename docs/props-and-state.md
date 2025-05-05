# ![Logo](images/icon32x32.png) Props, custom init functions, and state management

You already know that an init function has the signature:

```clojure
(fn [props parent] ,,,,)
```

and that the `props` parameter is an atom containing a map.  In this chapter, we'll describe conventions Clojure Desktop Toolkit applies to this `props` atom along with alternate ways to define an init function for accessing or manipulating props.

## Widget IDs

When defining a user interface, it can be necessary to save a reference to a particular user interface control so one can reference and/or manipulate it later.  For this, use the `id!` function.  By convention, user interface element keywords should use the `ui` namespace.

For example:

```clojure
(shell SWT/SHELL_TRIM (id! :ui/shell))
```

These ID kewords are defined as keys in the props map and are available there subsequently.

## Setting prop values

If you need to set an arbitrary top-level property value, use `reset-prop!`.

```clojure
(shell SWT/SHELL_TRIM (id! :ui/shell)
  (reset-prop! :closing false))
```

See also:

* `update-in-prop!` and Clojure's `update-in` function
* `assoc-in-prop!` and Clojure's `assoc-in` function

These Clojure Desktop Toolkit functions do what one would expect to the props atom

## Accessing prop values

Since the props atom is a parameter to an init function, one can simply write an init function in a place where one needs to access props.

```clojure
(shell SWT/SHELL_TRIM (id! :ui/shell)
  (reset-prop! :closing false)
  (fn [props _parent]
    (println (str "Is shell disposed? " (.isDisposed (:ui/shell props))))))
```

If you want to explicitly designate your init function (and get arity checking from the compiler), you can use `definit` instead of `fn` here.

```clojure
(shell SWT/SHELL_TRIM (id! :ui/shell)
  "Shell title"
  (reset-prop! :closing false)
  (definit [props parent]
    (println (str "Shell title " (.getText (:ui/shell @props))))))
```

Continuing our contrived example, by convention, the final init function in a top-level shell is named `defmain` because this is normally where data loading/binding occurs.

```clojure
(shell SWT/SHELL_TRIM (id! :ui/shell)
  (reset-prop! :closing false)

  (defmain [props parent]
    (println (str "Is shell disposed? " (.isDisposed (:ui/shell @props))))))
```

Semantically, these all mean literally the same thing, except that the version with `fn` doesn't check that you pass exactly the `props` and `parent` parameters and no others.


[Return to documentation index](index.md)
