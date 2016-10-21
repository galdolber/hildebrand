(ns hildebrand.internal.platform.number
  (:require [cljs.nodejs :as nodejs]))

(defn ddb-num?
  "Is `x` a number type natively storable by DynamoDB? Note that DDB stores
  _all_ numbers as exact-value strings with <= 38 digits of precision.

  Ref. http://goo.gl/jzzsIW"
  [x]
  (number? x))

(defn string->number [s]
  (if (= -1 (.indexOf s "."))
    (js/parseInt s)
    (js/parseFloat s)))
