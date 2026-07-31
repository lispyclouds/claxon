(ns claxon.impl.read-test
  (:require
   [claxon.conf :as conf]
   [claxon.impl.read :as ir]
   [clojure.test :refer [deftest is testing]])
  (:import
   [java.io ByteArrayInputStream EOFException]))

(defn test-stream
  [^String s]
  (ByteArrayInputStream. (.getBytes s "UTF-8")))

(def default-shapes (:claxon/frame-shapes (conf/defaults)))

(deftest read-all
  (testing "strips trailing crlf"
    (is (= "PING" (ir/read-all (test-stream "PING\r\n")))))

  (testing "only the first line is consumed; nothing past the first CRLF is read"
    (let [in (test-stream "PING\r\nPONG\r\n")
          first-line (ir/read-all in)]
      (is (= "PING" first-line))
      (is (= "PONG" (ir/read-all in)))))

  (testing "a bare LF without a preceding CR does not end the line"
    (let [in (test-stream "FOO\nBAR\r\n")]
      (is (= "FOO\nBAR" (ir/read-all in)))))

  (testing "throws eof when closed mid-line"
    (is (thrown? EOFException (ir/read-all (test-stream "PING")))))

  (testing "empty line"
    (is (= "" (ir/read-all (test-stream "\r\n"))))))

(deftest where-crlf
  (testing "finds crlf"
    (let [buf (.getBytes "PING\r\n" "UTF-8")]
      (is (= 4 (ir/where-crlf buf 0 (alength buf))))))

  (testing "not found returns -1"
    (let [buf (.getBytes "PING" "UTF-8")]
      (is (= -1 (ir/where-crlf buf 0 (alength buf))))))

  (testing "lone lf is not a match"
    (let [buf (.getBytes "FOO\nBAR\r\n" "UTF-8")]
      (is (= 7 (ir/where-crlf buf 0 (alength buf))))))

  (testing "respects from offset"
    (testing "a CRLF before `from` is not found"
      (let [buf (.getBytes "\r\nPING\r\n" "UTF-8")]
        (is (= 6 (ir/where-crlf buf 2 (alength buf)))))))

  (testing "respects to bound"
    (testing "a CRLF at or past `to` is not found"
      (let [buf (.getBytes "PING\r\n" "UTF-8")]
        (is (= -1 (ir/where-crlf buf 0 4)))))))

(deftest read-til-crlf-or-all
  (testing "finds crlf in one read"
    (let [in (test-stream "PING\r\n")
          buf (byte-array 64)
          idx (ir/read-til-crlf-or-all in buf)]
      (is (= 4 idx))
      (is (= "PING" (String. buf 0 idx)))))

  (testing "returns -1 for idx once the buffer is fully consumed with no CRLF found"
    (let [in (test-stream "PINGPONG")
          buf (byte-array 8)
          idx (ir/read-til-crlf-or-all in buf)]
      (is (= -1 idx))))

  (testing "the CRLF is found even when the CR and LF land in different chunks
            delivered by the underlying stream (throttled to short reads)"
    (let [src (test-stream "PING\r\n")
          throttled (proxy [java.io.InputStream] []
                      (read
                        ([buf off len] (.read src buf off (min 1 len)))))
          buf (byte-array 64)
          idx (ir/read-til-crlf-or-all throttled buf)]
      (is (= 4 idx))
      (is (= "PING" (String. buf 0 idx)))))

  (testing "throws eof mid line"
    (is (thrown? EOFException
                 (ir/read-til-crlf-or-all (test-stream "PING") (byte-array 64))))))

