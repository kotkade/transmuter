(ns transmuter.feed
  (:import
    java.util.Iterator
    clojure.lang.ISeq
    clojure.lang.Seqable)
  (:require
    [transmuter.guard :refer [void vacuum]]))

(defprotocol Feed
  (poll! [this] "Retrieve the next value from the Feed. Returns guard/void
  when the feed is exhausted. May return guard/vacuum when a
  new value is not available, yet."))

(defprotocol Source
  (>feed [this] "Create a feed from the given input source."))

(defn -iterator-feed
  [^Iterator iter]
  (reify Feed
    (poll! [_this]
      (if (.hasNext iter)
        (.next iter)
        void))))

(defn -iterable-feed
  [^Iterable this]
  (-iterator-feed (.iterator this)))

(defn -seq-feed
  [s]
  (let [vs (volatile! s)]
    (reify Feed
      (poll! [_this]
        (if-let [s (vswap! vs seq)]
          (do
            (let [fst (first s)]
              (vswap! vs rest)
              fst))
          void)))))

(defn -seqable-feed
  [coll]
  (-seq-feed (seq coll)))

(defn -extend-feed
  [klass f]
  (extend klass Source {:>feed f}))

(extend-protocol Source
  Object
  (>feed [this]
    (cond
      (instance? Iterator this) (-extend-feed (class this) -iterator-feed)
      (instance? Iterable this) (-extend-feed (class this) -iterable-feed)
      (instance? ISeq this)     (-extend-feed (class this) -seq-feed)
      (instance? Seqable this)  (-extend-feed (class this) -seqable-feed)
      :else (throw (ex-info "Don't know how to create feed from class"
                            {:class (class this)})))
    (>feed this))

  nil
  (>feed [this] (reify Feed (poll! [_this] void))))

