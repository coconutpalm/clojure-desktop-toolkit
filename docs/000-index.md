# ![Logo](images/icon32x32.png) Clojure Desktop Toolkit Documentation

Start here. 😁

## Introduction

The Eclipse SWT (Standard Widget Toolkit) library, on which the Clojure Desktop Toolkit is based, rigorously adheres to a set of standard naming and usage conventions.  As a result, it is possible to mechanically generate a Clojure API for SWT using Java reflection and Clojure macros at compile time.

This is what the Clojure Desktop Toolkit does.  Clojure Desktop Toolkit assists with mapping Clojure idioms to Java data types, but there is no magic in the API mapping.  There are no new abstractions to learn.

When you have worked through the documentation, you should be able to read SWT code examples and Javadocs and immediately understand how to write the equivalent Clojure code.

I hope this toolkit returns joy to creating beautiful and useful desktop applications. 😁

## Contents

Getting started

* Install Java, Clojure, Visual Studio Code, and Calva
* [A minimal/starter Clojure Desktop Toolkit application](../examples/starter) (You should really read the tutorial below first, but LOL.)

Tutorial

* [Hello, world](010-hello-world.md) - The basic API
* [Hello, world without syntactic sugar](020-hello-world-no-sugar.md) - Learn how (nearly) everything works
* [Idiomatic SWT property assignment and type conversions](030-idiomatic-property-assignment.md)
* [Props, custom init functions, and state management](040-props-and-state.md)
* [Event handling](050-event-handling.md)
* [The user interface thread](060-the-ui-thread.md)
* [Disposable resources](070-disposable-resources.md)

SWT Basics - A basic tour and quick reference

* [SWT documentation at Eclipse.org](https://eclipse.dev/eclipse/swt/)
   * [Layout manager tutorial](https://www.eclipse.org/articles/article/?file=Article-Understanding-Layouts/index.html)
   * [Widgets](https://help.eclipse.org/latest/topic/org.eclipse.platform.doc.isv/guide/swt_widgets.htm?cp=2_0_7_0)
   * [Layouts](https://help.eclipse.org/latest/topic/org.eclipse.platform.doc.isv/guide/swt_layouts.htm?cp=2_0_7_1)
   * [Managing operating system resources](https://www.eclipse.org/articles/swt-design-2/swt-design-2.html) official document
      * (See also [Disposable resources](070-disposable-resources.md))
* [The SWT widget hierarchy](100-swt-widget-hierarchy.md)
* [Typical widget methods](110-swt-widget-api.md)
* [SWT layout managers](120-layout-managers.md)
* [SWT Items](130-swt-items.md)
* [Graphics and drawing](140-graphics-classes.md)

Reference information

* Try the `swtdoc` function in the REPL
* [The SWT "snippets" example encyclopedia](https://eclipse.dev/eclipse/swt/snippets/index.html)
* [Design principles](200-principles.md)

Eclipse Databinding

As the Hyperfiddle developers observe, user interfaces are directed acyclic graphs (DAGs).  It would be nice to model this directly rather than handle events all the time.  Current work is to support Eclipse/JFace Databinding (possibly using Missionary).  In the meantime, the following references may be helpful:

* [JFace Databinding Documentation](https://github.com/eclipse-platform/eclipse.platform.ui/blob/master/docs/JFaceDataBinding.md)
* Maven coordinates: `org.eclipse.platform/org.eclipse.jface.databinding {:mvn/version "1.15.300"}`

[Back to README.md](../README.md)
