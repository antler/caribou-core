(ns caribou.config
  (:use [clojure.walk :only (keywordize-keys)]
        [caribou.util :only (map-vals pathify file-exists? deep-merge-with)])
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [caribou.util :as util]
            [caribou.db.adapter :as db-adapter]
            [caribou.db.adapter.protocol :as adapter]))

(import java.net.URI)
(declare config-path)

(defn set-properties
  [props]
  (doseq [prop-key (keys props)]
    (when (nil? (System/getProperty (name prop-key)))
      (System/setProperty (name prop-key) (str (get props prop-key))))))

(defn load-caribou-properties
  []
  (let [props (util/load-props "caribou.properties")]
    (set-properties props)))

(defn system-property
  [key]
  (.get (System/getProperties) (name key)))

(defn environment
  []
  (keyword
   (or (system-property :environment)
       "development")))

(defn default-config
  []
  {:app {:use-database true}
   :assets {:dir "app/"
            :prefix nil
            :root ""}
   :aws {:bucket nil
         :credentials nil}
   :database {:classname    "org.h2.Driver"
              :subprotocol  "h2"
              :host         "localhost"
              :protocol     "file"
              :path         "/tmp/"
              :database     "caribou_development"
              :user         "h2"
              :password     ""}
   :field {:constructors (atom {})
           :namespace "skel.fields"
           :slug-transform [[#"['\"]+" ""]
                            [#"[_ \\/?%:#^\[\]<>@!|$&*+;,.()]+" "-"]
                            [#"^-+|-+$" ""]]}
   :hooks {:namespace "skel.hooks"
           :lifecycle (atom {})}
   :index {:path "caribou-index"
           :default-limit 1000
           :store (atom nil)}
   :logging {:loggers [{:type :stdout :level :debug}]}
   :models (atom {})
   :nrepl {:port nil :server (atom nil)}
   :query {:queries (atom {})
           :enable-query-cache  false
           :query-defaults {}
           :reverse-cache (atom {})}})

(def ^:dynamic config {})

(defn draw
  [& path]
  (get-in config path))

(defn update-config
  [config path value]
  (let [at (get-in config path)]
    (if (= clojure.lang.Atom (class at))
      (do
        (reset! at value)
        config)
      (update-in config path value))))

(defn assoc-subname
  [db-config]
  (adapter/build-subname (draw :database :adapter) db-config))

(def ^{:private true :doc "Map of schemes to subprotocols"} subprotocols
  {"postgres" "postgresql"})

(defn- parse-properties-uri
  [^URI uri]
  (let [host (.getHost uri)
        port (if (pos? (.getPort uri)) (.getPort uri))
        path (.getPath uri)
        scheme (.getScheme uri)]
    (merge
     {:subname (if port
                 (str "//" host ":" port path)
                 (str "//" host path))
      :subprotocol (subprotocols scheme scheme)}
     (if-let [user-info (.getUserInfo uri)]
       {:user (first (string/split user-info #":"))
        :password (second (string/split user-info #":"))}))))

(defn- strip-jdbc
  [^String spec]
  (if (.startsWith spec "jdbc:")
    (.substring spec 5)
    spec))

(defn merge-db-creds-from-env
  [db-config user-prop pass-prop]
  (if (and user-prop pass-prop)
    (let [user (System/getProperty user-prop)
          password (System/getProperty pass-prop)]
      (if (and user password)
        (assoc db-config :user user :password password)
        db-config))
    db-config))

(defn configure-db-from-environment
  "Pass in the current config and a map of environment variables that specify where the db connection
  information is stored.  The keys to this map are:
    :connection --> the jdbc connection string
    :username   --> optional username parameter
    :password   --> optional password parameter"
  [config properties]
  (if-let [connection (System/getProperty (:connection properties))]
    (let [connection (strip-jdbc connection)
          uri (URI. connection)
          parsed (parse-properties-uri uri)
          db-config (merge-db-creds-from-env parsed (:username properties) (:password properties))]
      (update-in config [:database] #(merge % db-config)))
    config))

(defn process-config
  [config]
  (let [db-config (:database config)
        adapter (db-adapter/adapter-for db-config)
        subnamed (adapter/build-subname adapter db-config)
        adapted (assoc subnamed :adapter adapter)]
    (deep-merge-with
     (fn [& args]
       (last args))
     config
     {:database adapted})))

(defn read-config
  [config-file]
  (with-open [fd (java.io.PushbackReader.
                  (io/reader config-file))]
    (read fd)))

(defn merge-config
  [base over]
  (deep-merge-with
   (fn [& args]
     (last args))
   base over))

(defn config-from-resource
  "Loads the appropriate configuration file based on environment"
  [default resource]
  (merge-config default (read-config (io/resource resource))))

(defn environment-config-resource
  []
  (format "config/%s.clj" (name (environment))))

(defn config-from-environment
  [default]
  (-> default
      (config-from-resource (environment-config-resource))
      (process-config)))

(defmacro with-config
  [new-config & body]
  `(binding [caribou.config/config ~new-config]
     ~@body))
