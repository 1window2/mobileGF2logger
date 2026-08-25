# Security policy

## Supported versions

Security fixes are made on the latest published release and the current
`main` branch. Older releases should be upgraded before a report is evaluated.

| Version | Supported |
| --- | --- |
| 2.4.x | Yes |
| 2.3.x and earlier | No |

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
  Discord sharing, local persistence, or release signing.

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

On Android 10 and newer, the active VPN asks Android for the UID owning an
original connection tuple and maps that UID only to the fixed supported package
IDs. Remote IP addresses and DNS/SNI labels are diagnostic hints, not trusted
client or server identities. Android 8–9 falls back only when exactly one
supported client is installed; ambiguous flows remain quarantined. Payload
`21905` supplies a bounded Platoon identity for its own decoded flow. Only a
confirmed profile composed from the verified client, user-selected compatible
server region, and Platoon ID may receive an immutable isolated management
database, retained CSV directory, checkpoint, weekly settings, or backup scope.
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

Profile metadata, capture-region routing, active selection, SQLite state,
scoped settings, and retained CSV retirement share one durable restore journal.
A process death before the commit marker restores the previous values together.
Destructive profile deletion requires two confirmations including an exact-name
match, then uses a durable deletion queue so interrupted scoped cleanup resumes
before profiles are listed again.

Exports and Discord sends are explicit user actions that move selected data out
of Android private storage. Backups are checksummed and strictly validated but
are not encrypted or signed. Treat exported files and webhook destinations as
sensitive.

Weekly history remains inside the private SQLite database. Its report payloads
use a versioned bounded format, reject oversized compressed or decompressed
content, validate the complete immutable rendering context before storage or
restore, and are never exposed through the PNG `FileProvider` unless the user
separately chooses the existing share workflow.

Packet-triggered weekly-history generation consumes only the same validated,
deduplicated, count-bounded activity and update observations accepted for
persistence. A separate 32-period cap bounds report reconstruction and SQLite
history work even if future callers provide a broader accepted set.

## Response expectations

The maintainer will acknowledge a reproducible report, assess severity, and
coordinate a fix and disclosure timeline. A report may be closed when it is a
duplicate, requires a rooted/compromised Android system outside this project's
control, or does not cross a documented security boundary.
