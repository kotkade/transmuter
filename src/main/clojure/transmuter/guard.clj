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

(ns transmuter.guard)

(def stop (Object.))
(def void (Object.))
(def vacuum (Object.))

(def guards #{stop void vacuum})

(defn stop?
  [this]
  (identical? this stop))

(defn void?
  [this]
  (identical? this void))

(defn vacuum?
  [this]
  (identical? this vacuum))
