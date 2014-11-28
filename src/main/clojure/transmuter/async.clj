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
    [transmuter.guard :refer [vacuum vacuum? void void?]]
    [transmuter.pipeline :refer [>pipeline pull!]]
    [clojure.core.async :as async]))

(defn chan
  [input buf-or-n pipes]
  (let [inputv   (volatile! vacuum)
        feed     (fn []
                   (let [i @inputv]
                     (vreset! inputv vacuum)
                     (if-not (nil? i) i void)))
        pipeline (>pipeline pipes feed)
        output   (async/chan buf-or-n)]
    (async/go-loop [read? true]
      (when read? (vreset! inputv (async/<! input)))
      (let [r (pull! pipeline)]
        (cond
          (vacuum? r) (recur true)
          (void? r)   (do
                        (async/close! input)
                        (async/close! output))
          :else       (do (async/>! output r) (recur false)))))
    output))
