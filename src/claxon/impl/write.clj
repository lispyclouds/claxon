(ns claxon.impl.write
  (:require
   [claxon.impl.common :as ic]
   [clojure.string :as str])
  (:import
   [java.io OutputStream]))

(defn encode-headers-block
  [{:keys [headers status description]}]
  (let [version-line (cond-> "NATS/1.0"
                       status (str " " status)
                       description (str " " description))
        header-lines (mapcat (fn [[k vs]]
                               (map #(str k ": " %) vs))
                             headers)
        block (str/join "\r\n" (concat [version-line] header-lines ["" ""]))]
    (.getBytes ^String block "UTF-8")))

(defn ->payload-bytes
  [type v]
  (case type
    :headers (encode-headers-block v)
    :bytes   (cond
               (bytes? v) v
               (string? v) (.getBytes ^String v "UTF-8")
               (nil? v) (byte-array 0)
               :else (throw (ex-info "payload must be bytes or a string" {:value v})))
    (throw (ex-info "unknown payload type" {:type type}))))

(defn derive-length-args
  [payload-specs encoded]
  (let [total (apply + (map (fn [^bytes b] (alength b)) encoded))
        simple (reduce (fn [acc [{:keys [length]} ^bytes raw]]
                         (if (keyword? length)
                           (assoc acc length (alength raw))
                           acc))
                       {}
                       (map vector payload-specs encoded))
        composite-arg-names (->> payload-specs
                                 (keep (fn [{:keys [length]}]
                                         (when (vector? length) (second length))))
                                 set)]
    (reduce #(assoc %1 %2 total) simple composite-arg-names)))

(defn render-args-line
  [arg-specs args]
  (when arg-specs
    (if (= 1 (count arg-specs))
      (let [{:keys [name type]} (first arg-specs)]
        (case type
          :json (ic/write-json (get args name))
          (str (get args name))))
      (->> arg-specs
           (keep (fn [{:keys [name optional]}]
                   (let [v (get args name)]
                     (cond
                       (some? v) (str v)
                       optional nil
                       :else (throw (ex-info "missing required arg" {:name name}))))))
           (str/join " ")))))

(defn snd
  [{:keys [^OutputStream out frame-shapes]} op args payloads]
  (let [shape (get frame-shapes op)]
    (when-not shape
      (throw (ex-info "unknown op" {:op op})))
    (let [payload-specs (:payloads shape)
          encoded (mapv #(->payload-bytes (:type %) (get payloads (:name %)))
                        payload-specs)
          length-args (derive-length-args payload-specs encoded)
          full-args (merge args length-args)
          args-line (render-args-line (:args shape) full-args)
          control (str op (when (seq args-line)
                            (str " " args-line)) "\r\n")]
      (.write out (.getBytes control "UTF-8"))
      (.flush out)
      (when (seq encoded)
        (run! #(.write out ^bytes %) encoded)
        (.write out (.getBytes "\r\n" "UTF-8"))
        (.flush out)))))
