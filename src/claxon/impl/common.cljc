(ns claxon.impl.common
  (:require
   #?(:bb [cheshire.core :as json]
      :clj [clojure.data.json :as json])
   [clojure.string :as str])
  (:import
   [java.net URI]))

(def read-json #?(:bb json/parse-string :clj json/read-str))
(def write-json #?(:bb json/generate-string :clj json/write-str))
(defonce conn-ids (atom 0))
(defonce handlers (atom [])) ; TODO: Optimise better than O(n)

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
  [frame handlers conn]
  (->> handlers
       (filter (fn [handler]
                 (let [{:keys [op args]} (:matches handler)]
                   (and (= op (:op frame))
                        (submap? (:args frame) args)))))
       ;; TODO: Invoke all concurrently on the executor, making sure the writer is synchronised
       (run! #(try
                ((:fn %) frame conn)
                (catch Exception e
                  (binding [*out* *err*]
                    (println (str "Error running handler: " e))))))))

(comment
  (set! *warn-on-reflection* true)

  (submap? nil {:foo :bar})

  (parse-nats-url "nats://foo"))
