 (ns caribou.db.adapter.mysql
  (:use [caribou.db.adapter.protocol :only (DatabaseAdapter)])
  (:require [caribou.logger :as log]
            [caribou.util :as util]
            [clojure.java.jdbc.deprecated :as old-sql]
            [clojure.java.jdbc :as sql]))

(import java.util.regex.Matcher)

(defn mysql-table?
  "Determine if this table exists in the mysql database."
  [table]
  (if (util/query "show tables like '%1'" (util/dbize table))
    true false))

(defn find-column-type
  [table column]
  (let [result (util/query "show fields from %1 where Field = '%2'" (util/dbize table) (util/dbize column))]
    (-> result first :type)))

(defn mysql-set-required
  [table column value]
  (let [field-type (find-column-type table column)]
    (old-sql/do-commands
     (log/out :db (util/clause
                     (if value
                       "alter table %1 modify %2 %3 not null"
                       "alter table %1 modify %2 %3")
                     [(util/dbize table) (util/dbize column) field-type])))))

(defn mysql-rename-column
  [table column new-name]
  (try
    (let [field-type (find-column-type table column)
          alter-statement "alter table %1 change %2 %3 %4"
          rename (log/out :db (util/clause alter-statement (map util/dbize [table column new-name field-type])))]
      (old-sql/do-commands rename))
    (catch Exception e (log/render-exception e))))

(defn mysql-insert-result
  [this table result]
  (old-sql/with-query-results res
    [(str "select * from " (util/dbize table)
          " where id = " (result (first (keys result))))]
    (first (doall res))))

(defn mysql-drop-index
  [table column]
  (try
    (old-sql/do-commands
     (log/out :db (util/clause "alter table %1 drop index %1_%2_index" (map util/dbize [table column]))))
    (catch Exception e (log/render-exception e))))

(defn mysql-drop-model-index
  [old-table new-table column]
  (try
    (old-sql/do-commands
     (log/out :db (util/clause "alter table %2 drop index %1_%3_index" (map util/dbize [old-table new-table column]))))
    (catch Exception e (log/render-exception e))))

(defrecord MysqlAdapter [config]
  DatabaseAdapter
  (init [this])
  (unicode-supported? [this]
    (let [result (util/query "show variables like 'character_set_database'")]
      (log/debug (str "character_set_database" result))
      (= "utf8" (:value (first result)))))
  (supports-constraints? [this] true)
  (table? [this table]
    (mysql-table? table))
  (build-subname [this config]
    (let [host (or (config :host) "localhost")
          subname (or (config :subname) (str "//" host "/" (config :database)
                                             "?useUnicode=true"
                                             "&characterEncoding=UTF-8"
                                             "&zeroDateTimeBehavior=convertToNull"))]
      (assoc config :subname subname)))
  (insert-result [this table result]
    (mysql-insert-result this table result))
  (rename-column [this table column new-name]
    (mysql-rename-column table column new-name))
  (set-required [this table column value]
    (mysql-set-required table column value))
  (drop-index [this table column]
    (mysql-drop-index table column))
  (drop-model-index [this old-table new-table column]
    (mysql-drop-model-index old-table new-table column))
  (text-value [this text]
    text))
