# Release Checklist:
#
# Update version in SCM tag in POM.xml
# Update version number here
# `make`
# Push changes to Github
# Create version tag on Github

ALL: jar deploy

jar:
	exec clojure -X:jar :version '"0.2.2"'

deploy:
	./deploy.sh

