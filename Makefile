
jar:
	clojure -X:jar :version '"0.2.1"'

deploy:
	env CLOJARS_USERNAME=coconutpalm CLOJARS_PASSWORD=${CLOJARS_PASSWORD} clj -X:deploy

ALL: jar deploy
