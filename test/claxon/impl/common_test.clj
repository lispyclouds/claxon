(ns claxon.impl.common-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [claxon.impl.common :as ic]))

;; ---------------------------------------------------------------------------
;; parse-nats-url
;; ---------------------------------------------------------------------------

(deftest parse-nats-url-defaults
  (testing "bare host gets the default NATS port"
    (is (= {:scheme "nats" :host "localhost" :port 4222
            :user nil :password nil :token nil}
           (ic/parse-nats-url "nats://localhost")))))

(deftest parse-nats-url-explicit-port
  (testing "explicit port is preserved"
    (is (= 4223 (:port (ic/parse-nats-url "nats://localhost:4223"))))))

(deftest parse-nats-url-user-password
  (testing "user:password userinfo splits into user and password, no token"
    (let [{:keys [user password token]} (ic/parse-nats-url "nats://alice:s3cret@localhost:4222")]
      (is (= "alice" user))
      (is (= "s3cret" password))
      (is (nil? token)))))

(deftest parse-nats-url-token-only
  (testing "bare userinfo (no colon) is treated as a token, also echoed as user"
    (let [{:keys [user password token]} (ic/parse-nats-url "nats://mytoken@localhost:4222")]
      (is (= "mytoken" user))
      (is (nil? password))
      (is (= "mytoken" token)))))

(deftest parse-nats-url-no-userinfo
  (testing "no userinfo at all yields nils, not an exception"
    (let [{:keys [user password token]} (ic/parse-nats-url "nats://localhost:4222")]
      (is (nil? user))
      (is (nil? password))
      (is (nil? token)))))

(deftest parse-nats-url-rejects-unsupported-scheme
  (testing "non-nats schemes are rejected"
    (is (thrown? IllegalArgumentException (ic/parse-nats-url "http://localhost:4222")))
    (is (thrown? IllegalArgumentException (ic/parse-nats-url "tls://localhost:4222")))))

(deftest parse-nats-url-password-with-colon
  (testing "split on first colon only -- a password containing a colon is kept intact"
    (let [{:keys [user password]} (ic/parse-nats-url "nats://alice:pass:word@localhost:4222")]
      (is (= "alice" user))
      (is (= "pass:word" password)))))

;; ---------------------------------------------------------------------------
;; submap?
;; ---------------------------------------------------------------------------

(deftest submap-empty-sub-always-matches
  (testing "an empty/nil sub-map is satisfied by anything, including nil super"
    (is (true? (ic/submap? nil nil)))
    (is (true? (ic/submap? {:a 1} nil)))
    (is (true? (ic/submap? {} {})))))

(deftest submap-exact-match
  (is (true? (ic/submap? {:op "PING" :sid "1"} {:op "PING" :sid "1"}))))

(deftest submap-partial-match
  (testing "sub may be a strict subset of super's keys"
    (is (true? (ic/submap? {:op "MSG" :sid "1" :subject "FOO"} {:op "MSG"})))))

(deftest submap-missing-key-fails
  (testing "a key present in sub but absent from super fails the match"
    (is (false? (ic/submap? {:op "MSG"} {:op "MSG" :sid "1"})))))

(deftest submap-value-mismatch-fails
  (is (false? (ic/submap? {:op "MSG"} {:op "PING"}))))

(deftest submap-nil-value-present-as-key
  (testing "a key explicitly mapped to nil in super still counts as 'contains'"
    (is (true? (ic/submap? {:op nil} {:op nil})))))

;; ---------------------------------------------------------------------------
;; dispatch
;; ---------------------------------------------------------------------------

(deftest dispatch-invokes-matching-handlers-only
  (let [calls (atom [])
        handlers [{:matches {:op "PING" :args nil}
                   :fn (fn [frame _conn] (swap! calls conj [:ping frame]))}
                  {:matches {:op "MSG" :args nil}
                   :fn (fn [frame _conn] (swap! calls conj [:msg frame]))}]]
    (ic/dispatch {:op "PING"} handlers :conn)
    (is (= [[:ping {:op "PING"}]] @calls))))

(deftest dispatch-runs-all-matching-handlers
  (testing "more than one handler can match the same frame; all run"
    (let [calls (atom 0)
          handlers [{:matches {:op "PING" :args nil}
                     :fn (fn [_ _] (swap! calls inc))}
                    {:matches {:op "PING" :args nil}
                     :fn (fn [_ _] (swap! calls inc))}]]
      (ic/dispatch {:op "PING"} handlers :conn)
      (is (= 2 @calls)))))

(deftest dispatch-matches-on-args-submap
  (testing "handlers can be filtered further by a submap of :args"
    (let [calls (atom [])
          handlers [{:matches {:op "MSG" :args {:subject "FOO"}}
                     :fn (fn [frame _] (swap! calls conj frame))}]]
      (ic/dispatch {:op "MSG" :args {:subject "FOO" :sid "1"}} handlers :conn)
      (ic/dispatch {:op "MSG" :args {:subject "BAR" :sid "1"}} handlers :conn)
      (is (= 1 (count @calls)))
      (is (= "FOO" (get-in (first @calls) [:args :subject]))))))

(deftest dispatch-no-matching-handlers-is-a-noop
  (is (nil? (ic/dispatch {:op "PING"} [] :conn))))

(deftest dispatch-handler-exception-does-not-propagate
  (testing "an exception thrown by one handler does not stop dispatch or escape to the caller"
    (let [calls (atom [])
          handlers [{:matches {:op "PING" :args nil}
                     :fn (fn [_ _] (throw (ex-info "boom" {})))}
                    {:matches {:op "PING" :args nil}
                     :fn (fn [_ _] (swap! calls conj :ran))}]]
      (binding [*err* (java.io.StringWriter.)]
        (is (nil? (ic/dispatch {:op "PING"} handlers :conn))))
      (is (= [:ran] @calls)))))

(deftest dispatch-passes-conn-through-to-handler
  (let [seen (atom nil)
        handlers [{:matches {:op "PING" :args nil}
                   :fn (fn [_ conn] (reset! seen conn))}]]
    (ic/dispatch {:op "PING"} handlers {:id 42})
    (is (= {:id 42} @seen))))

;; ---------------------------------------------------------------------------
;; read-json / write-json round trip (platform-dependent impl, behaviour-tested)
;; ---------------------------------------------------------------------------

(deftest json-round-trip
  (testing "write-json then read-json recovers equivalent data"
    (let [data {"verbose" false "pedantic" false "name" "claxon"}
          encoded (ic/write-json data)
          decoded (ic/read-json encoded)]
      (is (string? encoded))
      (is (= data decoded)))))

(deftest json-round-trip-nested
  (let [data {"connect_urls" ["10.0.0.1:4222" "10.0.0.2:4222"] "max_payload" 1048576}
        decoded (ic/read-json (ic/write-json data))]
    (is (= data decoded))))
