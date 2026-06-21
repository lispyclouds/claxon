(ns claxon.client
  (:require
   [claxon.conf :as conf]
   [claxon.impl.common :as ic]
   [claxon.impl.read :as ir]
   [claxon.impl.sock :as sock]
   [claxon.impl.write :as iw])
  (:import
   [java.io BufferedInputStream BufferedOutputStream]
   [java.net Socket]
   [java.util.concurrent ExecutorService]))

(defn add-handler
  "Registers handler to be called on conn whenever an incoming frame matches op and args (a submap match).
  Returns a handler id, usable with remove-handler."
  [conn handler {:keys [op args]}]
  (let [id (swap! ic/handler-ids inc)]
    (swap! ic/handlers
           conj
           {:id id
            :conn (:id conn)
            :fn handler
            :matches {:op op :args args}})
    id))

(defn remove-handler
  "Unregisters the handler with the given id, as returned by add-handler."
  [id]
  (swap! ic/handlers (fn [hs] (remove #(= id (:id %)) hs))))

(defn invoke
  "Sends a frame ({:op ... :args ... :payloads ...}) to the server over conn."
  [conn {:keys [op args payloads]}]
  (iw/snd conn op args payloads))

(defn connect
  "Opens a connection to a NATS server and performs the INFO/CONNECT handshake, upgrading to TLS first if the server requires it. opts is merged over claxon.conf/defaults.
  Returns a conn map to be passed to every other function in this namespace."
  ([]
   (connect {}))
  ([opts]
   (let [opts (merge (conf/defaults) opts)
         {:keys [tls
                 claxon/urls
                 claxon/timeout-ms
                 claxon/executor
                 claxon/handlers
                 claxon/verify-tls
                 claxon/frame-shapes]} opts
         {:keys [host port ^Socket socket]}
         (->> urls
              (map ic/parse-nats-url)
              (shuffle)
              (some (fn [{:keys [host port]}]
                      (try
                        (let [socket (sock/connect {:host host
                                                    :port port
                                                    :tls tls
                                                    :verify verify-tls
                                                    :timeout timeout-ms})]
                          {:socket socket
                           :host host
                           :port port})
                        (catch Exception _ false)))))
         _ (when-not socket
             (throw (ex-info "Cannot connect to any of the urls" {:urls urls})))
         in (-> socket
                .getInputStream
                BufferedInputStream.)
         out (-> socket
                 .getOutputStream
                 BufferedOutputStream.)
         conn {:id (swap! ic/conn-ids inc)
               :socket socket
               :in in
               :out out
               :executor executor
               :frame-shapes frame-shapes}
         info (-> in
                  (ir/read-frame frame-shapes)
                  :args
                  :info)
         conn (if (and (get info "tls_available")
                       (get info "tls_required"))
                (if tls
                  conn
                  (do
                    (Socket/.close (:socket conn))
                    (let [new-sock (sock/connect {:host host
                                                  :port port
                                                  :tls true
                                                  :verify verify-tls
                                                  :timeout timeout-ms})]
                      (assoc conn
                             :socket new-sock
                             :in (-> new-sock
                                     .getInputStream
                                     BufferedInputStream.)
                             :out (-> new-sock
                                      .getOutputStream
                                      BufferedOutputStream.)))))
                conn)]
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
                            :claxon/verify-tls
                            :claxon/frame-shapes)}
             nil)
     conn)))

(defn close
  "Closes conn: deregisters its handlers, shuts down its executor, and closes the underlying socket."
  [{:keys [socket executor id]}]
  (swap! ic/handlers (fn [h] (vec (remove #(= (:conn %) id) h))))
  (ExecutorService/.shutdown executor)
  (Socket/.close socket))

(comment
  (set! *warn-on-reflection* true)

  (def conn (connect))

  (close conn))
