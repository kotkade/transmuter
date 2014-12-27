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

(ns transmuter.core
  (:refer-clojure
    :exclude [cat
              butlast
              dedupe
              distinct
              drop
              drop-last
              drop-while
              filter
              interpose
              into
              keep
              keep-indexed
              map
              map-indexed
              mapcat
              partition
              partition-all
              partition-by
              remove
              repeat
              replace
              reverse
              sequence
              shuffle
              sort
              sort-by
              take
              take-last
              take-nth
              take-while])
  (:require
    [transmuter.feed     :refer [<value stop! finish! >feed Feed Endpoint]]
    [transmuter.guard    :refer [vacuum stop void]]
    [transmuter.pipeline :refer [>pipeline defpipe fswap!]])
  (:import
    clojure.lang.PersistentQueue))

(alias 'cc 'clojure.core)

(defn transmute
  "Reduces the collection according to the reducing function f. Each
  value is transformed by virtue of the given pipe steps. The initial
  value for the reduction is obtained by calling the f without arguments.
  For each transformed value f is called with the accumulator and the
  value. After the input is exhausted f is called once with only the
  accumulator."
  [pipes f coll]
  (let [pipeline (>pipeline pipes (>feed coll))]
    (loop [acc (f)]
      (let [x (<value pipeline)]
        (if-not (identical? x void)
          (recur (f acc x))
          (f acc))))))

(defn sequence
  "Creates a lazy sequence based on the transformed values of the
  input."
  ([coll] (sequence nil coll))
  ([pipes coll]
   (let [pipeline (>pipeline pipes (>feed coll))
         step     (fn step []
                    (lazy-seq
                      (let [x (<value pipeline)]
                        (when-not (identical? x void)
                          (cons x (step))))))]
     (step))))

(defn into
  "Returns a new collection consisting of coll with all of the
  items of the input conjoined. Each input value is transformed
  by the given pipes."
  [coll pipes input]
  (let [f (if (instance? clojure.lang.IEditableCollection coll)
            (fn
              ([]      (transient coll))
              ([acc]   (with-meta (persistent! acc) (meta coll)))
              ([acc x] (conj! acc x)))
            (fn
              ([]      coll)
              ([acc]   acc)
              ([acc x] (conj acc x))))]
    (transmute pipes f input)))

(defpipe map
  [f]
  :state   [f f]
  :process ([x] (f x)))

(defpipe map-indexed
  [f]
  :state   [f f
            ^:unsynchronized-mutable ^long n -1]
  :process ([x] (f (fswap! n inc) x)))

(defpipe cat
  []
  :state [inner-feed (volatile! nil)]
  :feed  (loop []
           (let [v (<value @inner-feed)]
             (if (identical? v void)
               (let [iv (<value feed)]
                 (condp identical? iv
                   void   void
                   stop   stop
                   vacuum vacuum
                   (do (vreset! inner-feed (>feed iv)) (recur))))
               v))))

(defn mapcat
  [f]
  [(map f) cat])

(defpipe keep
  [f]
  :state   [f f]
  :process ([x]
             (let [r (f x)]
               (if-not (nil? r)
                 r
                 void))))

(defpipe keep-indexed
  [f]
  :state   [f f
            ^:unsynchronized-mutable ^long n -1]
  :process ([x]
             (let [r (f (fswap! n inc) x)]
               (if-not (nil? r)
                 r
                 void))))

(defpipe filter
  [pred]
  :state   [pred pred]
  :process ([x] (if (pred x) x void)))

(defn remove
  [pred]
  (filter (complement pred)))

(defpipe take
  [n]
  :state   [^:unsynchronized-mutable ^long n (dec n)]
  :process ([x] (if-not (neg? n) (do (fswap! n dec) x) stop)))

(defpipe take-nth
  [n]
  :state   [^long n (dec n)
            ^:unsynchronized-mutable ^long m 0]
  :process ([x]
             (cond
               (== n m)  (do (set! m 0) void)
               (zero? m) (do (fswap! m inc) x)
               :else     (do (fswap! m inc) void))))

(defpipe take-last
  [n]
  :state   [n n
            ^:unsynchronized-mutable batch PersistentQueue/EMPTY]
  :process ([x]
             (when (= (count batch) n)
               (fswap! batch pop))
             (fswap! batch conj x)
             void)
  :finish! (seq batch))

(defpipe take-while
  [pred]
  :state   [pred pred]
  :process ([x] (if (pred x) x stop)))

(defpipe drop
  [n]
  :state   [^:unsynchronized-mutable ^long n (dec n)]
  :process ([x] (if-not (neg? n) (do (fswap! n dec) void) x)))

