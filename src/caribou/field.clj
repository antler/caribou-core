(ns caribou.field
  (:require [caribou.db :as db]
            [caribou.util :as util]))

(defprotocol Field
  "a protocol for expected behavior of all model fields"
  (table-additions [this field]
    "the set of additions to this db table based on the given name")
  (subfield-names [this field]
    "the names of any additional fields added to the model
    by this field given this name")

  (setup-field [this spec] "further processing on creation of field")
  (rename-field [this old-slug new-slug] "further processing on creation of field")
  (cleanup-field [this] "further processing on removal of field")
  (target-for [this] "retrieves the model this field points to, if applicable")
  (update-values [this content values]
    "adds to the map of values that will be committed to the db for this row")
  (post-update [this content opts]
    "any processing that is required after the content is created/updated")
  (pre-destroy [this content]
    "prepare this content item for destruction")

  (join-fields [this prefix opts])
  (join-conditions [this prefix opts])
  (build-where [this prefix opts] "creates a where clause suitable to this field from the given where map, with fields prefixed by the given prefix.")
  (natural-orderings [this prefix opts])
  (build-order [this prefix opts])
  (field-generator [this generators])
  (fuse-field [this prefix archetype skein opts])

  (localized? [this])
  (models-involved [this opts all])

  (field-from [this content opts]
    "retrieves the value for this field from this content item")
  (render [this content opts] "renders out a single field from this content item")
  (validate [this opts] "given a set of options and the models, verifies the options are appropriate and well formed for gathering"))

(def models (ref {}))

;; functions for localized fields
(defn build-locale-field
  [prefix slug locale]
  (str prefix "." (name locale) "_" (name slug)))

(defn build-select-field
  [prefix slug]
  (str prefix "." (name slug)))

(defn build-coalesce
  [prefix slug locale]
  (let [global (build-select-field prefix slug)
        local (build-locale-field prefix slug locale)]
    (str "coalesce(" local ", " global ")")))

(defn select-locale
  [model field prefix slug opts]
  (let [locale (:locale opts)]
    (if (and locale (:localized model) (localized? field))
      (build-locale-field prefix slug locale)
      (build-select-field prefix slug))))

(defn coalesce-locale
  [model field prefix slug opts]
  (let [locale (:locale opts)]
    (if (and locale (:localized model) (localized? field))
      (build-coalesce prefix slug locale)
      (build-select-field prefix slug))))

;; functions used throughout field definitions
(defn where-operator
  [where]
  (if (map? where)
    [(-> where keys first name) (-> where vals first)]
    ["=" where]))

(defn field-where
  [field prefix opts do-where]
  (let [slug (keyword (-> field :row :slug))
        where (-> opts :where slug)]
    (if-not (nil? where)
      (do-where field prefix slug opts where))))

(defn pure-fusion
  [this prefix archetype skein opts]
  (let [slug (keyword (-> this :row :slug))
        bit (util/prefix-key prefix slug)
        containing (drop-while #(nil? (get % bit)) skein)
        value (get (first containing) bit)]
    (assoc archetype slug value)))

(defn id-models-involved
  [field opts all]
  (conj all (-> field :row :model_id)))

(defn pure-where
  [field prefix slug opts where]
  (let [model-id (-> field :row :model_id)
        model (db/find-model model-id @models)
        [operator value] (where-operator where)
        field-select (coalesce-locale model field prefix slug opts)]
    (util/clause "%1 %2 %3" [field-select operator value])))

(defn pure-order
  [field prefix opts]
  (let [slug (-> field :row :slug)]
    (if-let [by (get (:order opts) (keyword slug))]
      (let [model-id (-> field :row :model_id)
            model (db/find-model model-id @models)]
        (str (coalesce-locale model field prefix slug opts) " "
             (name by))))))

(defn string-where
  [field prefix slug opts where]
  (let [model-id (-> field :row :model_id)
        model (db/find-model model-id @models)
        [operator value] (where-operator where)
        field-select (coalesce-locale model field prefix slug opts)]
    (util/clause "%1 %2 '%3'" [field-select operator value])))

(defn field-cleanup
  [field]
  (let [model-id (-> field :row :model_id)
        model (get @models model-id)]
    (doseq [addition (table-additions field (-> field :row :slug))]
      (db/drop-column (:slug model) (first addition)))))

(def field-constructors
  (atom {}))

(defn add-constructor
  [key construct]
  (swap! field-constructors (fn [c] (assoc c key construct))))

