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

(defprotocol Source
  (>feed [this] "Create a feed from the given input source."))

(defprotocol Feed
  (<value [this]
  "Read one value from the feed, guard/vacuum when a new value is not
  yet available or guard/void if the input source is exhausted."))

(extend-protocol Feed
  Object
  (<value [this] void)

  nil
  (<value [this] void))

(defn >iterator-feed
  [^Iterator iter]
  (reify Feed
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
  Feed
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
         Feed
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
         Source {:>feed ~ctor-name}))))

(defarrayfeed objects)
(defarrayfeed bytes)
(defarrayfeed chars)
(defarrayfeed ints)
(defarrayfeed longs)
(defarrayfeed floats)
(defarrayfeed doubles)

(defn ^:private -extend-feed
  [klass f]
  (extend klass Source {:>feed f}))

(extend-protocol Source
  APersistentVector
  (>feed [this] (>seq-feed (seq this)))

  ChunkedCons
  (>feed [this] (>seq-feed (seq this)))

  Object
  (>feed [this]
    (cond
      (satisfies? Feed this)    (-extend-feed (class this) identity)
      (.isArray (class this))   (-extend-feed (class this) >objects-array-feed)
      (instance? Iterator this) (-extend-feed (class this) >iterator-feed)
      (instance? ISeq this)     (-extend-feed (class this) >seq-feed)
      (instance? Iterable this) (-extend-feed (class this) >iterable-feed)
      (instance? Seqable this)  (-extend-feed (class this) >seqable-feed)
      :else (throw (ex-info "Don't know how to create feed from class"
                            {:class (class this)})))
    (>feed this))

  nil
  (>feed [this] (constantly void)))
