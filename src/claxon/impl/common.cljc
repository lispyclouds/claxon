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
(defonce conn-ids (atom 0))
(defonce handler-ids (atom 0))
(defonce handlers (atom {})) ;; TODO: indexed on conn id -> op -> handlers, ENHANCE moar

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

(defn submap?
  [super sub]
  (every? (fn [[k v]]
            (and (contains? super k)
                 (= v (get super k))))
          sub))

(defn dispatch
  [{:keys [op] :as frame}
   handlers
   {:keys [^ExecutorService executor id] :as conn}]
  (->> (get-in handlers [id op])
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

  (parse-nats-url "nats://foo"))
