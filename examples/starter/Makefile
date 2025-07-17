#
# A Makefile that summarizes the ways to (build and) run the starter app.
#
# `make` builds the uberjar
# `make run-uber` builds and runs the uberjar
# `make clean` cleans the build artifacts
# `make run` runs the app directly from the file system

ALL: uber

clean:
	clj -T:build clean

uber: clean
	clj -T:build uber

run:
	clojure -M -m starter.main

run-uber: uber
	java -jar -XstartOnFirstThread target/your-app*.jar

