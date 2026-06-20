(ns claxon.impl.write-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [claxon.impl.write :as iw]
   [claxon.impl.read :as ir])
  (:import
   [java.io ByteArrayOutputStream ByteArrayInputStream]))

;; ---------------------------------------------------------------------------
;; helpers
;; ---------------------------------------------------------------------------

(def default-shapes
  "Mirrors claxon.conf/defaults' :claxon/frame-shapes closely enough to
   exercise snd end-to-end for every operation the client actually sends."
  {"INFO" {:args [{:name :info :type :json}]}
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
   "-ERR" {:args [{:name :msg :type :str}]}})

(defn capture
  "Build a fake conn backed by an in-memory OutputStream and invoke
   claxon.impl.write/snd, returning the raw bytes written as a string."
  [op args payloads]
  (let [out (ByteArrayOutputStream.)
        conn {:out out :frame-shapes default-shapes}]
    (iw/snd conn op args payloads)
    (String. (.toByteArray out) "UTF-8")))

(defn round-trip
  "Send a frame through snd, then parse the resulting bytes back with
   claxon.impl.read/read-frame, asserting write and read agree with each other."
  [op args payloads]
  (let [wire (capture op args payloads)
        in (ByteArrayInputStream. (.getBytes wire "UTF-8"))]
    (ir/read-frame in default-shapes)))

;; ---------------------------------------------------------------------------
;; encode-headers-block
;; ---------------------------------------------------------------------------

(deftest encode-headers-block-simple
  (let [encoded (iw/encode-headers-block {:headers {"Bar" ["Baz"]}})]
    (is (= "NATS/1.0\r\nBar: Baz\r\n\r\n" (String. ^bytes encoded "UTF-8")))))

(deftest encode-headers-block-multi-value
  (let [encoded (iw/encode-headers-block {:headers {"BREAKFAST" ["donut" "eggs"]}})]
    (is (= "NATS/1.0\r\nBREAKFAST: donut\r\nBREAKFAST: eggs\r\n\r\n"
           (String. ^bytes encoded "UTF-8")))))

(deftest encode-headers-block-no-headers
  (let [encoded (iw/encode-headers-block {:headers {}})]
    (is (= "NATS/1.0\r\n\r\n" (String. ^bytes encoded "UTF-8")))))

(deftest encode-headers-block-with-status-and-description
  (let [encoded (iw/encode-headers-block {:headers {} :status 503 :description "No Responders"})]
    (is (= "NATS/1.0 503 No Responders\r\n\r\n" (String. ^bytes encoded "UTF-8")))))

(deftest encode-headers-block-status-without-description
  (let [encoded (iw/encode-headers-block {:headers {} :status 100})]
    (is (= "NATS/1.0 100\r\n\r\n" (String. ^bytes encoded "UTF-8")))))

;; ---------------------------------------------------------------------------
;; ->payload-bytes
;; ---------------------------------------------------------------------------

(deftest payload-bytes-from-string
  (is (= "hello" (String. ^bytes (iw/->payload-bytes :bytes "hello") "UTF-8"))))

(deftest payload-bytes-from-byte-array
  (let [raw (byte-array [1 2 3])]
    (is (identical? raw (iw/->payload-bytes :bytes raw)))))

(deftest payload-bytes-nil-becomes-empty
  (is (= 0 (alength ^bytes (iw/->payload-bytes :bytes nil)))))

(deftest payload-bytes-rejects-unsupported-value-type
  (is (thrown? clojure.lang.ExceptionInfo (iw/->payload-bytes :bytes 12345))))

(deftest payload-bytes-headers-type-delegates-to-encoder
  (let [encoded (iw/->payload-bytes :headers {:headers {"Bar" ["Baz"]}})]
    (is (= "NATS/1.0\r\nBar: Baz\r\n\r\n" (String. ^bytes encoded "UTF-8")))))

(deftest payload-bytes-unknown-type-throws
  (is (thrown? clojure.lang.ExceptionInfo (iw/->payload-bytes :unknown "x"))))

;; ---------------------------------------------------------------------------
;; derive-length-args
;; ---------------------------------------------------------------------------

(deftest derive-length-args-simple-pub
  (let [specs [{:name :body :type :bytes :length :bytes}]
        encoded [(.getBytes "Hello NATS!" "UTF-8")]]
    (is (= {:bytes 11} (iw/derive-length-args specs encoded)))))

