(ns transmuter.pipeline
  (:require
    [transmuter.feed :as feed]
    [transmuter.guard :as g]))

(defprotocol PipeDefinition
  (>pipe [this] "Initialize the pipe described by this definition."))

(extend-protocol PipeDefinition
  clojure.lang.APersistentVector
  (>pipe [this] (map >pipe this))

  clojure.lang.ISeq
  (>pipe [this] (map >pipe this))

  clojure.lang.AFn
  (>pipe [this] (this))

  nil
  (>pipe [this] nil))

(defn >pipes
  [pipes]
  (->> pipes
    (map >pipe)
    flatten
    vec))

(defprotocol Pipe
  (process [this x]
  "Process the given value and return a result. May return
  void to ignore the processed value, an Injection to explode
  the processed value into several or stop to stop processing
  any further values.")
  (finish! [this]
  "Called after the last value is processed. May return nil,
  a final value or a final Injection with additional values.
  Must be called once and only once."))

(extend-protocol Pipe
  clojure.lang.AFn
  (process [this x] (this x))
  (finish! [this]   nil))

(defrecord Pipeline [pipes feed step backlog])

(defn >pipeline
  [pipes feed]
  (->Pipeline (>pipes pipes) (volatile! feed) (volatile! 0) (volatile! nil)))
