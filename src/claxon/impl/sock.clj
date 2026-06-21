(ns claxon.impl.sock
  (:import
   [java.net Socket]
   [java.security SecureRandom]
   [java.security.cert X509Certificate]
   [javax.net.ssl
    SSLContext
    SSLSocket
    SSLSocketFactory
    TrustManager
    X509TrustManager]))

(defn trust-all-context
  []
  (let [trust-all (reify X509TrustManager
                    (checkClientTrusted [_ _ _])
                    (checkServerTrusted [_ _ _])
                    (getAcceptedIssuers [_] (make-array X509Certificate 0)))
        ctx (SSLContext/getInstance "TLS")]
    (.init ctx nil (into-array TrustManager [trust-all]) (SecureRandom.))
    ctx))

(defn ->tls
  ^Socket
  [{:keys [^Socket socket ^String host ^Integer port verify]}]
  (let [factory (if verify
                  (SSLSocketFactory/getDefault)
                  (SSLContext/.getSocketFactory (trust-all-context)))
        ^SSLSocket ssl-socket (SSLSocketFactory/.createSocket factory socket host port true)]
    (when verify
      (let [params (.getSSLParameters ssl-socket)]
        (.setEndpointIdentificationAlgorithm params "HTTPS")
        (.setSSLParameters ssl-socket params)))
    (.setUseClientMode ssl-socket true)
    (.startHandshake ssl-socket)
    ssl-socket))

(comment
  (set! *warn-on-reflection* true))
