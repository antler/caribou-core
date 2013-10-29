(ns caribou.asset
  (:require [clojure.string :as string]
            [clojure.java.io :as io]
            [aws.sdk.s3 :as s3]
            [pantomime.mime :as mime]
            [caribou.util :as util]
            [caribou.config :as config])
  (:import [java.io ByteArrayInputStream]
           [org.apache.tika.config TikaConfig]))

(def tika-config (TikaConfig.))

(defn find-extension
  [content-type]
  (-> tika-config .getMimeRepository (.forName content-type) .getExtension))

(defn- pad-break-id [id]
  (let [root (str id)
        len (count root)
        pad-len (- 8 len)
        pad (apply str (repeat pad-len "0"))
        halves (map #(apply str %) (partition 4 (str pad root)))
        path (string/join "/" halves)]
    path))

(defn asset-dir
  "Construct the dir this asset will live in, or look it up."
  [asset]
  (util/pathify ["assets" (pad-break-id (:id asset))]))

(defn asset-location
  [asset]
  (if (and asset (:filename asset))
    (util/pathify
     [(asset-dir asset)
      ;; this regex is to deal with funky windows file paths
      (re-find
       #"[^:\\\/]*$"
       (:filename asset))])
    ""))

(defn s3-prefix
  ([]
     (s3-prefix (config/draw :assets :prefix)))
  ([prefix]
     (if prefix
       (str prefix "/")
       "")))

(defn s3-key
  [asset]
  (str (s3-prefix) (asset-location asset)))

(defn asset-path
  "Where to look to find this asset."
  [asset]
  (if (config/draw :aws :bucket)
    (if (and asset (:filename asset))
      (str "http://" (config/draw :aws :bucket) ".s3.amazonaws.com/"
           (s3-key asset))
      "")
    (str "/" (asset-location asset))))

(defn asset-upload-path
  "Where to send this asset to."
  [asset]
  (if (config/draw :aws :bucket)
    (if (and asset (:filename asset))
      (s3-key asset)
      "")
    (asset-path asset)))

(defn ensure-s3-bucket
  [cred bucket]
  (if-not (s3/bucket-exists? cred bucket)
    (do
      (s3/create-bucket cred bucket)
      (s3/update-bucket-acl cred bucket (s3/grant :all-users :read)))))

(defn upload-to-s3
  ([key asset size]
     (upload-to-s3 (config/draw :aws :bucket) key asset size))
  ([bucket key asset size]
     (try
       (let [cred (config/draw :aws :credentials)
             mime (mime/mime-type-of key)]
         (ensure-s3-bucket cred bucket)
         (s3/put-object cred bucket key asset
                        {:content-type mime
                         :content-length size}
                        (s3/grant :all-users :read)))
       (catch Exception e (do
                            (.printStackTrace e)
                            (println "KEY BAD" key))))))

(defn persist-asset-on-disk
  [dir name file]
  (.mkdirs (io/file (util/pathify [(config/draw :assets :dir) dir])))
  (io/copy file (io/file (util/pathify [(config/draw :assets :dir) dir name]))))

(defn put-asset
  [stream asset]
  (if (config/draw :aws :bucket)
    (if (and asset (:filename asset))
      (upload-to-s3 (asset-upload-path asset) stream (:size asset)))
    (persist-asset-on-disk (asset-dir asset) (:filename asset) stream)))

(defn migrate-dir-to-s3
  ([dir] (migrate-dir-to-s3 dir (config/draw :aws :bucket)))
  ([dir bucket] (migrate-dir-to-s3 dir bucket (config/draw :assets :prefix)))
  ([dir bucket prefix]
     (let [dir-pattern (re-pattern (str dir "/"))]
       (doseq [entry (file-seq (io/file dir))]
         (if-not (.isDirectory entry)
           (let [path (.getPath entry)
                 relative (string/replace-first path dir-pattern "")
                 prefixed (str (s3-prefix prefix) relative)
                 file (io/file path)
                 file-size (.length file)]
             (println "uploading" prefixed (.length entry))
             (upload-to-s3 bucket prefixed file file-size)))))))

;; ASSET CREATION ----------------

(def byte-class (Class/forName "[B"))

(defn slugify-filename
  [s]
  (let [transform (util/slug-transform (config/draw :field :slug-transform))
        parts (string/split s #"\.")
        name-parts (take (dec (count parts)) parts)
        extension (last parts)
        filename (string/join "." name-parts)]
    (str (transform filename) "." extension)))

(defmulti scry-asset
  (comp class :source))

(defmethod scry-asset nil
  [m]
  m)

(defmethod scry-asset byte-class
  [m]
  (let [source (:source m)
        content-type (or (:content-type m) (mime/mime-type-of source))
        size (or (:size m) (count source))
        filename (or (:filename m) (str (gensym) (find-extension content-type)))
        filename (slugify-filename filename)]
    {:source source 
     :content-type content-type 
     :size size 
     :filename filename}))

(defmethod scry-asset java.io.File
  [m]
  (let [source (:source m)
        content-type (or (:content-type m) (mime/mime-type-of source))
        size (or (:size m) (.length source))
        filename (or (:filename m) (.getName source))
        filename (slugify-filename filename)]
    {:source source 
     :content-type content-type 
     :size size 
     :filename filename}))

(defmethod scry-asset java.lang.String
  [m]
  (let [source (java.io.File. (:source m))
        content-type (or (:content-type m) (mime/mime-type-of source))
        size (or (:size m) (.length source))
        filename (or (:filename m) (.getName source))
        filename (slugify-filename filename)]
    {:source source
     :content-type content-type 
     :size size 
     :filename filename}))

(defmethod scry-asset java.net.URL
  [m]
  (let [source (:source m)
        connection (.openConnection source)
        content-type (or (:content-type m) (.getContentType connection) (mime/mime-type-of source))
        size (or (:size m) (.getContentLength connection))
        filename (or (:filename m) 
                     (-> source .getFile (string/replace-first #"^/" "")) 
                     (str (gensym) (find-extension content-type)))
        filename (slugify-filename filename)]
    {:source (.openStream source)
     :content-type content-type 
     :size size 
     :filename filename}))

;; (defmethod scry-asset java.io.InputStream
;;   [m]
;;   (let [source (:source m)
;;         content-type (or (:content-type m) (mime/mime-type-of source))
;;         size (or (:size m) (.getContentLength connection))
;;         filename (or (:filename m) (str (gensym) (find-extension content-type)))
;;         filename (slugify-filename filename)]
;;     {:source source
;;      :content-type content-type 
;;      :size size 
;;      :filename filename}))

(defn asset-scry-asset
  [env]
  (let [spec (:spec env)
        values (:values env)
        revealed (scry-asset spec)
        complete (merge values revealed)
        source (:source revealed)
        rarified (dissoc complete :source :path)]
    (assoc env 
      :values rarified
      :source source)))

(defn asset-commit-asset
  [env]
  (if-let [source (-> env :source)]
    (put-asset source (:content env)))
  env)

