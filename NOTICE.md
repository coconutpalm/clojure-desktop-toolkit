# NOTICE

Clojure Desktop Toolkit (CDT) bundles third-party code under the
[Eclipse Public License v2.0](https://www.eclipse.org/legal/epl-2.0/).

## Eclipse SWT

[Eclipse SWT](https://www.eclipse.org/swt/) (the Standard Widget Toolkit)
ships in CDT's `resources/` as six platform-specific ZIPs
(`swt-4.38-<platform>.zip`), each containing a `swt.jar` plus an optional
`src.zip`. At runtime, `ui.internal.SWT-deps` selects the matching ZIP for
the host platform and adds the extracted `swt.jar` to the classpath via
`cemerick.pomegranate`.

- **License**: Eclipse Public License 2.0
- **Upstream**: https://www.eclipse.org/swt/
- **Version bundled**: 4.38

## Eclipse Nebula Widgets

[Eclipse Nebula](https://github.com/EclipseNebula/nebula) supplies the
53 widgets bundled in the CDT JAR. Source files for the bundled widgets
are vendored under [`vendor/nebula/`](vendor/nebula/) at a pinned
upstream commit (see [`vendor/nebula/VERSION`](vendor/nebula/VERSION)).
Compiled bytecode ships as a runtime-extracted inner JAR
(`nebula.jar` inside the published CDT JAR) — `ui.nebula` extracts and
pomegranate-adds it at load time. The published CDT JAR contains no
loose `org/eclipse/nebula/**.class` files.

- **License**: Eclipse Public License 2.0
- **Upstream**: https://github.com/EclipseNebula/nebula
- **Pinned SHA**: see [`vendor/nebula/VERSION`](vendor/nebula/VERSION)

The vendored tree contains the full bundle `src/` for each included
widget, including subpackages that the build excludes at staging time
(e.g. JFace adapter packages like `org/eclipse/nebula/jface/*`). The
exclusion manifest is [`nebula-sources.edn`](nebula-sources.edn) in the
project root.

## Eclipse Public License compliance

CDT redistributes both SWT and Nebula under the terms of EPL-2.0. Source
availability is satisfied by:

- SWT: each bundled ZIP includes a `src.zip` from upstream.
- Nebula: full vendored source under `vendor/nebula/`.

A copy of the EPL-2.0 text is available at
[https://www.eclipse.org/legal/epl-2.0/](https://www.eclipse.org/legal/epl-2.0/)
and is included with each upstream project's source tree.
