(ns claxon.impl-test
  (:require
   [claxon.conf :as conf]
   [claxon.impl :as impl]
   [clojure.test :refer [deftest is testing]])
  (:import
   [java.io ByteArrayInputStream]))

(defn ->stream [s]
  (ByteArrayInputStream. (.getBytes s "UTF-8")))

(defn ->stream-with-payload [header payload]
  (let [header-bytes (.getBytes header "UTF-8")
        hlen (count header-bytes)
        plen (count payload)
        total (byte-array (+ hlen plen 2))]
    (System/arraycopy header-bytes 0 total 0 hlen)
    (System/arraycopy payload 0 total hlen plen)
    (aset-byte total (+ hlen plen) 13)
    (aset-byte total (+ hlen plen 1) 10)
    (ByteArrayInputStream. total)))

(def shapes (:claxon/frame-shapes (conf/defaults)))

(defn read-frame-from-string [s]
  (impl/read-frame (->stream s) shapes))

(defn read-frame-with-payload [header payload]
  (impl/read-frame (->stream-with-payload header payload) shapes))

(def frame-cases
  [{:name "PING"
    :input "PING\r\n"
    :expected {:op "PING"}}
   {:name "PONG"
    :input "PONG\r\n"
    :expected {:op "PONG"}}
   {:name "+OK"
    :input "+OK\r\n"
    :expected {:op "+OK"}}
   {:name "-ERR"
    :input "-ERR 'bad'\r\n"
    :expected {:op "-ERR" :args {:msg "'bad'"}}}
   {:name "INFO"
    :input "INFO {\"server\":\"test\"}\r\n"
    :expected {:op "INFO" :args {:info {"server" "test"}}}}
   {:name "SUB"
    :input "SUB foo 1\r\n"
    :expected {:op "SUB" :args {:subject "foo" :sid "1"}}}
   {:name "SUB w/queue"
    :input "SUB foo bar 1\r\n"
    :expected {:op "SUB" :args {:subject "foo" :queue-group "bar" :sid "1"}}}
   {:name "UNSUB"
    :input "UNSUB 1\r\n"
    :expected {:op "UNSUB" :args {:sid "1"}}}
   {:name "UNSUB w/max"
    :input "UNSUB 1 10\r\n"
    :expected {:op "UNSUB" :args {:sid "1" :max-msgs 10}}}
   {:name "CONNECT"
    :input "CONNECT {\"verbose\":true}\r\n"
    :expected {:op "CONNECT" :args {:opts {"verbose" true}}}}
   {:name "PUB"
    :header "PUB foo 5\r\n"
    :payload (byte-array (map byte "hello"))
    :expected {:op "PUB" :args {:subject "foo" :bytes 5} :body [104 101 108 108 111]}}
   {:name "PUB w/reply"
    :header "PUB foo bar 5\r\n"
    :payload (byte-array (map byte "hello"))
    :expected {:op "PUB" :args {:subject "foo" :reply-to "bar" :bytes 5} :body [104 101 108 108 111]}}
   {:name "MSG"
    :header "MSG foo 1 5\r\n"
    :payload (byte-array (map byte "hello"))
    :expected {:op "MSG" :args {:subject "foo" :sid "1" :bytes 5} :body [104 101 108 108 111]}}
   {:name "MSG w/reply"
    :header "MSG foo 1 bar 5\r\n"
    :payload (byte-array (map byte "hello"))
    :expected {:op "MSG" :args {:subject "foo" :sid "1" :reply-to "bar" :bytes 5} :body [104 101 108 108 111]}}])

(deftest test-frame-parsing
  (testing "All core frames parse correctly"
    (doseq [{:keys [name input header payload expected]} frame-cases]
      (let [result (if payload
                     (read-frame-with-payload header payload)
                     (read-frame-from-string input))
            ;; Convert actual body to vector for comparison, if present
            actual (if-let [body (:body result)]
                     (assoc result :body (vec body))
                     result)]
        (is (= expected actual) (str "Failed on: " name))))))

(def header-bytes (byte-array (.getBytes "NATS/1.0\r\nX-Custom: value\r\n\r\n" "UTF-8")))
(def body-bytes (byte-array (map byte "hello")))
(def all-payload (byte-array (concat header-bytes body-bytes)))
(def header-len (count header-bytes))
(def body-len (count body-bytes))
(def total-len (+ header-len body-len))
(def expected-body-vec (vec body-bytes))

