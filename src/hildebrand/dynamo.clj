(ns hildebrand.dynamo
  (:require
   [clojure.set :as set]
   [clojure.string :as str]
   [clojure.walk :as walk]
   [eulalie]
   [eulalie.dynamo]
   [glossop :refer :all :exclude [fn-> fn->>]]
   [hildebrand.dynamo.schema :as schema]
   [hildebrand.dynamo.expr :as expr :refer [flatten-expr]]
   [hildebrand.util :refer :all]
   [hildebrand.dynamo.util :refer :all]
   [plumbing.core :refer :all]
   [plumbing.map]))

(defn join-keywords [& args]
  (some->> args not-empty (map name) str/join keyword))

(defn from-attr-value [[tag value]]
  (condp = tag
    :S    value
    :N    (string->number value)
    :M    (map-vals (partial apply from-attr-value) value)
    :L    (mapv     (partial apply from-attr-value) value)
    :BOOL (boolean value)
    :NULL nil
    :SS   (into #{} value)
    :NS   (into #{} (map string->number value))))

(defn attr [t v] {t v})

(defn to-set-attr [v]
  (let [v (throw-empty v)]
    (cond
      (every? string? v)  {:SS (map fq-name v)}
      (every? ddb-num? v) {:NS (map str     v)}
      :else (throw (Exception. "Invalid set type")))))

(defn to-attr-value [v]
  (let [v (branch-> v keyword? fq-name)]
    (branch v
      string?  {:S (throw-empty v)}
      nil?     {:NULL true}
      boolean? {:BOOL v}
      ddb-num? {:N (str v)}
      vector?  {:L (map to-attr-value v)}
      map?     {:M (for-map [[k v'] v]
                     (name k) (to-attr-value v'))}
      set?     (to-set-attr v)
      (throw (Exception. (str "Invalid value " (type v)))))))

(defn key-schema-item [[k v]]
  {:attribute-name (name k)
   :key-type       v})

(def default-throughput {:read 1 :write 1})

(def ->throughput
  (key-renamer {:read  :read-capacity-units
                :write :write-capacity-units}))

(defn ->attr-value [[k v]]
  {:attribute-name (name k)
   :attribute-type v})

(def ->create-table
  (fn->>
   (transform-map
    {:table [:table-name name]
     :throughput [:provisioned-throughput
                  (fn->> (merge default-throughput) ->throughput)]
     :attrs [:attribute-definitions (partial map ->attr-value)]
     :keys [:key-schema (partial map key-schema-item)]})
   (schema/conforming schema/CreateTable*)))

(def ->delete-table
  (fn->> (transform-map {:table [:table-name name]})
         (schema/conforming schema/DeleteTable*)))

(def ->describe-table
  (fn->> (transform-map {:table [:table-name name]})
         (schema/conforming schema/DescribeTable*)))

(defn ->item-spec [m]
  (for-map [[k v] m]
    (name k) (to-attr-value v)))

(defmulti  raise-expression (fn [in-k type+out-k req] in-k))
(defmethod raise-expression :condition [in-k type req]
  ;; revisit this structure completely TODO
  (let [{:keys [attrs values] :as expr}
        (-> in-k req (assoc :hildebrand/type type))
        req (if (:hildebrand/expr (req in-k))
              ;; straight BS
              (assoc (dissoc req in-k) type (flatten-expr expr))
              (assoc req type (-> req in-k :expression)))]
    (println attrs values expr)
    (cond-> req
      (not-empty attrs)  (assoc :expression-attribute-names attrs)
      (not-empty values) (assoc :expression-attribute-values
                                (for-map [[k v] values]
                                  k (to-attr-value v))))))

(defn raise-update-expression [{:keys [update] :as req}]
  (let [{:keys [exprs attrs] :as update}
        (if (map? update)
          update
          {:exprs update})
        {:keys [values exprs]} (expr/build-update-expr exprs)]
    (cond-> (dissoc req :update)
      (not-empty values) (assoc :expression-attribute-values
                                (map-vals to-attr-value values))
      (not-empty attrs)  (assoc :expression-attribute-names attrs)
      (not-empty exprs)  (assoc :update-expression
                                (expr/flatten-update-expr exprs)))))

(def ->put-item
  (fn->>
   (transform-map
    {:table [:table-name name]
     :item  [:item ->item-spec]
     :return :return-values
     :capacity :return-consumed-capacity})
   (schema/conforming schema/PutItem*)))

(def ->get-item
  (fn->>
   (transform-map
    {:table [:table-name name]
     :key    ->item-spec
     :capacity :return-consumed-capacity
     :consistent :consistent-read})
   (schema/conforming schema/GetItem*)))

(def ->update-item
  (fn->>
   (transform-map
    {:table [:table-name name]
     :key   ->item-spec
     :return :return-values})
   raise-update-expression))

(def ->delete-item
  (fn->>
   (transform-map
    {:table [:table-name name]
     :key     ->item-spec
     :capacity :return-consumed-capacity
     :consistent :consistent-read})
   (raise-expression :condition :condition-expression)
   (schema/conforming schema/DeleteItem*)))

(defn defmulti-dispatch [method v->handler]
  (doseq [[v handler] v->handler]
    (defmethod method v [& args] (apply handler args))))

(defmulti transform-request :target)
(defmulti-dispatch
  transform-request
  (map-vals
   (fn [f] (fn-> :body f))
   {:create-table   ->create-table
    :put-item       ->put-item
    :delete-table   ->delete-table
    :get-item       ->get-item
    :delete-item    ->delete-item
    :describe-table ->describe-table
    :update-item    ->update-item}))

(def <-attr-def (juxt :attribute-name :attribute-type))

(def <-key-schema
  (partial keyed-map :attribute-name :key-type))

(def <-throughput
  (key-renamer
   {:read-capacity-units :read
    :write-capacity-units :write
    :number-of-decreases-today :decreases
    :last-increase-date-time :last-increase
    :last-decrease-date-time :last-decrease}))

(def <-projection
  (key-renamer
   {:non-key-attributes :attrs
    :projection-type    :type}))

(def <-global-index
  (map-transformer
   {:index-name :name
    :index-size-bytes :size
    :index-status :status
    :item-count :count
    :key-schema [:keys <-key-schema]
    :projection <-projection
    :provisioned-throughput [:throughput <-throughput]}))

;; that bidirectional transformation function though

(def <-table-description-body
  (map-transformer
   {:attribute-definitions [:attrs (fn->> (map <-attr-def) (into {}))]
    :key-schema [:keys <-key-schema]
    :provisioned-throughput [:throughput <-throughput]
    :table-status :status
    :table-size-byes :size
    :creation-date-time :created
    :global-secondary-indexes [:global-indexes (mapper <-global-index)]}))

(def <-create-table
  (fn->> :table-description <-table-description-body))

(def <-consumed-capacity
  (map-transformer
   {:consumed-capacity
    [:capacity (partial
                walk/prewalk-replace
                {:capacity-units :capacity
                 :table-name     :table})]}))

(defn <-get-item [{:keys [item] :as resp}]
  (let [resp (<-consumed-capacity resp)]
    (with-meta
      (map-vals (partial apply from-attr-value) item)
      (dissoc resp :item))))

(defn <-put-item [{:keys [attributes] :as resp}]
  (let [resp (<-consumed-capacity resp)]
    (with-meta
      (map-vals (partial apply from-attr-value) attributes)
      (dissoc resp :attributes))))

(defn <-delete-item [resp]
  (<-consumed-capacity resp))

(defmulti  transform-response :target)
(defmethod transform-response :default [{:keys [body]}] body)
(defmulti-dispatch
  transform-response
  (map-vals
   (fn [f] (fn-> :body f))
   {:create-table    <-create-table
    :delete-table    <-create-table
    :get-item        <-get-item
    :put-item        <-put-item
    :delete-item     <-delete-item
    :update-item     <-update-item
    :describe-table  (fn-> :table <-table-description-body)}))

(defmulti  transform-error (fn [{target :target {:keys [type]} :error}] [target type]))
(defmethod transform-error :default [m] m)
(defmulti-dispatch
  transform-error
  {[:describe-table :resource-not-found-exception]
   (fn-> (assoc :body {}) (dissoc :error))})

(defn issue-request! [creds {:keys [target] :as req}]
  (clojure.pprint/pprint (transform-request req))
  (go-catching
    (let [resp (-> (eulalie/issue-request!
                    eulalie.dynamo/service
                    creds
                    (assoc req :content (transform-request req)))
                   <?
                   (assoc :target target))]
      (if (:error resp)
        (transform-error    resp)
        (transform-response resp)))))

(def issue-request!! (comp <?! issue-request!))
