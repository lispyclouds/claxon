(ns claxon.impl.common-test
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [claxon.impl.common :as ic])
  (:import
   [java.util.concurrent ExecutorService Executors]))

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
    (is (true? (ic/matches? nil nil)))
    (is (true? (ic/matches? {:a 1} nil)))
    (is (true? (ic/matches? {} {})))))

(deftest matches-exact-match
  (is (true? (ic/matches? {:op "PING" :sid "1"} {:op "PING" :sid "1"}))))

(deftest matches-partial-match
  (testing "sub may be a strict subset of super's keys"
    (is (true? (ic/matches? {:op "MSG" :sid "1" :subject "FOO"} {:op "MSG"})))))

(deftest matches-subject-match
  (testing "match identical subjects"
    (is (true? (ic/matches? {:op "MSG" :sid "1" :subject "aa.bb.cc"} {:op "MSG" :subject "aa.bb.cc"})))))

(deftest matches-subject-more-elements
  (testing "match message subject has more elements than pattern subject"
    (is (false? (ic/matches? {:op "MSG" :sid "1" :subject "aa.bb.cc.dd"} {:op "MSG" :subject "aa.bb.cc"})))))

(deftest matches-subject-less-elements
  (testing "match message subject with fewer elements than pattern subject"
    (is (false? (ic/matches? {:op "MSG" :sid "1" :subject "aa.bb"} {:op "MSG" :subject "aa.bb.cc"})))))

(deftest matches-subject-one-wildcard
  (testing "match message subject with pattern with one * wildcard"
    (is (true? (ic/matches? {:op "MSG" :sid "1" :subject "aa.bb.cc"} {:op "MSG" :subject "aa.*.cc"})))))

(deftest matches-subject-two-wildcards
  (testing "match message subject with pattern with two * wildcards"
    (is (true? (ic/matches? {:op "MSG" :sid "1" :subject "aa.bb.cc"} {:op "MSG" :subject "*.bb.*"})))))

(deftest matches-subject-more-elements-wildcard
  (testing "match message subject with more elements than pattern with one * wildcard"
    (is (false? (ic/matches? {:op "MSG" :sid "1" :subject "aa.bb.cc.dd"} {:op "MSG" :subject "aa.bb.*"})))))

(deftest matches-subject-and-accept-the-rest-wildcard
  (testing "match message subject with pattern with one > wildcard"
    (is (true? (ic/matches? {:op "MSG" :sid "1" :subject "aa.bb.cc"} {:op "MSG" :subject "aa.>"})))))

(deftest matches-subject-to-many-elements-for-wildcard
  (testing "match message subject with fewer elements then required for > wildcard"
    (is (false? (ic/matches? {:op "MSG" :sid "1" :subject "aa"} {:op "MSG" :subject "aa.bb.>"})))))

(deftest matches-subject-multiple-wildcards
  (testing "match message subject with pattern with one * and one > wildcard"
    (is (true? (ic/matches? {:op "MSG" :sid "1" :subject "aa.bb.cc.dd"} {:op "MSG" :subject "aa.*.>"})))))

(deftest matches-missing-key-fails
  (testing "a key present in sub but absent from super fails the match"
    (is (false? (ic/matches? {:op "MSG"} {:op "MSG" :sid "1"})))))

(deftest matches-value-mismatch-fails
  (is (false? (ic/matches? {:op "MSG"} {:op "PING"}))))

(deftest matches-nil-value-present-as-key
  (testing "a key explicitly mapped to nil in super still counts as 'contains'"
    (is (true? (ic/matches? {:op nil} {:op nil})))))

;; ---------------------------------------------------------------------------
;; dispatch
;; ---------------------------------------------------------------------------

(defonce test-executors (atom []))

(defn ->executor
  []
  (let [executor (Executors/newSingleThreadExecutor)]
    (swap! test-executors conj executor)
    executor))

