# Design principles

This is part to-do list and part record of design choices.  It is intended both as readable documentation and as an outline of points for use in possible future presentations on this UI library.

## Top-level concerns

*  Maintain a 1:1 mapping with SWT.
    *  No magic; don't hide anything other than what is strictly necessary for creating a first-class Lisp experience.
    *  Names are derived mechanically from SWT's own names.
    *  SWT is ruthlessly consistent in naming conventions.  For example, controls that can display text have a `setText` and `getText` method.  This is already a very "lispish" sensibility and makes a 1:1 mapping a sensible choice.
*  Generate the API reflectively at library-compile time using macros.  This way properly type-hinted functions can avoid run-time reflection.  Also, this way the library can support third-party SWT controls that follow SWT's conventions.
*  Code is data.  Prefer regular functions over anything hiccup-like.  More below.
*  Be self-documenting.  More below.

## Code is data

* Quoting and (where necessary, macros) can literally turn code into data, with all the benefits this brings.
* Expressing SWT UI elements as functions and/or macros provides automatic editor support, which aids discoverability.

## "Pure"

* No visible side-effects during widget construction.
* Everything is a function with a specific signature or is automatically converted into one.
    *  "init" function: f: [props-atom parent] => child-or-nil)
    *  The "props" atom can maintain state, data bindings to the model, etc. but this isn't visible from a top-level UI construction function.

## Self-documenting

* Provide API (e.g.: `swtdoc` function) to aid in exploring the API.
    *  Organize both by Clojure API and by Java API.  Categorize the way SWT itself does.  Use reflection to provide complete and up-to-date information on the exact API in the current running platform.
    *  Expose URLs from generated SWT documentation to the current Javadoc at Eclipse.org, at least for built-in SWT classes.
* Reflectively exploit Java types to document what children apply to what parents.

## Error handling

* Because reflection, type errors can be explicit and self-explanatory.
* Keep the path to the widget-being-constructed and include the breadcrumb in error messages.
