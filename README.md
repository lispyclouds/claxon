# claxon

[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg?style=flat)](https://choosealicense.com/licenses/mit/)
[![bb compatible](https://raw.githubusercontent.com/babashka/babashka/master/logo/badge.svg)](https://book.babashka.org#badges)
[![](https://github.com/lispyclouds/claxon/workflows/Tests/badge.svg)](https://github.com/lispyclouds/claxon/actions?query=workflow%3ATests)

A minimal, pure-Clojure, data-driven [NATS](https://nats.io) client.

## Rationale

Most Clojure NATS clients are thin wrappers around the official Java SDK (`nats.java`). That's a perfectly reasonable choice, but it comes with a JVM tax: you get nats.java's threading model, its option-builder classes, and a hard dependency on a full JVM, which means no runtimes like Babashka.

claxon takes the other path. The [NATS client protocol](https://docs.nats.io/reference/reference-protocols/nats-protocol) is a small, text-based, line-oriented protocol. claxon implements the protocol directly against a plain `java.net.Socket`, using nothing but Clojure data to describe the wire format with the following goals:

- **Babashka-compatible.** claxon runs as a script, in a `bb.edn` project, or embedded in a larger bb-based tool, with no AOT compilation and no native dependencies beyond the JVM/GraalVM that bb already ships.
- **Small, inspectable and flexible** The entire protocol surface is described as data in one map (`claxon.conf/defaults`'s `:claxon/frame-shapes`) ops, their arguments, and their payloads. Reading and writing frames are both generic interpreters over that data, not one function per operation. Additionally, the default protocol behaviour can be influenced and new things added, all from the userland.
- **Lightweight.** No dependency on `nats.java`. The only third-party dependency is a JSON library (`clojure.data.json` on the JVM, nothing on bb), used only for `INFO`/`CONNECT` payloads.
- **Data in, data out.** Frames are Clojure maps. Connecting, publishing, subscribing, and handling messages are all just `assoc`-able data.

claxon is deliberately a _protocol_ client, not a full-featured NATS SDK.
It doesn't try to be a drop-in replacement for nats.java's surface area, see [Roadmap](#roadmap) for what's intentionally left out for now.

## Comparison to other Clojure NATS clients

| aspect                    | claxon                       | [clj-nats](https://github.com/cjohansen/clj-nats) | [monkey-projects/nats](https://github.com/monkey-projects/nats) |
| ------------------------- | ---------------------------- | ------------------------------------------------- | --------------------------------------------------------------- |
| Underlying impl           | Pure Clojure, raw sockets    | Wraps `nats.java`                                 | Wraps `nats.java`                                               |
| Babashka compatible       | **Yes**                      | No                                                | No                                                              |
| User modifiable protocols | **Yes**                      | No                                                | No                                                              |
| Dependencies              | data.json on JVM, none on bb | `nats.java` + its transitive deps                 | `nats.java`                                                     |
| Protocol description      | Data-driven                  | Delegated to nats.java                            | Delegated to nats.java                                          |

## Roadmap

**Current status: Stabilising**

The following are **not yet implemented** but planned (based on asks/issues) in order of priority:

- **WebSocket transport.** Only raw TCP sockets are supported as of now.
- **Editor integration** Since the protocol is spec driven, clj-kondo/clojure-lsp can be hooked in to provide real time feedback on function calls.
- **Advanced Authentication** No NKey/JWT signing of the `INFO` nonce yet.
- Maybe JetStream abstractions.
- Something else? Please raise an issue.

## Installation

Java 21+ and availability of virtual threads is assumed by default.

#### Clojure CLI/deps.edn

```clojure
{io.github.lispyclouds/claxon {:git/sha "<LATEST COMMIT SHA>"}}
```

TODO: clojars release

## Public API

The public surface lives in `claxon.client`. Everything else (`claxon.impl.*`) is implementation detail you shouldn't need to call directly.

### connect

```clojure
(connect)
(connect opts)
```

Opens a connection to a NATS server and performs the `INFO`/`CONNECT` handshake. Returns a `conn` map, pass this to every other function.

```clojure
(require '[claxon.client :as nats])

(def conn (nats/connect))

;; or, with options:
(def conn (nats/connect {:claxon/urls ["nats://localhost:4222"]
                         :claxon/timeout-ms 2000}))
```

`opts` is merged over `claxon.conf/defaults`. Any non `:claxon/` namespaced key passed (e.g. `:user`, `:pass`) is forwarded verbatim to the server.
See CONNECT [docs](https://docs.nats.io/reference/reference-protocols/nats-protocol#connect) for all options.
| key | default | details |
| ---------------------- | --------------------------------------------- | -------------------------------------------------------------------- |
| `:headers` | `true` | Whether the client supports headers. |
| `:lang` | `clojure` | The implementation language of the client. |
| `:name` | `claxon` | Client name. |
| `:pedantic` | `false` | Turns off additional strict format checking. |
| `:verbose` | `false` | Turns off +OK protocol acknowledgements. |
| `:claxon/executor` | `(Executors/newVirtualThreadPerTaskExecutor)` | Executor used to run the background frame-reading loop and handlers. |
| `:claxon/frame-shapes` | the full NATS op table | The data-driven protocol description. Override this with care. |
| `:claxon/handlers` | a default `PING`→`PONG` responder | Map of `{matcher fn}` handlers registered automatically on connect. |
| `:claxon/timeout-ms` | `2000` | Socket connect timeout per URL. |
| `:claxon/urls` | `["nats://localhost:4222"]` | Candidate server URLs, tried in random order until one connects. |
| `:claxon/verify-tls` | true | Set this to false to disable SSL verification. |

### invoke

```clojure
(invoke conn frame)
```

Sends a frame to the server. `frame` is a map of `{:op ... :args ... :payloads ...}`.

```clojure
;; publish "hello" to subject "greetings"
(nats/invoke conn {:op "PUB"
                   :args {:subject "greetings"}
                   :payloads {:body "hello"}})

;; subscribe to a subject
(nats/invoke conn {:op "SUB"
                   :args {:subject "greetings" :sid "1"}})

;; unsubscribe
(nats/invoke conn {:op "UNSUB"
                   :args {:sid "1"}})
```

`:args` and `:payloads` only need to contain what the op actually requires see `claxon.conf/defaults`'s `:claxon/frame-shapes` for the full list of ops and their fields (`PUB`, `HPUB`, `SUB`, `UNSUB`, `PING`, `PONG`, etc).
Byte counts (`:bytes`, `:hdr-bytes`) are always computed for you, don't pass them yourself.

### add-handler

```clojure
(add-handler conn handler matcher)
(add-handler conn handler err-handler matcher)
```

Registers a callback for incoming frames isolated to the connection. `matcher` is `{:op ... :args ...}`; `:args` is matched as a submap, so you can match loosely (e.g. only on `:subject`) or leave it out to match any args. Returns the handler id.
Optionally takes in an error handler which will be invoked with the uncaught exception in the handler. Note: if there's an uncaught error in the err handler, **it will be swallowed**.

```clojure
(nats/add-handler conn
  (fn [frame _conn]
    (println "got message on" (get-in frame [:args :subject]) "->" (String. (:body frame) "UTF-8")))
  (fn [fram conn ex] (println "Unhandled error " ex " on frame " frame " and conn " conn))
  {:op "MSG" :args {:subject "greetings"}}
```

The handler receives `(frame conn)` the parsed frame and the connection it arrived on. Handlers run on the background reader thread; keep them fast or hand off work yourself (e.g. via `future` or a queue).

### remove-handler

Removes/Unregisters a handler by id. no-op if not found.

```clojure
(remove-handler id)
```

### close

Tears down the connection: removes its handlers, closes the socket streams, shuts down its executor, and closes the socket.

```clojure
(close conn)
```

## Examples

The best way to know what each option's type and values could be is to refer the [Protocol Docs](https://docs.nats.io/reference/reference-protocols/nats-protocol) directly.
All values would be passed as described.

### publish and subscribe

```clojure
(require '[claxon.client :as nats])

(def conn (nats/connect))

(nats/add-handler conn
  (fn [frame _conn]
    (println "received:" (String. (:body frame) "UTF-8")))
  {:op "MSG" :args {:subject "demo"}})

(nats/invoke conn {:op "SUB" :args {:subject "demo" :sid "1"}})
(nats/invoke conn {:op "PUB" :args {:subject "demo"} :payloads {:body "hello, nats"}})

;; => prints "received: hello, nats" shortly after, on the reader thread

(nats/close conn)
```

### headers (HPUB / HMSG)

```clojure
(nats/add-handler conn
  (fn [frame _conn]
    (println "headers:" (get-in frame [:headers :headers]))
    (println "body:" (String. (:body frame) "UTF-8")))
  {:op "HMSG" :args {:subject "demo.headers"}})

(nats/invoke conn {:op "SUB" :args {:subject "demo.headers" :sid "2"}})

(nats/invoke conn
  {:op "HPUB"
   :args {:subject "demo.headers"}
   :payloads {:headers {:headers {"Content-Type" ["text/plain"]}}
              :body "with headers this time"}})
```

### JetStream and KV recipes

claxon has no JetStream or KV specific functions yet, all are implemented on the NATS server as regular subjects (`$JS.API.*` for management, `$KV.<bucket>.<key>` for KV) that you talk to with ordinary `PUB`/`SUB`/`HPUB`.
The examples below show that explicitly, including the request/reply pattern (subscribe to an inbox, publish with `:reply-to` set to it) that claxon doesn't wrap for you.

A small helper to simplify the request/reply dance, not something claxon ships (yet):

```clojure
(defn request
  "Send `body` to `subject` and block (up to `timeout-ms`) for a single reply."
  [conn subject body timeout-ms]
  (let [inbox (str "_INBOX." (random-uuid))
        p (promise)
        hid (add-handler conn
                         (fn [frame _conn] (deliver p frame))
                         {:op "MSG" :args {:subject inbox}})]
    (invoke conn {:op "SUB" :args {:subject inbox :sid inbox}})
    (invoke conn {:op "PUB"
                  :args {:subject subject :reply-to inbox}
                  :payloads {:body body}})
    (let [frame (deref p timeout-ms :timeout)]
      (remove-handler hid)
      (invoke conn {:op "UNSUB" :args {:sid inbox}})
      frame)))
```

#### Creating a stream and a durable pull consumer

```clojure
(require '[clojure.data.json :as json]) ; or cheshire.core on bb

(request conn "$JS.API.STREAM.CREATE.ORDERS"
         (json/write-str {"name" "ORDERS" "subjects" ["ORDERS.*"]})
         2000)

(request conn "$JS.API.CONSUMER.DURABLE.CREATE.ORDERS.PULLER"
         (json/write-str {"durable_name" "PULLER"
                          "ack_policy" "explicit"})
         2000)
```

#### Publishing into the stream

Publishing to a JetStream backed subject is just a `PUB`, the server intercepts it because the subject matches a stream:

```clojure
(nats/invoke conn {:op "PUB" :args {:subject "ORDERS.new"} :payloads {:body "order #1"}})
```

#### Pulling and acking from the consumer

A pull request is a `request` to `$JS.API.CONSUMER.MSG.NEXT.<stream>.<consumer>`, the reply's own `:reply-to` is the ack subject and publishing an empty body there acks the message:

```clojure
(let [msg (request conn "$JS.API.CONSUMER.MSG.NEXT.ORDERS.PULLER" "1" 5000)]
  (println "got:" (String. (:body msg) "UTF-8"))
  ;; ack it by publishing nothing to its reply-to
  (nats/invoke conn {:op "PUB" :args {:subject (get-in msg [:args :reply-to])}}))
```

#### A simple KV bucket

A KV bucket is just a stream named `KV_<bucket>` whose subjects look like `$KV.<bucket>.<key>`:

```clojure
;; create the bucket (a stream under the hood)
(request conn "$JS.API.STREAM.CREATE.KV_profiles"
         (json/write-str {"name" "KV_profiles" "subjects" ["$KV.profiles.>"]})
         2000)

;; put: publish the value to the key's subject
(nats/invoke conn {:op "PUB"
                   :args {:subject "$KV.profiles.sue"}
                   :payloads {:body "{\"color\":\"blue\"}"}})

;; get: ask the stream for the last message on that subject.
;; The reply is a JSON envelope, the actual value is base64-encoded
;; inside response["message"]["data"], not the raw body.
(let [resp (request conn "$JS.API.STREAM.MSG.GET.KV_profiles"
                     (json/write-str {"last_by_subj" "$KV.profiles.sue"})
                     2000)
      parsed (json/read-str (String. (:body resp) "UTF-8"))
      value (String. (.decode (java.util.Base64/getDecoder)
                               ^String (get-in parsed ["message" "data"]))
                      "UTF-8")]
  (println value)) ;; => {"color":"blue"}

;; delete: publish an empty body with a KV-Operation: DEL header
(nats/invoke conn {:op "HPUB"
                   :args {:subject "$KV.profiles.sue"}
                   :payloads {:headers {:headers {"KV-Operation" ["DEL"]}}
                              :body nil}})
```

### queueing (replacing something like RabbitMQ)

NATS has two different mechanisms for "queuing" and they give very different guarantees. Picking the right one matters if you're replacing a broker like RabbitMQ that you expect to hold messages durably and retry failed work.

#### Queue groups: load balancing, no durability

A queue group is a label on a `SUB`, the server picks one subscriber in the group per message instead of fanning out to all of them.
There's no storage involved. If nobody's subscribed when a message is published, it's gone, same as any other core NATS subject.

```clojure
;; start two "workers" in the same queue group: each PUB to "jobs" goes to
;; exactly one of them, round-robin, not both
(doseq [worker-id ["worker-1" "worker-2"]]
  (nats/add-handler conn
    (fn [frame _conn]
      (println worker-id "got:" (String. (:body frame) "UTF-8")))
    {:op "MSG" :args {:subject "jobs"}}))

(nats/invoke conn {:op "SUB" :args {:subject "jobs" :queue-group "workers" :sid "w1"}})
(nats/invoke conn {:op "SUB" :args {:subject "jobs" :queue-group "workers" :sid "w2"}})

(dotimes [i 5]
  (nats/invoke conn {:op "PUB" :args {:subject "jobs"} :payloads {:body (str "job " i)}}))
```

This is good for load-balancing fire-and-forget work where losing a message on a crash is acceptable.
It is **not** a RabbitMQ replacement on its own: there's no persistence, no ack, no redelivery.

#### JetStream work-queue stream: the actual RabbitMQ-equivalent

For RabbitMQ-style guarantees (messages survive until a worker successfully processes them, failed work gets retried) you want a JetStream stream with `"retention": "workqueue"` and a durable pull consumer.
The `request` helper is the same one defined in the sections above.

```clojure
;; create a work-queue stream: each message is delivered to exactly one
;; consumer and removed from the stream as soon as it's acked
(request conn "$JS.API.STREAM.CREATE.JOBS"
         (json/write-str {"name" "JOBS"
                          "subjects" ["jobs.>"]
                          "retention" "workqueue"})
         2000)

;; a single durable consumer, shared by every worker process —
;; work-queue streams only allow one (non-overlapping) consumer per subject,
;; so this is how you fan work out across many workers, not separate consumers
(request conn "$JS.API.CONSUMER.DURABLE.CREATE.JOBS.WORKERS"
         (json/write-str {"durable_name" "WORKERS"
                          "ack_policy" "explicit"
                          "ack_wait" 30000000000}) ; 30s, in nanoseconds
         2000)
```

Each worker pulls one message at a time, processes it, and acks or nacks:

```clojure
(defn run-worker [conn worker-id]
  (future
    (loop []
      (let [msg (request conn "$JS.API.CONSUMER.MSG.NEXT.JOBS.WORKERS" "1" 5000)]
        (when (not= msg :timeout)
          (let [reply-to (get-in msg [:args :reply-to])
                body (String. (:body msg) "UTF-8")]
            (try
              (println worker-id "processing:" body)
              ;; ... do the actual work here ...
              (nats/invoke conn {:op "PUB" :args {:subject reply-to}}) ; +ACK
              (catch Exception _
                ;; ask the server to redeliver this message
                (nats/invoke conn {:op "PUB" :args {:subject reply-to}
                                   :payloads {:body "-NAK"}}))))))
      (recur))))

(run-worker conn "worker-1")
(run-worker conn "worker-2")

(dotimes [i 5]
  (nats/invoke conn {:op "PUB" :args {:subject "jobs.new"} :payloads {:body (str "job " i)}}))
```

A few things to note, since this is what makes it different from the queue group above:

- Unacked messages are automatically redelivered after `ack_wait` (30s here), to whichever worker pulls next. No message is lost if a worker dies mid-job.
- A worker can actively reject a message (`-NAK`) to put it back for retry sooner than the ack-wait timeout, as shown in the `catch` above.
- Once a message is acked, `workqueue` retention deletes it from the stream: it's a true queue, not a log you replay.
- If you need a dead-letter queue, set `"max_deliver"` on the consumer config and watch `$JS.EVENT.ADVISORY.CONSUMER.MAX_DELIVERIES.JOBS.WORKERS` for messages that exhausted their retries.

## License

Copyright © Rahul De.

Distributed under the MIT License. See LICENSE.
