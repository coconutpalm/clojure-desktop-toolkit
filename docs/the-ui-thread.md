# ![Logo](images/icon32x32.png) The UI thread

Platform-native user interfaces are single-threaded.  SWT inherits this limitation, but provides ways to interact with the user interface from background threads.

Let's examine how Clojure Desktop Toolkit interfaces with this reality.

## The Display thread

Here are the rules to keep in mind:

1. In SWT, the thread that creates the Display object becomes the user interface thread.  Attempting to access SWT APIs from any other thread will throw an exception.  In Clojure Desktop Toolkit, this is the thread that invokes the `application` function.

2. Any thread can learn if it is the user interface thread using the `ui-thread?` function.

3. Use the macro `(ui ,,,)` like a `do` block that runs on the user interface thread.  Results are returned as expected and exceptions rethrown as needed.

4. For code that can run asynchronously, but has to run on the UI thread, use the `async-exec!` function.  It accepts a Clojure function and queues that function to run later on the SWT thread's event loop.


[Return to documentation index](index.md)
