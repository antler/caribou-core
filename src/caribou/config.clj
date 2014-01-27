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

(defprotocol StateCoordinator
  (reset [this replacement])
  (swap [this update]))

(extend-protocol StateCoordinator
  java.lang.Object
  (reset [this value] value)
  (swap [this f] (f this))
  clojure.lang.Atom
  (reset [this value] (reset! this value) this)
  (swap [this f] (swap! this f) this))

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
  [config path transform]
  (update-in config path #(swap % transform)))

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

(defn merge-db-creds
  [db-config user pass]
  (if (and user pass)
    (assoc db-config :user user :password password)
    db-config))

(defn configure-db
  "Pass in the current config and a map of connection variables
   that specify the db connection information. The keys to this map
   are:
    :connection --> the jdbc connection string
    :username   --> optional username parameter
    :password   --> optional password parameter"
  [config properties]
  (let [connection (strip-jdbc (:connection properties))
        uri (URI. connection)
        parsed (parse-properties-uri uri)
        db-config (merge-db-creds parsed (:username properties) (:password properties))]
    (assoc-in config [:database] db-config)))

(defn configure-db-from-environment
  "Pass in the current config and a map of connection variables
   that specify where the db connection informationis store.
   The keys to this map are:
    :connection --> the jdbc connection string
    :username   --> optional username parameter
    :password   --> optional password parameter"
  [config properties]
  (if-let [connection (System/getProperty (:connection properties))]
    (let [user (:username properties)
          user (if user (System/getProperty user))
          pass (:password properties)
          pass (if pass (System/getProperty pass))]
      (configure-db {:connection connection :username user :password pass}))))

(defn process-config
  [config]
  (let [db-config (:database config)
        adapter (db-adapter/adapter-for db-config)
        subnamed (adapter/build-subname adapter db-config)
        adapted (assoc subnamed :adapter adapter)
        default (default-config)
        merged (deep-merge-with
                (fn [& args]
                  (last args))
                (dissoc default :database)
                config
                {:database adapted})]
    (if (:database merged)
      merged
      (assoc merged :database (:database default-config)))))

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
