# Security policy

## Supported versions

Security fixes are made on the latest published release and the current
`main` branch. Older releases should be upgraded before a report is evaluated.

| Version | Supported |
| --- | --- |
| 2.7.x | Yes |
| 2.6.x and earlier | No |

Update this table with every minor release.

## Reporting a vulnerability

Please do not publish exploit details, captured player data, backup archives,
or packet payloads in a public issue.

Use the repository's **Security** tab to submit a private vulnerability report
when that option is available. If private reporting is unavailable, contact the
maintainer through the GitHub profile linked from the repository and request a
private channel without including sensitive details in the first message.

Include:

- the affected version or commit;
- the Android version and device architecture;
- the smallest reproducible input using synthetic data;
- the expected and observed behavior;
- the impact and any user interaction required; and
- whether the issue affects capture, parsing, CSV import/export, backup/restore,
  Discord sharing, local persistence, standby automation (the accessibility
  service), scheduled recording (exact alarms and the boot receiver), or
  release signing.

Do not test against another person's device, game account, Discord webhook, or
network traffic. Remove UIDs, names, notes, webhook secrets, signing material,
and real packet contents from reproductions.

## Security boundaries

mobileGF2logger observes traffic only after Android grants `VpnService` consent.
The fixed HaoPlay and Darkwinter package IDs are registered as separate allowed
applications; these identifiers are not matched against network domains or
packet contents. It does not decrypt TLS or authenticate the game's
plaintext protocol. Parsed data and backups therefore provide local integrity
and management convenience, not cryptographic proof that a remote payload is
genuine.

A package name alone does not make an app a supported client. The publishers'
signing certificates are not known in advance, so GF2logger pins, per supported
package, the SHA-256 digests of the signing certificates it first sees installed
while the user is using the app: when Home is shown, or when capture, standby,
or scheduled recording is started from the app. Capture routes, attributes, and
standby or scheduled recording opens a client only while every pinned
certificate still signs it (Android 9 and newer ask `hasSigningCertificate`,
which honours APK Signature Scheme v3 key rotation; Android 8 compares the
current signatures). Background work never pins. Uninstalling a client keeps its
pin, so the same publisher's reinstall matches and an app installed under that
package name by anyone else is left outside the VPN, never attributed, and never
launched, with a capture notice or standby result saying why, until the user
reviews the new certificate's fingerprint in Settings and trusts it explicitly.
The limit is that of any trust on first use: whatever is installed under a
supported package name the first time GF2logger sees it is trusted. Pins are
kept in app-private preferences, which Android backup excludes and `.gf2backup`
archives never contain.

On Android 10 and newer, the active VPN asks Android for the UID owning an
original connection tuple and maps that UID only to the fixed supported package
IDs whose signer is verified. Remote IP addresses and DNS/SNI labels are
diagnostic hints, not trusted client or server identities. Android 8–9 falls
back only when exactly one verified supported client is installed; ambiguous
flows remain quarantined. Payload
`21905` supplies a bounded Platoon identity for its own decoded flow. Its banner
frame and mark are accepted only as scalar IDs in the closed game-defined range
`1..6`; unknown values remain undisplayed rather than selecting arbitrary local
resources. Captured
packets may enter an immutable isolated management scope only after that profile
combines the Android-verified client, user-selected compatible server region,
and Platoon ID. An identity-free `21917` document can enter a scope only through
the explicit user-driven CSV flow, where the preview names either an existing
profile or a newly declared client/server/name/ID destination before mutation.
Pre-identity payloads, admission candidates, registered profiles, and profile
metadata are independently bounded. A flow is permanently
quarantined until closure if its identity changes or profile admission fails.
Each user-started capture may admit at most one new profile per supported
client; existing profiles remain usable. Full profile removal is an explicit
destructive workflow and cannot intentionally orphan a selectable scope.
Closed flows discard address and owner metadata even when no parser was created;
queue-rejected closes quarantine the flow so delayed open work cannot restore it.
The app does not turn an IP address, publisher default, or unverified hostname
into a persistent server-identity guess. A new supported-client/Platoon pair is
held only in a bounded process-memory admission queue until the user chooses one
of that client's compatible server regions. Before confirmation, decoded data
is absent from parsed-packet history, SQLite, retained CSV, and preferences;
explicit discard, overflow, force-stop, or process death removes it. Selecting
an existing profile safely restores that profile's saved capture region.

Roster CSV payload `21917` has no Platoon identity. Import never infers a target
from member overlap. It requires an explicitly selected existing profile or a
new profile whose client, compatible server, name, and ID the user declares,
displays that destination before mutation, and aborts if the scope is no longer
available. This prevents an identity-free roster from silently crossing Platoon
storage boundaries.

