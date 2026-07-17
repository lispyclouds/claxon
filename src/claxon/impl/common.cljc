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

(defn parse-nats-url
  [url]
  (let [uri (URI. url)
        supported-schemes #{"nats"}
        scheme (.getScheme uri)
        _ (when (not (contains? supported-schemes scheme))
            (throw (IllegalArgumentException. (str "Unsupported scheme: " url))))
        host  (.getHost uri)
        port  (let [p (.getPort uri)]
                (if (pos? p) p 4222))
        user-info (.getUserInfo uri)
        [user password token]
        (cond
          (nil? user-info)
          [nil nil nil]

          (str/includes? user-info ":")
          (let [[u p] (str/split user-info #":" 2)]
            [u p nil])
          :else [user-info nil user-info])]
    {:scheme scheme
     :host host
     :port port
     :user user
     :password password
     :token token}))

(defn subject-matches?
  "Matches subject in frame against given subject pattern from handler.
   Pattern works according to NATS subject hierarchy wildcards.
   Returns boolean"
  [subject subject-pattern]
  (let [s-pattern (str/split subject-pattern #"\.")
        sub (str/split subject #"\.")]
    (when (or (= (count s-pattern) (count sub))
              (some #{">"} s-pattern))
      (->> s-pattern
           (take-while (complement #{">"}))
           (map (fn [s p]
                  (or (= s p)
                      (= "*" p))) sub)
           (every? identity)))))

(defn matches?
  [super sub]
  (every? (fn [[pk pv]]
            (let [av (get super pk)]
              (when (contains? super pk)
                (if (= pk :subject)
                  (subject-matches? av pv)
                  (= av pv)))))

          sub))

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

  ;; Five main variants for subject matching
  (subject-matches? "ll.x.xx" "ll.x.xx") ;; true - subjects are identical

  (subject-matches? "ll.r.xx" "ll.x.xx") ;; false - second element in subjects differs

  (subject-matches? "ll.x.xx" "ll.x") ;; nil (equals false) - amont of elements in subjects differs

  (subject-matches? "ll.r.xx" "ll.*.xx") ;; true - second element contains * wildcard

  (subject-matches? "ll.r.xx" "ll.>") ;; true - contains > wildcard. Notice amount of element between subjects differs
  )


