(ns claxon.impl.write-test
  (:require
   [claxon.conf :as conf]
   [claxon.impl.read :as ir]
   [claxon.impl.write :as iw]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]])
  (:import
   [java.io ByteArrayInputStream ByteArrayOutputStream]
   [java.util.concurrent.locks ReentrantLock]))

(def default-shapes (:claxon/frame-shapes (conf/defaults)))

(defn capture
  "Build a fake conn backed by an in-memory OutputStream and invoke
   claxon.impl.write/snd, returning the raw bytes written as a string."
  [op args payloads]
  (let [out (ByteArrayOutputStream.)
        conn {:out out :frame-shapes default-shapes :write-lock (ReentrantLock.)}]
    (iw/snd conn op args payloads)
    (String. (.toByteArray out) "UTF-8")))

(defn round-trip
  "Send a frame through snd, then parse the resulting bytes back with
   claxon.impl.read/read-frame, asserting write and read agree with each other."
  [op args payloads]
  (let [wire (capture op args payloads)
        in (ByteArrayInputStream. (.getBytes wire "UTF-8"))]
    (ir/read-frame in default-shapes)))

(deftest encode-headers
  (testing "block-simple"
    (let [encoded (iw/encode-headers-block {:headers {"Bar" ["Baz"]}})]
      (is (= "NATS/1.0\r\nBar: Baz\r\n\r\n" (String. ^bytes encoded "UTF-8")))))

  (testing "block-multi-value"
    (let [encoded (iw/encode-headers-block {:headers {"BREAKFAST" ["donut" "eggs"]}})]
      (is (= "NATS/1.0\r\nBREAKFAST: donut\r\nBREAKFAST: eggs\r\n\r\n"
             (String. ^bytes encoded "UTF-8")))))

  (testing "block-no-headers"
    (let [encoded (iw/encode-headers-block {:headers {}})]
      (is (= "NATS/1.0\r\n\r\n" (String. ^bytes encoded "UTF-8")))))

  (testing "block-with-status-and-description"
    (let [encoded (iw/encode-headers-block {:headers {} :status 503 :description "No Responders"})]
      (is (= "NATS/1.0 503 No Responders\r\n\r\n" (String. ^bytes encoded "UTF-8")))))

  (testing "block-status-without-description"
    (let [encoded (iw/encode-headers-block {:headers {} :status 100})]
      (is (= "NATS/1.0 100\r\n\r\n" (String. ^bytes encoded "UTF-8")))))

  (testing "block-keyword-key"
    (let [encoded (iw/encode-headers-block {:headers {:Bar ["Baz"]}})]
      (is (= "NATS/1.0\r\nBar: Baz\r\n\r\n" (String. ^bytes encoded "UTF-8")))))

  (testing "block-namespaced-keyword-key"
    (let [encoded (iw/encode-headers-block {:headers {:kv/Operation ["DEL"]}})]
      (is (= "NATS/1.0\r\nOperation: DEL\r\n\r\n" (String. ^bytes encoded "UTF-8")))))

  (testing "block-keyword-and-string-keys-mixed"
    (let [encoded (iw/encode-headers-block {:headers {:Bar ["Baz"] "Lunch" ["Burger"]}})
          s (String. ^bytes encoded "UTF-8")]
      (is (str/includes? s "Bar: Baz\r\n"))
      (is (str/includes? s "Lunch: Burger\r\n"))))

  (testing "block-single-string-value-not-wrapped-by-caller"
    (let [encoded (iw/encode-headers-block {:headers {"Bar" "Baz"}})]
      (is (= "NATS/1.0\r\nBar: Baz\r\n\r\n" (String. ^bytes encoded "UTF-8")))))

  (testing "block-single-non-string-scalar-value"
    (let [encoded (iw/encode-headers-block {:headers {"Count" 42}})]
      (is (= "NATS/1.0\r\nCount: 42\r\n\r\n" (String. ^bytes encoded "UTF-8")))))

  (testing "block-keyword-key-with-scalar-value"
    (let [encoded (iw/encode-headers-block {:headers {:KV-Operation "DEL"}})]
      (is (= "NATS/1.0\r\nKV-Operation: DEL\r\n\r\n" (String. ^bytes encoded "UTF-8")))))

  (testing "block-vector-value-still-multi-valued"
    (let [encoded (iw/encode-headers-block {:headers {:Breakfast ["donut" "eggs"]}})]
      (is (= "NATS/1.0\r\nBreakfast: donut\r\nBreakfast: eggs\r\n\r\n"
             (String. ^bytes encoded "UTF-8")))))

  (testing "block-list-value-is-sequential-not-rewrapped"
    (let [encoded (iw/encode-headers-block {:headers {"Bar" (list "Baz" "Qux")}})]
      (is (= "NATS/1.0\r\nBar: Baz\r\nBar: Qux\r\n\r\n" (String. ^bytes encoded "UTF-8"))))))

