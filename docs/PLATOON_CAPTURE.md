# Platoon-member capture

This implementation is based on `blead/gfl2logger` v0.2.5 at commit `f2baf77c9d86cd9d2b232671680ea8ff905d3658`.

## Reference behavior

The desktop logger creates one parser for every TCP flow and ignores client-to-server messages. Its `IgnoreTls` addon tells mitmproxy to pass TLS connections through unchanged. Platoon data is therefore obtained from a plaintext server-to-client TCP stream, not by decrypting HTTPS.

The stream contains a five-byte outer header followed by one or more four-byte
inner payload headers. Inner type `21917` selects `GuildMembersData`. The
protobuf schema is:

```text
GuildMembers.members = field 1
GuildMember.player = field 1
GuildMember.weekly_merit = field 3
GuildMember.total_merit = field 4
GuildMember.high_score = field 5
GuildMember.total_score = field 6
GuildMember.uid = field 7
GuildMember.last_login = field 8
Player.player_info = field 1
PlayerInfo.name = field 2
PlayerInfo.level = field 3
```

Inner type `21935` selects the timestamped Platoon Activity response. Its
observed schema is:

```text
PlatoonActivity.summaries = repeated field 1
Summary.id = field 1
Summary.action_id = field 2
Summary.occurred_at = field 3
Summary.count = field 4

PlatoonActivity.entries = repeated field 2
Entry.kind = field 2
Entry.occurred_at = field 3
Entry.action_id = field 4
Entry.member_name = field 5
```

`occurred_at` is Unix time in seconds. Observed `802001` entries correlate with
the in-game Daily Patrol-completion/update line and are collapsed to one fact
per UID and game day. Duplicate `802001` rows do not mean that Daily Patrol was
completed twice. `801005` is a correlated companion daily reward/reset record;
its exact user-facing label remains provisional, so it is retained in parsed
history but is not counted independently.

`Summary.id` is a unique, monotonic server record/cursor identifier, not a
member UID. `Entry.kind` is an event variant/state discriminator: values 1, 2,
and 3 can occur for the same member, action, and timestamp, so it is neither an
attempt count nor an identity field. Activity entries contain a member name
but no UID. GF2logger therefore resolves an activity or membership event only
when the name maps to exactly one UID in nearby 21917 snapshots. Duplicate
names remain unresolved rather than risking an update to the wrong member.

The reference may receive one logical dataset across multiple inner payloads. It continues when the payload type matches and the previous outer message id is `0`, or when both outer message ids match. mobileGF2logger preserves that batching rule when writing CSV files.

The reference reports Platoon data during login, sometimes twice during login,
after reconnection, and on Platoon pages. The 21935 response is incremental:
the app stores every supplied fact, but cannot reconstruct entries the server
does not send. Durable membership history therefore comes from UID-based 21917
roster differences, optionally upgraded by exact 21935 timestamps, plus manual
membership history for older missing tenures.

## Output

mobileGF2logger writes UTF-8 CSV with this exact column order:

```text
uid,name,level,weeklyMerit,totalMerit,highScore,totalScore,lastLogin,logTime
```

`logTime` is the UTC instant at which the first Platoon payload in the batch was received. CSV quoting is applied to names containing commas, quotes, or line breaks.

Platoon Activity history uses this column order:

```text
recordType,id,kind,occurredAt,actionId,count,memberName
```
