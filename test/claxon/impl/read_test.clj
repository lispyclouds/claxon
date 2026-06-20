(ns claxon.impl.read-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [claxon.impl.read :as ir])
  (:import
   [java.io ByteArrayInputStream EOFException]))

;; ---------------------------------------------------------------------------
;; helpers
;; ---------------------------------------------------------------------------

(defn ->stream
  "Build an InputStream from a string, interpreting it as raw bytes (UTF-8)."
  ^ByteArrayInputStream [^String s]
  (ByteArrayInputStream. (.getBytes s "UTF-8")))

(def default-shapes
  "A representative subset of the real frame-shapes table, mirroring
   claxon.conf/defaults closely enough to exercise the parser end-to-end."
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

;; ---------------------------------------------------------------------------
;; read-all
;; ---------------------------------------------------------------------------

(deftest read-all-strips-trailing-crlf
  (is (= "PING" (ir/read-all (->stream "PING\r\n")))))

(deftest read-all-reads-up-to-first-crlf-only
  (testing "only the first line is consumed; nothing past the first CRLF is read"
    (let [in (->stream "PING\r\nPONG\r\n")
          first-line (ir/read-all in)]
      (is (= "PING" first-line))
      (is (= "PONG" (ir/read-all in))))))

(deftest read-all-lone-lf-is-not-a-terminator
  (testing "a bare LF without a preceding CR does not end the line"
    (let [in (->stream "FOO\nBAR\r\n")]
      (is (= "FOO\nBAR" (ir/read-all in))))))

(deftest read-all-throws-eof-when-closed-mid-line
  (is (thrown? EOFException (ir/read-all (->stream "PING")))))

(deftest read-all-empty-line
  (is (= "" (ir/read-all (->stream "\r\n")))))

;; ---------------------------------------------------------------------------
;; read-exactly
;; ---------------------------------------------------------------------------

(deftest read-exactly-reads-requested-bytes
  (let [result (ir/read-exactly (->stream "Hello NATS!") 11)]
    (is (= "Hello NATS!" (String. ^bytes result "UTF-8")))))

(deftest read-exactly-zero-length
  (let [result (ir/read-exactly (->stream "") 0)]
    (is (= 0 (alength ^bytes result)))))

(deftest read-exactly-throws-eof-on-short-stream
  (is (thrown? EOFException (ir/read-exactly (->stream "abc") 10))))

(deftest read-exactly-handles-partial-reads
  (testing "loops correctly even if the underlying stream were to deliver short reads
            (simulated here via a stream wrapper that returns 1 byte at a time)"
    (let [src (->stream "abcdef")
          throttled (proxy [java.io.InputStream] []
                      (read
                        ([] (.read src))
                        ([buf off len] (.read src buf off (min 1 len)))))]
      (is (= "abcdef" (String. ^bytes (ir/read-exactly throttled 6) "UTF-8"))))))

;; ---------------------------------------------------------------------------
;; consume-crlf
;; ---------------------------------------------------------------------------

(deftest consume-crlf-happy-path
  (is (nil? (ir/consume-crlf (->stream "\r\n")))))

(deftest consume-crlf-rejects-wrong-bytes
  (is (thrown? clojure.lang.ExceptionInfo (ir/consume-crlf (->stream "XY")))))

(deftest consume-crlf-rejects-lf-only
  (is (thrown? clojure.lang.ExceptionInfo (ir/consume-crlf (->stream "\n\n")))))

;; ---------------------------------------------------------------------------
;; cast-token
;; ---------------------------------------------------------------------------

(deftest cast-token-int
  (is (= 42 (ir/cast-token :int "42"))))

(deftest cast-token-default-passthrough
  (is (= "FOO.BAR" (ir/cast-token :str "FOO.BAR")))
  (is (= "anything" (ir/cast-token nil "anything"))))

(deftest cast-token-int-rejects-non-numeric
  (is (thrown? NumberFormatException (ir/cast-token :int "not-a-number"))))

;; ---------------------------------------------------------------------------
;; parse-tokenized-args
;; ---------------------------------------------------------------------------

(deftest parse-tokenized-args-all-present
  (let [specs [{:name :subject :type :str}
               {:name :sid :type :str}
               {:name :reply-to :type :str :optional true}
               {:name :bytes :type :int}]]
    (is (= {:subject "FOO.BAR" :sid "9" :reply-to "GREETING.34" :bytes 11}
           (ir/parse-tokenized-args specs ["FOO.BAR" "9" "GREETING.34" "11"])))))

(deftest parse-tokenized-args-optional-omitted
  (let [specs [{:name :subject :type :str}
               {:name :sid :type :str}
               {:name :reply-to :type :str :optional true}
               {:name :bytes :type :int}]]
    (is (= {:subject "FOO.BAR" :sid "9" :bytes 11}
           (ir/parse-tokenized-args specs ["FOO.BAR" "9" "11"])))))

(deftest parse-tokenized-args-trailing-optional-omitted
  (testing "UNSUB with no max-msgs"
    (let [specs [{:name :sid :type :str}
                 {:name :max-msgs :type :int :optional true}]]
      (is (= {:sid "1"} (ir/parse-tokenized-args specs ["1"]))))))

(deftest parse-tokenized-args-trailing-optional-present
  (let [specs [{:name :sid :type :str}
               {:name :max-msgs :type :int :optional true}]]
    (is (= {:sid "1" :max-msgs 5} (ir/parse-tokenized-args specs ["1" "5"])))))

(deftest parse-tokenized-args-too-few-tokens-throws
  (let [specs [{:name :subject :type :str}
               {:name :sid :type :str}]]
    (is (thrown? clojure.lang.ExceptionInfo
                 (ir/parse-tokenized-args specs ["only-one"])))))

(deftest parse-tokenized-args-too-many-tokens-throws
  (let [specs [{:name :sid :type :str}
               {:name :max-msgs :type :int :optional true}]]
    (is (thrown? clojure.lang.ExceptionInfo
                 (ir/parse-tokenized-args specs ["1" "5" "extra"])))))

(deftest parse-tokenized-args-sub-with-queue-group
  (let [specs [{:name :subject :type :str}
               {:name :queue-group :type :str :optional true}
               {:name :sid :type :str}]]
    (is (= {:subject "BAR" :queue-group "G1" :sid "44"}
           (ir/parse-tokenized-args specs ["BAR" "G1" "44"])))
    (is (= {:subject "FOO" :sid "1"}
           (ir/parse-tokenized-args specs ["FOO" "1"])))))

;; ---------------------------------------------------------------------------
;; parse-args
;; ---------------------------------------------------------------------------

(deftest parse-args-nil-spec-yields-nil
  (is (nil? (ir/parse-args nil "anything"))))

(deftest parse-args-single-json-arg
  (is (= {:opts {"verbose" false}}
         (ir/parse-args [{:name :opts :type :json}] "{\"verbose\":false}"))))

(deftest parse-args-single-str-arg-keeps-whole-line
  (testing "a single non-json arg (e.g. -ERR's message) is not tokenized on spaces"
    (is (= {:msg "'Authorization Violation'"}
           (ir/parse-args [{:name :msg :type :str}] "'Authorization Violation'")))))

(deftest parse-args-multi-arg-delegates-to-tokenizer
  (is (= {:subject "FOO" :sid "9" :bytes 11}
         (ir/parse-args [{:name :subject :type :str}
                         {:name :sid :type :str}
                         {:name :reply-to :type :str :optional true}
                         {:name :bytes :type :int}]
                        "FOO 9 11"))))

;; ---------------------------------------------------------------------------
;; parse-headers-block
;; ---------------------------------------------------------------------------

(defn bytes-of ^bytes [^String s] (.getBytes s "UTF-8"))

(deftest parse-headers-block-single-header
  (let [raw (bytes-of "NATS/1.0\r\nBar: Baz\r\n\r\n")
        parsed (ir/parse-headers-block raw)]
    (is (= {"Bar" ["Baz"]} (:headers parsed)))
    (is (not (contains? parsed :status)))
    (is (not (contains? parsed :description)))))

(deftest parse-headers-block-multi-value-header
  (let [raw (bytes-of "NATS/1.0\r\nBREAKFAST: donut\r\nBREAKFAST: eggs\r\n\r\n")
        parsed (ir/parse-headers-block raw)]
    (is (= {"BREAKFAST" ["donut" "eggs"]} (:headers parsed)))))

(deftest parse-headers-block-multiple-distinct-headers
  (let [raw (bytes-of "NATS/1.0\r\nBREAKFAST: donut\r\nLUNCH: burger\r\n\r\n")
        parsed (ir/parse-headers-block raw)]
    (is (= {"BREAKFAST" ["donut"] "LUNCH" ["burger"]} (:headers parsed)))))

(deftest parse-headers-block-no-headers
  (let [raw (bytes-of "NATS/1.0\r\n\r\n")
        parsed (ir/parse-headers-block raw)]
    (is (= {} (:headers parsed)))))

(deftest parse-headers-block-status-and-description
  (testing "status-line headers (used for things like no-responders / timeouts) parse status and description.
            The version line is split on space with a limit of 3, so a multi-word description
            (everything after the status code) is kept intact rather than truncated to its first word."
    (let [raw (bytes-of "NATS/1.0 503 No Responders\r\n\r\n")
          parsed (ir/parse-headers-block raw)]
      (is (= 503 (:status parsed)))
      (is (= "No Responders" (:description parsed))))))

(deftest parse-headers-block-status-only-no-description
  (let [raw (bytes-of "NATS/1.0 100\r\n\r\n")
        parsed (ir/parse-headers-block raw)]
    (is (= 100 (:status parsed)))
    (is (not (contains? parsed :description)))))

(deftest parse-headers-block-trims-header-value-whitespace
  (let [raw (bytes-of "NATS/1.0\r\nBar:   Baz  \r\n\r\n")
        parsed (ir/parse-headers-block raw)]
    (is (= {"Bar" ["Baz"]} (:headers parsed)))))

(deftest parse-headers-block-value-containing-colon-is-preserved
  (testing "a header value that itself contains a colon (e.g. a URL) must be preserved
            in full -- the line should only ever be split on the FIRST colon, since
            `name: value` is colon-delimited but the value itself is free text."
    (let [raw (bytes-of "NATS/1.0\r\nLocation: http://example.com:8080/path\r\n\r\n")
          parsed (ir/parse-headers-block raw)]
      (is (= {"Location" ["http://example.com:8080/path"]} (:headers parsed))))))

;; ---------------------------------------------------------------------------
;; eval-length
;; ---------------------------------------------------------------------------

(deftest eval-length-keyword-looks-up-arg
  (is (= 11 (ir/eval-length :bytes {:bytes 11}))))

(deftest eval-length-int-is-literal
  (is (= 4 (ir/eval-length 4 {}))))

(deftest eval-length-subtraction
  (is (= 11 (ir/eval-length [:- :bytes :hdr-bytes] {:bytes 33 :hdr-bytes 22}))))

(deftest eval-length-addition
  (is (= 55 (ir/eval-length [:+ :bytes :hdr-bytes] {:bytes 33 :hdr-bytes 22}))))

(deftest eval-length-nested-composite
  (is (= 11 (ir/eval-length [:- [:+ :a :b] :c] {:a 5 :b 10 :c 4}))))

(deftest eval-length-unknown-op-throws
  (is (thrown? clojure.lang.ExceptionInfo (ir/eval-length [:* :a :b] {:a 1 :b 2}))))

(deftest eval-length-invalid-expr-throws
  (is (thrown? clojure.lang.ExceptionInfo (ir/eval-length "nope" {}))))

;; ---------------------------------------------------------------------------
;; read-payloads
;; ---------------------------------------------------------------------------

(deftest read-payloads-single-bytes-payload
  (let [in (->stream "Hello NATS!\r\n")
        specs [{:name :body :type :bytes :length :bytes}]
        result (ir/read-payloads in specs {:bytes 11})]
    (is (= "Hello NATS!" (String. ^bytes (:body result) "UTF-8")))))

(deftest read-payloads-throws-when-trailing-crlf-missing
  (let [in (->stream "Hello NATS!XX")
        specs [{:name :body :type :bytes :length :bytes}]]
    (is (thrown? clojure.lang.ExceptionInfo (ir/read-payloads in specs {:bytes 11})))))

(deftest read-payloads-headers-and-body-hpub-style
  (let [headers-block "NATS/1.0\r\nBar: Baz\r\n\r\n"
        body "Hello NATS!"
        hdr-bytes (count (.getBytes ^String headers-block "UTF-8"))
        body-bytes (count (.getBytes ^String body "UTF-8"))
        total (+ hdr-bytes body-bytes)
        in (->stream (str headers-block body "\r\n"))
        specs [{:name :headers :type :headers :length :hdr-bytes}
               {:name :body :type :bytes :length [:- :bytes :hdr-bytes]}]
        result (ir/read-payloads in specs {:hdr-bytes hdr-bytes :bytes total})]
    (is (= {"Bar" ["Baz"]} (get-in result [:headers :headers])))
    (is (= "Hello NATS!" (String. ^bytes (:body result) "UTF-8")))))

(deftest read-payloads-empty-body
  (testing "PUB NOTIFY 0 -- an empty payload is still followed by CRLF"
    (let [in (->stream "\r\n")
          specs [{:name :body :type :bytes :length :bytes}]
          result (ir/read-payloads in specs {:bytes 0})]
      (is (= 0 (alength ^bytes (:body result)))))))

;; ---------------------------------------------------------------------------
;; read-frame -- integration across the whole parser for each op
;; ---------------------------------------------------------------------------

(deftest read-frame-ping
  (is (= {:op "PING"} (ir/read-frame (->stream "PING\r\n") default-shapes))))

(deftest read-frame-pong
  (is (= {:op "PONG"} (ir/read-frame (->stream "PONG\r\n") default-shapes))))

(deftest read-frame-ok
  (is (= {:op "+OK"} (ir/read-frame (->stream "+OK\r\n") default-shapes))))

(deftest read-frame-err
  (is (= {:op "-ERR" :args {:msg "'Unknown Protocol Operation'"}}
         (ir/read-frame (->stream "-ERR 'Unknown Protocol Operation'\r\n") default-shapes))))

(deftest read-frame-info
  (let [frame (ir/read-frame (->stream "INFO {\"server_id\":\"abc\",\"port\":4222}\r\n") default-shapes)]
    (is (= "INFO" (:op frame)))
    (is (= "abc" (get-in frame [:args :info "server_id"])))
    (is (= 4222 (get-in frame [:args :info "port"])))))

(deftest read-frame-pub-with-payload
  (let [frame (ir/read-frame (->stream "PUB FOO 11\r\nHello NATS!\r\n") default-shapes)]
    (is (= "PUB" (:op frame)))
    (is (= {:subject "FOO" :bytes 11} (:args frame)))
    (is (= "Hello NATS!" (String. ^bytes (:body frame) "UTF-8")))))

(deftest read-frame-pub-with-reply-to
  (let [frame (ir/read-frame (->stream "PUB FRONT.DOOR JOKE.22 11\r\nKnock Knock\r\n") default-shapes)]
    (is (= {:subject "FRONT.DOOR" :reply-to "JOKE.22" :bytes 11} (:args frame)))
    (is (= "Knock Knock" (String. ^bytes (:body frame) "UTF-8")))))

(deftest read-frame-pub-empty-payload
  (let [frame (ir/read-frame (->stream "PUB NOTIFY 0\r\n\r\n") default-shapes)]
    (is (= {:subject "NOTIFY" :bytes 0} (:args frame)))
    (is (= 0 (alength ^bytes (:body frame))))))

(deftest read-frame-msg-with-reply-to
  (let [frame (ir/read-frame (->stream "MSG FOO.BAR 9 GREETING.34 11\r\nHello World\r\n") default-shapes)]
    (is (= {:subject "FOO.BAR" :sid "9" :reply-to "GREETING.34" :bytes 11} (:args frame)))
    (is (= "Hello World" (String. ^bytes (:body frame) "UTF-8")))))

(deftest read-frame-msg-without-reply-to
  (let [frame (ir/read-frame (->stream "MSG FOO.BAR 9 11\r\nHello World\r\n") default-shapes)]
    (is (= {:subject "FOO.BAR" :sid "9" :bytes 11} (:args frame)))
    (is (= "Hello World" (String. ^bytes (:body frame) "UTF-8")))))

(deftest read-frame-hpub
  (let [frame (ir/read-frame (->stream "HPUB FOO 22 33\r\nNATS/1.0\r\nBar: Baz\r\n\r\nHello NATS!\r\n") default-shapes)]
    (is (= "HPUB" (:op frame)))
    (is (= {:subject "FOO" :hdr-bytes 22 :bytes 33} (:args frame)))
    (is (= {"Bar" ["Baz"]} (get-in frame [:headers :headers])))
    (is (= "Hello NATS!" (String. ^bytes (:body frame) "UTF-8")))))

(deftest read-frame-hmsg-with-reply-to
  (let [line "HMSG FOO.BAR 9 BAZ.69 34 45\r\nNATS/1.0\r\nFoodGroup: vegetable\r\n\r\nHello World\r\n"
        frame (ir/read-frame (->stream line) default-shapes)]
    (is (= {:subject "FOO.BAR" :sid "9" :reply-to "BAZ.69" :hdr-bytes 34 :bytes 45} (:args frame)))
    (is (= {"FoodGroup" ["vegetable"]} (get-in frame [:headers :headers])))
    (is (= "Hello World" (String. ^bytes (:body frame) "UTF-8")))))

(deftest read-frame-sub-no-queue-group
  (is (= {:op "SUB" :args {:subject "FOO" :sid "1"}}
         (ir/read-frame (->stream "SUB FOO 1\r\n") default-shapes))))

(deftest read-frame-sub-with-queue-group
  (is (= {:op "SUB" :args {:subject "BAR" :queue-group "G1" :sid "44"}}
         (ir/read-frame (->stream "SUB BAR G1 44\r\n") default-shapes))))

(deftest read-frame-unsub-without-max-msgs
  (is (= {:op "UNSUB" :args {:sid "1"}}
         (ir/read-frame (->stream "UNSUB 1\r\n") default-shapes))))

(deftest read-frame-unsub-with-max-msgs
  (is (= {:op "UNSUB" :args {:sid "1" :max-msgs 5}}
         (ir/read-frame (->stream "UNSUB 1 5\r\n") default-shapes))))

(deftest read-frame-unknown-op-throws
  (is (thrown? clojure.lang.ExceptionInfo
               (ir/read-frame (->stream "BOGUS foo\r\n") default-shapes))))

(deftest read-frame-op-is-case-insensitive-no-args
  (testing "the NATS protocol specifies that op names are case-insensitive
            (e.g. 'sub foo 1' and 'SUB foo 1' are equivalent); a lowercase
            op must still resolve against the (uppercase) frame-shapes table"
    (is (= {:op "PING"} (ir/read-frame (->stream "ping\r\n") default-shapes)))
    (is (= {:op "PONG"} (ir/read-frame (->stream "Pong\r\n") default-shapes)))))

(deftest read-frame-op-is-case-insensitive-with-args
  (is (= {:op "SUB" :args {:subject "FOO" :sid "1"}}
         (ir/read-frame (->stream "sub FOO 1\r\n") default-shapes)))
  (is (= {:op "UNSUB" :args {:sid "1" :max-msgs 5}}
         (ir/read-frame (->stream "Unsub 1 5\r\n") default-shapes))))

(deftest read-frame-op-is-case-insensitive-with-payload
  (is (= "PUB" (:op (ir/read-frame (->stream "pub FOO 11\r\nHello NATS!\r\n") default-shapes))))
  (let [frame (ir/read-frame (->stream "Pub FOO 11\r\nHello NATS!\r\n") default-shapes)]
    (is (= {:subject "FOO" :bytes 11} (:args frame)))
    (is (= "Hello NATS!" (String. ^bytes (:body frame) "UTF-8")))))

(deftest read-frame-op-case-insensitive-for-symbolic-ops
  (testing "+OK and -ERR have non-alphabetic leading characters, which upper-case
            must leave untouched while still folding the alphabetic part"
    (is (= {:op "+OK"} (ir/read-frame (->stream "+ok\r\n") default-shapes)))
    (is (= {:op "-ERR" :args {:msg "boom"}}
           (ir/read-frame (->stream "-err boom\r\n") default-shapes)))))

(deftest read-frame-returned-op-is-normalized-for-handler-matching
  (testing "the :op in the returned frame is the normalized (uppercase) form,
            not whatever case the server actually sent -- this matters because
            claxon.impl.common/dispatch matches handlers using exact string
            equality against :op, and handlers are registered with uppercase
            op names (see claxon.conf/defaults)"
    (is (= "PING" (:op (ir/read-frame (->stream "ping\r\n") default-shapes))))))

(deftest read-frame-connect
  (let [line "CONNECT {\"verbose\":false,\"pedantic\":false,\"lang\":\"clojure\"}\r\n"
        frame (ir/read-frame (->stream line) default-shapes)]
    (is (= "CONNECT" (:op frame)))
    (is (= false (get-in frame [:args :opts "verbose"])))
    (is (= "clojure" (get-in frame [:args :opts "lang"])))))
