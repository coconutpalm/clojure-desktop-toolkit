# ![Logo](images/icon32x32.png) Clojure Desktop Toolkit Tutorial

Start here. 😁

## Introduction

The Eclipse SWT (Standard Widget Toolkit) library, on which the Clojure Desktop Toolkit is based, rigorously adheres to a set of standard naming and usage conventions.  As a result, it is possible to mechanically generate a Clojure API for SWT using Java reflection and Clojure macros at compile time.

This is what the Clojure Desktop Toolkit does; it also makes SWT and Clojure Desktop Toolkit easier to understand by reducing the surface area of things one must remember in order to work effectively.  Everything boils down to a small set of rules and naming conventions.

## Contents

Getting started

* Install Java, Clojure, Visual Studio Code, and Calva
* Create a new Clojure Desktop Toolkit application

Tutorial

* [Hello, world](hello-world.md) - The basic API
* [Hello, world without syntactic sugar](hello-world-no-sugar.md) - Learn how (nearly) everything works
* [Idiomatic SWT property assignment and type conversions](idiomatic-property-assignment.md)
* [Props, custom init functions, and state management](props-and-state.md)
* [Event handling](event-handling.md)
* [The user interface thread](the-ui-thread.md)
* [Disposable resources](disposable-resources.md)

SWT Basics - A basic tour and quick reference

* SWT documentation at Eclipse.org
   * [Widgets](https://help.eclipse.org/latest/topic/org.eclipse.platform.doc.isv/guide/swt_widgets.htm?cp=2_0_7_0)
   * [Layouts](https://help.eclipse.org/latest/topic/org.eclipse.platform.doc.isv/guide/swt_layouts.htm?cp=2_0_7_1)
* [The SWT widget hierarchy](swt-widget-hierarchy.md)
* [Typical widget methods](swt-widget-api.md)
* [SWT layout managers](layout-managers.md)
* [SWT Items](swt-items.md)
* [Graphics and drawing](graphics-classes.md)

Reference information

* Try the `swtdoc` function in the REPL
* [The SWT "snippets" collection](https://eclipse.dev/eclipse/swt/snippets/index.html)
* [Design principles](principles.md)

[Back to README.md](../README.md)