(deftest derive-length-args-hpub-style-composite
  (testing "the composite arg name (:bytes here) receives the TOTAL across all payloads,
            while the simple keyword-length arg (:hdr-bytes) receives just its own payload's length"
    (let [headers-bytes (.getBytes "NATS/1.0\r\nBar: Baz\r\n\r\n" "UTF-8")
          body-bytes (.getBytes "Hello NATS!" "UTF-8")
          specs [{:name :headers :type :headers :length :hdr-bytes}
                 {:name :body :type :bytes :length [:- :bytes :hdr-bytes]}]
          encoded [headers-bytes body-bytes]
          result (iw/derive-length-args specs encoded)]
      (is (= (alength ^bytes headers-bytes) (:hdr-bytes result)))
      (is (= (+ (alength ^bytes headers-bytes) (alength ^bytes body-bytes)) (:bytes result))))))

(deftest derive-length-args-no-payloads
  (is (= {} (iw/derive-length-args [] []))))

(deftest derive-length-args-empty-payload
  (let [specs [{:name :body :type :bytes :length :bytes}]
        encoded [(byte-array 0)]]
    (is (= {:bytes 0} (iw/derive-length-args specs encoded)))))

;; ---------------------------------------------------------------------------
;; render-args-line
;; ---------------------------------------------------------------------------

(deftest render-args-line-nil-spec
  (is (nil? (iw/render-args-line nil {}))))

(deftest render-args-line-single-json-arg
  (is (= "{\"verbose\":false}"
         (iw/render-args-line [{:name :opts :type :json}] {:opts {"verbose" false}}))))

(deftest render-args-line-single-str-arg
  (is (= "boom" (iw/render-args-line [{:name :msg :type :str}] {:msg "boom"}))))

(deftest render-args-line-multi-arg-all-present
  (let [specs [{:name :subject :type :str}
               {:name :sid :type :str}
               {:name :reply-to :type :str :optional true}
               {:name :bytes :type :int}]]
    (is (= "FOO.BAR 9 GREETING.34 11"
           (iw/render-args-line specs {:subject "FOO.BAR" :sid "9" :reply-to "GREETING.34" :bytes 11})))))

(deftest render-args-line-multi-arg-optional-omitted
  (let [specs [{:name :subject :type :str}
               {:name :sid :type :str}
               {:name :reply-to :type :str :optional true}
               {:name :bytes :type :int}]]
    (is (= "FOO.BAR 9 11"
           (iw/render-args-line specs {:subject "FOO.BAR" :sid "9" :bytes 11})))))

(deftest render-args-line-missing-required-throws
  (let [specs [{:name :subject :type :str} {:name :bytes :type :int}]]
    (is (thrown? clojure.lang.ExceptionInfo
                 (iw/render-args-line specs {:subject "FOO"})))))

;; ---------------------------------------------------------------------------
;; snd -- wire format assertions
;; ---------------------------------------------------------------------------

(deftest snd-ping-no-args-no-payload
  (is (= "PING\r\n" (capture "PING" nil nil))))

(deftest snd-pong
  (is (= "PONG\r\n" (capture "PONG" nil nil))))

(deftest snd-unknown-op-throws
  (is (thrown? clojure.lang.ExceptionInfo (capture "BOGUS" nil nil))))

(deftest snd-pub-basic
  (is (= "PUB FOO 11\r\nHello NATS!\r\n"
         (capture "PUB" {:subject "FOO"} {:body "Hello NATS!"}))))

(deftest snd-pub-with-reply-to
  (is (= "PUB FRONT.DOOR JOKE.22 11\r\nKnock Knock\r\n"
         (capture "PUB" {:subject "FRONT.DOOR" :reply-to "JOKE.22"} {:body "Knock Knock"}))))

(deftest snd-pub-empty-payload
  (is (= "PUB NOTIFY 0\r\n\r\n"
         (capture "PUB" {:subject "NOTIFY"} {:body nil}))))

