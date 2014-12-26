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
  (:require
    [transmuter.feed :refer [<value >feed Feed]]
    [transmuter.guard :refer [void? void stop? stop guards]]
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

(defprotocol Pipe
  (<feed   [this]
  "Returns the next input value for this pipe step.")
  (process [this x]
  "Process the given value and return a result. May return
  void to ignore the processed value, an Injection to explode
  the processed value into several or stop to stop processing
  any further values.")
  (stop! [this]
  "Stop this pipe step and its feed unconditionally and don't
  do any further processing. This is called only internally
  and should not be used directly by the user!")
  (finish! [this]
  "Called after the last value is processed. May return nil,
  a final value or a final Injection with additional values.
  Must be called once and only once."))

(extend-protocol Pipe
  Object
  (<feed   [this]   nil)
  (process [this x] x)
  (stop!   [this]   nil)
  (finish! [this]   nil)

  nil
  (<feed   [this]   nil)
  (process [this x] x)
  (stop!   [this]   nil)
  (finish! [this]   nil))

(deftype Pipeline [^:unsynchronized-mutable inner-pipe]
  Feed
  (<value [this]
    (let [value (<value inner-pipe)]
      (cond
        (void? value) (if-let [final-values (finish! inner-pipe)]
                        (do
                          (set! inner-pipe (>feed final-values))
                          (recur))
                        void)
        (stop? value) void
        :else value))))

(defn >pipeline
  [pipes input]
  (->Pipeline (>pipe pipes input)))

(defn ^:private >pipe-type
  [s]
  (-> s
    name
    (str "-pipe")
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
  (let [[docstring args & {:keys [state <feed process stop! finish!]}]
        (fn-tail body)
        <feed        (or <feed `(<value ~'feed))
        process      (or process `([value#] value#))
        state-defs   (take-nth 2 state)
        state-locals (map #(with-meta % nil) state-defs)
        state-inits  (take-nth 2 (next state))
        pname        (vary-meta pname update-in [:doc] (fnil identity docstring))
        wrap         (if (pos? (count args))
                       (fn [body] `(fn ~args ~body))
                       (fn [body] body))
        tname        (>pipe-type pname)]
    `(do
       (deftype ~tname
         ~(into '[^:unsynchronized-mutable feed] state-defs)
         Pipe
         (<feed   [this#] ~(or <feed `(<value ~'feed)))
         (process [this# ~@(first process)] ~@(next process))
         (stop!   [this#] (stop! ~'feed) ~stop!)
         (finish! [this#] (set! ~'feed nil) (stop! this#) ~finish!)
         Feed
         (<value [this#]
           (let [value# (<feed this#)]
             (cond
               (void? value#)
               (if-let [final-value# (finish! ~'feed)]
                 (do
                   (set! ~'feed (>feed final-value#))
                   (recur))
                 void)

               (stop? value#)            void
               (contains? guards value#) value#

               :else
               (let [inner-value# (process this# value#)]
                 (if (void? inner-value#)
                   (recur)
                   inner-value#))))))
       (def ~pname
         ~(wrap
            `(fn [input#]
               (let ~(vec (interleave state-locals state-inits))
                 (new ~tname input# ~@state-locals))))))))
