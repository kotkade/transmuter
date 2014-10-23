(ns transmuter.pipeline
  (:require
    [transmuter.feed :as feed]
    [transmuter.guard :as g]))

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

(defrecord Pipeline [pipes feed step backlog])
