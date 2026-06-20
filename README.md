# claxon

A minimal, pure-Clojure, data-driven [NATS](https://nats.io) client.

## Philosophy

Most Clojure NATS clients are thin wrappers around the official Java SDK
(`jnats`). That's a perfectly reasonable choice, but it comes with a JVM tax:
you get jnats' threading model, its option-builder classes, and a hard
dependency on a full JVM — which means no Babashka.

claxon takes the other path. The [NATS client protocol](https://docs.nats.io/reference/reference-protocols/nats-protocol)
is a small, text-based, line-oriented protocol — it doesn't need a 10MB Java
SDK to speak it. claxon implements the protocol directly against a plain
`java.net.Socket`, using nothing but Clojure data to describe the wire format.
The result is:

- **Babashka-compatible.** claxon runs as a script, in a `bb.edn` project, or
  embedded in a larger bb-based tool, with no AOT compilation and no native
  dependencies beyond the JVM/GraalVM that bb already ships.
- **Small and inspectable.** The entire protocol surface is described as data
  in one map (`claxon.conf/defaults`'s `:claxon/frame-shapes`) — ops, their
  arguments, and their payloads. Reading and writing frames are both generic
  interpreters over that data, not one function per operation.
- **Lightweight.** No dependency on `jnats`. The only third-party dependency
  is a JSON library (`clojure.data.json` on the JVM, `cheshire` on bb, picked
  automatically via reader conditionals), used only for `INFO`/`CONNECT`
  payloads.
- **Data in, data out.** Frames are Clojure maps. Connecting, publishing,
  subscribing, and handling messages are all just `assoc`-able data — no
  builder classes, no checked exceptions to catch.

claxon is deliberately a *protocol* client, not a full-featured NATS SDK. It
doesn't try to be a drop-in replacement for jnats' surface area — see
[Roadmap](#roadmap) for what's intentionally left out for now.

## Comparison to other Clojure NATS clients

| | claxon | [clj-nats](https://github.com/cjohansen/clj-nats) | [monkey-projects/nats](https://github.com/monkey-projects/nats) | [clj-nats (thunknyc)](https://github.com/thunknyc/clj-nats) |
|---|---|---|---|---|
| Underlying impl | Pure Clojure, raw sockets | Wraps `jnats` (Java SDK) | Wraps `jnats` | Wraps `jnats` |
| Babashka compatible | **Yes** | No | No | No |
| Dependencies | JSON lib only | `jnats` + its transitive deps | `jnats` | `jnats` |
| Protocol description | Data-driven (one map of frame shapes) | Delegates to SDK internals | Delegates to SDK internals | Delegates to SDK internals |
| Scope | Core pub/sub protocol | PubSub, JetStream, KV, object store | PubSub, error listeners | PubSub, request/reply |

The jnats-based clients are mature and full-featured — if you need JetStream,
key/value buckets, or object store today and you're already on a regular JVM,
they're the better choice. claxon exists for the case those clients structurally
can't cover: running inside Babashka, or anywhere you want NATS connectivity
without pulling in a full Java messaging SDK.

## Roadmap

The following are **not yet implemented** but planned:

- **Authentication** beyond what's already parseable from a `nats://` URL
  (user/password, token). No TLS, no NKey/JWT signing of the `INFO` nonce yet.
- **WebSocket transport.** Only raw TCP sockets are supported today.
- JetStream, key/value, and object store are out of scope for now — claxon
  focuses on core pub/sub.

## Installation

TODO

## Public API

The public surface lives in `claxon.client`. Everything else (`claxon.impl.*`)
is implementation detail you shouldn't need to call directly.

### `(connect)` / `(connect opts)`

Opens a connection to a NATS server and performs the `INFO`/`CONNECT`
handshake. Returns a `conn` map — pass this to every other function.

```clojure
(require '[claxon.client :as nats])

(def conn (nats/connect))
;; or, with options:
(def conn (nats/connect {:claxon/urls ["nats://localhost:4222"]
                          :claxon/timeout-ms 2000}))
```

`opts` is merged over `claxon.conf/defaults`. The keys you'll commonly care about:

| key | default | meaning |
|---|---|---|
| `:claxon/urls` | `["nats://localhost:4222"]` | Candidate server URLs, tried in random order until one connects. |
| `:claxon/timeout-ms` | `2000` | Socket connect timeout per URL. |
| `:claxon/handlers` | a default `PING`→`PONG` responder | Map of `{matcher fn}` handlers registered automatically on connect. |
| `:claxon/executor` | a virtual-thread-per-task executor | Executor used to run the background frame-reading loop. |
| `:claxon/frame-shapes` | the full NATS op table | The data-driven protocol description. You generally won't override this. |

Any other key you pass (e.g. `:user`, `:pass`, `:name`) is forwarded as part of
the JSON `CONNECT` payload sent to the server.

### `(invoke conn frame)`

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

`:args` and `:payloads` only need to contain what the op actually requires —
see `claxon.conf/defaults`'s `:claxon/frame-shapes` for the full list of ops
and their fields (`PUB`, `HPUB`, `SUB`, `UNSUB`, `PING`, `PONG`, etc). Byte
counts (`:bytes`, `:hdr-bytes`) are always computed for you; don't pass them
yourself.

### `(add-handler conn handler matcher)`

Registers a callback for incoming frames. `matcher` is `{:op ... :args ...}`;
`:args` is matched as a submap, so you can match loosely (e.g. only on
`:subject`) or leave it out to match any args.

```clojure
(nats/add-handler conn
  (fn [frame _conn]
    (println "got message on" (get-in frame [:args :subject])
              "->" (String. (:body frame) "UTF-8")))
  {:op "MSG" :args {:subject "greetings"}})
```

The handler receives `(frame conn)` — the parsed frame and the connection it
arrived on. Handlers run on the background reader thread; keep them fast or
hand off work yourself (e.g. via `future` or a queue).

### `(close conn)`

Tears down the connection: removes its handlers, closes the socket streams,
shuts down its executor, and closes the socket.

```clojure
(nats/close conn)
```

## Example: publish and subscribe

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

## Example: headers (HPUB / HMSG)

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

## Example: running under Babashka

```clojure
#!/usr/bin/env bb
(require '[claxon.client :as nats])

(def conn (nats/connect))
(nats/invoke conn {:op "PUB" :args {:subject "bb.demo"} :payloads {:body "from babashka"}})
(Thread/sleep 100)
(nats/close conn)
```

Run it with `bb script.clj` — no `lein`, no `clj`, no AOT step, no `jnats` jar
on the classpath.

## License

Copyright © Rahul De.

Distributed under the MIT License. See LICENSE.
