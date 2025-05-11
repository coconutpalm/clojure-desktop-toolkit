#!/bin/bash
set -x

env CLOJARS_USERNAME=coconutpalm CLOJARS_PASSWORD=$CLOJARS_PASSWORD clj -X:deploy