(deftest skip-exactly
  (testing "skips requested bytes then reads remainder"
    (let [in (test-stream "1234567890")]
      (ir/skip-exactly in 5)
      (is (= "67890" (String. ^bytes (ir/read-exactly in 5) "UTF-8")))))

  (testing "zero is a no op"
    (let [in (test-stream "abc")]
      (ir/skip-exactly in 0)
      (is (= "abc" (String. ^bytes (ir/read-exactly in 3) "UTF-8")))))

  (testing "throws eof on short stream"
    (is (thrown? EOFException (ir/skip-exactly (test-stream "abc") 10))))

  (testing "loops correctly even if the underlying stream only skips a little at a time"
    (let [src (test-stream "abcdefghij")
          throttled (proxy [java.io.InputStream] []
                      (read
                        ([] (.read src))
                        ([buf off len] (.read src buf off (min 1 len))))
                      (skip [n] (.skip src (min 1 n))))]
      (ir/skip-exactly throttled 5)
      (is (= "fghij" (String. ^bytes (ir/read-exactly throttled 5) "UTF-8"))))))

(deftest whitespace?
  (testing "recognizes-space-and-tab"
    (is (ir/whitespace? \space))
    (is (ir/whitespace? \tab))
    (is (not (ir/whitespace? \a)))
    (is (not (ir/whitespace? \newline)))))

(deftest split-op
  (testing "line no args"
    (is (= ["PING" ""] (ir/split-op-line "PING"))))

  (testing "line with args"
    (is (= ["SUB" "FOO 1"] (ir/split-op-line "SUB FOO 1"))))

  (testing "line tab delimited"
    (is (= ["PUB" "FOO\t11"] (ir/split-op-line "PUB\tFOO\t11"))))

  (testing "extra whitespace between the op and its args is skipped"
    (is (= ["SUB" "FOO 1"] (ir/split-op-line "SUB   FOO 1"))))

  (testing "split op line empty string"
    (is (= ["" ""] (ir/split-op-line "")))))

(deftest read-exactly-reads-requested-bytes
  (let [result (ir/read-exactly (test-stream "Hello NATS!") 11)]
    (is (= "Hello NATS!" (String. ^bytes result "UTF-8")))))

(deftest read-exactly
  (testing "zero length"
    (let [result (ir/read-exactly (test-stream "") 0)]
      (is (= 0 (alength ^bytes result)))))

  (testing "throws eof on short stream"
    (is (thrown? EOFException (ir/read-exactly (test-stream "abc") 10))))

  (testing "loops correctly even if the underlying stream were to deliver short reads"
    (let [src (test-stream "abcdef")
          throttled (proxy [java.io.InputStream] []
                      (read
                        ([] (.read src))
                        ([buf off len] (.read src buf off (min 1 len)))))]
      (is (= "abcdef" (String. ^bytes (ir/read-exactly throttled 6) "UTF-8"))))))

(deftest consume-crlf
  (testing "happy-path"
    (is (nil? (ir/consume-crlf (test-stream "\r\n")))))

  (testing "rejects-wrong-bytes"
    (is (thrown? clojure.lang.ExceptionInfo (ir/consume-crlf (test-stream "XY")))))

  (testing "rejects-lf-only"
    (is (thrown? clojure.lang.ExceptionInfo (ir/consume-crlf (test-stream "\n\n"))))))

(deftest cast-token
  (testing "correct"
    (is (= 42 (ir/cast-token :int "42"))))

  (testing "passthrough"
    (is (= "FOO.BAR" (ir/cast-token :str "FOO.BAR")))
    (is (= "anything" (ir/cast-token nil "anything"))))

  (testing "rejects non-numeric"
    (is (thrown? NumberFormatException (ir/cast-token :int "not-a-number")))))