(deftest test-header-frames
  (testing "HPUB frame with headers"
    (let [header-str (str "HPUB foo " header-len " " total-len "\r\n")
          result (read-frame-with-payload header-str all-payload)]
      (is (= "HPUB" (:op result)))
      (is (= {:subject "foo" :hdr-bytes header-len :bytes total-len}
             (:args result)))
      (is (= {:headers {"X-Custom" ["value"]}} (:headers result)))
      (is (= expected-body-vec (vec (:body result))))))

  (testing "HMSG frame with headers and reply-to"
    (let [header-str (str "HMSG foo 1 bar " header-len " " total-len "\r\n")
          result (read-frame-with-payload header-str all-payload)]
      (is (= "HMSG" (:op result)))
      (is (= {:subject "foo" :sid "1" :reply-to "bar"
              :hdr-bytes header-len :bytes total-len}
             (:args result)))
      (is (= {:headers {"X-Custom" ["value"]}} (:headers result)))
      (is (= expected-body-vec (vec (:body result)))))))

(deftest test-parse-errors
  (testing "Unknown operation"
    (is (thrown? Exception (read-frame-from-string "UNKNOWN\r\n"))))

  (testing "Incomplete line (missing CRLF)"
    (is (thrown? java.io.EOFException (impl/read-all (->stream "PING")))))

  (testing "Wrong argument count"
    (is (thrown? Exception (read-frame-from-string "SUB foo\r\n"))))

  (testing "Invalid CRLF after payload"
    (let [stream (ByteArrayInputStream.
                  (byte-array (concat (.getBytes "PUB foo 5\r\n" "UTF-8")
                                      (map byte "hello")
                                      [10])))] ; only LF, no CR
      (is (thrown? Exception (impl/read-frame stream shapes))))))

(deftest test-multi-frame
  (let [frames ["PING\r\n" "PONG\r\n"]
        stream (ByteArrayInputStream. (.. (apply str frames) (getBytes "UTF-8")))]
    (is (= {:op "PING"} (impl/read-frame stream shapes)))
    (is (= {:op "PONG"} (impl/read-frame stream shapes)))))

(deftest test-parse-headers-block
  (let [raw (byte-array (.getBytes "NATS/1.0 200 OK\r\nX-Foo: bar\r\n\r\n" "UTF-8"))]
    (is (= {:headers {"X-Foo" ["bar"]} :status 200 :description "OK"}
           (impl/parse-headers-block raw)))))

(deftest test-eval-length
  (testing "Literal numbers"
    (is (= 5 (impl/eval-length 5 {}))))
  (testing "Keyword lookup"
    (is (= 10 (impl/eval-length :bytes {:bytes 10}))))
  (testing "Subtraction with keywords"
    (is (= 7 (impl/eval-length [:- :total :used] {:total 10 :used 3}))))
  (testing "Addition with keywords"
    (is (= 15 (impl/eval-length [:+ :a :b] {:a 10 :b 5}))))
  (testing "Mix of literals and keywords"
    (is (= 8 (impl/eval-length [:+ 3 :x] {:x 5})))
    (is (= 2 (impl/eval-length [:- :total 8] {:total 10}))))
  (testing "Realistic usage from protocol"
    (is (= 5 (impl/eval-length [:- :hdr-bytes :bytes] {:hdr-bytes 10 :bytes 5})))))

