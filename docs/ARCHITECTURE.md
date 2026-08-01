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
Gfl2PayloadDecoder (seven recognized protobuf message types)
        |
        v
Typed GameData events
        |
        +--> Parsed-packet history / CSV exporter
        |
        +--> Structured Platoon repository / weekly reports
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
| 21935 | Platoon activity |
| 21960 | Platoon updates |
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

The single worker is deliberate: these seven responses are sparse, and avoiding
a worker pool reduces scheduling, memory, and ordering complexity. If
benchmarking later proves this insufficient, partition work by flow while
preserving in-flow ordering.

## Native integration contract

`NativeCaptureBridge` loads `gf2capture`, which dynamically links the bundled `zdtun` library. It exposes two lifecycle operations:

1. Start forwarding using the supplied TUN file descriptor.
2. Stop and release native resources.

The listener reports candidate plaintext payloads after TCP reassembly, flow closure, unexpected native termination, and aggregate byte counters. Every upstream socket is passed through `VpnService.protect()` so forwarded traffic cannot re-enter the VPN.

The implementation uses zdtun rather than introducing a new TCP/IP stack.
General PCAP export, nDPI classification, TLS decryption, root capture, remote
collectors, and the full PCAPdroid UI are not included.

## Structured Platoon evidence

The private management database stores 21917 roster snapshots, 21935 activity
facts, and 21960 Updates facts separately. The roster is authoritative for
stable UID identity. Activity entries supply an action, name, and Unix
timestamp but no UID, so a fact is linked only when nearby roster snapshots
map the name to exactly one member. Ambiguous duplicate names remain stored
but unresolved.

Observed action `802001` is deduplicated to one Daily Patrol fact per UID and
matching 05:00-based game day. Membership boundaries remain available as
UID-safe 21917 observation facts. Payload 21960 supplies exact UID and event
timestamps for Join, Withdraw, and Remove boundaries; those individual
boundaries are immutable. Manual boundaries remain editable and can coexist
with an exact boundary in the same tenure.

## HTTPS and application-layer encryption

An Android VPN can observe packet metadata, but it cannot automatically read
TLS-protected application data. Like the desktop reference, mobileGF2logger
identifies TLS/HTTP flows and forwards them unchanged without parsing. The
seven recognized game frame signatures are evaluated only on candidate
plaintext TCP streams. Do not add pinning or anti-cheat bypasses.

## Module and object boundaries

- `protocol` is an Android-independent decoding library. It owns framing,
  protobuf wire decoding, typed payload models, and text/CSV formatting.
- `capture` owns the VPN/native lifecycle, bounded per-flow parsing, and
  translation of completed capture batches into management-domain input.
- `management` owns evidence policy, reporting rules, the repository facade,
  private SQLite persistence, and the retained completed-roster directory.
  It does not depend on the capture package.
- Activities own Android presentation and delegate evidence precedence,
  inference, ordering, and CSV construction to pure policy objects.

The design deliberately favors composition over deep inheritance. Abstraction
and polymorphism appear at real variation points (`GameData`, `ParseEvent`, and
the native listener contract); encapsulation is provided by stores and the
repository facade. Android lifecycle inheritance remains shallow through
`LocalizedActivity`, `VpnService`, and `SQLiteOpenHelper`.

The large SQLite helper remains a known maintenance boundary. Schema,
migrations, and transactional evidence correlation stay together for v2.0.1
to avoid a risky pre-release rewrite; future schema work should extract those
concerns behind the existing repository while retaining single-transaction
ingestion.

## Parsed-packet history

Every completed recognized payload is formatted in protocol order and written atomically to the app's private `files/capture-history` directory. `CaptureHistoryStore` returns entries newest-first, trims the oldest files once the count exceeds 100, rejects path-like identifiers, and supports explicit deletion of user-selected entries. `SavedHistoryStore` atomically copies selected entries into `files/saved-history`, rejects duplicates, caps the collection at 50 without FIFO deletion, and keeps saved entries independent from recent-history rotation.

The main activity renders both collections with timestamp-only titles in `yy/MM/dd HH:mm:ss` using the Android device timezone. Selecting a title opens a cleaned table parsed from the stored CSV body; the same screen can reveal the complete raw stored text and copy it to the clipboard. Android backup rules exclude all private files, including recent history, saved history, generated Platoon CSV files, and the structured management database. A user can separately invoke the explicit Platoon backup export, which contains parsed management data but never raw traffic.

## Explicit backup boundary

The canonical `.gf2backup` container is a bounded ZIP with a checksummed
manifest and SQLite management database. Legacy format v1 remains a
Platoon-only compatibility backup. Format v2 adds a checksummed, strictly
typed settings payload containing only user-owned configuration; capture
diagnostics, raw packet history, signing material, and internal migration flags
are excluded.

Complete restore validates the filename, archive entries and identity,
checksums, settings completeness and ranges, current database schema, SQLite
integrity, and foreign keys before mutation. Database replacement runs under
the repository maintenance lock. The previous database and a settings snapshot
are retained until both resources commit, so any failure restores the prior
state instead of leaving a partial import. A fresh install records that no
database existed so rollback removes the staged replacement rather than
mistaking it for prior state. Schema introspection uses the extended SQLite
column metadata where available and the equivalent ordinary-column metadata on
the older SQLite engine shipped with the supported Android 8 minimum.

After a successful complete restore, the target device's retained roster CSV
ingestion cache is retired. Export already materializes those files into the
backed-up database, and keeping unrelated target-side files would allow a later
screen startup to mutate the restored state. If any commit step fails, the
retained cache is restored alongside the previous database and settings.

Weekly tables remain projections over persisted snapshots, activity facts,
membership events, notes, and manual overrides. The repository owns available
period discovery and report construction; Activities only request reports and
format them for display or CSV export.

`WeeklyReportActivity` loads one immutable projection on a serialized worker.
A generation token rejects results superseded by navigation or lifecycle
changes, and the activity adds nested member rows in small display-frame
batches. The Gunsmoke solver merges equivalent partial histories instead of
retaining every complete path and enforces deterministic state/operation
budgets; evidence beyond those budgets stays conservative rather than blocking
Android input or publishing an unsupported estimate.

Metric certainty is evaluated in the report domain. Exact 05:00 boundaries
close the preceding game day, while sparse Updates facts retain their event
timestamp until the builder verifies they preceded the captured counters.
Manual corrections overlay only explicitly edited fields; untouched derived
values keep their original exact, lower-bound, or unknown certainty.

Completed roster captures are written under a non-importable temporary suffix
and atomically published as `.csv` only after protocol completion. Retained CSV
reconciliation runs off the UI thread, skips represented source identities,
and imports older evidence without rewriting current membership. Both backup
exports reconcile completed retained evidence before taking their locked
database snapshot.
