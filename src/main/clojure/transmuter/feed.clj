(ns transmuter.feed
  (:import
    java.util.Iterator
    clojure.lang.ISeq
    clojure.lang.Seqable)
  (:require
    [transmuter.guard :refer [void vacuum]]))

(defprotocol Source
  (>feed [this] "Create a feed from the given input source. A feed is a
  procedure, which either returns the next value from the source,
  guard/vacuum when a new value is not yet available or guard/void
  if the input source is exhausted."))

(defn -iterator-feed
  [^Iterator iter]
  (fn []
    (if (.hasNext iter)
      (.next iter)
      void)))

(defn -iterable-feed
  [^Iterable this]
  (-iterator-feed (.iterator this)))

(defn -seq-feed
  [s]
  (let [vs (volatile! s)]
    (fn []
      (if-let [s (vswap! vs seq)]
        (do
          (let [fst (first s)]
            (vswap! vs rest)
            fst))
        void))))

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
  (>feed [this] (constantly void)))

