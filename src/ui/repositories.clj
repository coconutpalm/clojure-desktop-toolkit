(ns ui.repositories
  "Defines the Maven repositories to search for resolving SWT or other dynamic libraries via
   Pomegranite."
  (:require
   [cemerick.pomegranate.aether :as aether]))


(def ^{:dynamic true
       :doc "Repositories for Pomegranate to search for dependencies in the Pomegranate
             library's repository map format.

             If you package SWT yourself, use a Maven repository directory layout and modify
             this map to point to your repository (as a file URL) instead of the default ones.

             Alternatively, ensure the correct SWT is already on the classpath at launch.
             Then this code will automatically detect it and won't try to dynamically resolve SWT.

             By default, this includes Maven Central, Clojars, and Clojure Desktop Toolkit's
             SWT mirror.  While the community repositories are included by default, this is
             really only intended to be used to load SWT and its platform libraries."}
  *repositories* (merge aether/maven-central
                        {"clojars" "https://clojars.org/repo"
                         "clojure-desktop-toolkit" "https://coconutpalm.github.io/clojure-desktop-toolkit/maven"}))

