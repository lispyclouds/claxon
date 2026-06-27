(ns claxon.client-test
  (:require
   [claxon.client :as client]
   [claxon.impl.common :as ic]
   [clojure.test :refer [deftest testing is use-fixtures]]))

;; ---------------------------------------------------------------------------
;; fixtures
;; ---------------------------------------------------------------------------

(use-fixtures :each
  (fn [run-test]
    (reset! ic/handlers {})
    (reset! ic/handler-ids 0)
    (run-test)))

;; ---------------------------------------------------------------------------
;; add-handler
;; ---------------------------------------------------------------------------

(deftest add-handler-stores-under-conn-id-op-and-id
  (testing "add-handler assoc-in's the handler at [conn-id op id] in ic/handlers"
    (let [conn {:id "conn-a"}
          f (fn [_ _])
          id (client/add-handler conn f {:op "PING" :args nil})]
      (is (= {:fn f :efn nil :matches {:args nil}}
             (get-in @ic/handlers ["conn-a" "PING" id]))))))

(deftest add-handler-returns-an-id
  (testing "add-handler returns the freshly incremented handler id"
    (let [conn {:id "conn-a"}
          id (client/add-handler conn (fn [_ _]) {:op "PING" :args nil})]
      (is (= 1 id)))))

(deftest add-handler-ids-are-unique-and-increasing
  (let [conn {:id "conn-a"}
        id-1 (client/add-handler conn (fn [_ _]) {:op "PING" :args nil})
        id-2 (client/add-handler conn (fn [_ _]) {:op "PONG" :args nil})]
    (is (= 1 id-1))
    (is (= 2 id-2))
    (is (not= id-1 id-2))))

(deftest add-handler-3-arity-defaults-err-handler-to-nil
  (testing "the 3-arity (conn handler matches) defers to the 4-arity with
            err-handler explicitly nil"
    (let [conn {:id "conn-a"}
          id (client/add-handler conn (fn [_ _]) {:op "PING" :args nil})]
      (is (nil? (:efn (get-in @ic/handlers ["conn-a" "PING" id])))))))

(deftest add-handler-4-arity-stores-err-handler
  (let [conn {:id "conn-a"}
        efn (fn [_ _ _])
        id (client/add-handler conn (fn [_ _]) efn {:op "PING" :args nil})]
    (is (= efn (:efn (get-in @ic/handlers ["conn-a" "PING" id]))))))

(deftest add-handler-matches-only-keeps-args-not-op
  (testing ":op is consumed into the index path; only :args survives inside
            :matches, since op-matching is now structural, not predicate-based"
    (let [conn {:id "conn-a"}
          id (client/add-handler conn (fn [_ _]) {:op "MSG" :args {:subject "FOO"}})]
      (is (= {:args {:subject "FOO"}}
             (:matches (get-in @ic/handlers ["conn-a" "MSG" id])))))))

