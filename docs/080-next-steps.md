# ![Logo](images/icon32x32.png) A path toward fluency

Congratulations!  You now know:

* The conventions Clojure Desktop Toolkit uses to map between its own API and SWT's API.
* Basic SWT API conventions you can use to quickly understand and apply SWT's API.

How does one move from this place to becoming proficient with SWT and Clojure Desktop Toolkit?

SWT is a fairly large API.  While its consistent use of conventions helps, each widget has its own nuances.

Further, being a native widget toolkit introduces its own complexities.

Here are some tactics and approaches that will help you manage this complexity as you learn.

### Event handling using native widgets

Since SWT wraps native platform widgets, the order in which you receive what events will not be consistent across platforms!  Here is a best practice to help manage this:

#### Never mutate user interface state from within an event handler itself

* If you do this, you will interrupt pending event chains in ways that are unpredictable, particularly across platforms.  This results in undefined behavior.
* Instead, use the `async-exec!` function within your event handler to queue a function that will execute your state changes after all pending events have completed!

For example, if the user clicks the mouse on a text field to move the focus there, the user interface will generate a sequence of events something like:

* mouse-down
* traverse event (can we leave the old control?)
* focus-out (from the old control)
* focus-in (to the new control)
* mouse-up

Unless you're going to use event's `doit` flag to block the traverse event from continuing, you will want to place any additional state changes after the `mouse-up` event.  The way to do this is via the `async-exec!` function from `mouse-down` or `traverse event` so that your code runs after the final `mouse-up`.

The docstrings for the `ui` macro along with `sync-exec!` and `async-exec!` offer more color.

### SWT's example encyclopedia

There's an encyclopedia of SWT code examples, organized by SWT widget!  It's called the "[SWT snippet](https://eclipse.dev/eclipse/swt/snippets/index.html)" library over at Eclipse.org and it's amazing!

Using these examples, you can learn how to accomplish pretty much any esoteric thing using SWT!

### More SWT hints

The following [section](000-index.md) in the tutorial index introduces more of SWT's API and offers a quick reference guide to the current SWT API.

[Return to documentation index](000-index.md)
