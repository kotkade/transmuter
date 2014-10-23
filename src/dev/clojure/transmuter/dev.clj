(ns transmuter.dev
  (:require
    [clojure.tools.nrepl.server :as repl]
    redl.core
    redl.complete))

(reset! redl.core/print-fn prn)

(defn repl-handler
  []
  (repl/default-handler))