(deftest parse-nats-url-test
  (testing "basic URLs without authentication"
    (let [parsed (impl/parse-nats-url "nats://localhost:4222")]
      (is (= {:scheme "nats"
              :host   "localhost"
              :port   4222
              :user   nil
              :password nil
              :token  nil}
             parsed)))
    (let [parsed (impl/parse-nats-url "nats://nats.example.com")]
      (is (= "nats" (:scheme parsed)))
      (is (= "nats.example.com" (:host parsed)))
      (is (= 4222 (:port parsed)))
      (is (nil? (:user parsed))))

    (let [parsed (impl/parse-nats-url "nats://1.2.3.4:5000")]
      (is (= "1.2.3.4" (:host parsed)))
      (is (= 5000 (:port parsed)))))

  (testing "URLs with user:password authentication"
    (let [parsed (impl/parse-nats-url "nats://alice:secret@localhost:4222")]
      (is (= "alice" (:user parsed)))
      (is (= "secret" (:password parsed)))
      (is (nil? (:token parsed)))))

  (testing "URLs with token authentication"
    (let [parsed (impl/parse-nats-url "nats://mytoken@localhost:4222")]
      (is (= "mytoken" (:user parsed)))
      (is (nil? (:password parsed)))
      (is (= "mytoken" (:token parsed))))
    (let [parsed (impl/parse-nats-url "nats://sometoken@example.com")]
      (is (= "sometoken" (:user parsed)))
      (is (= "sometoken" (:token parsed)))))

  (testing "default port when missing"
    (let [parsed (impl/parse-nats-url "nats://localhost")]
      (is (= 4222 (:port parsed))))
    (let [parsed (impl/parse-nats-url "nats://localhost:")]
      (is (= 4222 (:port parsed)))))

  (testing "unsupported scheme throws IllegalArgumentException"
    (is (thrown? IllegalArgumentException (impl/parse-nats-url "tls://localhost:4443")))
    (is (thrown? IllegalArgumentException (impl/parse-nats-url "ws://localhost")))
    (is (thrown? IllegalArgumentException (impl/parse-nats-url "http://nats")))
    (is (thrown? IllegalArgumentException (impl/parse-nats-url "nats2://localhost"))))

  (testing "malformed or invalid URLs"
    (is (thrown? Exception (impl/parse-nats-url "nats://")))
    (is (thrown? Exception (impl/parse-nats-url "not-a-url"))))

  (testing "edge cases for user-info"
    (let [parsed (impl/parse-nats-url "nats://user:pass:with:colon@localhost")]
      (is (= "user" (:user parsed)))
      (is (= "pass:with:colon" (:password parsed))))
    (let [parsed (impl/parse-nats-url "nats://@localhost")]
      (is (= "" (:user parsed)))
      (is (nil? (:password parsed)))
      (is (= "" (:token parsed)))))

  (testing "URLs with query strings or paths are ignored by URI, but we don't use them"
    (let [parsed (impl/parse-nats-url "nats://localhost:4222?foo=bar")]
      (is (= "localhost" (:host parsed)))
      (is (= 4222 (:port parsed))))))

(deftest nil-sub-is-always-true
  (testing "an empty/absent requirement matches anything, including nil super"
    (is (true? (impl/submap? {:a 1} nil)))
    (is (true? (impl/submap? {} nil)))
    (is (true? (impl/submap? nil nil)))))

(deftest empty-map-sub-is-always-true
  (is (true? (impl/submap? {:a 1} {})))
  (is (true? (impl/submap? nil {}))))

(deftest nil-super-with-non-empty-sub-is-false
  (is (false? (impl/submap? nil {:a 1}))))

(deftest identical-maps-match
  (is (true? (impl/submap? {:a 1 :b 2} {:a 1 :b 2}))))

(deftest sub-is-strict-subset-of-super
  (is (true? (impl/submap? {:a 1 :b 2 :c 3} {:a 1}))))

(deftest sub-with-multiple-keys-all-present-and-equal
  (is (true? (impl/submap? {:a 1 :b 2 :c 3} {:a 1 :c 3}))))

(deftest super-missing-a-key-sub-requires-is-false
  (is (false? (impl/submap? {:a 1} {:a 1 :b 2}))))

(deftest differing-value-for-shared-key-is-false
  (is (false? (impl/submap? {:a 1} {:a 2}))))

(deftest one-matching-one-mismatching-key-is-false
  (is (false? (impl/submap? {:a 1 :b 99} {:a 1 :b 2})))
  (is (false? (impl/submap? {:a 99 :b 2} {:a 1 :b 2}))))

(deftest missing-key-in-super-is-false-even-if-required-value-is-nil
  (is (false? (impl/submap? {:a 1} {:b nil}))))

(deftest key-explicitly-mapped-to-nil-in-super-matches-nil-requirement
  (is (true? (impl/submap? {:a 1 :b nil} {:b nil}))))

(deftest key-explicitly-mapped-to-nil-in-super-fails-non-nil-requirement
  (is (false? (impl/submap? {:a 1 :b nil} {:b 2}))))

(deftest equal-string-values-match
  (is (true? (impl/submap? {:subject "foo.bar"} {:subject "foo.bar"}))))

(deftest equal-nested-collection-values-match
  (is (true? (impl/submap? {:headers {"X-Id" ["1"]}} {:headers {"X-Id" ["1"]}}))))

(deftest unequal-nested-collection-values-fail
  (is (false? (impl/submap? {:headers {"X-Id" ["1"]}} {:headers {"X-Id" ["2"]}}))))

(deftest equal-vector-values-match
  (is (true? (impl/submap? {:tags [:a :b]} {:tags [:a :b]}))))

