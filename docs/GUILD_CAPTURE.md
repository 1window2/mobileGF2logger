# Guild-member capture

This implementation is based on `blead/gfl2logger` v0.2.5 at commit `f2baf77c9d86cd9d2b232671680ea8ff905d3658`.

## Reference behavior

The desktop logger creates one parser for every TCP flow and ignores client-to-server messages. Its `IgnoreTls` addon tells mitmproxy to pass TLS connections through unchanged. Guild data is therefore obtained from a plaintext server-to-client TCP stream, not by decrypting HTTPS.

The stream contains a five-byte outer header followed by one or more four-byte inner payload headers. Inner type `21917` selects `GuildMembersData`. The protobuf schema is:

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

The reference may receive one logical dataset across multiple inner payloads. It continues when the payload type matches and the previous outer message id is `0`, or when both outer message ids match. GF2log preserves that batching rule when writing CSV files.

The reference reports guild data during login, sometimes twice during login, after reconnection, and on Platoon pages.

## Output

GF2log writes UTF-8 CSV with this exact column order:

```text
uid,name,level,weeklyMerit,totalMerit,highScore,totalScore,lastLogin,logTime
```

`logTime` is the UTC instant at which the first guild payload in the batch was received. CSV quoting is applied to names containing commas, quotes, or line breaks.
