(ns transmuter.guard)

(def stop (Object.))
(def void (Object.))
(def vacuum (Object.))

(defn stop?
  [this]
  (identical? this stop))

(defn void?
  [this]
  (identical? this void))

(defn vacuum?
  [this]
  (identical? this vacuum))

(deftype Injection [payload])

(defn injection?
  [this]
  (instance? Injection this))