(deftest key-order-in-sub-does-not-matter
  (is (true? (impl/submap? {:a 1 :b 2 :c 3} {:c 3 :a 1})))
  (is (true? (impl/submap? {:a 1 :b 2 :c 3} {:a 1 :c 3}))))

(deftest submap-is-not-symmetric
  (is (true? (impl/submap? {:a 1 :b 2} {:a 1})))
  (is (false? (impl/submap? {:a 1} {:a 1 :b 2}))))

(deftest mirrors-ping-handler-shape-matching-any-args
  (is (true? (impl/submap? nil nil)))
  (is (true? (impl/submap? {:subject "x"} nil))))

(deftest mirrors-msg-handler-shape-requiring-specific-subject
  (is (true? (impl/submap? {:subject "foo.bar" :sid "1"} {:subject "foo.bar"})))
  (is (false? (impl/submap? {:subject "other" :sid "1"} {:subject "foo.bar"})))
  (is (false? (impl/submap? {:sid "1"} {:subject "foo.bar"}))))

(defn handler
  [op args f]
  {:conn 1 :fn f :matches {:op op :args args}})

(defn recording-handler
  [op args]
  (let [log (atom [])]
    [log (handler op args (fn [frame conn] (swap! log conj [frame conn])))]))

(deftest dispatch-calls-matching-handler-by-op
  (let [[log h] (recording-handler "PING" nil)
        frame {:op "PING"}]
    (impl/dispatch frame [h] :the-conn)
    (is (= [[frame :the-conn]] @log))))

(deftest dispatch-no-match-on-different-op
  (let [[log h] (recording-handler "PING" nil)
        frame {:op "PONG"}]
    (impl/dispatch frame [h] :the-conn)
    (is (= [] @log))))

(deftest dispatch-no-handlers-is-a-no-op
  (is (nil? (impl/dispatch {:op "PING"} [] :the-conn)))
  (is (nil? (impl/dispatch {:op "PING"} nil :the-conn))))

(deftest dispatch-no-match-does-not-throw
  (let [[_ h] (recording-handler "PING" nil)]
    (is (nil? (impl/dispatch {:op "MSG"} [h] :the-conn)))))

(deftest dispatch-matches-when-handler-args-nil-regardless-of-frame-args
  (let [[log h] (recording-handler "PING" nil)
        frame {:op "PING" :args {:subject "foo"}}]
    (impl/dispatch frame [h] :the-conn)
    (is (= 1 (count @log)))))

(deftest dispatch-matches-when-handler-args-is-subset-of-frame-args
  (let [[log h] (recording-handler "MSG" {:subject "foo.bar"})
        frame {:op "MSG" :args {:subject "foo.bar" :sid "1"}}]
    (impl/dispatch frame [h] :the-conn)
    (is (= 1 (count @log)))))

(deftest dispatch-no-match-when-frame-missing-required-arg-key
  (let [[log h] (recording-handler "MSG" {:subject "foo.bar"})
        frame {:op "MSG" :args {:sid "1"}}] ; no :subject at all
    (impl/dispatch frame [h] :the-conn)
    (is (= 0 (count @log)))))

(deftest dispatch-no-match-when-arg-value-differs
  (let [[log h] (recording-handler "MSG" {:subject "foo.bar"})
        frame {:op "MSG" :args {:subject "other.subject"}}]
    (impl/dispatch frame [h] :the-conn)
    (is (= 0 (count @log)))))

(deftest dispatch-no-match-when-frame-has-no-args-but-handler-requires-some
  (let [[log h] (recording-handler "MSG" {:subject "foo.bar"})
        frame {:op "MSG"}] ; :args missing entirely on frame
    (impl/dispatch frame [h] :the-conn)
    (is (= 0 (count @log)))))

(deftest dispatch-invokes-every-matching-handler-not-just-first
  (let [[log1 h1] (recording-handler "PING" nil)
        [log2 h2] (recording-handler "PING" nil)
        frame {:op "PING"}]
    (impl/dispatch frame [h1 h2] :the-conn)
    (is (= 1 (count @log1)))
    (is (= 1 (count @log2)))))

(deftest dispatch-invokes-matching-handlers-in-given-order
  (let [order (atom [])
        h1 (handler "PING" nil (fn [_ _] (swap! order conj :h1)))
        h2 (handler "PING" nil (fn [_ _] (swap! order conj :h2)))
        h3 (handler "PING" nil (fn [_ _] (swap! order conj :h3)))]
    (impl/dispatch {:op "PING"} [h1 h2 h3] :the-conn)
    (is (= [:h1 :h2 :h3] @order))))

