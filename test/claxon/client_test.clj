(ns claxon.client-test
  (:require
   [claxon.client :as client]
   [clojure.test :refer [deftest testing is]]))

;; ---------------------------------------------------------------------------
;; helpers
;; ---------------------------------------------------------------------------

(defn ->conn
  "Build a minimal conn with its own fresh handlers and handler-ids atoms."
  []
  {:handlers (atom {})
   :handler-ids (atom 0)})

;; ---------------------------------------------------------------------------
;; add-handler
;; ---------------------------------------------------------------------------

(deftest add-handler-stores-under-op-and-id-on-the-conns-own-atom
  (testing "add-handler assoc-in's the handler at [op id] in (:handlers conn)"
    (let [conn (->conn)
          f (fn [_ _])
          id (client/add-handler conn f {:op "PING" :args nil})]
      (is (= {:fn f :efn nil :matches {:args nil}}
             (get-in @(:handlers conn) ["PING" id]))))))

(deftest add-handler-returns-an-id
  (testing "add-handler returns the freshly incremented handler id"
    (let [conn (->conn)
          id (client/add-handler conn (fn [_ _]) {:op "PING" :args nil})]
      (is (= 1 id)))))

(deftest add-handler-ids-are-unique-and-increasing
  (let [conn (->conn)
        id-1 (client/add-handler conn (fn [_ _]) {:op "PING" :args nil})
        id-2 (client/add-handler conn (fn [_ _]) {:op "PONG" :args nil})]
    (is (= 1 id-1))
    (is (= 2 id-2))
    (is (not= id-1 id-2))))

(deftest add-handler-ids-are-only-unique-within-their-own-conn
  (testing "handler-ids now lives on the conn itself (no more global counter),
            so two different conns each start their own sequence from 1 --
            ids are unique within a conn, not across every conn in the process"
    (let [conn-a (->conn)
          conn-b (->conn)
          id-a (client/add-handler conn-a (fn [_ _]) {:op "PING" :args nil})
          id-b (client/add-handler conn-b (fn [_ _]) {:op "PING" :args nil})]
      (is (= 1 id-a))
      (is (= 1 id-b)))))

(deftest add-handler-3-arity-defaults-err-handler-to-nil
  (testing "the 3-arity (conn handler matches) defers to the 4-arity with
            err-handler explicitly nil"
    (let [conn (->conn)
          id (client/add-handler conn (fn [_ _]) {:op "PING" :args nil})]
      (is (nil? (:efn (get-in @(:handlers conn) ["PING" id])))))))

(deftest add-handler-4-arity-stores-err-handler
  (let [conn (->conn)
        efn (fn [_ _ _])
        id (client/add-handler conn (fn [_ _]) efn {:op "PING" :args nil})]
    (is (= efn (:efn (get-in @(:handlers conn) ["PING" id]))))))

(deftest add-handler-matches-only-keeps-args-not-op
  (testing ":op is consumed into the index path; only :args survives inside
            :matches, since op-matching is now structural, not predicate-based"
    (let [conn (->conn)
          id (client/add-handler conn (fn [_ _]) {:op "MSG" :args {:subject "FOO"}})]
      (is (= {:args {:subject "FOO"}}
             (:matches (get-in @(:handlers conn) ["MSG" id])))))))