(deftest parse-tokenized-args
  (testing "all-present"
    (let [specs [{:name :subject :type :str}
                 {:name :sid :type :str}
                 {:name :reply-to :type :str :optional true}
                 {:name :bytes :type :int}]]
      (is (= {:subject "FOO.BAR" :sid "9" :reply-to "GREETING.34" :bytes 11}
             (ir/parse-tokenized-args specs ["FOO.BAR" "9" "GREETING.34" "11"])))))

  (testing "optional-omitted"
    (let [specs [{:name :subject :type :str}
                 {:name :sid :type :str}
                 {:name :reply-to :type :str :optional true}
                 {:name :bytes :type :int}]]
      (is (= {:subject "FOO.BAR" :sid "9" :bytes 11}
             (ir/parse-tokenized-args specs ["FOO.BAR" "9" "11"])))))

  (testing "UNSUB with no max-msgs"
    (let [specs [{:name :sid :type :str}
                 {:name :max-msgs :type :int :optional true}]]
      (is (= {:sid "1"} (ir/parse-tokenized-args specs ["1"])))))

  (testing "trailing-optional-present"
    (let [specs [{:name :sid :type :str}
                 {:name :max-msgs :type :int :optional true}]]
      (is (= {:sid "1" :max-msgs 5} (ir/parse-tokenized-args specs ["1" "5"])))))

  (testing "too-few-tokens-throws"
    (let [specs [{:name :subject :type :str}
                 {:name :sid :type :str}]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (ir/parse-tokenized-args specs ["only-one"])))))

  (testing "too-many-tokens-throws"
    (let [specs [{:name :sid :type :str}
                 {:name :max-msgs :type :int :optional true}]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (ir/parse-tokenized-args specs ["1" "5" "extra"])))))

  (testing "sub-with-queue-group"
    (let [specs [{:name :subject :type :str}
                 {:name :queue-group :type :str :optional true}
                 {:name :sid :type :str}]]
      (is (= {:subject "BAR" :queue-group "G1" :sid "44"}
             (ir/parse-tokenized-args specs ["BAR" "G1" "44"])))
      (is (= {:subject "FOO" :sid "1"}
             (ir/parse-tokenized-args specs ["FOO" "1"]))))))

(deftest parse-args
  (testing "nil spec yields nil"
    (is (nil? (ir/parse-args nil "anything"))))

  (testing "single json arg"
    (is (= {:opts {"verbose" false}}
           (ir/parse-args [{:name :opts :type :json}] "{\"verbose\":false}"))))

  (testing "single str arg keeps whole line"
    (is (= {:msg "'Authorization Violation'"}
           (ir/parse-args [{:name :msg :type :str}] "'Authorization Violation'"))))

  (testing "multi arg delegates to tokenizer"
    (is (= {:subject "FOO" :sid "9" :bytes 11}
           (ir/parse-args [{:name :subject :type :str}
                           {:name :sid :type :str}
                           {:name :reply-to :type :str :optional true}
                           {:name :bytes :type :int}]
                          "FOO 9 11"))))

  (testing "tab delimited tokens"
    (is (= {:subject "FOO" :sid "9" :bytes 11}
           (ir/parse-args [{:name :subject :type :str}
                           {:name :sid :type :str}
                           {:name :reply-to :type :str :optional true}
                           {:name :bytes :type :int}]
                          "FOO\t9\t11")))))

(defn bytes-of ^bytes [s]
  (.getBytes s "UTF-8"))

