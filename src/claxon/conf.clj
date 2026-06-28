(ns claxon.conf
  (:require
   [claxon.impl.write :as iw])
  (:import
   [java.util.concurrent Executors]))

(defn defaults
  []
  {:verbose false
   :pedantic false
   :lang "clojure"
   :name "claxon"
   :headers true
   :claxon/urls ["nats://localhost:4222"]
   :claxon/timeout-ms 2000
   :claxon/executor (Executors/newVirtualThreadPerTaskExecutor)
   :claxon/handlers {{:op "PING"} {:f (fn [_ conn] (iw/snd conn "PONG" nil nil))
                                   :ef (fn [_ _ ex] (prn ex))}}
   :claxon/verify-tls true
   :claxon/frame-shapes {"INFO" {:args [{:name :info :type :json}]}
                         "CONNECT" {:args [{:name :opts :type :json}]}
                         "PUB" {:args [{:name :subject :type :str}
                                       {:name :reply-to :type :str :optional true}
                                       {:name :bytes :type :int}]
                                :payloads [{:name :body :type :bytes :length :bytes}]}
                         "HPUB" {:args [{:name :subject :type :str}
                                        {:name :reply-to :type :str :optional true}
                                        {:name :hdr-bytes :type :int}
                                        {:name :bytes :type :int}]
                                 :payloads [{:name :headers :type :headers :length :hdr-bytes}
                                            {:name :body :type :bytes :length [:- :bytes :hdr-bytes]}]}
                         "MSG" {:args [{:name :subject :type :str}
                                       {:name :sid :type :str}
                                       {:name :reply-to :type :str :optional true}
                                       {:name :bytes :type :int}]
                                :payloads [{:name :body :type :bytes :length :bytes}]}
                         "HMSG" {:args [{:name :subject :type :str}
                                        {:name :sid :type :str}
                                        {:name :reply-to :type :str :optional true}
                                        {:name :hdr-bytes :type :int}
                                        {:name :bytes :type :int}]
                                 :payloads [{:name :headers :type :headers :length :hdr-bytes}
                                            {:name :body :type :bytes :length [:- :bytes :hdr-bytes]}]}
                         "SUB" {:args [{:name :subject :type :str}
                                       {:name :queue-group :type :str :optional true}
                                       {:name :sid :type :str}]}
                         "UNSUB" {:args [{:name :sid :type :str}
                                         {:name :max-msgs :type :int :optional true}]}
                         "PING" {}
                         "PONG" {}
                         "+OK" {}
                         "-ERR" {:args [{:name :msg :type :str}]}}})
