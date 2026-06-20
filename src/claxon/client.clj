(ns claxon.client
  (:require
   [claxon.conf :as conf]
   [claxon.impl.common :as ic]
   [claxon.impl.read :as ir]
   [claxon.impl.write :as iw])
  (:import
   [java.io BufferedInputStream BufferedOutputStream]
   [java.net InetSocketAddress Socket]
   [java.util.concurrent ExecutorService]))

(defn add-handler
  [conn handler {:keys [op args]}]
  (swap! ic/handlers
         conj
         {:conn (:id conn)
          :fn handler
          :matches {:op op :args args}}))

(defn invoke
  [conn {:keys [op args payloads]}]
  (iw/snd conn op args payloads))

(defn connect
  ([]
   (connect {}))
  ([opts]
   (let [opts (merge (conf/defaults) opts)
         {:keys [claxon/urls
                 claxon/timeout-ms
                 claxon/executor
                 claxon/handlers
                 claxon/frame-shapes]} opts
         ^Socket sock (->> urls
                           (map ic/parse-nats-url)
                           (shuffle)
                           (some (fn [{:keys [^String host ^Integer port]}]
                                   (try
                                     (doto (Socket.)
                                       (.connect (InetSocketAddress. host port)
                                                 timeout-ms))
                                     (catch Exception _ false)))))
         _ (when-not sock
             (throw (ex-info "Cannot connect to any of the urls" {:urls urls})))
         in (-> sock
                .getInputStream
                BufferedInputStream.)
         out (-> sock
                 .getOutputStream
                 BufferedOutputStream.)
         conn {:id (swap! ic/conn-ids inc)
               :socket sock
               :in in
               :out out
               :executor executor
               :frame-shapes frame-shapes}]
     (run! (fn [[{:keys [op args]} f]]
             (add-handler conn f {:op op :args args}))
           handlers)
     (ir/start conn)
     (iw/snd conn
             "CONNECT"
             {:opts (dissoc opts
                            :claxon/urls
                            :claxon/timeout-ms
                            :claxon/executor
                            :claxon/handlers
                            :claxon/frame-shapes)}
             nil)
     conn)))

(defn close
  [{:keys [socket executor id in out]}]
  (swap! ic/handlers (fn [h] (vec (remove #(= (:conn %) id) h))))
  (BufferedInputStream/.close in)
  (BufferedOutputStream/.close out)
  (ExecutorService/.shutdown executor)
  (Socket/.close socket))

(comment
  (set! *warn-on-reflection* true)

  (def conn (connect))

  (close conn))
