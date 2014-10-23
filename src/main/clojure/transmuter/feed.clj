(ns transmuter.feed
  (:require
    [transmuter.guard :refer [void vacuum]]))

(defprotocol Feed
  (poll! [this] "Retrieve the next value from the Feed. Returns guard/void
  when the feed is exhausted. May return guard/vacuum when a
  new value is not available, yet."))

(defprotocol Source
  (>feed [this] "Create a feed from the given input source."))
