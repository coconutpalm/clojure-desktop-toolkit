# ![Logo](../images/icon32x32.png) Version 0.4.4 New and Noteworthy

# Automatic Platform-specific library resolution

We've upgraded the way we do this!

* Resolving SWT no longer depends on network connectivity!
* SWT platform libraries for Windows, Mac, and Linux are now included inside the Jar.
If you build an Uberjar, these will automatically be included in your application.
* When you `require ui.SWT`, we automatically detect if SWT is already on the classpath or not.
If SWT isn't on the classpath, we install the correct platform JAR into a tempdir that's
automatically deleted on exit and add it to the classpath.

# Deprecated and removed the SWT Maven repository

We deprecated and removed our SWT Maven repository mirroring SWT.  Here's why:

Eclipse changed the way the SWT download web pages work and broke our scripts that mirror SWT
here.

The community had been asking for a way to package SWT native libraries that doesn't require
Internet connectivity to launch a UI.

Since we added SWT's libraries to _Clojure Desktop Toolkit's_ resources along with code to resolve
it from inside an uberjar, this Maven repository is no longer necessary.

To our knowledge, nobody else was consuming SWT through our repositories, so as of this release,
the locally-hosted Maven repository

# Updated example starter application

* Updated example [minimal/starter application](../../examples/starter/)

It still works with the SWT platform autodetector; it shows how to build and deploy your
own cross-platform SWT application using Clojure.

[Prior N&N](version-0.4.0.md)
