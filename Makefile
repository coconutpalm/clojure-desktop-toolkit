# Release Checklist:
#
# Update version in SCM tag in POM.xml
# Update version number here
# `make`
# Push changes to Github
# Create version tag on Github

ALL: jar deploy

VERSION = 0.7.0

jar:
	clojure -T:build jar :version '"$(VERSION)"'

compile-java:
	clojure -T:build compile-java

update-vendored-nebula:
	clojure -T:build update-vendored-nebula

install:
	clojure -T:build install :version '"$(VERSION)"'

deploy:
	./deploy.sh

clean:
	clojure -T:build clean
