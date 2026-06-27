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
  The handler will be passed in the frame and conn, the err-handler the exception as well.
  Returns a handler id, usable with remove-handler."
  ([conn handler matches]
   (add-handler conn handler nil matches))
  ([conn handler err-handler {:keys [op args]}]
   (let [id (swap! ic/handler-ids inc)]
     (swap! ic/handlers
            assoc-in
            [(:id conn) op id]
            {:fn handler
             :efn err-handler
             :matches {:args args}})
     id)))

(defn remove-handler
  "Unregisters the handler with the given id, as returned by add-handler."
  [id]
  (doseq [[cid ops] @ic/handlers ;; TODO: Maybe O(1) deletions later
          [op hs] ops
          :when (contains? hs id)]
    (swap! ic/handlers update-in [cid op] dissoc id)))

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
         {:keys [claxon/urls
                 claxon/timeout-ms
                 claxon/executor
                 claxon/handlers
                 claxon/verify-tls
                 claxon/frame-shapes]} opts
         {:keys [host port ^Socket socket]}
         (->> urls
              (map ic/parse-nats-url)
              (shuffle)
              (some (fn [{:keys [^String host ^Integer port]}]
                      (try
                        {:socket (doto (Socket.)
                                   (.connect (InetSocketAddress. host port) timeout-ms))
                         :host host
                         :port port}
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
               :frame-shapes frame-shapes
               :write-lock (ReentrantLock.)}
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
  "Closes conn: deregisters its handlers, shuts down its executor, and closes the underlying socket."
  [{:keys [socket executor id]}]
  (swap! ic/handlers dissoc id)
  (ExecutorService/.shutdown executor)
  (Socket/.close socket))

(comment
  (set! *warn-on-reflection* true)

  (deref ic/handlers)

  (def conn (connect {:claxon/verify-tls false}))

  (invoke conn {:op "PING"})

  (close conn))