(deftest add-handler-distinct-ops-land-in-distinct-buckets
  (let [conn (->conn)
        ping-id (client/add-handler conn (fn [_ _]) {:op "PING" :args nil})
        pong-id (client/add-handler conn (fn [_ _]) {:op "PONG" :args nil})]
    (is (= #{ping-id} (set (keys (get @(:handlers conn) "PING")))))
    (is (= #{pong-id} (set (keys (get @(:handlers conn) "PONG")))))))

(deftest add-handler-distinct-conns-have-distinct-handlers-atoms
  (testing "two different conns never share handler state -- each conn carries
            its own atom, so adding a handler on one never affects the other"
    (let [conn-a (->conn)
          conn-b (->conn)
          id-a (client/add-handler conn-a (fn [_ _]) {:op "PING" :args nil})
          id-b (client/add-handler conn-b (fn [_ _]) {:op "PING" :args nil})]
      (is (= #{id-a} (set (keys (get @(:handlers conn-a) "PING")))))
      (is (= #{id-b} (set (keys (get @(:handlers conn-b) "PING"))))))))

(deftest add-handler-multiple-handlers-coexist-on-the-same-conn-and-op
  (let [conn (->conn)
        id-1 (client/add-handler conn (fn [_ _]) {:op "PING" :args nil})
        id-2 (client/add-handler conn (fn [_ _]) {:op "PING" :args nil})]
    (is (= #{id-1 id-2} (set (keys (get @(:handlers conn) "PING")))))))

;; ---------------------------------------------------------------------------
;; remove-handler
;; ---------------------------------------------------------------------------

(deftest remove-handler-deletes-the-handler
  (let [conn (->conn)
        id (client/add-handler conn (fn [_ _]) {:op "PING" :args nil})]
    (client/remove-handler conn id)
    (is (not (contains? (get @(:handlers conn) "PING") id)))))

(deftest remove-handler-on-unknown-id-is-a-noop
  (testing "removing an id that was never registered does not throw and
            leaves existing handlers untouched"
    (let [conn (->conn)
          id (client/add-handler conn (fn [_ _]) {:op "PING" :args nil})]
      (is (nil? (client/remove-handler conn 999999)))
      (is (contains? (get @(:handlers conn) "PING") id)))))

(deftest remove-handler-only-removes-the-targeted-id
  (testing "removing one handler leaves its siblings on the same op intact"
    (let [conn (->conn)
          id-1 (client/add-handler conn (fn [_ _]) {:op "PING" :args nil})
          id-2 (client/add-handler conn (fn [_ _]) {:op "PING" :args nil})]
      (client/remove-handler conn id-1)
      (is (not (contains? (get @(:handlers conn) "PING") id-1)))
      (is (contains? (get @(:handlers conn) "PING") id-2)))))

(deftest remove-handler-leaves-other-ops-on-the-same-conn-untouched
  (let [conn (->conn)
        ping-id (client/add-handler conn (fn [_ _]) {:op "PING" :args nil})
        pong-id (client/add-handler conn (fn [_ _]) {:op "PONG" :args nil})]
    (client/remove-handler conn ping-id)
    (is (not (contains? (get @(:handlers conn) "PING") ping-id)))
    (is (contains? (get @(:handlers conn) "PONG") pong-id))))

(deftest remove-handler-leaves-other-conns-untouched
  (testing "remove-handler only ever swaps! the handlers atom it's given --
            a sibling conn's atom is a different object entirely and is
            never touched"
    (let [conn-a (->conn)
          conn-b (->conn)
          id-a (client/add-handler conn-a (fn [_ _]) {:op "PING" :args nil})
          id-b (client/add-handler conn-b (fn [_ _]) {:op "PING" :args nil})]
      (client/remove-handler conn-a id-a)
      (is (not (contains? (get @(:handlers conn-a) "PING") id-a)))
      (is (contains? (get @(:handlers conn-b) "PING") id-b)))))

(deftest remove-handler-searches-across-every-op-on-the-conn
  (testing "remove-handler doesn't take op as an argument -- it has to scan
            every op bucket on the conn's handlers atom to find which one
            holds the given id"
    (let [conn (->conn)
          _ (client/add-handler conn (fn [_ _]) {:op "PING" :args nil})
          target-id (client/add-handler conn (fn [_ _]) {:op "MSG" :args {:subject "FOO"}})]
      (client/remove-handler conn target-id)
      (is (not (contains? (get @(:handlers conn) "MSG") target-id))))))

(deftest remove-handler-twice-is-idempotent
  (testing "removing the same id a second time is a harmless no-op"
    (let [conn (->conn)
          id (client/add-handler conn (fn [_ _]) {:op "PING" :args nil})]
      (client/remove-handler conn id)
      (is (nil? (client/remove-handler conn id)))
      (is (not (contains? (get @(:handlers conn) "PING") id))))))

(deftest remove-handler-can-leave-an-empty-op-bucket-behind
  (testing "remove-handler dissoc's the id from its op map but doesn't prune
            the now-empty op entry -- this is a documented shape detail, not
            a correctness bug, since dispatch's (vals nil) and (vals {}) both
            behave as an empty seq"
    (let [conn (->conn)
          id (client/add-handler conn (fn [_ _]) {:op "PING" :args nil})]
      (client/remove-handler conn id)
      (is (= {} (get @(:handlers conn) "PING"))))))

;; ---------------------------------------------------------------------------
;; add-handler + remove-handler, round trip
;; ---------------------------------------------------------------------------

(deftest add-then-remove-round-trip-is-a-noop-on-the-handlers-map
  (testing "adding a handler and immediately removing it returns the conn's
            handlers atom to an equivalent (if not pruned) empty state for
            that op"
    (let [conn (->conn)
          before @(:handlers conn)
          id (client/add-handler conn (fn [_ _]) {:op "PING" :args nil})]
      (client/remove-handler conn id)
      (is (= (get before "PING" {})
             (get @(:handlers conn) "PING"))))))