(deftest add-handler-distinct-ops-land-in-distinct-buckets
  (let [conn {:id "conn-a"}
        ping-id (client/add-handler conn (fn [_ _]) {:op "PING" :args nil})
        pong-id (client/add-handler conn (fn [_ _]) {:op "PONG" :args nil})]
    (is (= #{ping-id} (set (keys (get-in @ic/handlers ["conn-a" "PING"])))))
    (is (= #{pong-id} (set (keys (get-in @ic/handlers ["conn-a" "PONG"])))))))

(deftest add-handler-distinct-conns-land-in-distinct-buckets
  (testing "two different conn ids never share an op bucket, even for the same op"
    (let [conn-a {:id "conn-a"}
          conn-b {:id "conn-b"}
          id-a (client/add-handler conn-a (fn [_ _]) {:op "PING" :args nil})
          id-b (client/add-handler conn-b (fn [_ _]) {:op "PING" :args nil})]
      (is (= #{id-a} (set (keys (get-in @ic/handlers ["conn-a" "PING"])))))
      (is (= #{id-b} (set (keys (get-in @ic/handlers ["conn-b" "PING"])))))
      (is (not= id-a id-b)))))

(deftest add-handler-multiple-handlers-coexist-on-the-same-conn-and-op
  (let [conn {:id "conn-a"}
        id-1 (client/add-handler conn (fn [_ _]) {:op "PING" :args nil})
        id-2 (client/add-handler conn (fn [_ _]) {:op "PING" :args nil})]
    (is (= #{id-1 id-2} (set (keys (get-in @ic/handlers ["conn-a" "PING"])))))))

(deftest add-handler-nil-conn-id-is-a-valid-index-key
  (testing "a conn with no :id (nil) still indexes correctly, since nil is
            a perfectly ordinary map key"
    (let [conn {}
          id (client/add-handler conn (fn [_ _]) {:op "PING" :args nil})]
      (is (contains? (get-in @ic/handlers [nil "PING"]) id)))))

;; ---------------------------------------------------------------------------
;; remove-handler
;; ---------------------------------------------------------------------------

(deftest remove-handler-deletes-the-handler
  (let [conn {:id "conn-a"}
        id (client/add-handler conn (fn [_ _]) {:op "PING" :args nil})]
    (client/remove-handler id)
    (is (not (contains? (get-in @ic/handlers ["conn-a" "PING"]) id)))))

(deftest remove-handler-on-unknown-id-is-a-noop
  (testing "removing an id that was never registered does not throw and
            leaves existing handlers untouched"
    (let [conn {:id "conn-a"}
          id (client/add-handler conn (fn [_ _]) {:op "PING" :args nil})]
      (is (nil? (client/remove-handler 999999)))
      (is (contains? (get-in @ic/handlers ["conn-a" "PING"]) id)))))

(deftest remove-handler-only-removes-the-targeted-id
  (testing "removing one handler leaves its siblings on the same conn/op intact"
    (let [conn {:id "conn-a"}
          id-1 (client/add-handler conn (fn [_ _]) {:op "PING" :args nil})
          id-2 (client/add-handler conn (fn [_ _]) {:op "PING" :args nil})]
      (client/remove-handler id-1)
      (is (not (contains? (get-in @ic/handlers ["conn-a" "PING"]) id-1)))
      (is (contains? (get-in @ic/handlers ["conn-a" "PING"]) id-2)))))

(deftest remove-handler-leaves-other-ops-on-the-same-conn-untouched
  (let [conn {:id "conn-a"}
        ping-id (client/add-handler conn (fn [_ _]) {:op "PING" :args nil})
        pong-id (client/add-handler conn (fn [_ _]) {:op "PONG" :args nil})]
    (client/remove-handler ping-id)
    (is (not (contains? (get-in @ic/handlers ["conn-a" "PING"]) ping-id)))
    (is (contains? (get-in @ic/handlers ["conn-a" "PONG"]) pong-id))))

(deftest remove-handler-leaves-other-conns-untouched
  (let [conn-a {:id "conn-a"}
        conn-b {:id "conn-b"}
        id-a (client/add-handler conn-a (fn [_ _]) {:op "PING" :args nil})
        id-b (client/add-handler conn-b (fn [_ _]) {:op "PING" :args nil})]
    (client/remove-handler id-a)
    (is (not (contains? (get-in @ic/handlers ["conn-a" "PING"]) id-a)))
    (is (contains? (get-in @ic/handlers ["conn-b" "PING"]) id-b))))

(deftest remove-handler-searches-across-every-conn-and-op
  (testing "remove-handler doesn't take conn or op as arguments -- it has to
            scan every [cid op] bucket in ic/handlers to find which one holds
            the given id, regardless of which conn/op it was registered under"
    (let [conn-a {:id "conn-a"}
          conn-b {:id "conn-b"}
          _ (client/add-handler conn-a (fn [_ _]) {:op "PING" :args nil})
          target-id (client/add-handler conn-b (fn [_ _]) {:op "MSG" :args {:subject "FOO"}})]
      (client/remove-handler target-id)
      (is (not (contains? (get-in @ic/handlers ["conn-b" "MSG"]) target-id))))))

(deftest remove-handler-twice-is-idempotent
  (testing "removing the same id a second time is a harmless no-op"
    (let [conn {:id "conn-a"}
          id (client/add-handler conn (fn [_ _]) {:op "PING" :args nil})]
      (client/remove-handler id)
      (is (nil? (client/remove-handler id)))
      (is (not (contains? (get-in @ic/handlers ["conn-a" "PING"]) id))))))

(deftest remove-handler-can-leave-an-empty-op-bucket-behind
  (testing "remove-handler dissoc's the id from its [cid op] map but doesn't
            prune the now-empty op or conn entries -- this is a documented
            shape detail, not a correctness bug, since dispatch's (vals nil)
            and (vals {}) both behave as an empty seq"
    (let [conn {:id "conn-a"}
          id (client/add-handler conn (fn [_ _]) {:op "PING" :args nil})]
      (client/remove-handler id)
      (is (= {} (get-in @ic/handlers ["conn-a" "PING"]))))))

;; ---------------------------------------------------------------------------
;; add-handler + remove-handler, round trip
;; ---------------------------------------------------------------------------

(deftest add-then-remove-round-trip-is-a-noop-on-the-handlers-map
  (testing "adding a handler and immediately removing it returns ic/handlers
            to an equivalent (if not pruned) empty state for that conn/op"
    (let [conn {:id "conn-a"}
          before @ic/handlers
          id (client/add-handler conn (fn [_ _]) {:op "PING" :args nil})]
      (client/remove-handler id)
      (is (= (get-in before ["conn-a" "PING"] {})
             (get-in @ic/handlers ["conn-a" "PING"]))))))
