(ns claxon.impl.read
  (:require
   [claxon.impl.common :as ic]
   [clojure.string :as str])
  (:import
   [java.io ByteArrayOutputStream EOFException InputStream]
   [java.util.concurrent ExecutorService]))

(defn read-all
  [in]
  (let [buf (ByteArrayOutputStream.)]
    (loop [prev -1]
      (let [b (InputStream/.read in)]
        (cond
          (= b -1)
          (throw (EOFException. "socket closed mid-line"))

          (and (= prev 13) (= b 10))
          (let [^bytes line-bytes (.toByteArray buf)]
            (String. line-bytes 0 (dec (alength line-bytes)) "UTF-8"))

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
                :json (ic/read-json rest-line)
                rest-line)})
      (parse-tokenized-args arg-specs (str/split rest-line #" +")))))

(defn parse-headers-block
  [^bytes raw]
  (let [s (String. raw "UTF-8")
        [version & lines] (str/split s #"\r\n")
        [_ status desc] (str/split version #" " 3)
        headers (reduce (fn [m line]
                          (let [[k v] (str/split line #":" 2)]
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
        op (str/upper-case
            (if (neg? sp)
              line
              (subs line 0 sp)))
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

(defn start
  [{:keys [reader ^ExecutorService executor frame-shapes] :as conn}]
  (.submit executor
           ^Runnable
           #(loop []
              (let [frame (read-frame reader frame-shapes)]
                (ic/dispatch frame @ic/handlers conn))
              (recur))))
