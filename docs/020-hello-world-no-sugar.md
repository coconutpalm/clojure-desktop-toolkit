# ![Logo](images/icon32x32.png) Hello, world - How it Really Works

Last time, we examined an idiomatic "Hello, world" application written using Clojure Desktop Toolkit.

This episode, we'll rewrite "Hello, world" but start by using the naked Clojure Desktop Toolkit user interface engine and none of the helpers that Clojure Desktop Toolkit automatically generates from SWT's API.  From there, we'll rebuild the sugared version step-by-step.

As a result, you will:

1. Understand how Clojure Desktop Toolkit itself works.
2. Know how to build your own first-class extensions by following the same rules.

## Hello, world, redux

Here's the "Hello, world" application we examined last time:

```clojure
(defn hello []
  (application
   (shell SWT/SHELL_TRIM
          "Hello application"

          :layout (FillLayout.)

          (label "Hello, world"))))
```

Here is the same application, with all syntactic sugar removed:

```clojure
(defn hello-desugared []
  (application
   (fn [props parent]
     (let [child (Shell. parent SWT/SHELL_TRIM)]
       (doall
        (map (fn [initfn] (initfn props child))
             [(fn [_props parent] (.setText parent "Hello application"))
              (fn [_props parent] (.setLayout parent (FillLayout.)))
              (fn [props parent]
                (let [child (Label. parent SWT/NONE)]
                  (doall
                   (map (fn [initfn] (initfn props child))
                        [(fn [_props parent] (.setText parent "Hello, world"))]))
                  child))]))
       (.open child)
       child))))
```

What are the the lessons?

1. Every parameter within a Clojure Desktop Toolkit user interface is translated to an anonymous function with the following signature:

```clojure
(fn [props parent] ,,,,)
```

2. (What is this `props` parameter?  It's an atom containing a map where one can set and retrieve state throughout user interface construction and later bind data into the user interface elements stored there.  We'll see more of this later.)

3. Init functions that construct their own widgets *(such as Shell and Label in this example)* must also accept a parameter list of init functions for setting their own properties and for constructing their own children.  These "constructor" init functions are responsible to run these child inits as well.  Constructor init functions must return the thing that they constructed.

## Desugared "Hello, world", redux redux

Regarding point #3 above, in our example, the "constructor" init functions don't accept parameter lists at all, but hard-code their child init functions into the `map` functiion call.  How does Clojure Desktop Toolkit do this?

Clojure Desktop Toolkit's own special-purpose constructor init functions like `shell` and `label` are functions that return init functions.

Let's use a macro to illustrate the general idea:

```clojure
(defmacro widget
  "Add a child widget to specified parent and run the functions in its arglist"
  [clazz style-bits & initfns]
  `(fn [props# parent#]
     (let [child# (new ~clazz parent# ~style-bits)]
       (doall (map (fn [initfn#] (initfn# props# child#)) [~@initfns]))
       child#)))

(defn hello-desugared-with-widget-helper []
  (application
   (widget Shell SWT/SHELL_TRIM
           (fn [_props parent] (.setText parent "Hello application"))
           (fn [_props parent] (.setLayout parent (FillLayout.)))
           (widget Label SWT/NONE
                   (fn [_props parent] (.setText parent "Hello, world")))
           (fn [_props parent] (.open parent)))))
```

In this revised "desugared" Hello program, we use a macro to rewrite the `hello-desugared-with-widget-helper` function body to almost exactly what `hello-desugared` was.

Now we know enough to understand how the syntax sugar works.

## Resugaring "Hello, world"

Returning to our original "Hello, world":

```clojure
(defn hello []
  (application
   (shell SWT/SHELL_TRIM
          "Hello application"

          :layout (FillLayout.)

          (label "Hello, world"))))
```

Question: How does Clojure Desktop Toolkit support all of this sugar?  Clearly, the argument lists in Clojure Desktop Toolkit (like those above) aren't expecting each argument to be a function with the init function signature.

Answer: For arguments that aren't already init functions, Clojure Desktop Toolkit first translates (or wraps) those arguments into init functions.

To illustrate, here is the actual code Clojure Desktop Toolkit uses to construct a `Shell`:

```clojure
(defn shell
  "org.eclipse.swt.widgets.Shell"
  [& args]
  (let [[style
         args] (i/extract-style-from-args args)
        style   (nothing->identity SWT/SHELL_TRIM style)
        style   (if (= style SWT/DEFAULT)
                  SWT/SHELL_TRIM
                  style)
        init    (i/widget* Shell style (or args []))]

    (fn [props disp]
      (let [sh (init props disp)]
        (.open sh)
        sh))))
```

In the code above, the `i` alias points to a namespace with utilities for building init functions from argument lists.  The `i/widget*` macro builds an all-in-one init function that constructs the `Shell` and also runs the list of inits constructed from the `shell` function's arguments against the shell.

> ***NOTE:** In Clojure Desktop Toolkit, `shell` is a special case, because the `Shell` requires special style bit processing, and because the `Shell` itself has to be opened after construction.*
>
> *In contrast, the other widget classes' initialization functions can be (and are) generated mechanically.  These expand to init functions that are nearly identical to what we wrote above by hand.*

## Conclusions

* The Clojure Desktop Toolkit engine constructs a user interface by recursively running "init" functions with the signature `(fn [props parent] ,,,,)`.
* `props` is an atom containing a map.  It can store user interface widget instances or any other state needed to implement a desired UX.
* You can extend Clojure Desktop Toolkit simply by creating factory functions or macros that return a function implementing the "init" function signature.  The `widget` macro above is a simple example.  `shell` is a built-in example.  That's all there is to extending Clojure Desktop Toolkit's basic vocabulary and behavior!

There's one more bit of "magic" happening when you assign to widget properties:

* If you don't supply the precise type that SWT is expecting, Clojure Desktop Toolkit will attempt to automatically coerce the value you supplied to the value SWT expects.

We'll talk about this in the next chapter.

[Return to documentation index](000-index.md)
