(ns claxon.impl.sock
  (:import
   [java.io FileInputStream]
   [java.net Socket]
   [java.security KeyStore SecureRandom]
   [java.security.cert CertificateFactory X509Certificate]
   [javax.net.ssl
    SSLContext
    SSLSocket
    SSLSocketFactory
    TrustManager
    TrustManagerFactory
    X509TrustManager]))

(defn ca-context
  [paths]
  (let [ks (doto (KeyStore/getInstance (KeyStore/getDefaultType))
             (.load nil nil))
        _ (doseq [path paths]
            (let [cert (with-open [is (FileInputStream. ^String path)]
                         (.generateCertificate (CertificateFactory/getInstance "X.509") is))]
              (.setCertificateEntry ks "ca" cert)))
        tmf (doto (TrustManagerFactory/getInstance (TrustManagerFactory/getDefaultAlgorithm))
              (.init ks))]
    (doto (SSLContext/getInstance "TLS")
      (.init nil (.getTrustManagers tmf) nil))))

(defn trust-all-context
  []
  (let [trust-all (reify X509TrustManager
                    (checkClientTrusted [_ _ _])
                    (checkServerTrusted [_ _ _])
                    (getAcceptedIssuers [_] (make-array X509Certificate 0)))]
    (doto (SSLContext/getInstance "TLS")
      (.init nil (into-array TrustManager [trust-all]) (SecureRandom.)))))

(defn ->tls
  ^Socket
  [{:keys [^Socket socket ^String host ^Integer port verify ssl-context ca-certs]}]
  (let [factory (cond
                  ssl-context (SSLContext/.getSocketFactory ssl-context)
                  (not verify) (SSLContext/.getSocketFactory (trust-all-context))
                  (> (count ca-certs) 0) (SSLContext/.getSocketFactory (ca-context ca-certs))
                  :else (SSLSocketFactory/getDefault))
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
