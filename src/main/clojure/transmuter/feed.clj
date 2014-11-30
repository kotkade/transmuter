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
    clojure.lang.IFn
    clojure.lang.ISeq
    clojure.lang.Seqable)
  (:require
    [transmuter.guard :refer [void vacuum]]))

(defmacro ^:private -fswap!
  [v f & args]
  `(set! ~v (~f ~v ~@args)))

(defprotocol Source
  (>feed [this] "Create a feed from the given input source. A feed is a
  procedure, which either returns the next value from the source,
  guard/vacuum when a new value is not yet available or guard/void
  if the input source is exhausted."))

(defn >iterator-feed
  [^Iterator iter]
  (fn []
    (if (.hasNext iter)
      (.next iter)
      void)))

(defn >iterable-feed
  [^Iterable this]
  (>iterator-feed (.iterator this)))

(deftype SeqFeed [^:unsynchronized-mutable s]
  IFn
  (invoke [this]
    (if (-fswap! s seq)
      (let [f (first s)]
        (-fswap! s rest)
        f)
      void)))

(def >seq-feed ->SeqFeed)

(deftype ChunkedSeqFeed [^:unsynchronized-mutable cs
                         ^:unsynchronized-mutable ^ArrayChunk current-chunk
                         ^:unsynchronized-mutable ^long idx
                         ^:unsynchronized-mutable ^long end]
  IFn
  (invoke [this]
    (cond
      (< idx end)      (let [v (.nth current-chunk idx)]
                         (-fswap! idx inc)
                         v)
      (-fswap! cs seq) (do
                         (set! current-chunk (chunk-first cs))
                         (set! idx 0)
                         (set! end (.count current-chunk))
                         (-fswap! cs chunk-rest)
                         (recur))
      :else void)))

(defn >chunked-seq-feed
  [cs]
  (->ChunkedSeqFeed cs nil 0 0))

(defn >seqable-feed
  [coll]
  (let [s (seq coll)]
    (if (chunked-seq? s)
      (>chunked-seq-feed s)
      (>seq-feed s))))

(defn ^:private -extend-feed
  [klass f]
  (extend klass Source {:>feed f}))

(extend-protocol Source
  APersistentVector
  (>feed [this] (>chunked-seq-feed (seq this)))

  Object
  (>feed [this]
    (cond
      (instance? Iterator this) (-extend-feed (class this) >iterator-feed)
      (instance? Iterable this) (-extend-feed (class this) >iterable-feed)
      (instance? ISeq this)     (-extend-feed (class this) >seq-feed)
      (instance? Seqable this)  (-extend-feed (class this) >seqable-feed)
      :else (throw (ex-info "Don't know how to create feed from class"
                            {:class (class this)})))
    (>feed this))

  nil
  (>feed [this] (constantly void)))

