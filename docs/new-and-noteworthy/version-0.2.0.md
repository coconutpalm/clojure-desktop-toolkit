# ![Logo](../images/icon32x32.png) Version 0.2.0 New and Noteworthy

Prior version: 0.0.2 - initial public release

The following are noteworthy additions since the prior release.

## New documentation

This release adds a new [tutorial](../index.md) covering the full RightTypes API and introducing SWT's API.

## More automatic type conversion coverage

### What automatic type conversion?

Clojure Desktop Toolkit uses the `convert` multimethod from the [RightTypes](https://github.com/coconutpalm/righttypes) library to generically convert among types.

For example, SWT frequently wants an array of String or an array of Int, as with the `weights` property on SashForm:

```clojure
(sash-form SWT/HORIZONTAL
  (label "pane 1")
  (label "pane 2")
  :weights (array [int] 25 75)
)
```

While I'm sure there are good implementation reasons for the `weights` property to be an array of int, from a Clojure perspective, it is more idiomatic to supply a vector containing the desired values and not have to think about the precise underlying array type:

```clojure
(sash-form SWT/HORIZONTAL
  (label "pane 1")
  (label "pane 2")
  :weights [25 75]
)
```

Clojure Desktop Toolkit already supplied this automatic conversion (and many more) via the `convert` multimethod from the [RightTypes](https://github.com/coconutpalm/righttypes) foundation library.

### New type conversions

This release extends the `convert` multimethod to support automatically converting from vectors to additional SWT types when assigning a SWT property.

The additional types supported in this release are:

* Point
* Rectangle
* RGB
* RGBA

### Footnote: *RightTypes!?  Another typey library!?*

*RightTypes* began when I asked, "Given that types are sets, what if we define type checking as detecting when values are *outside* a given set (and in this case, generate precise diagnostics) and behave like the identity function otherwise?*

It turns out that this framing of type "definition" has two primary results:

* It's much easier to write a type checking library.  Much less code is required.
* The resulting library is transparent to downstream operations, so it can enhance Specs and Malli rather than needing to compete with them.

The core of RightTypes is still just this.

Additions have focused on two primary areas:

1. Converting among types in a straightforward way, particularly in ways that enhance Java and Javascript interoperability.
2. Defining unambiguous well-behaved error and identity values.

