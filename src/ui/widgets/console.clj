(ns ui.widgets.console
  (:require [clj-foundation.data :refer [strip-margin ->js-string-literal]]))


(def ^:private html
  (strip-margin
   "<html>
   |<head>
   |  <style type=\"text/css\">
   |    body { background: black; }
   |    pre {
   |      margin: 0;
   |      font-family: monaco, \"Courier New\", Courier, monospace;
   |      line-height: 1.3;
   |      background: black;
   |    }
   |  </style>
   |</head>
   |<body>
   |  <pre id=\"console\"></pre>
   |</body>
   |  <script src=\"https://cdn.jsdelivr.net/npm/ansi_up@5.1.0/ansi_up.min.js\"></script>
   |  <script type=\"text/javascript\">
   |    var ansi_up = new AnsiUp;
   |
   |    var txt  = \"\\n\\n\\033[1;33;40m 33;40  \\033[1;33;41m 33;41  \\033[1;33;42m 33;42  \\033[1;33;43m 33;43  \\033[1;33;44m 33;44  \\033[1;33;45m 33;45  \\033[1;33;46m 33;46  \\033[1m\\033[0\\n\\n\\033[1;33;42m >> Tests OK\\n\\n\"
   |
   |    var html = ansi_up.ansi_to_html(txt);
   |    var cdiv = document.getElementById(\"console\");
   |    cdiv.innerHTML = html;
   |  </script>
   |</html>"))


(defn init
  "Turn a browser widget into a read-only ANSI console."
  [browser]
  (.setText browser html))


(defn append
  "Append text, interpreting ANSI color codes into HTML."
  [browser text]
  (let [script (strip-margin
                "var ansi_up = new AnsiUp;
                |var html = ansi_up.ansi_to_html(" (->js-string-literal text) ");
                |var cdiv = document.getElementById(\"console\");
                |cdiv.innerHTML += html;
                |window.scrollTo(0,document.body.scrollHeight);")]
    (.execute browser script)))


(defn clear
  "Clear console buffer"
  [browser text]
  (let [script (strip-margin
                "var cdiv = document.getElementById(\"console\");
                |cdiv.innerHTML = \"\";
                |window.scrollTo(0,0);")]
    (.execute browser script)))
