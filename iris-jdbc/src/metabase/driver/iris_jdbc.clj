(ns metabase.driver.iris-jdbc
  "InterSystems IRIS JDBC driver."
  (:require
   [clojure.java.jdbc :as jdbc]
   [clojure.string :as str]
   [clojure.core :refer [get-method]]
   [honey.sql :as sql]
   [honey.sql.helpers :as sql.helpers]
   [java-time.api :as t]
   [metabase.driver :as driver]
   [metabase.driver.sql-jdbc.common :as sql-jdbc.common]
   [metabase.driver.sql-jdbc.connection :as sql-jdbc.conn]
   [metabase.driver.sql-jdbc.execute :as sql-jdbc.execute]
   [metabase.driver.sql-jdbc.sync :as sql-jdbc.sync]
   [metabase.driver.sql-jdbc.sync.interface :as sync.interface]
   [metabase.driver.sql.query-processor :as sql.qp]
   [metabase.driver.sql.util :as sql.u]
   [metabase.util :as u]
   [metabase.util.date-2 :as u.date]
   [metabase.util.honey-sql-2 :as h2x]
   [metabase.util.i18n :refer [trs]]
   [metabase.util.log :as log])
  (:import
   (java.sql
    Connection
    PreparedStatement
    ResultSet
    Types)
   (java.time
    LocalDateTime
    LocalTime
    OffsetDateTime
    OffsetTime
    ZonedDateTime)))

(set! *warn-on-reflection* true)

