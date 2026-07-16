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

(defn subject-match?
  "Matches subject strings as defined in message and handler.
     Match applies NATS defined wildcards to be used.
     Returns boolean"
  [message handler]
  (let [hlr (str/split handler #"\.")
        msg (str/split message #"\.")]
    (when (or (= (count hlr) (count msg))
              (some #{">"} hlr))
      (->> hlr
           (take-while (complement #{">"}))
           (map (fn [m h]
                  (or (= m h)
                      (= "*" h))) msg)
           (every? identity)))))

(defn submap?
  [super sub]
  (every? (fn [[hk hv]]
            (let [mv (get super hk)]
              (when (contains? super hk)
                (cond->> hv
                  (not (= hk :subject)) (= mv)
                  (= hk :subject) (subject-match? mv)))))

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
                     (submap? (:args frame))))
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

  (submap? nil {:foo :bar})

  (defn submap?
    [super sub]
    (every? (fn [[hk hv]]
              (let [mv (get super hk)]
                (when (contains? super hk)
                  (cond->> hv
                    (not (= hk :subject)) (= mv)
                    (= hk :subject) (subject-match? mv)))))

            sub))

  (defn subject-match?
    "Matches subject strings as defined in message and handler.
     Match applies NATS defined wildcards to be used.
     Returns boolean"
    [message handler]
    (let [hlr (str/split handler #"\.")
          msg (str/split message #"\.")]
      (when (or (= (count hlr) (count msg))
                (some #{">"} hlr))
        (->> hlr
             (take-while (complement #{">"}))
             (map (fn [m h]
                    (or (= m h)
                        (= "*" h))) msg)
             (every? identity)))))

  (subject-match? "ll.r.xx" "ll.x.xx"))
