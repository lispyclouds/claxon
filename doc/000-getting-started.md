# Getting started

Java 21+ and availability of virtual threads is assumed by default.

### Clojure CLI/deps.edn and Babashka

```clojure
{org.clojars.lispyclouds/claxon {:mvn/version "1.2"}}

; as a git dep
{io.github.lispyclouds/claxon {:git/sha "<COMMIT SHA>"}}
```

### Leiningen

```clojure
[org.clojars.lispyclouds/claxon "1.2"]
```

## API

The public surface lives in `claxon.client`. Everything else is implementation detail.

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
| `:pedantic` | `false` | Set additional strict format checking. |
| `:verbose` | `false` | Set +OK protocol acknowledgements. |
| `:protocol` | `1` | 0 or 1 inidicates support for dynamic INFO messages from the sever. |
| `:claxon/executor` | `(Executors/newVirtualThreadPerTaskExecutor)` | Executor used to run the background frame-reading loop and handlers. |
| `:claxon/frame-shapes` | the full NATS op table | The data-driven protocol description. Override this with care. |
| `:claxon/handlers` | a `PING` responder and an `-ERR` handler | Handlers registered automatically on connect. |
| `:claxon/timeout-ms` | `2000` | Socket connect timeout per URL. |
| `:claxon/urls` | `["nats://localhost:4222"]` | Candidate server URLs, tried in random order until one connects. |
| `:claxon/verify-tls` | `true` | Set this to disable client SSL verification. |

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

Registers a callback for incoming frames isolated to the connection. `matcher` is `{:op ... :args ...}`; `:args` is matched as a submap, so you can match loosely (e.g. only on `:subject`) or leave it out to match any args.
Returns the handler id. Optionally takes in an error handler which will be invoked with the uncaught exception in the handler.
Note: if there's an uncaught error in the err handler, **it will be swallowed**.

```clojure
(nats/add-handler conn
  (fn [frame _conn]
    (println "got message on" (get-in frame [:args :subject]) "->" (String. (:body frame) "UTF-8")))
  (fn [fram conn ex] (println "Unhandled error " ex " on frame " frame " and conn " conn))
  {:op "MSG" :args {:subject "greetings"}}
```

The handler receives `(frame conn)` the parsed frame and the connection it arrived on.
Handlers run on the background reader thread; keep them fast or hand off work yourself (e.g. via `future` or a queue).

### remove-handler

Removes/Unregisters a handler in a connection by id. no-op if not found.

```clojure
(remove-handler conn id)
```

### close

Tears down the connection: removes its handlers, closes the socket streams, shuts down its executor, and closes the socket.

```clojure
(close conn)
```
