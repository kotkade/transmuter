(ns transmuter.pipeline
  (:refer-clojure
    :exclude [sequence map cat mapcat filter remove
              take take-while drop drop-while distinct])
  (:require
    [transmuter.feed :refer [>feed]]
    [transmuter.guard
     :refer [vacuum vacuum? stop stop? void void? injection?]])
  (:import
    transmuter.guard.Injection))

(alias 'cc 'clojure.core)

(defprotocol PipeDefinition
  (>pipe [this] "Initialize the pipe described by this definition."))

(extend-protocol PipeDefinition
  clojure.lang.APersistentVector
  (>pipe [this] (cc/map >pipe this))

  clojure.lang.ISeq
  (>pipe [this] (cc/map >pipe this))

  clojure.lang.AFn
  (>pipe [this] (this))

  nil
  (>pipe [this] nil))

(defn >pipes
  [pipes]
  (->> pipes
    (cc/map >pipe)
    flatten
    (into-array Object)))

(defprotocol Pipe
  (process [this x]
  "Process the given value and return a result. May return
  void to ignore the processed value, an Injection to explode
  the processed value into several or stop to stop processing
  any further values.")
  (finish! [this]
  "Called after the last value is processed. May return nil,
  a final value or a final Injection with additional values.
  Must be called once and only once."))

(extend-protocol Pipe
  clojure.lang.AFn
  (process [this x] (this x))
  (finish! [this]   nil))

(defprotocol Pipeline
  (pull!           [this])
  (-push-feed!     [this feed step])
  (-pop-feed!      [this])
  (-process-input! [this]))

(deftype APipeline
  [pipes
   ^:unsynchronized-mutable feed
   ^:unsynchronized-mutable step
   ^:unsynchronized-mutable backlog]
  Pipeline
  (-push-feed! [this f s]
    (set! backlog (conj backlog [feed step]))
    (set! feed f)
    (set! step s)
    nil)

  (-pop-feed! [this]
    (let [[f s] (first backlog)]
      (set! feed f)
      (set! step s)
      (set! backlog (next backlog)))
    nil)

  (-process-input! [this]
    ; Get an input and start at the current step.
    (loop [x (feed)
           n step]
      (cond
        ; We actually tried to read from the feed but there was
        ; nothing available. That also means we talk about the
        ; initial feed and the first pipeline step. Inner steps
        ; may only inject values. Escalate to upstream.
        (vacuum? x) vacuum

        ; The input is void. So no further input in this feed.
        ; Escalate to upstream.
        (void? x)   void

        ; There is some input and we got more steps left.
        (< n (alength pipes))
        ; Run the transformation.
        (let [r (process (aget pipes n) x)]
          (cond
            ; The transformation requested to stop here.
            ; Escalate upwards.
            (stop? r)      stop

            ; The transformation chose to elide the value.
            ; Continue with the current step.
            (void? r)      (recur (feed) n)

            ; The transformation wants to inject values. Eg. cat.
            ; Push the current feed and step position in the backlog.
            ; Continue with the injected feed and the next step.
            (injection? r) (do
                             (-push-feed! this
                                          (>feed (.payload ^Injection r))
                                          (inc n))
                             (recur (feed) (inc n)))

            ; The transform transformed, but returned a single value.
            ; Proceed with the next step.
            :else          (recur r (inc n))))

        ; We reached the last of the pipeline steps without a special
        ; value. Return the value upstream.
        :else x)))

  (pull! [this]
    (cond
      ; There is a feed. Process inputs until a value is realized
      ; at the end of the pipeline.
      feed
      (let [r (-process-input! this)]
        (cond
          ; Vaccum means we need more input to realize a value, but
          ; there is not enough input available.
          ; Escalate to upstream.
          (vacuum? r) vacuum

          ; A transformation asked to stop the pipeline processing.
          ; Call the finish! methods of the pipeline steps, but
          ; ignore any additional values. Return void upstream.
          (stop? r)   (do
                        (doseq [i (range step (alength pipes))]
                          (finish! (aget pipes i)))
                        void)

          ; The current feed ran out of values. Pop the feed and
          ; try again.
          (void? r)   (do (-pop-feed! this) (recur))

          ; We produced a regular value. Return it upstream.
          :else       r))

      ; There is no feed left. Even in the backlog. finish! the
      ; pipeline steps and mop up any late values.
      (< step (alength pipes))
      (let [r (finish! (aget pipes step))]
        (set! step (inc step))
        (cond
          ; There was either an injection or a singular value
          ; produced by the finalizer of this step. Push the feed.
          ; The values will be processed in the next iteration.
          (injection? r) (-push-feed! this (>feed (.payload r)) step)
          r              (-push-feed! this (>feed [r]) step))
        ; In case nil was returned by the finalizer nothing
        ; happens. In the next iteration the next finalizer
        ; will be called.
        (recur))

      ; We are done. All input feeds are exhausted and all
      ; finalizers were called. Return void upstream.
      :else void)))

(defn >pipeline
  [pipes feed]
  (->APipeline (>pipes pipes) feed 0 nil))

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
