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
    [transmuter.feed :refer [>feed <value]]
    [transmuter.guard
     :refer [vacuum vacuum? stop stop? void void? injection?]])
  (:import
    transmuter.guard.Injection))

(defprotocol PipeDefinition
  (>pipe [this] "Initialize the pipe described by this definition."))

(extend-protocol PipeDefinition
  clojure.lang.APersistentVector
  (>pipe [this] (map >pipe this))

  clojure.lang.ISeq
  (>pipe [this] (map >pipe this))

  clojure.lang.AFn
  (>pipe [this] (this))

  nil
  (>pipe [this] nil))

(defn >pipes
  [pipes]
  (->> pipes
    (map >pipe)
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
  [^objects pipes
   ^:unsynchronized-mutable feed
   ^:unsynchronized-mutable step
   ^:unsynchronized-mutable shutdown
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
      (set! step (or s 0))
      (set! backlog (next backlog)))
    nil)

  (-process-input! [this]
    ; Get an input and start at the current step.
    (loop [x (<value feed)
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
            ; Mark the stopping step and escalate upwards.
            (stop? r)      (do (set! step n) stop)

            ; The transformation chose to elide the value.
            ; Continue with the current step.
            (void? r)      (recur (<value feed) step)

            ; The transformation wants to inject values. Eg. cat.
            ; Push the current feed and step position in the backlog.
            ; Continue with the injected feed and the next step.
            (injection? r) (do
                             (-push-feed! this
                                          (>feed (.payload ^Injection r))
                                          (inc n))
                             (recur (<value feed) (inc n)))

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
          ; Vacuum means we need more input to realize a value, but
          ; there is not enough input available.
          ; Escalate to upstream.
          (vacuum? r) vacuum

          ; A transformation asked to stop the pipeline processing.
          ; Calling the finish! methods of the pipeline steps, but
          ; ignore any additional values above the current position.
          ; Recur to finish down the pipeline.
          (stop? r)   (do
                        (set! feed nil)
                        (doseq [i (range shutdown step)]
                          (finish! (aget pipes i)))
                        (set! shutdown step)
                        (recur))

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
        (set! shutdown step)
        (cond
          ; There was either an injection or a singular value
          ; produced by the finalizer of this step. Push the feed.
          ; The values will be processed in the next iteration.
          (injection? r) (-push-feed! this (>feed (.payload ^Injection r)) step)
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
  (->APipeline (>pipes pipes) feed 0 0 nil))
