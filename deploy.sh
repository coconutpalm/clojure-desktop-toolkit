#!/bin/bash
# Credential-injection wrapper for `clojure -T:build deploy`. Reads
# the canonical VERSION from the Makefile so the version is defined in
# one place. `build.clj`'s `deploy` task builds + publishes the JAR via
# slipset/deps-deploy.
set -e
set -x

VERSION=$(awk -F'= *' '/^VERSION *=/ {print $2; exit}' Makefile | tr -d ' ')
if [ -z "$VERSION" ]; then
  echo "deploy.sh: could not read VERSION from Makefile" >&2
  exit 1
fi

env CLOJARS_USERNAME=coconutpalm \
    CLOJARS_PASSWORD="$CLOJARS_PASSWORD" \
    clojure -T:build deploy :version "\"$VERSION\""