(defpipe drop-last
  [n]
  :state   [n n
            ^:unsynchronized-mutable batch PersistentQueue/EMPTY]
  :process ([x]
             (fswap! batch conj x)
             (if (> (count batch) n)
               (let [y (peek batch)]
                 (fswap! batch pop)
                 y)
               void)))

(defpipe drop-while
  [pred]
  :state   [pred pred
            ^:unsynchronized-mutable drop? true]
  :process ([x]
             (when drop? (set! drop? (pred x)))
             (if-not drop? x void)))

(defpipe distinct
  []
  :state   [^:unsynchronized-mutable seen? (transient {})]
  :process ([x]
             (if-not (seen? x)
               (do (fswap! seen? assoc! x true) x)
               void)))

(defpipe dedupe
  []
  :state [^:unsynchronized-mutable prev (Object.)]
  :process ([x]
             (if (not= x prev)
               (do (set! prev x) x)
               void)))

(defpipe interpose
  [sep]
  :state [sep sep
          ^:unsynchronized-mutable first? false
          ^:unsynchronized-mutable elem void]
  :feed  (cond
           first?                 (do (set! first? false) (<value feed))
           (identical? elem void) (let [e (<value feed)]
                                    (condp identical? e
                                      void   void
                                      stop   stop
                                      vacuum vacuum
                                      (do (set! elem e) sep)))
           :else                  (let [e elem] (set! elem void) e)))

(defpipe butlast
  []
  :state   [^:unsynchronized-mutable last-elem void]
  :process ([x]
             (let [prev-elem last-elem]
               (set! last-elem x)
               prev-elem)))

(defpipe sort
  []
  :state   [^:unsynchronized-mutable v (transient [])]
  :process ([x] (fswap! v conj! x) void)
  :finish! (-> v persistent! cc/sort))

(defpipe sort-by
  [sort-args]
  :state   [sorter (apply partial cc/sort-by sort-args)
            ^:unsynchronized-mutable v (transient [])]
  :process ([x] (fswap! v conj! x) void)
  :finish! (-> v persistent! sorter))

(defpipe replace
  [replacements]
  :state   [replacements replacements]
  :process ([x] (get replacements x x)))

(defpipe reverse
  []
  :state   [^:unsynchronized-mutable v (transient [])]
  :process ([x] (fswap! v conj! x) void)
  :finish! (-> v persistent! rseq))

(defpipe shuffle
  []
  :state   [^:unsynchronized-mutable v (transient [])]
  :process ([x] (fswap! v conj! x) void)
  :finish! (-> v persistent! cc/shuffle))

(defpipe full-partition
  [n pad all?]
  :state   [pad     pad
            all?    all?
            ^long n n
            ^:unsynchronized-mutable batch (transient [])]
  :process ([x]
             (fswap! batch conj! x)
             (if (= (count batch) n)
               (let [b (persistent! batch)]
                 (set! batch (transient []))
                 b)
               void))
  :finish! (when (and (or pad all?) (pos? (count batch)))
             [(persistent!
                (reduce conj! batch (cc/take (- n (count batch)) pad)))]))

(defpipe offset-partition
  [n step pad all?]
  :state   [pad          pad
            all?         all?
            ^long n      n
            ^long offset (- n step)
            ^:unsynchronized-mutable history PersistentQueue/EMPTY
            ^:unsynchronized-mutable batch   (transient [])]
  :process ([x]
             (when (= (count history) offset)
               (fswap! history pop))
             (fswap! history conj x)
             (fswap! batch conj! x)
             (if (= (count batch) n)
               (let [b (persistent! batch)]
                 (set! batch (reduce conj! (transient []) history))
                 b)
               void))
  :finish! (when (or pad all?)
             [(persistent!
                (reduce conj! batch (cc/take (- n (count batch)) pad)))]))

(defn partition
  ([n]          (partition n n nil))
  ([n step]     (partition n step nil))
  ([n step pad] (if (= n step)
                  (full-partition n pad false)
                  (offset-partition n step pad false))))

(defn partition-all
  ([n]      (partition-all n n))
  ([n step] (if (= n step)
              (full-partition n nil true)
              (offset-partition n step nil true))))

(defpipe partition-by
  [f]
  :state   [f f
            ^:unsynchronized-mutable prev  (Object.)
            ^:unsynchronized-mutable batch nil]
  :process ([x]
             (let [v (f x)]
               (if (= v prev)
                 (do (fswap! batch conj! x) void)
                 (let [b (if batch (persistent! batch) void)]
                   (set! batch (transient [x]))
                   (set! prev v)
                   b))))
  :finish! (when batch (persistent! batch)))

(defn repeat
  [elem]
  (reify
    Endpoint
    Feed
    (<value [this] elem)
    clojure.lang.Seqable
    (seq [this] (sequence nil this))))
