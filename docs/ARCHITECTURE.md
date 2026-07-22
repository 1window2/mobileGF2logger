# Architecture

## Design goals

mobileGF2logger favors a small APK and an auditable data path over a general-purpose packet analyzer. Android owns VPN consent and package selection; a proven userspace forwarding core will own TCP/UDP forwarding and reassembly; Kotlin owns only game-specific framing and field extraction.

No UML artifacts existed in the repository when this design was created. This document is the baseline component and data-flow description until UML is added.

```text
Selected game process
        |
        v
Android VpnService TUN (package allowlist)
        |
        v
Native zdtun forwarding/reassembly core
        |
        | incoming application payload chunk + flow id
        v
Gfl2StreamParser (one bounded instance per live TCP flow)
        |
        v
Gfl2PayloadDecoder (five recognized protobuf message types)
        |
        v
Typed GameData events -> Platoon CSV exporter
```

## Protocol framing

The framing behavior was reproduced from `blead/gfl2logger` v0.2.5 rather than inferred from individual IP packets. TCP payload boundaries are not message boundaries, so every flow owns a persistent stream parser.

Outer message:

| Offset | Size | Meaning |
| --- | ---: | --- |
| 0 | 3 | Message id, little-endian |
| 3 | 2 | Body length, little-endian |
| 5 | N | One or more inner payloads |

Inner payload:

| Offset | Size | Meaning |
| --- | ---: | --- |
| 0 | 2 | Payload type, little-endian |
| 2 | 2 | Payload body length, little-endian |
| 4 | N | Protocol Buffers wire payload |

Recognized inner types:

| Type | Model |
| ---: | --- |
| 11021 | Weapons |
| 11061 | Attachments |
| 11138 | Common keys |
| 21917 | Platoon members |
| 23201 | Formations |

Unknown payload types are skipped without allocation. A recognized but malformed protobuf payload produces a warning event and does not terminate parsing of later messages.

## Memory and concurrency limits

- Each active flow parser is capped at 2 MiB of buffered stream data.
- The parser service has one worker and a queue capped at 256 payload chunks.
- TLS, HTTP, and UDP payloads remain native and are excluded from the parser.
- Outgoing plaintext chunks are used only for native flow classification.
- Flow-close callbacks finalize any pending recognized payload before removing parser state.
- Queue saturation is counted and surfaced in the capture status instead of being silently discarded.
- Raw IP packets and application payloads are not persisted.

The single worker is deliberate: these five responses are sparse, and avoiding a worker pool reduces scheduling, memory, and ordering complexity. If benchmarking later proves this insufficient, partition work by flow while preserving in-flow ordering.

## Native integration contract

`NativeCaptureBridge` loads `gf2capture`, which dynamically links the bundled `zdtun` library. It exposes two lifecycle operations:

1. Start forwarding using the supplied TUN file descriptor.
2. Stop and release native resources.

The listener reports candidate plaintext payloads after TCP reassembly, flow closure, unexpected native termination, and aggregate byte counters. Every upstream socket is passed through `VpnService.protect()` so forwarded traffic cannot re-enter the VPN.

The implementation uses zdtun rather than introducing a new TCP/IP stack. General PCAP export, nDPI classification, TLS decryption, root capture, remote collectors, and the full PCAPdroid UI are not included.

## HTTPS and application-layer encryption

An Android VPN can observe packet metadata, but it cannot automatically read TLS-protected application data. Like the desktop reference, mobileGF2logger identifies TLS/HTTP flows and forwards them unchanged without parsing. The five game frame signatures are evaluated only on candidate plaintext TCP streams. Do not add pinning or anti-cheat bypasses.

## Parsed-packet history

Every completed recognized payload is formatted in protocol order and written atomically to the app's private `files/capture-history` directory. `CaptureHistoryStore` returns entries newest-first, trims the oldest files once the count exceeds 100, rejects path-like identifiers, and supports explicit deletion of user-selected entries. The main activity renders timestamp-only titles in `yy/MM/dd HH:mm:ss` UTC format; selecting a title opens a separate selectable text view with a clipboard action. Backup rules exclude both packet history and generated Platoon CSV files so inspected data does not leave the device through Android backup.