(deftest payload-bytes
  (testing "from-string"
    (is (= "hello" (String. ^bytes (iw/->payload-bytes :bytes "hello") "UTF-8"))))

  (testing "from-byte-array"
    (let [raw (byte-array [1 2 3])]
      (is (identical? raw (iw/->payload-bytes :bytes raw)))))

  (testing "nil-becomes-empty"
    (is (= 0 (alength ^bytes (iw/->payload-bytes :bytes nil)))))

  (testing "rejects-unsupported-value-type"
    (is (thrown? clojure.lang.ExceptionInfo (iw/->payload-bytes :bytes 12345))))

  (testing "headers-type-delegates-to-encoder"
    (let [encoded (iw/->payload-bytes :headers {:headers {"Bar" ["Baz"]}})]
      (is (= "NATS/1.0\r\nBar: Baz\r\n\r\n" (String. ^bytes encoded "UTF-8")))))

  (testing "headers-type-supports-keyword-keys-and-scalar-values"
    (let [encoded (iw/->payload-bytes :headers {:headers {:KV-Operation "DEL"}})]
      (is (= "NATS/1.0\r\nKV-Operation: DEL\r\n\r\n" (String. ^bytes encoded "UTF-8")))))

  (testing "unknown-type-throws"
    (is (thrown? clojure.lang.ExceptionInfo (iw/->payload-bytes :unknown "x")))))

(deftest derive-length-args
  (testing "simple-pub"
    (let [specs [{:name :body :type :bytes :length :bytes}]
          encoded [(.getBytes "Hello NATS!" "UTF-8")]]
      (is (= {:bytes 11} (iw/derive-length-args specs encoded)))))

  (testing "the composite arg name (:bytes here) receives the TOTAL across all payloads,
            while the simple keyword-length arg (:hdr-bytes) receives just its own payload's length"
    (let [headers-bytes (.getBytes "NATS/1.0\r\nBar: Baz\r\n\r\n" "UTF-8")
          body-bytes (.getBytes "Hello NATS!" "UTF-8")
          specs [{:name :headers :type :headers :length :hdr-bytes}
                 {:name :body :type :bytes :length [:- :bytes :hdr-bytes]}]
          encoded [headers-bytes body-bytes]
          result (iw/derive-length-args specs encoded)]
      (is (= (alength ^bytes headers-bytes) (:hdr-bytes result)))
      (is (= (+ (alength ^bytes headers-bytes) (alength ^bytes body-bytes)) (:bytes result)))))

  (testing "no-payloads"
    (is (= {} (iw/derive-length-args [] []))))

  (testing "empty-payload"
    (let [specs [{:name :body :type :bytes :length :bytes}]
          encoded [(byte-array 0)]]
      (is (= {:bytes 0} (iw/derive-length-args specs encoded))))))

(deftest render-args-line
  (testing "nil-spec"
    (is (nil? (iw/render-args-line nil {}))))

  (testing "single-json-arg"
    (is (= "{\"verbose\":false}"
           (iw/render-args-line [{:name :opts :type :json}] {:opts {"verbose" false}}))))

  (testing "single-str-arg"
    (is (= "boom" (iw/render-args-line [{:name :msg :type :str}] {:msg "boom"}))))

  (testing "multi-arg-all-present"
    (let [specs [{:name :subject :type :str}
                 {:name :sid :type :str}
                 {:name :reply-to :type :str :optional true}
                 {:name :bytes :type :int}]]
      (is (= "FOO.BAR 9 GREETING.34 11"
             (iw/render-args-line specs {:subject "FOO.BAR" :sid "9" :reply-to "GREETING.34" :bytes 11})))))

  (testing "multi-arg-optional-omitted"
    (let [specs [{:name :subject :type :str}
                 {:name :sid :type :str}
                 {:name :reply-to :type :str :optional true}
                 {:name :bytes :type :int}]]
      (is (= "FOO.BAR 9 11"
             (iw/render-args-line specs {:subject "FOO.BAR" :sid "9" :bytes 11})))))

  (testing "missing-required-throws"
    (let [specs [{:name :subject :type :str} {:name :bytes :type :int}]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (iw/render-args-line specs {:subject "FOO"}))))))

