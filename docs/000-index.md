# ![Logo](images/icon32x32.png) Clojure Desktop Toolkit Documentation

Start here. 😁

## Introduction

The Eclipse SWT (Standard Widget Toolkit) library, on which the Clojure Desktop Toolkit is based, rigorously adheres to a set of standard naming and usage conventions.  As a result, it is possible to mechanically generate a Clojure API for SWT using Java reflection and Clojure macros at compile time.

This is what the Clojure Desktop Toolkit does; it also makes SWT and Clojure Desktop Toolkit easier to understand by reducing the surface area of things one must remember in order to work effectively.  Everything boils down to a small set of rules and naming conventions.

## Contents

Getting started

* Install Java, Clojure, Visual Studio Code, and Calva
* [A minimal Clojure Desktop Toolkit application](../examples/starter)

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
   * [Widgets](https://help.eclipse.org/latest/topic/org.eclipse.platform.doc.isv/guide/swt_widgets.htm?cp=2_0_7_0)
   * [Layouts](https://help.eclipse.org/latest/topic/org.eclipse.platform.doc.isv/guide/swt_layouts.htm?cp=2_0_7_1)
* [The SWT widget hierarchy](100-swt-widget-hierarchy.md)
* [Typical widget methods](110-swt-widget-api.md)
* [SWT layout managers](120-layout-managers.md)
* [SWT Items](130-swt-items.md)
* [Graphics and drawing](140-graphics-classes.md)

Reference information

* Try the `swtdoc` function in the REPL
* [The SWT "snippets" collection](https://eclipse.dev/eclipse/swt/snippets/index.html)
* [Design principles](200-principles.md)

[Back to README.md](../README.md)