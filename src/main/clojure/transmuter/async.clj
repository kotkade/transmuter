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

(ns transmuter.async
  (:require
    [transmuter.guard    :refer [vacuum void]]
    [transmuter.feed     :refer [<value Feed]]
    [transmuter.pipeline :refer [>pipeline]]
    [clojure.core.async :as async]))

(defn chan
  ([input pipes] (chan input 0 pipes))
  ([input buf-or-n pipes]
   (let [inputv   (volatile! vacuum)
         feed     (reify Feed
                    (<value [this]
                      (let [i @inputv]
                        (vreset! inputv vacuum)
                        (if-not (nil? i) i void))))
         pipeline (>pipeline pipes feed)
         output   (async/chan buf-or-n)]
     (async/go-loop [read? true]
       (when read? (vreset! inputv (async/<! input)))
       (let [r (<value pipeline)]
         (condp identical? r
           vacuum (recur true)
           void   (do
                    (async/close! input)
                    (async/close! output))
           (do
             (async/>! output r)
             (recur false)))))
     output)))
