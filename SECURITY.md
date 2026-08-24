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
`21905` supplies a bounded Platoon identity for its own decoded flow. Only the
composite of verified client, user-selected server region, and Platoon ID may
select an isolated management database, retained CSV directory, checkpoint,
weekly settings, or backup scope. Pre-identity payloads, registered profiles,
and profile metadata are independently bounded. A flow is permanently
quarantined until closure if its identity changes or profile admission fails.
Each user-started capture may admit at most one new profile per supported
client; existing profiles remain usable, and a confirmed selector action can
forget registry metadata to recover capacity without deleting isolated data.

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
