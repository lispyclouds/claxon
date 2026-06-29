(ns claxon.client
  (:require
   [claxon.conf :as conf]
   [claxon.impl.common :as ic]
   [claxon.impl.read :as ir]
   [claxon.impl.sock :as sock]
   [claxon.impl.write :as iw])
  (:import
   [java.io BufferedInputStream BufferedOutputStream]
   [java.net InetSocketAddress Socket]
   [java.util.concurrent ExecutorService]
   [java.util.concurrent.locks ReentrantLock]))

(defn add-handler
  "Registers handler to be called on conn whenever an incoming frame matches op and args (a submap match).
  Takes in an optional error-handler in case of uncaught exceptions in the handler.
  Uncaught exceptions in the error-handler will be swallowed.
  The handler will be passed in the frame and conn, the err-handler the exception as well.
  Returns a handler id, usable with remove-handler."
  ([conn handler matches]
   (add-handler conn handler nil matches))
  ([{:keys [handlers handler-ids]} handler err-handler {:keys [op args]}]
   (let [id (swap! handler-ids inc)]
     (swap! handlers
            assoc-in
            [op id]
            {:fn handler
             :efn err-handler
             :matches {:args args}})
     id)))

(defn remove-handler
  "Unregisters the handler in a conn by id."
  [{:keys [handlers]} id]
  ;; TODO: Maybe O(1) deletions later
  (swap! handlers
         #(reduce-kv (fn [acc op hs]
                       (assoc acc
                              op
                              (if (contains? hs id)
                                (dissoc hs id)
                                hs)))
                     {}
                     %))
  nil)

(defn invoke
  "Sends a frame ({:op ... :args ... :payloads ...}) to the server over conn.
  op: the operation, eg PUB, SUB, PING etc as a string
  args: the map of args, eg {:subject foo :sid 10}
  payloads: the map of (generally bytes payloads), eg for PUB"
  [conn {:keys [op args payloads]}]
  (iw/snd conn op args payloads))

(defn connect
  "Connects to a NATS server and performs the INFO/CONNECT handshake, upgrading to TLS if required.
  opts are merged over claxon.conf/defaults.
  Returns a conn map to be passed to other functions."
  ([]
   (connect {}))
  ([opts]
   (let [opts (merge (conf/defaults) opts)
         {:keys [claxon/urls
                 claxon/timeout-ms
                 claxon/executor
                 claxon/handlers
                 claxon/verify-tls
                 claxon/frame-shapes]} opts
         {:keys [host port ^Socket socket user password]}
         (->> urls
              (map ic/parse-nats-url)
              (shuffle)
              (some (fn [{:keys [^String host ^Integer port user password]}]
                      (try
                        {:socket (doto (Socket.)
                                   (.connect (InetSocketAddress. host port) timeout-ms))
                         :host host
                         :port port
                         :user user
                         :password password}
                        (catch Exception _ false)))))
         _ (when-not socket
             (throw (ex-info "Cannot connect to any of the urls" {:urls urls})))
         opts (cond-> opts
                user (assoc :user user)
                password (assoc :password password))
         in (-> socket
                .getInputStream
                BufferedInputStream.)
         out (-> socket
                 .getOutputStream
                 BufferedOutputStream.)
         conn {:socket socket
               :in in
               :out out
               :executor executor
               :frame-shapes frame-shapes
               :write-lock (ReentrantLock.)
               :handler-ids (atom 0)
               :handlers (atom {})} ;; TODO: indexed on op -> hid -> handler, ENHANCE moar
         info (-> in
                  (ir/read-frame frame-shapes)
                  :args
                  :info)
         conn (if (get info "tls_required")
                (let [ssock (sock/->tls {:socket socket
                                         :host host
                                         :port port
                                         :verify verify-tls})]
                  (assoc conn
                         :socket ssock
                         :in (-> ssock
                                 .getInputStream
                                 BufferedInputStream.)
                         :out (-> ssock
                                  .getOutputStream
                                  BufferedOutputStream.)))
                conn)]
     (run! (fn [[{:keys [op args]} {:keys [f ef]}]]
             (add-handler conn f ef {:op op :args args}))
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
  "Closes conn by cleaning up all underlying resources."
  [{:keys [socket executor]}]
  (ExecutorService/.shutdown executor)
  (Socket/.close socket))

(comment
  (set! *warn-on-reflection* true)

  (def conn (connect {:claxon/verify-tls false}))

  (invoke conn {:op "PING"})

  (close conn))
