# Getting into a world

Connecting and sending a command is the small part. Most of the work is satisfying everything a
server wants before it treats the connection as a real player. Each item below either drops the
connection or stalls the join when it is missing, and chronit handles all of them.

- Code of conduct, new in Minecraft 26.x. A server can present one during the configuration phase
  and disconnects a client that does not accept it. The text is written to the log, since agreeing
  to something unread is worth recording.
- Resource packs. A server with `require-resource-pack=true` disconnects on a decline. A real
  client reports *accepted*, then *downloaded*, then *successfully loaded*, separated by however
  long the download and the reload took. See [Resource packs](configuration.md#resource-packs).
- Cookie requests, added in 1.20.5 and used by proxy networks. An unanswered request stalls the
  join indefinitely.
- Client settings and brand, sent on entering configuration and again after joining, as vanilla
  does.
- Teleport confirmation. The first confirmed teleport is the dependable "in the world" signal.
  Reaching the play state only means configuration finished.
- Chunk batch acknowledgement. Without it the server keeps its chunk throttle at the starting value
  and chunks trickle in, so a readiness condition waiting on them never completes.
- Chat acknowledgements. Since 1.19.1 the server counts the signed messages it has sent and expects
  them acknowledged. Neglect it and a busy server closes the connection over a chat validation
  failure within a minute of joining, whether or not the bot ever says anything.
- Client tick cadence: the tick-end packet every tick and a state report every second, which is what
  a stationary vanilla client sends.

Commands go out on the unsigned command packet, which carries no signature or acknowledgement
fields. That is why command sequences work even on servers with `enforce-secure-profile=true`.
Plain `chat:` messages are signed when the account has a usable certificate.
