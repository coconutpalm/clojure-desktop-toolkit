# ![Logo](images/icon32x32.png) Props, custom init functions, and state management

So far, we've danced around the `props` parameter that is the initial argument in an init function; we've acknowledged what it is, but haven't really discussed why it's there or how Clojure Desktop Toolkit facilitates its usage.  After you've read this chapter, you will understand:

1. How the `props` atom allows you to link together all parts of a user interface
2. Functions Clojure Desktop Toolkit provides to handle common use-cases
3. Utilities Clojure Desktop Toolkit provides that make it easier to write your own custom init functions

## Why props?

You already know that an init function has the signature:

```clojure
(fn [props parent] ,,,,)
```

and that the `props` parameter is an atom containing a map.

The `application` function creates this `props` value, and the same atom is passed to every init function during construction of the user interface.  This provides an opportunity for init functions to:

* Store references to controls there.
* Store or update state there.
* Retrieve any value saved there.

Let's explore this.

## Widget IDs

When defining a user interface, it can be necessary to save a reference to a particular user interface control so one can reference and/or manipulate it later.  For this, use the `id!` function.  By convention, user interface elements' keywords should use the `ui` namespace.

For example:

```clojure
(shell SWT/SHELL_TRIM (id! :ui/shell))
```

These ID keywords then are defined as keys in the props map and are available there subsequently for retrieving the associated user interface widget.

## Arbitrary key/value pairs

If you need to set an arbitrary top-level property value, use `reset-prop!`.

```clojure
(shell SWT/SHELL_TRIM (id! :ui/shell)
  (reset-prop! :closing false))
```

Similarly, Clojure Desktop Toolkit defines:

* `update-in-prop!` and Clojure's `update-in` function
* `assoc-in-prop!` and Clojure's `assoc-in` function

These Clojure Desktop Toolkit functions do what one would expect to the props atom based on the behavior of the similarly-named Clojure functions.

## Access prop values

Since the props atom is a parameter to an init function, one can simply write an init function in a place where one needs to access props.  We've already discussed how to do this the manual way:

```clojure
(shell SWT/SHELL_TRIM (id! :ui/shell)
  (reset-prop! :closing false)
  (fn [props _parent]
    (println (str "Is shell disposed? " (.isDisposed (:ui/shell props))))))
```

There is API support for this, too.

### Define an explicit init function

If you want to explicitly designate your init function (and get arity checking from the compiler), you can use `definit` instead of `fn` here.

```clojure
(shell SWT/SHELL_TRIM (id! :ui/shell)
  "Shell title"
  (reset-prop! :closing false)

  (definit [props parent]
    (println (str "Shell title " (.getText (:ui/shell @props))))))
```

### The init function that starts an application

Continuing our contrived example, by convention, the final init function in a top-level shell is named `defmain` because this is normally where data loading/binding occurs.

```clojure
(shell SWT/SHELL_TRIM (id! :ui/shell)
  (reset-prop! :closing false)

  (defmain [props parent]
    (println (str "Is shell disposed? " (.isDisposed (:ui/shell @props))))))
```

Semantically, these all mean literally the same thing, except that the version with `fn` doesn't check that you pass exactly the `props` and `parent` parameters and no others.

## Conclusion

Clojure desktop Toolkit maintains a props "map" behind the scenes.  This map can be transformed at any step in the process of constructing a user interface, and it can contain mutable state that the entire user interface needs in order to function correctly.  Common things to store in this map include:

* Widget IDs / references to widgets
* State (mutable or not) that is local to a given user interface screen.

To facilitate this, Clojure Desktop Toolkit supplies functions for adding or changing data in this map mirrored after Clojure's own functions for transforming maps.

In addition, CLojure Desktop Toolkit supplies utilities for creating custom init functions when direct access to the props map is necessary.


[Return to documentation index](index.md)
