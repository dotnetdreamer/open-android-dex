# Tests

`openandroiddex-signal` is a protocol implementation, which makes it the one
piece of this project worth checking without a phone in your hand. It runs under
plain Node with no dependencies:

```sh
node test/signal-test.js
```

It starts the rendezvous on a spare port and runs a host and two viewers through
the exact message dance the phone and the browser do — including a viewer trying
to forge another viewer's id, a viewer closing its tab, and the phone
reconnecting to reclaim its room.

It is also how the half-open socket bug was found: a socket taken over from an
HTTP upgrade emits `end`, not `close`, so a viewer that shut its tab was never
reported gone and its room entry lived forever.

Everything else needs a device. `doc/web-viewer.md` lists what is still
unverified there.
