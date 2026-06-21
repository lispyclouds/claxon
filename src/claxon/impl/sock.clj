(ns claxon.impl.sock
  (:import
   [java.net InetSocketAddress Socket]
   [java.security.cert X509Certificate]
   [javax.net.ssl
    SSLContext
    SSLSocket
    SSLSocketFactory
    TrustManager
    X509TrustManager]
   [java.security SecureRandom]))

(defn trust-all-context
  []
  (let [trust-all (proxy [X509TrustManager] []
                    (checkClientTrusted [_ _])
                    (checkServerTrusted [_ _])
                    (getAcceptedIssuers [] (make-array X509Certificate 0)))
        ctx (SSLContext/getInstance "TLS")]
    (.init ctx nil (into-array TrustManager [trust-all]) (SecureRandom.))
    ctx))

(defn connect
  ^Socket
  [{:keys [^String host ^Integer port tls verify timeout]}]
  (let [socket (if-not tls
                 (Socket.)
                 (SSLSocketFactory/.createSocket
                  (if verify
                    (SSLSocketFactory/getDefault)
                    (SSLContext/.getSocketFactory (trust-all-context)))))]
    (when (and tls verify)
      (let [params (SSLSocket/.getSSLParameters socket)]
        (.setEndpointIdentificationAlgorithm params "HTTPS")
        (SSLSocket/.setSSLParameters socket params)))
    (.connect socket (InetSocketAddress. host port) timeout)
    socket))

(comment
  (set! *warn-on-reflection* true))