(deftest snd-pub-derives-byte-count-even-if-caller-supplied-wrong-value
  (testing "the :bytes arg is always derived from the actual encoded payload length,
            so a stale or incorrect value passed in `args` is silently overridden"
    (is (= "PUB FOO 11\r\nHello NATS!\r\n"
           (capture "PUB" {:subject "FOO" :bytes 999} {:body "Hello NATS!"})))))

(deftest snd-sub-no-queue-group
  (is (= "SUB FOO 1\r\n" (capture "SUB" {:subject "FOO" :sid "1"} nil))))

(deftest snd-sub-with-queue-group
  (is (= "SUB BAR G1 44\r\n" (capture "SUB" {:subject "BAR" :queue-group "G1" :sid "44"} nil))))

(deftest snd-unsub-no-max-msgs
  (is (= "UNSUB 1\r\n" (capture "UNSUB" {:sid "1"} nil))))

(deftest snd-unsub-with-max-msgs
  (is (= "UNSUB 1 5\r\n" (capture "UNSUB" {:sid "1" :max-msgs 5} nil))))

(deftest snd-connect-encodes-json
  (testing "args-line is valid JSON containing the expected keys/values
            (not asserting exact key order, since clj/data.json and bb/cheshire
            may serialize map keys in different orders)"
    (let [wire (capture "CONNECT" {:opts {"verbose" false "lang" "clojure"}} nil)]
      (is (re-matches #"CONNECT \{.*\}\r\n" wire))
      (is (re-find #"\"verbose\":false" wire))
      (is (re-find #"\"lang\":\"clojure\"" wire)))))

(deftest snd-err
  (is (= "-ERR 'Unknown Protocol Operation'\r\n"
         (capture "-ERR" {:msg "'Unknown Protocol Operation'"} nil))))

(deftest snd-hpub-derives-header-and-total-lengths
  (let [wire (capture "HPUB" {:subject "FOO"} {:headers {:headers {"Bar" ["Baz"]}} :body "Hello NATS!"})]
    (is (= "HPUB FOO 22 33\r\nNATS/1.0\r\nBar: Baz\r\n\r\nHello NATS!\r\n" wire))))

(deftest snd-flushes-control-line-and-payload-separately
  (testing "snd performs (at least) two flushes: once after the control line, once after the payload"
    (let [flush-count (atom 0)
          out (proxy [java.io.OutputStream] []
                (write [b] nil)
                (flush [] (swap! flush-count inc)))
          conn {:out out :frame-shapes default-shapes}]
      (iw/snd conn "PUB" {:subject "FOO"} {:body "hi"})
      (is (>= @flush-count 2)))))

;; ---------------------------------------------------------------------------
;; round-trip: write then read should agree, for every op the client emits
;; ---------------------------------------------------------------------------

(deftest round-trip-pub
  (let [frame (round-trip "PUB" {:subject "FOO" :reply-to "BAR"} {:body "payload"})]
    (is (= {:subject "FOO" :reply-to "BAR" :bytes 7} (:args frame)))
    (is (= "payload" (String. ^bytes (:body frame) "UTF-8")))))

(deftest round-trip-sub-unsub
  (is (= {:op "SUB" :args {:subject "FOO" :queue-group "G1" :sid "9"}}
         (round-trip "SUB" {:subject "FOO" :queue-group "G1" :sid "9"} nil)))
  (is (= {:op "UNSUB" :args {:sid "9" :max-msgs 3}}
         (round-trip "UNSUB" {:sid "9" :max-msgs 3} nil))))

(deftest round-trip-hpub-headers-and-body-preserved
  (let [frame (round-trip "HPUB"
                          {:subject "FOO"}
                          {:headers {:headers {"BREAKFAST" ["donut" "eggs"]}}
                           :body "Yum!"})]
    (is (= {"BREAKFAST" ["donut" "eggs"]} (get-in frame [:headers :headers])))
    (is (= "Yum!" (String. ^bytes (:body frame) "UTF-8")))))

(deftest round-trip-connect-json
  (let [frame (round-trip "CONNECT" {:opts {"verbose" false "lang" "clojure" "protocol" 1}} nil)]
    (is (= {"verbose" false "lang" "clojure" "protocol" 1} (get-in frame [:args :opts])))))

(deftest round-trip-ping-pong
  (is (= {:op "PING"} (round-trip "PING" nil nil)))
  (is (= {:op "PONG"} (round-trip "PONG" nil nil))))
