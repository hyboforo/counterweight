# counterweight-agent

A small HTTP service that runs on each till machine and owns the printer. It
listens on `http://127.0.0.1:9110` and does one thing: turns the print
documents the server emits into bytes a thermal printer understands.

There is no cash drawer in this shop, so the agent does not drive one.

> **Built and running.** The shop's printer is a **Syncotek**, USB, which
> Windows installs as the queue `80mm Series Printer` on a USB port. A receipt
> has been driven end to end — sale completed at the till, job claimed by the
> browser relay, encoded here, spooled to the printer.

## Why it exists

The server never emits ESC/POS bytes. It emits a **print document** — a
structured description of what should appear — and the agent encodes it for
whatever printer is physically attached. Swapping an 80 mm Syncotek for a 58 mm
Xprinter is agent configuration and nothing else.

The split also keeps the fast-moving part (the selling UI) behind a browser
refresh and confines the slow-moving part (driver quirks, byte encoding) to a
component that changes perhaps twice a year.

## Running it

```bash
./mvnw package                       # target/counterweight-agent-1.0.0-SNAPSHOT.jar
java -jar target/counterweight-agent-1.0.0-SNAPSHOT.jar --config agent.properties
java -jar target/counterweight-agent-1.0.0-SNAPSHOT.jar --config agent.properties --test-print
```

`--test-print` is the install-day command. It prints a page from the machine
that will be doing the printing, through the configured transport, without the
server, the till or a sale — a double-height heading, a dotted column pair, a
rule at the paper width, an em dash CP437 has never heard of, a barcode to try
the scanner on, and a cut. If that page comes out, everything from here to the
paper is right and any later problem is on the network side.

`install/install-task.ps1` registers it to start at logon, and
`install/agent.example.properties` is the config with every decision explained.

## API

```
GET  /v1/status        → { paired, transport, printers[], paperOut, coverOpen, statusReadback, queueDepth }
GET  /v1/printers      → the Windows queues this machine has, and the configured target
POST /v1/print         ← the server's PrintJob JSON       → the job's status
GET  /v1/jobs/{id}     → QUEUED | PRINTING | DONE | FAILED (+ error)
POST /v1/jobs/{id}/retry
```

**`/v1/print` answers only when the printing is done.** The till marks the
server's copy of the job DONE on any 2xx, so answering early would report a
receipt as printed while it was still on its way to a printer with no paper in
it — and the reprint the shop is owed would never be offered. It is bounded at
20 seconds, because the other failure is a relay tick that never returns.

**`paperOut` and `coverOpen` are null, and say so.** Reading them needs
`DLE EOT` and a transport that answers back; the Windows spooler is write-only,
so the agent cannot know. §14.1 already has the answer — the sale completes,
the job spools, the till offers a reprint — and a hopeful `paperOut: false`
would only tell the counter something nobody checked.

## What it guarantees

**Every job is on disk before anything reaches the printer**, and is not
deleted afterwards. A jam or an empty roll must not lose a receipt somebody
asked for; because the job survives, "print it again" is a lookup rather than a
feature the server has to support. Status files are written through a temporary
file and moved into place, since a till loses power exactly as often as the
shop does.

**One thread prints.** Interleaved ESC/POS is not a race that produces a wrong
number — it produces half of one receipt inside another.

**Origins are checked before anything prints.** The agent is on loopback, which
means the till page can reach it and so can any other page in that browser.
Loopback origins are allowed; the shop server's origin has to be named in the
config, and a refusal logs the origin it refused so the fix is one line away. A
pairing token is supported and off by default — nothing on the till page can
obtain one yet, so switching it on before the server hands it over would stop
every receipt in the shop.

## Transports

| | For | Notes |
|---|---|---|
| `spooler` | USB | Raw bytes to a Windows queue via `javax.print` with `AUTOSENSE`. What the Syncotek uses. |
| `tcp` | Network printers | Raw port 9100, with connect and read timeouts. |
| `file` | Serial, Bluetooth SPP, development | A device path like `\.\COM3`, or a plain file whose bytes can be read back. |

**The trap worth knowing**: a printer queue installed for one Windows user is
invisible to a service running as SYSTEM. An agent that prints when launched by
hand and never as a service is almost always a per-user printer — install the
queue for all users, or run the agent as the till's own account, which is what
the install script does.

Barcode scanners are deliberately **out of scope**. USB HID scanners present as
a keyboard, need no driver, and work in any input field.

## Encoding

CP437, the printer's power-on code page, so a printer that was power-cycled
mid-shift comes back correct rather than printing mojibake until someone
restarts the agent. Typographic characters are transliterated first — the
receipt template prints an em dash for a missing receipt number, and the count
sheet says "write 0 — a blank line reads as not yet counted", both of which
would otherwise print as `?` in the middle of a sentence somebody has to act on.

`jlink`/`jpackage` must include `jdk.charsets` or CP437 is not there; the
fallback is ASCII rather than an exception, because a receipt in plain ASCII is
readable and a till that refuses to print because of a character set is not.

## Tests

```bash
./mvnw test
```

Ten of them, no printer required: ESC/POS is a byte stream, and every failure
they cover — a receipt that stays bold to the bottom, a cut through the last
three lines, an em dash printed as `?`, a barcode sent as ESC/POS garbage — is
visible in the bytes. One of them decodes a document in **the server's own
JSON**, written out by hand rather than imported, because the two components
ship separately and the only honest test of a wire contract is one that reads
bytes the other side could have sent. If somebody renames a `PrintElement`
subclass, that test fails here rather than silently in a shop.

What a real printer adds is whether the paper feeds, which is what
`--test-print` is for.