(deftest dispatch-skips-non-matching-and-invokes-all-matching
  (let [[log-other h-other] (recording-handler "PONG" nil)
        [log1 h1] (recording-handler "PING" nil)
        [log2 h2] (recording-handler "PING" nil)
        frame {:op "PING"}]
    (impl/dispatch frame [h-other h1 h2] :the-conn)
    (is (= 0 (count @log-other)))
    (is (= 1 (count @log1)))
    (is (= 1 (count @log2)))))

(deftest dispatch-handlers-for-other-ops-dont-interfere
  (let [[log-ping h-ping] (recording-handler "PING" nil)
        [log-msg h-msg] (recording-handler "MSG" {:subject "x"})
        frame {:op "PING"}]
    (impl/dispatch frame [h-msg h-ping] :the-conn)
    (is (= 0 (count @log-msg)))
    (is (= 1 (count @log-ping)))))

(deftest dispatch-with-mixed-args-only-matching-ones-fire
  (let [[log-x h-x] (recording-handler "MSG" {:subject "x"})
        [log-y h-y] (recording-handler "MSG" {:subject "y"})
        [log-any h-any] (recording-handler "MSG" nil)
        frame {:op "MSG" :args {:subject "x"}}]
    (impl/dispatch frame [h-x h-y h-any] :the-conn)
    (is (= 1 (count @log-x)))
    (is (= 0 (count @log-y)))
    (is (= 1 (count @log-any)))))

(deftest dispatch-forwards-conn-unmodified-to-handler
  (let [[log h] (recording-handler "PING" nil)
        conn {:id 1 :writer :a-writer :reader :a-reader}]
    (impl/dispatch {:op "PING"} [h] conn)
    (is (= conn (second (first @log))))))

(deftest dispatch-forwards-conn-by-identity-not-just-equality
  (let [received (atom nil)
        h (handler "PING" nil (fn [_ conn] (reset! received conn)))
        conn {:id 1 :writer :a-writer}]
    (impl/dispatch {:op "PING"} [h] conn)
    (is (identical? conn @received))))

(deftest dispatch-forwards-same-conn-to-every-matching-handler
  (let [received (atom [])
        h1 (handler "PING" nil (fn [_ conn] (swap! received conj conn)))
        h2 (handler "PING" nil (fn [_ conn] (swap! received conj conn)))
        conn {:id 1 :writer :a-writer}]
    (impl/dispatch {:op "PING"} [h1 h2] conn)
    (is (every? #(identical? conn %) @received))))

(deftest snd-works-on-the-conn-dispatch-forwards
  (let [sw (java.io.StringWriter.)
        bw (java.io.BufferedWriter. sw)
        conn {:writer bw}
        h (handler "PING" nil (fn [_ conn] (impl/snd conn "PONG")))]
    (impl/dispatch {:op "PING"} [h] conn)
    (is (= "PONG\r\n" (str sw)))))

(deftest dispatch-with-bare-writer-as-conn-does-not-satisfy-snd
  (let [sw (java.io.StringWriter.)
        bw (java.io.BufferedWriter. sw)
        h (handler "PING" nil (fn [_ conn] (impl/snd conn "PONG")))]
    (is (thrown? Exception
                 (impl/dispatch {:op "PING"} [h] bw)))))

(deftest dispatch-one-handler-throwing-still-runs-via-run!-but-aborts-remaining
  (let [ran (atom [])
        h1 (handler "PING" nil (fn [_ _] (swap! ran conj :h1) (throw (ex-info "boom" {}))))
        h2 (handler "PING" nil (fn [_ _] (swap! ran conj :h2)))]
    (is (thrown? Exception (impl/dispatch {:op "PING"} [h1 h2] :the-conn)))
    (is (= [:h1] @ran))))

(deftest dispatch-matches-op-only-frame-with-no-args-key
  (let [[log h] (recording-handler "+OK" nil)
        frame {:op "+OK"}]
    (impl/dispatch frame [h] :the-conn)
    (is (= 1 (count @log)))))

(deftest dispatch-passes-full-frame-including-payloads-to-handler
  (let [[log h] (recording-handler "MSG" {:subject "x"})
        frame {:op "MSG" :args {:subject "x" :sid "1"} :body (byte-array [1 2 3])}]
    (impl/dispatch frame [h] :the-conn)
    (is (= frame (first (first @log))))))

(deftest dispatch-returns-nil
  (let [[_ h] (recording-handler "PING" nil)]
    (is (nil? (impl/dispatch {:op "PING"} [h] :the-conn)))))