(deftest parse-headers-block
  (testing "single-header"
    (let [raw (bytes-of "NATS/1.0\r\nBar: Baz\r\n\r\n")
          parsed (ir/parse-headers-block raw)]
      (is (= {"Bar" ["Baz"]} (:headers parsed)))
      (is (not (contains? parsed :status)))
      (is (not (contains? parsed :description)))))

  (testing "multi-value-header"
    (let [raw (bytes-of "NATS/1.0\r\nBREAKFAST: donut\r\nBREAKFAST: eggs\r\n\r\n")
          parsed (ir/parse-headers-block raw)]
      (is (= {"BREAKFAST" ["donut" "eggs"]} (:headers parsed)))))

  (testing "multiple-distinct-headers"
    (let [raw (bytes-of "NATS/1.0\r\nBREAKFAST: donut\r\nLUNCH: burger\r\n\r\n")
          parsed (ir/parse-headers-block raw)]
      (is (= {"BREAKFAST" ["donut"] "LUNCH" ["burger"]} (:headers parsed)))))

  (testing "no-headers"
    (let [raw (bytes-of "NATS/1.0\r\n\r\n")
          parsed (ir/parse-headers-block raw)]
      (is (= {} (:headers parsed)))))

  (testing "status-line headers parse status and description"
    (let [raw (bytes-of "NATS/1.0 503 No Responders\r\n\r\n")
          parsed (ir/parse-headers-block raw)]
      (is (= 503 (:status parsed)))
      (is (= "No Responders" (:description parsed)))))

  (testing "status-only-no-description"
    (let [raw (bytes-of "NATS/1.0 100\r\n\r\n")
          parsed (ir/parse-headers-block raw)]
      (is (= 100 (:status parsed)))
      (is (not (contains? parsed :description)))))

  (testing "trims-header-value-whitespace"
    (let [raw (bytes-of "NATS/1.0\r\nBar:   Baz  \r\n\r\n")
          parsed (ir/parse-headers-block raw)]
      (is (= {"Bar" ["Baz"]} (:headers parsed)))))

  (testing "a header value that itself contains a colon (e.g. a URL) must be preserved in full"
    (let [raw (bytes-of "NATS/1.0\r\nLocation: http://example.com:8080/path\r\n\r\n")
          parsed (ir/parse-headers-block raw)]
      (is (= {"Location" ["http://example.com:8080/path"]} (:headers parsed))))))

(deftest eval-length
  (testing "keyword-looks-up-arg"
    (is (= 11 (ir/eval-length :bytes {:bytes 11}))))

  (testing "int-is-literal"
    (is (= 4 (ir/eval-length 4 {}))))

  (testing "subtraction"
    (is (= 11 (ir/eval-length [:- :bytes :hdr-bytes] {:bytes 33 :hdr-bytes 22}))))

  (testing "addition"
    (is (= 55 (ir/eval-length [:+ :bytes :hdr-bytes] {:bytes 33 :hdr-bytes 22}))))

  (testing "nested-composite"
    (is (= 11 (ir/eval-length [:- [:+ :a :b] :c] {:a 5 :b 10 :c 4}))))

  (testing "unknown-op-throws"
    (is (thrown? clojure.lang.ExceptionInfo (ir/eval-length [:* :a :b] {:a 1 :b 2}))))

  (testing "invalid-expr-throws"
    (is (thrown? clojure.lang.ExceptionInfo (ir/eval-length "nope" {}))))

  (testing "single-bytes-payload"
    (let [in (test-stream "Hello NATS!\r\n")
          specs [{:name :body :type :bytes :length :bytes}]
          result (ir/read-payloads in specs {:bytes 11})]
      (is (= "Hello NATS!" (String. ^bytes (:body result) "UTF-8"))))))

(deftest read-payloads
  (testing "throws-when-trailing-crlf-missing"
    (let [in (test-stream "Hello NATS!XX")
          specs [{:name :body :type :bytes :length :bytes}]]
      (is (thrown? clojure.lang.ExceptionInfo (ir/read-payloads in specs {:bytes 11})))))

  (testing "headers-and-body-hpub"
    (let [headers-block "NATS/1.0\r\nBar: Baz\r\n\r\n"
          body "Hello NATS!"
          hdr-bytes (count (.getBytes ^String headers-block "UTF-8"))
          body-bytes (count (.getBytes ^String body "UTF-8"))
          total (+ hdr-bytes body-bytes)
          in (test-stream (str headers-block body "\r\n"))
          specs [{:name :headers :type :headers :length :hdr-bytes}
                 {:name :body :type :bytes :length [:- :bytes :hdr-bytes]}]
          result (ir/read-payloads in specs {:hdr-bytes hdr-bytes :bytes total})]
      (is (= {"Bar" ["Baz"]} (get-in result [:headers :headers])))
      (is (= "Hello NATS!" (String. ^bytes (:body result) "UTF-8")))))

  (testing "an empty payload is still followed by CRLF"
    (let [in (test-stream "\r\n")
          specs [{:name :body :type :bytes :length :bytes}]
          result (ir/read-payloads in specs {:bytes 0})]
      (is (= 0 (alength ^bytes (:body result)))))))

