# Third-party notices

## zdtun

- Project: [emanuele-f/zdtun](https://github.com/emanuele-f/zdtun)
- Revision: `c2a2674a411efdd71f7595e5395d8b49c52ab347`
- License: GNU Lesser General Public License v3.0
- Bundled source: `app/src/main/cpp/third_party/zdtun`
- License text: `app/src/main/cpp/third_party/zdtun/COPYING`

zdtun is dynamically packaged as `libzdtun.so`; mobileGF2logger's JNI integration is packaged separately as `libgf2capture.so`.

Local modifications to the bundled zdtun (under the same LGPL-3.0 terms):

- The open-socket counter cannot underflow.
- A TCP or ICMP connection whose socket cannot be opened is closed through `zdtun_conn_close`, so `on_connection_close` runs and the TCP client gets an RST for its SYN.
- When the socket cap forces a purge, connections without a socket are not counted, and the rest go in the order UDP/ICMP, TCP, then connections an optional `on_purge_priority` callback asks to keep.
- DNS flags are converted to host byte order before the QR bit is tested.
- A client FIN is forwarded to the server and ACKed only after every byte before it, including the FIN segment's own payload, has been sent.

Host regression tests for these changes are in `app/src/test/cpp` (`run-zdtun-host-test.sh`).

## gfl2logger protocol reference

- Project: [blead/gfl2logger](https://github.com/blead/gfl2logger)
- Version: v0.2.5
- Revision: `f2baf77c9d86cd9d2b232671680ea8ff905d3658`
- License declaration: MIT

mobileGF2logger reproduces the documented framing, recognized payload identifiers, protobuf field mappings, and Platoon CSV column order. It does not bundle the Python application or generated Python protobuf runtime.

## Material icons

- Project: [google/material-design-icons](https://github.com/google/material-design-icons)
- License: Apache License 2.0
- Bundled source: vector paths in `app/src/main/res/drawable/ic_*.xml`