(deftest snd
  (testing "ping-no-args-no-payload"
    (is (= "PING\r\n" (capture "PING" nil nil))))

  (testing "pong"
    (is (= "PONG\r\n" (capture "PONG" nil nil))))

  (testing "unknown-op-throws"
    (is (thrown? clojure.lang.ExceptionInfo (capture "BOGUS" nil nil))))

  (testing "pub-basic"
    (is (= "PUB FOO 11\r\nHello NATS!\r\n"
           (capture "PUB" {:subject "FOO"} {:body "Hello NATS!"}))))

  (testing "pub-with-reply-to"
    (is (= "PUB FRONT.DOOR JOKE.22 11\r\nKnock Knock\r\n"
           (capture "PUB" {:subject "FRONT.DOOR" :reply-to "JOKE.22"} {:body "Knock Knock"}))))

  (testing "pub-empty-payload"
    (is (= "PUB NOTIFY 0\r\n\r\n"
           (capture "PUB" {:subject "NOTIFY"} {:body nil}))))

  (testing "pub-derives-byte-count-even-if-caller-supplied-wrong-value"
    (is (= "PUB FOO 11\r\nHello NATS!\r\n"
           (capture "PUB" {:subject "FOO" :bytes 999} {:body "Hello NATS!"}))))

  (testing "sub-no-queue-group"
    (is (= "SUB FOO 1\r\n" (capture "SUB" {:subject "FOO" :sid "1"} nil))))

  (testing "sub-with-queue-group"
    (is (= "SUB BAR G1 44\r\n" (capture "SUB" {:subject "BAR" :queue-group "G1" :sid "44"} nil))))

  (testing "unsub-no-max-msgs"
    (is (= "UNSUB 1\r\n" (capture "UNSUB" {:sid "1"} nil))))

  (testing "unsub-with-max-msgs"
    (is (= "UNSUB 1 5\r\n" (capture "UNSUB" {:sid "1" :max-msgs 5} nil))))

  (testing "connect-encodes-json"
    (let [wire (capture "CONNECT" {:opts {"verbose" false "lang" "clojure"}} nil)]
      (is (re-matches #"CONNECT \{.*\}\r\n" wire))
      (is (re-find #"\"verbose\":false" wire))
      (is (re-find #"\"lang\":\"clojure\"" wire))))

  (testing "err"
    (is (= "-ERR 'Unknown Protocol Operation'\r\n"
           (capture "-ERR" {:msg "'Unknown Protocol Operation'"} nil))))

  (testing "hpub-derives-header-and-total-lengths"
    (let [wire (capture "HPUB" {:subject "FOO"} {:headers {:headers {"Bar" ["Baz"]}} :body "Hello NATS!"})]
      (is (= "HPUB FOO 22 33\r\nNATS/1.0\r\nBar: Baz\r\n\r\nHello NATS!\r\n" wire)))))

(deftest round-trip-test
  (testing "pub"
    (let [frame (round-trip "PUB" {:subject "FOO" :reply-to "BAR"} {:body "payload"})]
      (is (= {:subject "FOO" :reply-to "BAR" :bytes 7} (:args frame)))
      (is (= "payload" (String. ^bytes (:body frame) "UTF-8")))))

  (testing "sub-unsub"
    (is (= {:op "SUB" :args {:subject "FOO" :queue-group "G1" :sid "9"}}
           (round-trip "SUB" {:subject "FOO" :queue-group "G1" :sid "9"} nil)))
    (is (= {:op "UNSUB" :args {:sid "9" :max-msgs 3}}
           (round-trip "UNSUB" {:sid "9" :max-msgs 3} nil))))

  (testing "hpub-headers-and-body-preserved"
    (let [frame (round-trip "HPUB"
                            {:subject "FOO"}
                            {:headers {:headers {"BREAKFAST" ["donut" "eggs"]}}
                             :body "Yum!"})]
      (is (= {"BREAKFAST" ["donut" "eggs"]} (get-in frame [:headers :headers])))
      (is (= "Yum!" (String. ^bytes (:body frame) "UTF-8")))))

  (testing "hpub-keyword-keys-and-scalar-values-normalize-on-read"
    (let [frame (round-trip "HPUB"
                            {:subject "$KV.profiles.sue"}
                            {:headers {:headers {:KV-Operation "DEL"}}
                             :body nil})]
      (is (= {"KV-Operation" ["DEL"]} (get-in frame [:headers :headers])))
      (is (= 0 (alength ^bytes (:body frame))))))

  (testing "hpub-keyword-keys-mixed-with-multi-value-headers"
    (let [frame (round-trip "HPUB"
                            {:subject "FOO"}
                            {:headers {:headers {:Breakfast ["donut" "eggs"]
                                                 "Lunch" "Burger"}}
                             :body "Yum!"})]
      (is (= {"Breakfast" ["donut" "eggs"] "Lunch" ["Burger"]}
             (get-in frame [:headers :headers])))))

  (testing "connect-json"
    (let [frame (round-trip "CONNECT" {:opts {"verbose" false "lang" "clojure" "protocol" 1}} nil)]
      (is (= {"verbose" false "lang" "clojure" "protocol" 1} (get-in frame [:args :opts])))))

  (testing "ping-pong"
    (is (= {:op "PING"} (round-trip "PING" nil nil)))
    (is (= {:op "PONG"} (round-trip "PONG" nil nil)))))