(driver/register! :iris-jdbc, :parent #{:sql-jdbc})

(doseq [[feature supported?] {:basic-aggregations              true
                              :binning                         true
                              :expression-aggregations         true
                              :expression-literals             true
                              :expressions                     true
                              :native-parameters               true
                              :now                             true
                              :set-timezone                    false
                              :standard-deviation-aggregations true
                              :metadata/key-constraints        true
                              :database-routing                false}]
  (defmethod driver/database-supports? [:iris-jdbc feature] [_driver _feature _db] supported?))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                          IRIS Type Mappings                                                    |
;;; +----------------------------------------------------------------------------------------------------------------+

(def ^:private ^{:arglists '([column-type])} iris-type->base-type
  "Function that returns a `base-type` for the given `iris-type` (can be a keyword or string)."
  (sql-jdbc.sync/pattern-based-database-type->base-type
   [[#"(?i)bit"                        :type/Boolean]
    [#"(?i)tinyint"                    :type/Integer]
    [#"(?i)smallint"                   :type/Integer]
    [#"(?i)int"                        :type/Integer]
    [#"(?i)integer"                    :type/Integer]
    [#"(?i)bigint"                     :type/BigInteger]
    [#"(?i)numeric.*"                  :type/Decimal]
    [#"(?i)decimal.*"                  :type/Decimal]
    [#"(?i)money"                      :type/Decimal]
    [#"(?i)float"                      :type/Float]
    [#"(?i)real"                       :type/Float]
    [#"(?i)double"                     :type/Float]
    [#"(?i)varchar.*"                  :type/Text]
    [#"(?i)char.*"                     :type/Text]
    [#"(?i)text"                       :type/Text]
    [#"(?i)longvarchar"                :type/Text]
    [#"(?i)nvarchar.*"                 :type/Text]
    [#"(?i)nchar.*"                    :type/Text]
    [#"(?i)date"                       :type/Date]
    [#"(?i)time"                       :type/Time]
    [#"(?i)timestamp"                  :type/DateTime]
    [#"(?i)datetime"                   :type/DateTime]
    [#"(?i)binary.*"                   :type/*]
    [#"(?i)varbinary.*"                :type/*]
    [#"(?i)longvarbinary"              :type/*]
    [#".*"                             :type/*]]))

(defmethod sql-jdbc.sync/database-type->base-type :iris-jdbc [_driver database-type]
  (iris-type->base-type database-type))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                          DateTime functions                                                    |
;;; +----------------------------------------------------------------------------------------------------------------+


(defmethod sql.qp/current-datetime-honeysql-form :iris-jdbc
  [_driver]
  (h2x/with-database-type-info :%now "timestamp"))

(defmethod sql.qp/unix-timestamp->honeysql [:iris-jdbc :seconds]
  [_driver _seconds-or-milliseconds expr]
  [:dateadd "second" expr [:cast "1970-01-01 00:00:00" :timestamp]])

(defmethod sql.qp/cast-temporal-string [:iris-jdbc :Coercion/ISO8601->DateTime]
  [_driver _semantic_type expr]
  (h2x/->timestamp [:cast expr :timestamp]))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                          Date Extaction Functions                                              |
;;; +----------------------------------------------------------------------------------------------------------------+

(defmethod sql.qp/date [:iris-jdbc :default]         [_ _ expr] expr)
(defmethod sql.qp/date [:iris-jdbc :minute]          [_ _ expr] [:datepart "minute" expr])
(defmethod sql.qp/date [:iris-jdbc :minute-of-hour]  [_ _ expr] [:datepart "minute" expr])
(defmethod sql.qp/date [:iris-jdbc :hour]            [_ _ expr] [:datepart "hour" expr])
(defmethod sql.qp/date [:iris-jdbc :hour-of-day]     [_ _ expr] [:datepart "hour" expr])
(defmethod sql.qp/date [:iris-jdbc :day]             [_ _ expr] [:cast expr :date])
(defmethod sql.qp/date [:iris-jdbc :day-of-month]    [_ _ expr] [:datepart "day" expr])
(defmethod sql.qp/date [:iris-jdbc :day-of-year]     [_ _ expr] [:datepart "dayofyear" expr])
(defmethod sql.qp/date [:iris-jdbc :day-of-week]     [_ _ expr] [:datepart "weekday" expr])
(defmethod sql.qp/date [:iris-jdbc :week]            [_ _ expr] [:datepart "week" expr])
(defmethod sql.qp/date [:iris-jdbc :month]           [_ _ expr] [:datepart "month" expr])
(defmethod sql.qp/date [:iris-jdbc :month-of-year]   [_ _ expr] [:datepart "month" expr])
(defmethod sql.qp/date [:iris-jdbc :quarter]         [_ _ expr] [:datepart "quarter" expr])
(defmethod sql.qp/date [:iris-jdbc :quarter-of-year] [_ _ expr] [:datepart "quarter" expr])
(defmethod sql.qp/date [:iris-jdbc :year]            [_ _ expr] [:datepart "year" expr])

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                          Date Artithmetic                                                      |
;;; +----------------------------------------------------------------------------------------------------------------+


(defmethod sql.qp/add-interval-honeysql-form :iris-jdbc
  [_driver expr amount unit]
  [:dateadd (h2x/literal unit) amount expr])

(defmethod sql.qp/datetime-diff [:iris-jdbc :year]    [_driver _unit x y] [:datediff "year" x y])
(defmethod sql.qp/datetime-diff [:iris-jdbc :quarter] [_driver _unit x y] [:datediff "quarter" x y])
(defmethod sql.qp/datetime-diff [:iris-jdbc :month]   [_driver _unit x y] [:datediff "month" x y])
(defmethod sql.qp/datetime-diff [:iris-jdbc :week]    [_driver _unit x y] [:datediff "week" x y])
(defmethod sql.qp/datetime-diff [:iris-jdbc :day]     [_driver _unit x y] [:datediff "day" x y])
(defmethod sql.qp/datetime-diff [:iris-jdbc :hour]    [_driver _unit x y] [:datediff "hour" x y])
(defmethod sql.qp/datetime-diff [:iris-jdbc :minute]  [_driver _unit x y] [:datediff "minute" x y])
(defmethod sql.qp/datetime-diff [:iris-jdbc :second]  [_driver _unit x y] [:datediff "second" x y])

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                            sql-jdbc implementations                                            |
;;; +----------------------------------------------------------------------------------------------------------------+

;; IRIS does not support holdability over CLOSE_CURSORS_ON_COMMIT so for now 
;; define the two methods below to omit setting the holdability. 
;; TODO: Only set holdability over HOLD_CURSORS_OVER_COMMIT

(defmethod sql-jdbc.execute/statement :iris-jdbc
  [driver ^Connection conn]
  (.createStatement conn))

(defmethod sql-jdbc.execute/prepared-statement :iris-jdbc
  [driver ^Connection conn ^String sql]
  (.prepareStatement conn sql))

;;; Connection

(defn- jdbc-spec
  "Creates a spec for `clojure.java.jdbc` to use for connecting to IRIS via JDBC."
  [{:keys [host port namespace user password additional-options]
    :or   {host "localhost", port 52774, namespace "USER"}
    :as   details}]
  (-> {:classname   "com.intersystems.jdbc.IRISDriver"
       :subprotocol "IRIS"
       :subname     (str "//" host ":" port "/" namespace)
       :user        user
       :password    password}
      (merge (dissoc details :host :port :namespace :user :password :additional-options))
      (sql-jdbc.common/handle-additional-options additional-options)))

(defmethod sql-jdbc.conn/connection-details->spec :iris-jdbc
  [_ details-map]
  "Converts Metabase DB connection config into a JDBC connection using spec above"
  (jdbc-spec details-map))

(defmethod sql-jdbc.sync/fallback-metadata-query :iris-jdbc
  [_ _db-name schema table]
  ;; return a vector of one or more SQL strings to probe column metadata
  [(format "SELECT * FROM %s.%s WHERE 1=0 LIMIT 0"
           schema
           table)])

(defmethod sql-jdbc.sync/have-select-privilege? :iris-jdbc
  ;; [driver db-name schema-name table-name]
  [_ _db schema table]
  ;; IRIS doesn’t like `TRUE AS "_"`, so use `1 AS _` and drop any fancy quoting.
  ;; You can add double‐quotes around schema/table if your names are case-sensitive.
  [(format "SELECT 1 AS _ FROM %s.%s WHERE 1=0"
           schema
           table)])

;; Stop importing system tables Ens*
;; (defmethod sql-jdbc.sync/excluded-schemas :iris-jdbc [_]
;;   #{"Ens"})

;; 1) pull out the original :sql-jdbc handler
(def ^:private sql-jdbc-default
  (get-method sync.interface/filtered-syncable-schemas :sql-jdbc))
  
(defmethod sync.interface/filtered-syncable-schemas :iris-jdbc
  [driver ^java.sql.Connection conn ^java.sql.DatabaseMetaData meta
   ^String incl-pat ^String excl-pat]

  ;; Apply all of Metabase’s filtering + your extra exclusions into a vector
  (let [filtered-schemas
        (into []
              (remove (fn [schema]
                        (or
                          (str/starts-with? schema "%")
                          (str/starts-with? schema "EnsLib_")
                          (str/starts-with? schema "Ens_")
                          (str/starts-with? schema "EnsPortal")
                          (= schema "INFORMATION_SCHEMA")
                          (= schema "Ens"))))
              (sql-jdbc-default driver conn meta incl-pat excl-pat))]

    ;; Log each schema that survived the filter
    (doseq [schema filtered-schemas]
      (log/infof "[IRIS-DRIVER] Remaining schema → %s" schema))

    ;; Return the filtered list
    filtered-schemas))

    
;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                          Other Functions                                                       |
;;; +----------------------------------------------------------------------------------------------------------------+


(defmethod sql.qp/->honeysql [:iris-jdbc :regex-match-first]
  [driver [_ arg pattern]]
  ;; IRIS uses REGEX function
  (let [arg-sql (sql.qp/->honeysql driver arg)
        pattern-sql (sql.qp/->honeysql driver pattern)]
    [:regex arg-sql pattern-sql]))

(defmethod sql.qp/->honeysql [:iris-jdbc :median]
  [driver [_ arg]]
  ;; IRIS doesn't have built-in median, use percentile
  [:percentile_cont 0.5 :within-group [:order-by (sql.qp/->honeysql driver arg)]])

(defmethod sql.qp/->honeysql [:iris-jdbc :percentile]
  [driver [_ arg p]]
  [:percentile_cont (sql.qp/->honeysql driver p) :within-group [:order-by (sql.qp/->honeysql driver arg)]])

;;; String functions

(defmethod sql.qp/->honeysql [:iris-jdbc :concat]
  [driver args]
  (->> args
       (map (partial sql.qp/->honeysql driver))
       (reduce (fn [x y] [:concat x y]))))

;;; Pagination

(defmethod sql.qp/apply-top-level-clause [:iris-jdbc :page]
  [_driver _top-level-clause honeysql-query {{:keys [items page]} :page}]
  {:pre [(pos-int? items) (pos-int? page)]}
  (let [offset (* (dec page) items)]
    (-> honeysql-query
        (sql.helpers/limit [:inline items])
        (sql.helpers/offset [:inline offset]))))

;;; Start of week

(defmethod driver/db-start-of-week :iris-jdbc
  [_]
  :sunday)

;;; Escape alias

(defmethod driver/escape-alias :iris-jdbc
  [_driver s]
  ;; IRIS follows SQL standard for identifiers
  (str/replace s #"[^a-zA-Z0-9_]" "_"))