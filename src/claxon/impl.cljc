(ns claxon.impl
  (:require
   #?(:bb [cheshire.core :as json]
      :clj [clojure.data.json :as json])
   [clojure.string :as str])
  (:import
   [java.io
    ByteArrayOutputStream
    EOFException
    InputStream
    Writer]
   [java.net URI]
   [java.util.concurrent ExecutorService]))

(def read-json #?(:bb json/parse-string :clj json/read-str))
(def write-json #?(:bb json/generate-string :clj json/write-str))
(defonce conn-ids (atom 0))
(defonce handlers (atom [])) ; TODO: Optimise

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

(defn snd
  ([conn op]
   (snd conn op nil))
  ([{:keys [^Writer writer]} op msg]
   (.write writer (str op (if msg (str " " msg) "") "\r\n"))
   (.flush writer)))

(defn read-all
  [in]
  (let [buf (ByteArrayOutputStream.)]
    (loop [prev -1]
      (let [b (InputStream/.read in)]
        (cond
          (= b -1)
          (throw (EOFException. "socket closed mid-line"))

          (and (= prev 13) (= b 10))
          (let [bytes (.toByteArray buf)]
            (String. bytes 0 (dec (alength bytes)) "UTF-8"))

          :else
          (do
            (.write buf b)
            (recur b)))))))

(defn read-exactly
  [in n]
  (let [buf (byte-array n)]
    (loop [off 0]
      (when (< off n)
        (let [r (InputStream/.read in buf off (- n off))]
          (when (= r -1)
            (throw (EOFException. "socket closed mid-payload")))
          (recur (+ off r)))))
    buf))

(defn consume-crlf
  [^InputStream in]
  (let [cr (.read in)
        lf (.read in)]
    (when-not (and (= cr 13) (= lf 10))
      (throw (ex-info "expected CRLF after payload" {:got [cr lf]})))))

(defn cast-token
  [type tok]
  (case type
    :int (Long/parseLong tok)
    tok))

(defn parse-tokenized-args
  [arg-specs tokens]
  (let [n (count tokens)
        max-n (count arg-specs)
        min-n (count (remove :optional arg-specs))]
    (when-not (<= min-n n max-n)
      (throw (ex-info "wrong arg count" {:expected [min-n max-n] :got n :tokens tokens})))
    (loop [specs arg-specs
           toks tokens
           skip (- max-n n)
           acc {}]
      (cond
        (empty? specs)
        acc

        (and (:optional (first specs)) (pos? skip))
        (recur (rest specs) toks (dec skip) acc)

        :else
        (let [{:keys [name type]} (first specs)]
          (recur (rest specs)
                 (rest toks)
                 skip
                 (assoc acc name (cast-token type (first toks)))))))))

(defn parse-args
  [arg-specs rest-line]
  (when arg-specs
    (if (= 1 (count arg-specs))
      (let [{:keys [name type]} (first arg-specs)]
        {name (case type
                :json (read-json rest-line)
                rest-line)})
      (parse-tokenized-args arg-specs (str/split rest-line #" +")))))

(defn parse-headers-block
  [^bytes raw]
  (let [s (String. raw "UTF-8")
        [version & lines] (str/split s #"\r\n")
        [_ status desc] (str/split version #" " 3)
        headers (reduce (fn [m line]
                          (let [[k v] (str/split line #":")]
                            (if (and k v)
                              (update m k (fnil conj []) (str/trim v))
                              m)))
                        {}
                        (remove str/blank? lines))]
    (cond-> {:headers headers}
      status (assoc :status (Long/parseLong status))
      desc (assoc :description desc))))

(defn eval-length
  [expr args]
  (cond
    (keyword? expr)
    (get args expr)

    (int? expr)
    expr

    (vector? expr)
    (let [[op & operands] expr
          vals (map #(eval-length % args) operands)]
      (case op
        :- (apply - vals)
        :+ (apply + vals)
        (throw (ex-info "unknown length op" {:op op}))))

    :else
    (throw (ex-info "invalid length expr" {:expr expr}))))

(defn read-payloads
  [in payload-specs args]
  (let [result (reduce (fn [acc {:keys [name type length]}]
                         (let [len (eval-length length args)
                               raw (read-exactly in len)]
                           (assoc acc name
                                  (if (= type :headers)
                                    (parse-headers-block raw)
                                    raw))))
                       {}
                       payload-specs)]
    (consume-crlf in)
    result))

(defn read-frame
  [in shapes]
  (let [line (read-all in)
        sp (String/.indexOf line " ")
        op (if (neg? sp)
             line
             (subs line 0 sp))
        rest-line (if (neg? sp)
                    ""
                    (subs line (inc sp)))
        shape (get shapes op)]
    (when-not shape
      (throw (ex-info "unknown op" {:op op :line line})))
    (let [args (parse-args (:args shape) rest-line)
          payloads (when (:payloads shape)
                     (read-payloads in (:payloads shape) args))]
      (cond-> {:op op}
        args (assoc :args args)
        payloads (merge payloads)))))

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
       (run! #((:fn %) frame conn))))

(defn start
  [{:keys [reader ^ExecutorService executor frame-shapes] :as conn}]
  (.submit executor
           ^Runnable
           #(loop []
              (let [frame (read-frame reader frame-shapes)]
                (dispatch frame @handlers conn))
              (recur))))

(comment
  (set! *warn-on-reflection* true)

  (submap? nil {:foo :bar})

  (parse-nats-url "nats://foo"))
