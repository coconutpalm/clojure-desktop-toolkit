# ![Logo](images/icon32x32.png) Clojure Desktop Toolkit Tutorial

Start here. 😁

## Introduction

The Eclipse SWT (Standard Widget Toolkit) library, on which the Clojure Desktop Toolkit is based, rigorously adheres to a set of standard naming and usage conventions.  As a result, it is possible to mechanically generate a Clojure API for SWT using Java reflection and Clojure macros at compile time.

This is what the Clojure Desktop Toolkit does; it also makes SWT and Clojure Desktop Toolkit easier to understand by reducing the surface area of things one must remember in order to work effectively.  Everything boils down to a small set of rules and naming conventions.

## Contents

Getting started

* Install Java, Clojure, Visual Studio Code, and Calva
* Create a new Clojure Desktop Toolkit application

Let's examine the API and usage more closely.

* [Hello, world](hello-world.md)
* [Hello, world without syntactic sugar](hello-world-no-sugar.md)
* [Idiomatic SWT property assignment](idiomatic-property-assignment.md)
* [Props, custom init functions, and state management](props-and-state.md)
* [Event handling](event-handling.md)
* [The user interface thread](the-ui-thread.md)
* [Disposable resources](disposable-resources.md)

SWT Basics

* The SWT widget hierarchy (swt-basics, composite, canvas)
* Typical widget methods (swt-widget-api)
* SWT layout managers
* Graphics and drawing
* SWT Items

Reference information

* The `swtdoc` API and online references
* [The SWT "snippets" collection](https://eclipse.dev/eclipse/swt/snippets/index.html)
* [Design principles](principles.md)

[Back to README.md](../README.md)