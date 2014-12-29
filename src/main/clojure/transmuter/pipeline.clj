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

(ns transmuter.pipeline
  (:import
    clojure.lang.Seqable)
  (:require
    [transmuter.feed  :refer [<value finish! stop! >feed Source Feed Endpoint]]
    [transmuter.guard :refer [void stop vacuum]]
    [clojure.string :as string]))

(defprotocol PipeDefinition
  (>pipe [this feed] "Initialize the pipe described by this definition."))

(extend-protocol PipeDefinition
  clojure.lang.APersistentVector
  (>pipe [this feed] (>pipe (seq this) feed))

  clojure.lang.ISeq
  (>pipe [this feed] (reduce #(>pipe %2 %1) feed this))

  clojure.lang.AFn
  (>pipe [this feed] (this feed))

  nil
  (>pipe [this feed] feed))

(defmacro fswap!
  [field f & args]
  `(set! ~field (~f ~field ~@args)))

(deftype Pipeline [^:unsynchronized-mutable inner-feed]
  Endpoint
  Feed
  (stop!   [this] (stop! inner-feed) nil)
  (finish! [this] nil)
  (<value [this]
    (let [value (<value inner-feed)]
      (condp identical? value
        void (if-let [final-values (finish! inner-feed)]
               (do (set! inner-feed (>feed final-values)) (recur))
               void)
        value))))

(defn >pipeline
  [pipes input]
  (let [pipeline (>pipe pipes input)]
    (cond-> pipeline
      (not (satisfies? Endpoint pipeline)) ->Pipeline)))

(defn ^:private >type-name
  [s postfix]
  (-> s
    name
    (str "-" postfix)
    (.split "-")
    (->>
      (map string/capitalize)
      (apply str)
      symbol)))

(defn ^:private fn-tail
  [body]
  (if (string? (first body))
    (list* (first body) (next body))
    (list* nil body)))

(defmacro defpipe
  [pname & body]
  (let [[docstring args & {:keys [state feed process stop! finish!]}]
        (fn-tail body)
        feed         (or feed `(<value ~'feed))
        value        (gensym "value_")
        this         (gensym "this_")
        process      (when process
                       (fn [value-sym]
                         `(let ~(conj (first process) value-sym)
                            ~@(next process))))
        state-defs   (take-nth 2 state)
        state-locals (map #(with-meta % nil) state-defs)
        state-inits  (take-nth 2 (next state))
        pname        (vary-meta pname update-in [:doc] (fnil identity docstring))
        wrap         (if (pos? (count args))
                       (fn [body] `(fn ~args ~body))
                       (fn [body] body))
        tname        (>type-name pname "pipe")]
    `(do
       (deftype ~tname
         ~(into '[^:unsynchronized-mutable feed] state-defs)
         ~@(when-not finish! `[Endpoint])
         Feed
         (stop!   [~this] (stop! ~'feed) ~stop!)
         (finish! [~this] ~finish!)
         (<value [~this]
           (let [~value ~feed]
             (condp identical? ~value
               void   (if-let [final-values# (finish! ~'feed)]
                        (do (set! ~'feed (>feed final-values#)) (recur))
                        (do ~stop! void))
               vacuum vacuum
               ~(if process
                  `(let [inner-value# ~(process value)]
                     (condp identical? inner-value#
                       void (recur)
                       stop (do (stop! ~this) void)
                       inner-value#))
                  value)))))
       (def ~pname
         ~(wrap
            `(fn [input#]
               (let ~(vec (interleave state-locals state-inits))
                 (new ~tname input# ~@state-locals))))))))

(defmacro defproducer
  [pname & body]
  (let [[docstring args & {:keys [state feeder]}]
        (fn-tail body)
        state-defs   (take-nth 2 state)
        state-locals (map #(with-meta % nil) state-defs)
        state-inits  (take-nth 2 (next state))
        pname        (vary-meta pname update-in [:doc] (fnil identity docstring))
        tname        (>type-name pname "feed")]
    `(do
       (deftype ~tname
         ~(vec state-defs)
         Endpoint
         Feed
         (stop!   [this#] nil)
         (finish! [this#] nil)
         (<value  [this#] ~feeder))
       (defn ~pname
         ~args
         (let [f (fn []
                   (let ~(vec (interleave state-locals state-inits))
                     (new ~tname ~@state-locals)))
               s (delay (transmuter.core/sequence (f)))]
           (reify
             Source
             (>feed* [this#] (f))
             Seqable
             (seq [this#] @s)))))))
