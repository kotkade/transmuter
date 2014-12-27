;-
; Copyright 2014 © Meikel Brandmeyer.
; All rights reserved.
;
; Licensed under the EUPL V.1.1 (cf. file EUPL-1.1 distributed with the
; source code.) Translations in other european languages available at
; https://joinup.ec.europa.eu/software/page/eupl.
;
; Alternatively, you may choose to use the software under the MIT license
; (cf. file MIT distributed with the source code).

(ns transmuter.feed
  (:import
    java.util.Iterator
    clojure.lang.ArrayChunk
    clojure.lang.APersistentVector
    clojure.lang.ChunkedCons
    clojure.lang.IFn
    clojure.lang.ISeq
    clojure.lang.Seqable)
  (:require
    [transmuter.guard :refer [void vacuum]]
    [clojure.string :as string]))

(defmacro ^:private -fswap!
  [v f & args]
  `(set! ~v (~f ~v ~@args)))

(defprotocol Feed
  (<value [this]
  "Read one value from the feed, guard/vacuum when a new value is not
  yet available or guard/void if the input source is exhausted.")
  (stop! [this]
  "Stop this feed unconditionally and don't do any further processing.
  In case resources have to be released, this is the place to do it.")
  (finish! [this]
  "Called after the last value is processed. May return nil or a collection
  of final values to be processed by the consumer of this feed. A feed
  return nil on finish! should also extend the Endpoint marker protocol."))

;; Special type of feed, which will return nil on `finish!`.
(defprotocol Endpoint)

(extend-protocol Endpoint nil)

(extend-protocol Feed
  nil
  (<value  [this] void)
  (stop!   [this] nil)
  (finish! [this] nil))

(defn >iterator-feed
  [^Iterator iter]
  (reify Endpoint Feed
    (stop!   [this] nil)
    (finish! [this] nil)
    (<value [this]
      (if (.hasNext iter)
        (.next iter)
        void))))

(defn >iterable-feed
  [^Iterable this]
  (>iterator-feed (.iterator this)))

(deftype SeqFeed [^:unsynchronized-mutable s
                  ^:unsynchronized-mutable ^ArrayChunk current-chunk
                  ^:unsynchronized-mutable ^long idx
                  ^:unsynchronized-mutable ^long end]
  Endpoint
  Feed
  (stop!   [this] nil)
  (finish! [this] nil)
  (<value [this]
    (cond
      current-chunk   (if (< idx end)
                         (let [v (.nth current-chunk idx)]
                           (-fswap! idx inc)
                           v)
                         (do
                           (set! current-chunk nil)
                           (recur)))
      (-fswap! s seq) (if (chunked-seq? s)
                        (do
                          (set! current-chunk (chunk-first s))
                          (set! idx 0)
                          (set! end (long (.count current-chunk)))
                          (-fswap! s chunk-rest)
                          (recur))
                        (let [v (first s)]
                          (-fswap! s rest)
                          v))
      :else void)))

(defn >seq-feed
  [s]
  (->SeqFeed s nil 0 0))

(defn >seqable-feed
  [coll]
  (>seq-feed (seq coll)))

(defprotocol Source
  (>feed* [this] "Create a feed from the given input source."))

(defmacro defarrayfeed
  [elem-type]
  (let [elem-type-name (name elem-type)
        feed-type      (-> elem-type-name
                         string/capitalize
                         (str "ArrayFeed")
                         symbol)
        ctor-name      (symbol (str ">" elem-type-name "-array-feed"))
        prototype      (symbol (str (subs elem-type-name
                                          0 (dec (count elem-type-name)))
                                    "-array"))]
    `(do
       (deftype ~feed-type
         [~(with-meta 'array {:tag elem-type})
          ~(with-meta 'idx {:tag 'long :unsynchronized-mutable true})]
         Endpoint
         Feed
         (stop!   [this#] nil)
         (finish! [this#] nil)
         (<value [this#]
           (if (< ~'idx (alength ~'array))
             (do
               (let [v# (aget ~'array ~'idx)]
                 (-fswap! ~'idx inc)
                 v#))
             void)))
       (defn ~ctor-name
         [array#]
         (~(symbol (str "->" feed-type)) array# 0))
       (extend (class (~prototype 0))
         Source {:>feed* ~ctor-name}))))

(defarrayfeed objects)
(defarrayfeed bytes)
(defarrayfeed chars)
(defarrayfeed ints)
(defarrayfeed longs)
(defarrayfeed floats)
(defarrayfeed doubles)

(defn ^:private -extend-source
  [klass f]
  (extend klass Source {:>feed* f}))

(extend-protocol Source
  APersistentVector
  (>feed* [this] (>seq-feed (seq this)))

  ChunkedCons
  (>feed* [this] (>seq-feed (seq this)))

  Object
  (>feed* [this]
    (cond
      (.isArray (class this))   (-extend-source (class this) >objects-array-feed)
      (instance? Iterator this) (-extend-source (class this) >iterator-feed)
      (instance? ISeq this)     (-extend-source (class this) >seq-feed)
      (instance? Iterable this) (-extend-source (class this) >iterable-feed)
      (instance? Seqable this)  (-extend-source (class this) >seqable-feed)
      :else (throw (ex-info "Don't know how to create feed from class"
                            {:class (class this)})))
    (>feed* this))

  nil
  (>feed* [this] nil))

(defn >feed
  [this]
  (cond-> this (not (satisfies? Feed this)) >feed*))