Profile metadata, capture-region routing, active selection, SQLite state,
scoped settings, and retained CSV retirement share one durable restore journal.
A process death before the commit marker restores the previous values together.
Destructive profile deletion requires two confirmations including an exact-name
match, then uses a durable deletion queue so interrupted scoped cleanup resumes
before profiles are listed again. Deletion is refused while that Platoon's CSV
import or undo runs, and work that was already running cannot recreate the
deleted database. It also removes the restore rollback and staging copies,
quarantined corrupt copies, the weekly PNG share cache, and a capture
diagnostics status that names the Platoon.

Exports and Discord sends are explicit user actions that move selected data out
of Android private storage. Backups are checksummed and strictly validated but
are not encrypted or signed. Treat exported files and webhook destinations as
sensitive. A restore never installs the selected file's schema: after
validation its rows are copied, column by column, into a database that the
current schema code creates, so table constraints an archive declares (CHECK,
ON CONFLICT, COLLATE, STRICT, WITHOUT ROWID) are discarded and the installed
DDL must equal the app's own text exactly. The copied rows are refused, and the
restore fails as an invalid backup, when an enum column holds a name the app
does not define, a text value is oversized, or a row id or AUTOINCREMENT
counter is out of range; maintenance keys the app never writes are dropped. A
Platoon restored before 2.7.2, which installed the selected file as it was, is
checked once per launch before its first use: table DDL the app never wrote is
rebuilt into the app's own schema the same way, keeping every row.
While a Platoon's CSV import or
undo holds its checkpoint claim, captures, edits, and backup restores of that
Platoon are refused instead of being sealed into, or later erased by, the
import's undo.

A database that fails SQLite's integrity check is never exported or used for a
CSV checkpoint. A database that SQLite reports as corrupt is moved aside under a
`.corrupt-<time>` name instead of being deleted, and that Platoon stays
unavailable, rather than silently starting empty, until a backup is restored or
the user chooses to rebuild it from the retained roster CSVs.

Weekly history remains inside the private SQLite database. Its report payloads
use a versioned bounded format, reject oversized compressed or decompressed
content, validate the complete immutable rendering context before storage or
restore, and are never exposed through the PNG `FileProvider` unless the user
separately chooses the existing share workflow. A restored database must hold
only history the app could have written: at most 15 revisions per week, each a
bounded decodable payload filed under its own week, and selections that point
within their week. Deleting a member deletes every stored revision that shows
or names them, discards the CSV-import undo checkpoint that still contains
them, and clears the weekly PNG share cache. Editing a private note leaves
earlier revisions with the note they were recorded with until they are
replaced or the member is deleted. Captured packets in Packet history are raw
evidence and stay until the user deletes them there.

Packet-triggered weekly-history generation consumes only the same validated,
deduplicated, count-bounded activity and update observations accepted for
persistence. A separate 32-period cap bounds report reconstruction and SQLite
history work even if future callers provide a broader accepted set.

Standby mode's optional accessibility service is the most privileged component.
Its platform grant allows gestures and window-content retrieval, but it reads
only the foreground package and the app-name labels of Recents cards, observes
only window-state changes, and taps at one fixed point of the title screen
only while a supported game package is in front. Taps stop at the first capture notice that
only a logged-in game produces, and in any case after two minutes per run. Its
other actions are the global Home, Recents, and (Android 9 and newer) lock
actions, plus a 1-pixel, untouchable screen-on overlay whose hold expires at the
standby deadline by itself. Standby needs the phone unlocked with the screen on
for the whole night; tapping the standby screen or switching apps leaves it
without locking, and on Android 8 the phone is left to its own screen timeout.
On a phone with a PIN, pattern, or password the user is told before the first
use that standby keeps the phone unlocked. Scheduled recording is available
only on phones without a PIN, pattern, or password, and stops itself once one
is set. Its exact alarm reaches a non-exported receiver through an immutable
`PendingIntent`, and a reschedule receiver re-plans it after boot, an app update, or a clock,
time-zone, or exact-alarm permission change. The alarm clock's show intent,
which Android hands to any app that queries the next alarm, opens only the
exported launcher screen with no extras; the creator package it reveals is
inherent to any alarm clock. Every wake-up is re-planned from the saved
Platoons, and a server without a saved Platoon is neither woken for nor
reported. Profile deletion, server-region changes, and backup restores run only
while capture is stopped, checked where they run, and capture starts wait for
them to finish.

## Response expectations

The maintainer will acknowledge a reproducible report, assess severity, and
coordinate a fix and disclosure timeline. A report may be closed when it is a
duplicate, requires a rooted/compromised Android system outside this project's
control, or does not cross a documented security boundary.
