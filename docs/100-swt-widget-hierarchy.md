# ![Logo](images/icon32x32.png) The SWT WIdget Hierarchy

This page is a quick reference to SWT's `Widget` class hierarchy.

## Top-level highlights

![SWT Basics](images/swt/swt-basics.png)

## Composites

Composites are controls that can contain other controls.  Custom controls also usually extend `Composite` or `Canvas`.

Within SWT, custom controls have a "C" prefix.  `CBanner`, `CCombo`, etc., are examples.

![Composites](images/swt/composite.png)

## Canvas

`Canvas` is the superclass of controls that are designed to be be drawn on using a graphics context, or `GC`.

![Canvas](images/swt/canvas.png)


[Return to documentation index](000-index.md)