(deftest read-frame
  (testing "ping"
    (is (= {:op "PING"} (ir/read-frame (test-stream "PING\r\n") default-shapes))))

  (testing "pong"
    (is (= {:op "PONG"} (ir/read-frame (test-stream "PONG\r\n") default-shapes))))

  (testing "Ok"
    (is (= {:op "+OK"} (ir/read-frame (test-stream "+OK\r\n") default-shapes))))

  (testing "Err"
    (is (= {:op "-ERR" :args {:msg "'Unknown Protocol Operation'"}}
           (ir/read-frame (test-stream "-ERR 'Unknown Protocol Operation'\r\n") default-shapes))))

  (testing "info"
    (let [frame (ir/read-frame (test-stream "INFO {\"server_id\":\"abc\",\"port\":4222}\r\n") default-shapes)]
      (is (= "INFO" (:op frame)))
      (is (= "abc" (get-in frame [:args :info "server_id"])))
      (is (= 4222 (get-in frame [:args :info "port"])))))

  (testing "pub-with-payload"
    (let [frame (ir/read-frame (test-stream "PUB FOO 11\r\nHello NATS!\r\n") default-shapes)]
      (is (= "PUB" (:op frame)))
      (is (= {:subject "FOO" :bytes 11} (:args frame)))
      (is (= "Hello NATS!" (String. ^bytes (:body frame) "UTF-8")))))

  (testing "pub-with-reply-to"
    (let [frame (ir/read-frame (test-stream "PUB FRONT.DOOR JOKE.22 11\r\nKnock Knock\r\n") default-shapes)]
      (is (= {:subject "FRONT.DOOR" :reply-to "JOKE.22" :bytes 11} (:args frame)))
      (is (= "Knock Knock" (String. ^bytes (:body frame) "UTF-8")))))

  (testing "op-tab-delimited"
    (let [frame (ir/read-frame (test-stream "PUB\tFOO\t11\r\nHello NATS!\r\n") default-shapes)]
      (is (= "PUB" (:op frame)))
      (is (= {:subject "FOO" :bytes 11} (:args frame)))
      (is (= "Hello NATS!" (String. ^bytes (:body frame) "UTF-8")))))

  (testing "args-mixed-space-and-tab-delimited"
    (let [frame (ir/read-frame (test-stream "MSG FOO.BAR\t9\tGREETING.34 11\r\nHello World\r\n") default-shapes)]
      (is (= {:subject "FOO.BAR" :sid "9" :reply-to "GREETING.34" :bytes 11} (:args frame)))))

  (testing "pub-empty-payload"
    (let [frame (ir/read-frame (test-stream "PUB NOTIFY 0\r\n\r\n") default-shapes)]
      (is (= {:subject "NOTIFY" :bytes 0} (:args frame)))
      (is (= 0 (alength ^bytes (:body frame))))))

  (testing "msg-with-reply-to"
    (let [frame (ir/read-frame (test-stream "MSG FOO.BAR 9 GREETING.34 11\r\nHello World\r\n") default-shapes)]
      (is (= {:subject "FOO.BAR" :sid "9" :reply-to "GREETING.34" :bytes 11} (:args frame)))
      (is (= "Hello World" (String. ^bytes (:body frame) "UTF-8")))))

  (testing "msg-without-reply-to"
    (let [frame (ir/read-frame (test-stream "MSG FOO.BAR 9 11\r\nHello World\r\n") default-shapes)]
      (is (= {:subject "FOO.BAR" :sid "9" :bytes 11} (:args frame)))
      (is (= "Hello World" (String. ^bytes (:body frame) "UTF-8")))))

  (testing "hpub"
    (let [frame (ir/read-frame (test-stream "HPUB FOO 22 33\r\nNATS/1.0\r\nBar: Baz\r\n\r\nHello NATS!\r\n") default-shapes)]
      (is (= "HPUB" (:op frame)))
      (is (= {:subject "FOO" :hdr-bytes 22 :bytes 33} (:args frame)))
      (is (= {"Bar" ["Baz"]} (get-in frame [:headers :headers])))
      (is (= "Hello NATS!" (String. ^bytes (:body frame) "UTF-8")))))

  (testing "hmsg-with-reply-to"
    (let [line "HMSG FOO.BAR 9 BAZ.69 34 45\r\nNATS/1.0\r\nFoodGroup: vegetable\r\n\r\nHello World\r\n"
          frame (ir/read-frame (test-stream line) default-shapes)]
      (is (= {:subject "FOO.BAR" :sid "9" :reply-to "BAZ.69" :hdr-bytes 34 :bytes 45} (:args frame)))
      (is (= {"FoodGroup" ["vegetable"]} (get-in frame [:headers :headers])))
      (is (= "Hello World" (String. ^bytes (:body frame) "UTF-8")))))

  (testing "sub-no-queue-group"
    (is (= {:op "SUB" :args {:subject "FOO" :sid "1"}}
           (ir/read-frame (test-stream "SUB FOO 1\r\n") default-shapes))))

  (testing "sub-with-queue-group"
    (is (= {:op "SUB" :args {:subject "BAR" :queue-group "G1" :sid "44"}}
           (ir/read-frame (test-stream "SUB BAR G1 44\r\n") default-shapes))))

  (testing "unsub-without-max-msgs"
    (is (= {:op "UNSUB" :args {:sid "1"}}
           (ir/read-frame (test-stream "UNSUB 1\r\n") default-shapes))))

  (testing "unsub-with-max-msgs"
    (is (= {:op "UNSUB" :args {:sid "1" :max-msgs 5}}
           (ir/read-frame (test-stream "UNSUB 1 5\r\n") default-shapes))))

  (testing "unknown-op-throws"
    (is (thrown? clojure.lang.ExceptionInfo
                 (ir/read-frame (test-stream "BOGUS foo\r\n") default-shapes))))

  (testing "the NATS protocol specifies that op names are case-insensitive"
    (is (= {:op "PING"} (ir/read-frame (test-stream "ping\r\n") default-shapes)))
    (is (= {:op "PONG"} (ir/read-frame (test-stream "Pong\r\n") default-shapes))))

  (testing "op-is-case-insensitive-with-args"
    (is (= {:op "SUB" :args {:subject "FOO" :sid "1"}}
           (ir/read-frame (test-stream "sub FOO 1\r\n") default-shapes)))
    (is (= {:op "UNSUB" :args {:sid "1" :max-msgs 5}}
           (ir/read-frame (test-stream "Unsub 1 5\r\n") default-shapes))))

  (testing "op-is-case-insensitive-with-payload"
    (is (= "PUB" (:op (ir/read-frame (test-stream "pub FOO 11\r\nHello NATS!\r\n") default-shapes))))
    (let [frame (ir/read-frame (test-stream "Pub FOO 11\r\nHello NATS!\r\n") default-shapes)]
      (is (= {:subject "FOO" :bytes 11} (:args frame)))
      (is (= "Hello NATS!" (String. ^bytes (:body frame) "UTF-8")))))

  (testing "op-case-insensitive-for-symbolic-ops"
    (is (= {:op "+OK"} (ir/read-frame (test-stream "+ok\r\n") default-shapes)))
    (is (= {:op "-ERR" :args {:msg "boom"}}
           (ir/read-frame (test-stream "-err boom\r\n") default-shapes))))

  (testing "returned-op-is-normalized-for-handler-matching"
    (is (= "PING" (:op (ir/read-frame (test-stream "ping\r\n") default-shapes)))))

  (testing "connect"
    (let [line "CONNECT {\"verbose\":false,\"pedantic\":false,\"lang\":\"clojure\"}\r\n"
          frame (ir/read-frame (test-stream line) default-shapes)]
      (is (= "CONNECT" (:op frame)))
      (is (= false (get-in frame [:args :opts "verbose"])))
      (is (= "clojure" (get-in frame [:args :opts "lang"]))))))
