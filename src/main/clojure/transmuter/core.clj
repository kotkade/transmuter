(ns transmuter.core
  (:refer-clojure
    :exclude [sequence map cat mapcat filter remove
              take take-while drop drop-while distinct])
  (:require
    [transmuter.feed :refer [>feed]]
    [transmuter.guard
     :refer [vacuum vacuum? stop stop? void void? injection?]]
    [transmuter.pipeline :refer [>pipeline pull!]])
  (:import
    transmuter.guard.Injection))

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
      (let [x (pull! pipeline)]
        (if-not (void? x)
          (recur (f acc x))
          (f acc))))))

(defn sequence
  "Creates a lazy sequence based on the transformed values of the
  input."
  [pipes coll]
  (let [pipeline (>pipeline pipes (>feed coll))
        step     (fn step []
                   (lazy-seq
                     (let [x (pull! pipeline)]
                       (when-not (void? x)
                         (cons x (step))))))]
    (step)))

(defmacro defstep
  [name args & body]
  `(defn ~name
     ~args
     (fn [] ~@body)))

(defstep map [f] f)

(defn cat
  []
  (fn [x] (Injection. x)))

(defn mapcat
  [f]
  [(map f) cat])

(defstep filter
  [pred]
  (fn [x] (if (pred x) x void)))

(defn remove
  [pred]
  (filter (complement pred)))

(defstep take
  [n]
  (let [vn (volatile! (dec n))]
    (fn [x]
      (if-not (neg? @vn)
        (do (vswap! vn dec) x)
        stop))))

(defstep take-while
  [pred]
  (fn [x] (if (pred x) x stop)))

(defstep drop
  [n]
  (let [vn (volatile! (dec n))]
    (fn [x]
      (if-not (neg? @vn)
        (do (vswap! vn dec) void)
        x))))

(defstep drop-while
  [pred]
  (fn [x] (if (pred x) void x)))

(defstep distinct
  []
  (let [seen? (volatile! #{})]
    (fn [x]
      (if-not (contains? @seen? x)
        (do (vswap! seen? conj x) x)
        void))))
