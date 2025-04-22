#!/usr/bin/env bash

for jar in `find maven -name '*.jar'`; do
    echo "Fixing $jar"
    zip -d "$jar" META-INF/*.RSA META-INF/*.DSA META-INF/*.SF
done
