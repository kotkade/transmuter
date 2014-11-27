(ns transmuter.core
  (:refer-clojure
    :exclude [sequence map cat mapcat filter remove
              take take-while drop drop-while distinct])
  (:require
    [transmuter.feed :refer [>feed]]
    [transmuter.guard
     :refer [vacuum vacuum? stop stop? void void? ->Injection injection?]]
    [transmuter.pipeline :refer [>pipeline pull! Pipe]]
    [clojure.string :as string])
  (:import
    transmuter.guard.Injection))

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

(defn ^:private >pipe-type
  [s]
  (-> s
    name
    (str "-pipe")
    (.split "-")
    (->>
      (cc/map string/capitalize)
      (apply str)
      symbol)))

(defmacro fswap!
  [field f & args]
  `(set! ~field (~f ~field ~@args)))

(defn ^:private fn-tail
  [body]
  (if (string? (first body))
    (list* (first body) (next body))
    (list* nil body)))

(defmacro defpipe
  [pname & body]
  (let [[docstring args & {:keys [state process finish!]}] (fn-tail body)
        pname (vary-meta pname update-in [:doc] #(or % docstring))
        wrap  (if (pos? (count args))
                (fn [body] `(fn ~args (fn [] ~body)))
                (fn [body] `(fn [] ~body)))]
    (if state
      (let [tname (>pipe-type pname)]
        `(do
           (deftype ~tname ~(vec (take-nth 2 state))
             Pipe
             (process [this# ~@(first process)] ~@(next process))
             (finish! [this#] ~finish!))
           (def ~pname
             ~(wrap `(new ~tname ~@(take-nth 2 (next state)))))))
      `(def ~pname
         ~(wrap `(reify Pipe
                   (process [this# ~@(first process)] ~@(next process))
                   (finish! [this#] ~finish!)))))))

(defpipe map
  [f]
  :process ([x] (f x)))

(def cat (map ->Injection))

(defn mapcat
  [f]
  [(map f) cat])

(defpipe filter
  [pred]
  :process ([x] (if (pred x) x void)))

(defn remove
  [pred]
  (filter (complement pred)))

(defpipe take
  [n]
  :state   [^:unsynchronized-mutable ^long n (dec n)]
  :process ([x] (if-not (neg? n) (do (fswap! n dec) x) stop)))

(defpipe take-while
  [pred]
  :process ([x] (if (pred x) x stop)))

(defpipe drop
  [n]
  :state   [^:unsynchronized-mutable ^long n (dec n)]
  :process ([x] (if-not (neg? n) (do (fswap! n dec) void) x)))

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
