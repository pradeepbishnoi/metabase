(ns metabase.models.card
  (:require [clojure.core.match :refer [match]]
            [korma.core :refer :all, :exclude [defentity update]]
            [medley.core :as m]
            [metabase.db :refer :all]
            (metabase.models [dependency :as dependency]
                             [interface :refer :all]
                             [revision :as revision]
                             [user :refer [User]])))

(def ^:const display-types
  "Valid values of `Card.display_type`."
  #{:area
    :bar
    :country
    :line
    :pie
    :pin_map
    :scalar
    :state
    :table
    :timeseries})

(defrecord CardInstance []
  clojure.lang.IFn
  (invoke [this k]
    (get this k)))

(extend-ICanReadWrite CardInstance :read :public-perms, :write :public-perms)


(defn- populate-query-fields [card]
  (let [{{table-id :source_table} :query database-id :database query-type :type} (:dataset_query card)
        defaults {:database_id database-id
                  :table_id    table-id
                  :query_type  (keyword query-type)}]
    (if query-type
      (merge defaults card)
      card)))

(defentity Card
  [(table :report_card)
   (hydration-keys card)
   (types :display :keyword, :query_type :keyword, :dataset_query :json, :visualization_settings :json)
   timestamped]

   (pre-insert [_ card]
     (populate-query-fields card))

   (pre-update [_ card]
     (populate-query-fields card))

  (post-select [_ {:keys [creator_id] :as card}]
    (map->CardInstance (assoc card
                              :creator         (delay (User creator_id))
                              :dashboard_count (delay (-> (select @(ns-resolve 'metabase.models.dashboard-card 'DashboardCard)
                                                                  (aggregate (count :*) :dashboards)
                                                                  (where {:card_id (:id card)}))
                                                          first
                                                          :dashboards)))))

  (pre-cascade-delete [_ {:keys [id]}]
    (cascade-delete 'PulseCard :card_id id)
    (cascade-delete 'Revision :model "Card" :model_id id)
    (cascade-delete 'DashboardCard :card_id id)
    (cascade-delete 'CardFavorite :card_id id)))

(extend-ICanReadWrite CardEntity :read :public-perms, :write :public-perms)


;;; ## ---------------------------------------- REVISIONS ----------------------------------------


(defn serialize-instance [_ _ instance]
  (->> (dissoc instance :created_at :updated_at)
       (into {})                                 ; if it's a record type like CardInstance we need to convert it to a regular map or filter-vals won't work
       (m/filter-vals (complement delay?))))

(extend CardEntity
  revision/IRevisioned
  {:serialize-instance serialize-instance
   :revert-to-revision revision/default-revert-to-revision
   :diff-map           revision/default-diff-map
   :diff-str           revision/default-diff-str})


;;; ## ---------------------------------------- DEPENDENCIES ----------------------------------------


(defn- parse-filter-subclause [subclause]
  (match subclause
    ["SEGMENT" (segment-id :guard integer?)] segment-id
    subclause                                nil))

(defn- parse-filter [clause]
  (match clause
    ["AND" & subclauses] (mapv parse-filter subclauses)
    ["OR" & subclauses]  (mapv parse-filter subclauses)
    subclause            (parse-filter-subclause subclause)))

(defn- extract-segment-ids [filter-clause]
  (when filter-clause
    (->> (parse-filter filter-clause)
         flatten
         (filter identity)
         set)))

(defn- extract-metric-ids [aggregation-clause]
  (match aggregation-clause
    ["METRIC" (metric-id :guard integer?)] #{metric-id}
    other                                  nil))

(defn card-dependencies
  "Calculate any dependent objects for a given `Card`."
  [this id {:keys [dataset_query] :as instance}]
  (when (and dataset_query
             (= :query (keyword (:type dataset_query))))
    {:Metric  (extract-metric-ids (get-in dataset_query [:query :aggregation]))
     :Segment (extract-segment-ids (get-in dataset_query [:query :filter]))}))

(extend CardEntity
  dependency/IDependent
  {:dependencies card-dependencies})
