# Rationale

Most Clojure NATS clients are thin wrappers around the official Java SDK (`nats.java`). That's a perfectly reasonable choice, but it comes with a JVM tax: you get nats.java's threading model, its option-builder classes, and a hard dependency on a full JVM, which means no runtimes like Babashka.

claxon takes the other path. The [NATS client protocol](https://docs.nats.io/reference/reference-protocols/nats-protocol) is a small, text-based, line-oriented protocol. claxon implements the protocol directly against a plain `java.net.Socket`, using nothing but Clojure data to describe the wire format with the following goals:

- **Babashka-compatible.** claxon runs as a script, in a `bb.edn` project, or embedded in a larger bb-based tool, with no AOT compilation and no native dependencies beyond the JVM/GraalVM that bb already ships.
- **Small, inspectable and flexible** The entire protocol surface is described as data in one map (`claxon.conf/defaults`'s `:claxon/frame-shapes`) ops, their arguments, and their payloads. Reading and writing frames are both generic interpreters over that data, not one function per operation. Additionally, the default protocol behaviour can be influenced and new things added, all from the userland.
- **Lightweight.** No dependency on `nats.java`. The only third-party dependency is a JSON library (`clojure.data.json` on the JVM, nothing on bb), used only for `INFO`/`CONNECT` payloads.
- **Data in, data out.** Frames are Clojure maps. Connecting, publishing, subscribing, and handling messages are all just `assoc`-able data.

claxon is deliberately a _protocol_ client, not a full-featured NATS SDK.
It doesn't try to be a drop-in replacement for nats.java's surface area, see [Roadmap](#roadmap) for what's intentionally left out for now.

## Design

```mermaid
flowchart TB
    App["your application code"]
    Client["claxon.client<br/><b>connect · invoke · add-handler · remove-handler · close</b><br/><i>public API</i>"]

    subgraph Impl["claxon.impl.* and claxon.conf (implementation detail)"]
        direction LR
        Conf["claxon.conf<br/><i>defaults,<br/>:claxon/frame-shapes</i>"]
        Write["claxon.impl.write<br/><i>snd: encodes &amp;<br/>writes a frame</i>"]
        Read["claxon.impl.read<br/><i>read-frame: decodes<br/>a frame from bytes</i>"]
        Sock["claxon.impl.sock<br/><i>-&gt;tls: upgrades<br/>the socket</i>"]
        Common["claxon.impl.common<br/><i>dispatch, submap?,<br/>parse-nats-url, json</i>"]
    end

    Socket["java.net.Socket<br/>TCP, optionally TLS"]
    Server(("NATS server"))
    Loop["background reader loop<br/>on :claxon/executor<br/><i>read-frame → dispatch →<br/>matching handlers</i>"]

    App -->|"connect / invoke / add-handler"| Client
    Client --> Conf & Write & Read & Sock & Common
    Conf --> Write
    Write & Read & Sock --> Common
    Write -->|writes| Socket
    Socket -->|"PUB / SUB / PING / ..."| Server
    Server -->|"MSG / PING / ..."| Socket
    Socket -->|reads| Read
    Read --> Loop
    Loop -->|"runs your handler fn"| App

    classDef api fill:#eef2ff,stroke:#6366f1,color:#3730a3
    classDef impl fill:#fafafa,stroke:#a1a1aa,color:#3f3f46
    classDef sock fill:#1e293b,stroke:#0f172a,color:#f1f5f9
    classDef loop fill:#fff1f2,stroke:#fb7185,color:#9f1239
    classDef app fill:#f8fafc,stroke:#cbd5e1,color:#0f172a

    class Client api
    class Conf,Write,Read,Sock,Common impl
    class Socket sock
    class Loop loop
    class App,Server app
```

## Comparison to other Clojure NATS clients

| aspect                    | claxon                       | [clj-nats](https://github.com/cjohansen/clj-nats) | [monkey-projects/nats](https://github.com/monkey-projects/nats) |
| ------------------------- | ---------------------------- | ------------------------------------------------- | --------------------------------------------------------------- |
| Underlying impl           | Pure Clojure, raw sockets    | Wraps `nats.java`                                 | Wraps `nats.java`                                               |
| Babashka compatible       | **Yes**                      | No                                                | No                                                              |
| User modifiable protocols | **Yes**                      | No                                                | No                                                              |
| Dependencies              | data.json on JVM, none on bb | `nats.java` + its transitive deps                 | `nats.java`                                                     |
| Protocol description      | Data-driven                  | Delegated to nats.java                            | Delegated to nats.java                                          |

## Roadmap

The following are **not yet implemented** but planned (based on asks/issues) in order of priority:

- **Custom TLS CAs**: It uses the system store as of now, support for specifying a CA file could be added.
- **TLS-first handshake**: Support no plain text connections as described [here](https://docs.nats.io/running-a-nats-service/configuration/securing_nats/tls#tls-first-handshake).
- **WebSocket transport.** Only raw TCP sockets are supported as of now.
- **Editor integration** Since the protocol is spec driven, clj-kondo/clojure-lsp can be hooked in to provide real time feedback on function calls.
- **Advanced Authentication** No NKey/JWT signing of the `INFO` nonce yet.
- Maybe JetStream abstractions.
- Something else? Please raise an issue.