(use-fixtures :each
  (fn [run-test]
    (reset! test-executors [])
    (run-test)
    (run! #(.shutdown ^ExecutorService %) @test-executors)))

(defn sync-dispatch!
  "Wraps the plain handlers map every test builds in an atom before calling
   dispatch, since dispatch now expects an atom (matching how claxon.impl.read/start
   calls it with (:handlers conn), which is itself an atom)."
  [frame handlers {:keys [^ExecutorService executor] :as conn}]
  (let [result (ic/dispatch frame (atom handlers) conn)]
    (.get (.submit executor ^Runnable (fn [])))
    result))

(deftest dispatch-invokes-matching-handlers-only
  (let [conn-a {:executor (->executor)}
        calls (atom [])
        handlers {"PING" {1 {:matches {:args nil}
                             :fn (fn [frame _conn] (swap! calls conj [:ping frame]))}}
                  "MSG" {2 {:matches {:args nil}
                            :fn (fn [frame _conn] (swap! calls conj [:msg frame]))}}}]
    (sync-dispatch! {:op "PING"} handlers conn-a)
    (is (= [[:ping {:op "PING"}]] @calls))))

(deftest dispatch-runs-all-matching-handlers
  (testing "more than one handler can match the same frame; all run"
    (let [conn-a {:executor (->executor)}
          calls (atom 0)
          handlers {"PING" {1 {:matches {:args nil}
                               :fn (fn [_ _] (swap! calls inc))}
                            2 {:matches {:args nil}
                               :fn (fn [_ _] (swap! calls inc))}}}]
      (sync-dispatch! {:op "PING"} handlers conn-a)
      (is (= 2 @calls)))))

(deftest dispatch-matches-on-args-submap
  (testing "handlers can be filtered further by a submap of :args"
    (let [conn-a {:executor (->executor)}
          calls (atom [])
          handlers {"MSG" {1 {:matches {:args {:subject "FOO"}}
                              :fn (fn [frame _] (swap! calls conj frame))}}}]
      (sync-dispatch! {:op "MSG" :args {:subject "FOO" :sid "1"}} handlers conn-a)
      (sync-dispatch! {:op "MSG" :args {:subject "BAR" :sid "1"}} handlers conn-a)
      (is (= 1 (count @calls)))
      (is (= "FOO" (get-in (first @calls) [:args :subject]))))))

(deftest dispatch-no-matching-handlers-is-a-noop
  (is (nil? (sync-dispatch! {:op "PING"} {} {:executor (->executor)}))))

(deftest dispatch-handler-exception-without-efn-is-swallowed
  (testing "an exception thrown by a handler with no :efn does not stop
            dispatch, crash the task, or escape to the caller -- it is
            simply swallowed"
    (let [conn-a {:executor (->executor)}
          calls (atom [])
          handlers {"PING" {1 {:matches {:args nil}
                               :fn (fn [_ _] (throw (ex-info "boom" {})))}
                            2 {:matches {:args nil}
                               :fn (fn [_ _] (swap! calls conj :ran))}}}]
      (is (nil? (sync-dispatch! {:op "PING"} handlers conn-a)))
      (is (= [:ran] @calls)))))

(deftest dispatch-runs-efn-on-handler-exception
  (testing "a handler's :efn is called with frame, conn, and the exception
            when its :fn throws"
    (let [seen (atom nil)
          conn-a {:executor (->executor)}
          handlers {"PING" {1 {:matches {:args nil}
                               :fn (fn [_ _] (throw (ex-info "boom" {})))
                               :efn (fn [frame conn e] (reset! seen [frame conn e]))}}}]
      (sync-dispatch! {:op "PING"} handlers conn-a)
      (let [[frame conn e] @seen]
        (is (= {:op "PING"} frame))
        (is (= conn-a conn))
        (is (= "boom" (ex-message e)))))))

(deftest dispatch-efn-can-surface-errors-without-logging
  (testing ":efn gives a caller a hook to surface a handler's exception
            however they choose -- e.g. onto a queue for later inspection
            -- as an alternative to the library doing any logging itself"
    (let [errors (atom [])
          conn-a {:executor (->executor)}
          handlers {"PING" {1 {:matches {:args nil}
                               :fn (fn [_ _] (throw (ex-info "boom" {:detail 42})))
                               :efn (fn [_ _ e] (swap! errors conj (ex-data e)))}}}]
      (binding [*err* (java.io.StringWriter.)]
        (sync-dispatch! {:op "PING"} handlers conn-a)
        (testing "nothing was written to *err* -- :efn took over entirely"
          (is (= "" (str *err*)))))
      (is (= [{:detail 42}] @errors)))))

(deftest dispatch-efn-sees-callers-dynamic-bindings
  (testing ":efn runs inside the task submitted to the executor, on a
            different thread than the one that called dispatch -- bound-fn
            is what makes a caller's binding (e.g. redirecting *out*
            around connect) still visible to :efn when it runs there"
    (let [conn-a {:executor (->executor)}
          captured (atom nil)
          handlers {"PING" {1 {:matches {:args nil}
                               :fn (fn [_ _] (throw (ex-info "boom" {})))
                               :efn (fn [_ _ _] (reset! captured *out*))}}}
          custom-out (java.io.StringWriter.)]
      (binding [*out* custom-out]
        (sync-dispatch! {:op "PING"} handlers conn-a))
      (is (= custom-out @captured)))))

(deftest dispatch-passes-conn-through-to-handler
  (let [conn-a {:extra :data :executor (->executor)}
        seen (atom nil)
        handlers {"PING" {1 {:matches {:args nil}
                             :fn (fn [_ conn] (reset! seen conn))}}}]
    (sync-dispatch! {:op "PING"} handlers conn-a)
    (is (= conn-a @seen))))

;; --- connection isolation ---------------------------------------------------

(deftest dispatch-only-ever-sees-the-handlers-atom-it-was-given
  (testing "two conns with their own handlers atoms never cross-fire, simply
            because dispatch only ever looks inside the one atom it's handed"
    (let [conn-a {:executor (->executor)}
          conn-b {:executor (->executor)}
          calls-a (atom 0)
          calls-b (atom 0)
          handlers-a (atom {"PING" {1 {:matches {:args nil}
                                       :fn (fn [_ _] (swap! calls-a inc))}}})
          handlers-b (atom {"PING" {2 {:matches {:args nil}
                                       :fn (fn [_ _] (swap! calls-b inc))}}})]
      (ic/dispatch {:op "PING"} handlers-a conn-a)
      (.get (.submit ^ExecutorService (:executor conn-a) ^Runnable (fn [])))
      (is (= 1 @calls-a))
      (is (= 0 @calls-b))
      (ic/dispatch {:op "PING"} handlers-b conn-b)
      (.get (.submit ^ExecutorService (:executor conn-b) ^Runnable (fn [])))
      (is (= 1 @calls-a))
      (is (= 1 @calls-b)))))

(deftest dispatch-runs-handlers-on-the-provided-executor-not-the-caller-thread
  (testing "handlers are submitted to (:executor conn) rather than run inline,
            so a slow handler cannot block the thread calling dispatch"
    (let [conn-a {:executor (->executor)}
          caller-thread (Thread/currentThread)
          handler-thread (atom nil)
          handlers {"PING" {1 {:matches {:args nil}
                               :fn (fn [_ _] (reset! handler-thread (Thread/currentThread)))}}}]
      (sync-dispatch! {:op "PING"} handlers conn-a)
      (is (some? @handler-thread))
      (is (not= caller-thread @handler-thread)))))

;; --- op-as-index ------------------------------------------------------------

(deftest dispatch-op-mismatch-is-a-structural-miss-not-a-filtered-one
  (testing "a handler registered under a different op key in the index is
            simply never looked up -- unlike the old vector+filter design,
            there's no predicate comparing :op here to ever short-circuit"
    (let [conn-a {:executor (->executor)}
          calls (atom [])
          handlers {"MSG" {1 {:matches {:args nil}
                              :fn (fn [_ _] (swap! calls conj :ran))}}}]
      (sync-dispatch! {:op "PING"} handlers conn-a)
      (is (= [] @calls)))))

(deftest dispatch-only-consults-the-ops-bucket-matching-the-frame
  (testing "handlers registered under sibling op keys are not even considered
            when dispatching a different op"
    (let [conn-a {:executor (->executor)}
          calls (atom [])
          handlers {"PING" {1 {:matches {:args nil}
                               :fn (fn [_ _] (swap! calls conj :ping))}}
                    "PONG" {2 {:matches {:args nil}
                               :fn (fn [_ _] (swap! calls conj :pong))}}}]
      (sync-dispatch! {:op "PONG"} handlers conn-a)
      (is (= [:pong] @calls)))))

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
