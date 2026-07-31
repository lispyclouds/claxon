(ns claxon.client-test
  (:require
   [claxon.client :as client]
   [clojure.test :refer [deftest testing is]]))

(defn test-conn
  []
  {:handlers (atom {})
   :handler-ids (atom 0)})

(deftest add-handler
  (testing "assoc-in's the handler at [op id] in (:handlers conn)"
    (let [conn (test-conn)
          f (fn [_ _])
          id (client/add-handler conn f {:op "PING" :args nil})]
      (is (= {:fn f :efn nil :matches {:args nil}}
             (get-in @(:handlers conn) ["PING" id])))))

  (testing "returns the freshly incremented handler id"
    (let [conn (test-conn)
          id (client/add-handler conn (fn [_ _]) {:op "PING" :args nil})]
      (is (= 1 id))))

  (testing "ids are unique and increasing"
    (let [conn (test-conn)
          id-1 (client/add-handler conn (fn [_ _]) {:op "PING" :args nil})
          id-2 (client/add-handler conn (fn [_ _]) {:op "PONG" :args nil})]
      (is (= 1 id-1))
      (is (= 2 id-2))
      (is (not= id-1 id-2))))

  (testing "ids are unique within a conn, not across every conn in the process"
    (let [conn-a (test-conn)
          conn-b (test-conn)
          id-a (client/add-handler conn-a (fn [_ _]) {:op "PING" :args nil})
          id-b (client/add-handler conn-b (fn [_ _]) {:op "PING" :args nil})]
      (is (= 1 id-a))
      (is (= 1 id-b))))

  (testing "the 3-arity defers to the 4-arity with err-handler explicitly nil"
    (let [conn (test-conn)
          id (client/add-handler conn (fn [_ _]) {:op "PING" :args nil})]
      (is (nil? (:efn (get-in @(:handlers conn) ["PING" id]))))))

  (testing "4-arity stores-err-handler"
    (let [conn (test-conn)
          efn (fn [_ _ _])
          id (client/add-handler conn (fn [_ _]) efn {:op "PING" :args nil})]
      (is (= efn (:efn (get-in @(:handlers conn) ["PING" id]))))))

  (testing ":op is consumed into the index path; only :args survives inside :matches"
    (let [conn (test-conn)
          id (client/add-handler conn (fn [_ _]) {:op "MSG" :args {:subject "FOO"}})]
      (is (= {:args {:subject "FOO"}}
             (:matches (get-in @(:handlers conn) ["MSG" id]))))))

  (testing "distinct ops land in distinct buckets"
    (let [conn (test-conn)
          ping-id (client/add-handler conn (fn [_ _]) {:op "PING" :args nil})
          pong-id (client/add-handler conn (fn [_ _]) {:op "PONG" :args nil})]
      (is (= #{ping-id} (set (keys (get @(:handlers conn) "PING")))))
      (is (= #{pong-id} (set (keys (get @(:handlers conn) "PONG")))))))

  (testing "two different conns never share handler state each conn carries
            its own atom, so adding a handler on one never affects the other"
    (let [conn-a (test-conn)
          conn-b (test-conn)
          id-a (client/add-handler conn-a (fn [_ _]) {:op "PING" :args nil})
          id-b (client/add-handler conn-b (fn [_ _]) {:op "PING" :args nil})]
      (is (= #{id-a} (set (keys (get @(:handlers conn-a) "PING")))))
      (is (= #{id-b} (set (keys (get @(:handlers conn-b) "PING")))))))

  (testing "multiple handlers coexist on the same conn and op"
    (let [conn (test-conn)
          id-1 (client/add-handler conn (fn [_ _]) {:op "PING" :args nil})
          id-2 (client/add-handler conn (fn [_ _]) {:op "PING" :args nil})]
      (is (= #{id-1 id-2} (set (keys (get @(:handlers conn) "PING"))))))))

(deftest remove-handler
  (testing "removes the handler"
    (let [conn (test-conn)
          id (client/add-handler conn (fn [_ _]) {:op "PING" :args nil})]
      (client/remove-handler conn id)
      (is (not (contains? (get @(:handlers conn) "PING") id)))))

  (testing "removing an id that was never registered does not throw and leaves existing handlers untouched"
    (let [conn (test-conn)
          id (client/add-handler conn (fn [_ _]) {:op "PING" :args nil})]
      (is (nil? (client/remove-handler conn 999999)))
      (is (contains? (get @(:handlers conn) "PING") id))))

  (testing "removing one handler leaves its siblings on the same op intact"
    (let [conn (test-conn)
          id-1 (client/add-handler conn (fn [_ _]) {:op "PING" :args nil})
          id-2 (client/add-handler conn (fn [_ _]) {:op "PING" :args nil})]
      (client/remove-handler conn id-1)
      (is (not (contains? (get @(:handlers conn) "PING") id-1)))
      (is (contains? (get @(:handlers conn) "PING") id-2))))

  (testing "leaves other ops on the same conn untouched"
    (let [conn (test-conn)
          ping-id (client/add-handler conn (fn [_ _]) {:op "PING" :args nil})
          pong-id (client/add-handler conn (fn [_ _]) {:op "PONG" :args nil})]
      (client/remove-handler conn ping-id)
      (is (not (contains? (get @(:handlers conn) "PING") ping-id)))
      (is (contains? (get @(:handlers conn) "PONG") pong-id))))

  (testing "remove-handler only ever swaps! the handlers atom it's given
            a sibling conn's atom is a different object entirely and is
            never touched"
    (let [conn-a (test-conn)
          conn-b (test-conn)
          id-a (client/add-handler conn-a (fn [_ _]) {:op "PING" :args nil})
          id-b (client/add-handler conn-b (fn [_ _]) {:op "PING" :args nil})]
      (client/remove-handler conn-a id-a)
      (is (not (contains? (get @(:handlers conn-a) "PING") id-a)))
      (is (contains? (get @(:handlers conn-b) "PING") id-b))))

  (testing "remove-handler doesn't take op as an argument: it has to scan
            every op bucket on the conn's handlers atom to find which one
            holds the given id"
    (let [conn (test-conn)
          _ (client/add-handler conn (fn [_ _]) {:op "PING" :args nil})
          target-id (client/add-handler conn (fn [_ _]) {:op "MSG" :args {:subject "FOO"}})]
      (client/remove-handler conn target-id)
      (is (not (contains? (get @(:handlers conn) "MSG") target-id)))))

  (testing "removing the same id a second time is a harmless no-op"
    (let [conn (test-conn)
          id (client/add-handler conn (fn [_ _]) {:op "PING" :args nil})]
      (client/remove-handler conn id)
      (is (nil? (client/remove-handler conn id)))
      (is (not (contains? (get @(:handlers conn) "PING") id)))))

  (testing "remove-handler dissoc's the id from its op map but doesn't prune the now-empty op entry"
    (let [conn (test-conn)
          id (client/add-handler conn (fn [_ _]) {:op "PING" :args nil})]
      (client/remove-handler conn id)
      (is (= {} (get @(:handlers conn) "PING"))))))
