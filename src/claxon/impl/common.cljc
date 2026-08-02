(ns claxon.impl.common
  (:require
   #?(:bb [cheshire.core :as json]
      :clj [clojure.data.json :as json])
   [clojure.string :as str])
  (:import
   [java.net URI]
   [java.util.concurrent ExecutorService]))

(def read-json #?(:bb json/parse-string :clj json/read-str))

(def write-json #?(:bb json/generate-string :clj json/write-str))

(def ^:const DOT #"\.")

(def ^:const COLON #":")

(defn parse-nats-url
  [url]
  (let [uri (URI. url)
        supported-schemes #{"nats"}
        scheme (.getScheme uri)
        _ (when (not (contains? supported-schemes scheme))
            (throw (IllegalArgumentException. (str "Unsupported scheme: " url))))
        host (.getHost uri)
        port (let [p (.getPort uri)]
               (if (pos? p) p 4222))
        user-info (.getUserInfo uri)
        [user password token]
        (cond
          (nil? user-info)
          [nil nil nil]

          (str/includes? user-info ":")
          (let [[u p] (.split COLON user-info 2)]
            [u p nil])

          :else
          [user-info nil user-info])]
    {:scheme scheme
     :host host
     :port port
     :user user
     :password password
     :token token}))

(defn subject-matches?
  [subject subject-pattern]
  (let [s-pattern (.split DOT subject-pattern)
        sub (.split DOT subject)
        pc (alength s-pattern)
        sc (alength sub)]
    (if-not (or (= pc sc)
                (and (= ">" (last s-pattern))
                     (<= (- pc 1) sc)))
      false
      (->> s-pattern
           (take-while #(not= % ">"))
           (map (fn [s p]
                  (or (= s p)
                      (= "*" p)))
                sub)
           (every? identity)))))

(defn matches?
  [frame matcher]
  (every?
   (fn [[mk mv]]
     (let [fv (get frame mk)]
       (when (contains? frame mk)
         (if (= mk :subject)
           (subject-matches? fv mv)
           (= fv mv)))))
   matcher))

(defn dispatch
  [{:keys [op] :as frame}
   handlers
   {:keys [^ExecutorService executor] :as conn}]
  (->> (get @handlers op)
       (vals)
       (filter #(->> %
                     :matches
                     :args
                     (matches? (:args frame))))
       (run! (fn [handler]
               (let [task (bound-fn [] ;; bound-fn captures scope before dispatching on a thread
                            (try
                              ((:fn handler) frame conn)
                              (catch Exception e
                                (when-let [efn (:efn handler)]
                                  (efn frame conn e)))))]
                 (.submit executor ^Runnable task))))))

(comment
  (set! *warn-on-reflection* true)

  (matches? nil {:foo :bar})

  ;; Some main variants for subject matching
  (subject-matches? "ll.x.xx" "ll.x.xx") ;; true - subjects are identical

  (subject-matches? "ll.r.xx" "ll.x.xx") ;; false - second element in subjects differs

  (subject-matches? "ll.x.xx" "ll.x") ;; false - amont of elements in subjects differs

  (subject-matches? "ll.r.xx" "ll.*.xx") ;; true - second element contains * wildcard

  (subject-matches? "ll.r.xx" "ll.>") ;; true - contains > wildcard. Notice amount of element between subjects differs

  (subject-matches? "ll.r.xx.zz" "ll.>.xx") ;; false - subject pattern is excessive and not applicable. The added element implies something that does not exist.

  (subject-matches? "ll" "ll.a.>")) ;; false - contains > wildcard. Elements before > require to match)
