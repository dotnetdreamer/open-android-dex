# Send to computer, and photos that just arrive

In any app on the phone, Share → **Send to computer** puts what was shared on
the computer the phone is attached to: a file in its Downloads folder, a link
open in its browser, text on its clipboard — and one of the computer's own
notifications says so. A small card comes up from the bottom of the phone's
screen while it goes, names the computer, and goes by itself.

On the computer, the window's fourth tab, **Photos**, shows the phone's
newest photos and screenshots, and a new one shows up there by itself. Open
it, save it to Downloads, copy it, show it in its folder. **Save photos to
this computer** saves each new one — or all the photos on the phone — into a
folder here, and carries on each time the phone comes back.

This record exists for the reason the others do: several decisions below look
arbitrary until you know what was measured, or what the platform refuses.

## Shape

| Piece | File | What it owns |
| --- | --- | --- |
| The share entry | launcher `SendActivity.java` | the share menu's target, the card, what it says |
| What waits to be sent | launcher `SendQueue.java` | shares by number, the knock, what the server is told, the open files, the entry switched |
| The launcher's answers | launcher `AppsProvider.java` | `share.take`, `share.refuse`, `share.opened`, `share.progress`, `share.done`, `share.enable`; `share/<id>/<i>/<name>` as an open file |
| The phone's half of the link | wmd `AppSend.java` | the `send` connection, the port, asking the launcher, offering |
| The phone's pictures | wmd `AppPhotos.java` | the query, the once-a-second look, inotify, thumbnails, an original on a `photo` connection |
| Shared plumbing | wmd `AppServer.java`, `AppOffers.java`, `Content.java`, `Providers.java` | two new first messages; an offer whose file is open already; a provider held for many queries; letting the launcher's provider go |
| The computer's half | `src-tauri/src/send/mod.rs` | the settings (`send.json`), the keeper, the commands |
| The link | `send/link.rs` | the `send` connection: made, read, made again |
| What arrives | `send/inbox.rs` | files, a link, text; the notification; the short list |
| The feed | `send/photos.rs` | each phone's pictures, their thumbnails, an original fetched |
| Save photos | `send/keep.rs`, `send/ledger.rs` | the loop and its guards; the record in the folder |
| The folder chooser | `send/pick.rs` | NSOpenPanel, IFileOpenDialog, zenity/kdialog |
| The words | `send/words.rs` | every sentence a notification or a status line says, tested |
| The page | `src/features/photos/*` | the Photos tab |
| The test | `test/send-test.mjs`, `test/send/` | the phone's half over the wire; a throwaway sharing app |

## The share entry

One activity, `SendActivity`, with filters for `SEND` and `SEND_MULTIPLE` of
`*/*`, under the app's own icon and the label "Send to computer" — the name
the Linux dock's entry already has, short enough for One UI's sheet.

**It is declared disabled.** The computer's half on the phone has the
launcher enable it the first time a computer that takes shares links, and set
it back when one that does not links ("Receive files from my phone" off). A
phone that only ever runs the launcher on its own never grows an entry that
can do nothing, and turning the feature off takes the entry away again — also
on a phone that was not attached then: the computer remembers each phone
whose entry it turned on (`entryOn` in send.json), links it once more when it
next comes, and lets it go when its hello says the entry is off. Turned off
with the phone attached, the link likewise stays until that hello. With two
computers, the last word wins.

The plan had the shell switch the component itself, from the app server's
own process, to wake nothing. Android 15 refuses that: "Shell cannot change
component state for …SendActivity to 1" (S24, 2026-11-12) — the shell may
change only a whole package, or a test-only one's components. So the server
asks the launcher (`share.enable`, one call), which may change its own. A
desktop start installs this build's launcher after the link is already up, so
every install of the launcher is followed by one `look`, and while the
launcher is ready and its entry still off the computer asks again every 10 s:
the entry was on 4.4 s after the install, measured. A phone whose launcher is
missing or old is not asked again until an install.

No direct-share target with the computer's name: it would have to be
published before the share and withdrawn when the computer goes, and nothing
on the phone outlives a Samsung adbd restart to withdraw it. The card names
the computer instead. It follows the phone's language, not the desktop's: it
sits over a phone app, beside a share sheet in the phone's language.

The card is a translucent activity over the app it was shared from, with
the screen dimmed a little behind it; it owns a task of its own and is out of
Recents. It is `singleTask`: a second share while the card is still up was
swallowed when the task was only brought forward (the test found it), and
now always reaches the card (`onNewIntent`). On the phone's desktop it sits
above the taskbar, which is an overlay of ours and gives no inset; it wears
no caption, and is not resized (looked at on the S24's desktop).

What it says: "Looking for your computer…", "Getting it ready…", "Sending to
IK's M1…" with `2 of 5` and a bar, "Saved to Downloads on IK's M1", "Opened
on…", "Sent to…", "Copied to the clipboard on…" — or "No computer is
connected", "Your computer didn't answer", "Send to computer is turned off
on…", "Waiting for IK's M1…" (its turn behind other files, below), "It
didn't arrive" with why — "This phone is still opening earlier files" among
them (below) — "IK's M1 is busy. Send it again in a
minute." (a cap, below). Files and text shared together (`EXTRA_STREAM` and
`EXTRA_TEXT`): the files; the text is dropped. Text with a picture only in
`ClipData` — how a browser shares a page with its preview — is the text, so
the link arrives and not the preview. Up to 1000 items and 200,000
characters.

What a share carries is read defensively: any app may send anything there.
An `EXTRA_STREAM` that is a list of Strings, another Parcelable, or a class
the launcher cannot unparcel was a `ClassCastException` on the launcher's
main thread, which took the phone's desktop down with it (the review found
it); now only real Uris count, and the card says "There's nothing here to
send" (the test sends both and checks the launcher's process is the same).

## A knock, then a question

The app server takes connections only from the shell and root
(`SO_PEERCRED`): its socket types into any app on the phone. The launcher —
whose uid is the Linux guest's too — cannot reach it, and does not need to.

1. The card **knocks** on `127.0.0.1:7190`, a port the server listens on
   while a computer's `send` link is up: one byte that carries nothing. A
   refused connection is "no computer", in a millisecond; three knocks over
   1.5 s cover a blip. Anyone on the phone may knock, so a knock costs what
   it can: its byte is read off the accepting thread (two readers, 300 ms
   each, 64 waiting, more closed unread), and however many knocks come, one
   take waits at most. If something else on the phone has the port, the hello
   says so (`port: false`), the Photos tab says so, and the server tries again
   every 10 s, with a new hello when it has it.
2. The server **asks the launcher** what it has, through the launcher's
   provider (`share.take`). Binder says who calls: the provider answers the
   shell alone. The answer is the share: its text, or its files' names, sizes
   and types, and a 512 px preview of a picture.
3. For each file the server opens `content://…launcher.apps/share/<id>/<i>/<name>`,
   and the launcher hands back **the open file itself**, opened under its own
   identity — it holds the share's permission. The launcher opens on a
   worker of its own and lets the server's binder call go after 50 s, saying
   why: a provider that never answers holds that worker, never one of the
   launcher's binder threads, which the phone's desktop answers on (the
   review's point: one stuck call per share, sixteen shares, and the desktop
   stopped). What a given-up open opens in the end is closed. With three
   opens stuck, a new files share is refused — "This phone is still opening
   earlier files" — and with every worker held an item is refused at once.
   On the server, three shares' files are opened at once, sixteen more wait,
   past that a share is refused the same way; each item has 60 s there too,
   for a launcher that does not answer at all. One that takes longer counts
   as unreadable, and so does the rest of its share. One slow provider holds
   up only its own share — never another, a Cancel, or a card's "done", which
   share one thread. The card does not call a share silent while its files
   are still being opened (up to 5 minutes).
4. The server offers the files by number (`AppOffers`) and tells the
   computer (`shared`); the computer takes each on a `get` connection, as it
   takes a file dragged out of a window, and says how it went (`sendDone`),
   which the card shows.

No token, no `adb reverse`, no broadcast. What lost: the file service's port
(7192 over `adb reverse`), which only a desktop session has, needs a reverse
per transport re-armed after every adbd restart and a listening port on the
computer; the server asking the launcher over and over, which would keep the
launcher's process up for a share that mostly never comes; and a content
observer on a URI of the launcher's, which the shell's process is not told of
(below).

A provider called from the server is held (`Providers.HELD`), which keeps the
launcher's process from being reclaimed; the server lets it go 30 s after the
last share when no window and no touchpad use it (one release waiting, not
one per take).

A share is known by its number on both sides. The numbers start at a random
point in each run of the launcher's process, so a share of an earlier run
still on the server's or the computer's side is never taken for a new one.

## Open files, not permissions

A drag carried out of a window hands the shell a grant on the dragged URIs
(`PcDrags`). A share does not: the launcher opens each item and passes the
file descriptor through binder. No grant outlives the share, and the shell
never holds one. The card's activity holds the share's read permission, so it
must outlive the opening: a tap outside the card does nothing until the files
are open (`share.opened`) — it used to end the activity at once, and the
share failed where nobody saw it — and after that lets the card go while the
transfer carries on. Back or Cancel takes the share back and the card goes:
before hand-over the share
is dropped; after, the server closes what it opened, which ends the `get` in
flight, and tells the computer (`cancelled`), which removes the part and the
reservation (while it is still the empty file it made) and says nothing.

Once a `get` has answered, the connection is the file's bytes: a read that
fails on the phone after that closes the connection short, and never writes
a frame into the stream, which the computer would have kept as more bytes.

## What a link is

Text that is, once trimmed, one `http://` or `https://` URL — or has exactly
one such URL in it and nothing else longer than a title (300 characters). A
word's wrapping and its sentence's end are taken off: `.,;:!?` at the end,
and a closing bracket or quote only when its opener is not in the URL —
`https://en.wikipedia.org/wiki/Rust_(programming_language)` keeps its `)`,
"See https://example.com/a, and more" opens `…/a`. It opens in the default
browser at once, unless "Open links automatically" is off, or a link opened
less than 3 s ago; then the notification offers it. No other scheme is ever
opened: `file:`, `javascript:`, `tel:`, a custom one are text. Opening at
once is what the person just asked for with their thumb; the allowlist, the
pause and the caps (below) are for the one thing that acts by itself. The
notification shows where a link goes — its host, then its path — and never
what sits before an `@` (`accounts.google.com@evil.example` shows
`evil.example`). Words beside the link — a title, "Meet here at 5" — are not
lost: the whole text goes on the clipboard as well, and the notification
says so under the link ("The text that came with it is copied."). Brackets and
punctuation around a link are no words.

Text goes on the clipboard, with the phone's server told it is that text
(`clip_last`), so the clipboard watcher does not hand it straight back. It
says "Copied" only when the clipboard took it.

## Where things land

Downloads, no sub-folder: where a browser puts things. **Never over
anything**: the final name is reserved first by creating it exclusively —
`name`, `name (2)`, … — and so is the hidden `.name.part` beside it: a part
already there is somebody else's, left as it is, and that name is passed
over. The bytes go into the part as it was made, never into a path opened
again. The part is renamed over the reservation only while that is still the
empty file made for it — the same file (its device and inode; on Windows its
volume serial number and file id) and empty; something put there meanwhile is left alone, and
the file lands under the next free name instead (three names are tried before
it is given up). A reservation taken away meanwhile — someone deleting the
empty file they see in Downloads — is made again under its own name, and the
file lands there. On Windows a rename another program holds up for a moment
(an antivirus, an indexer, Explorer at an open folder) is tried again for
about a second first. Giving up removes the part, and the reservation only
while it is still that empty file. A part is known by what it is as it is
closed, every byte in, not as it was made: FAT and exFAT (an external drive,
a card) number a file anew by its first byte, and a part known by its empty
self was never removed there (the final review ran it on both). Between that
look and the rename there is no lock: a write into the reservation in that
instant would be lost.

Free space is checked first: what the share says it comes to (summed
without wrapping) must leave **500 MB** free — less than the 2 GB Save photos leaves free,
since one file is not a backlog. The size a file says is a hint, not a cap:
a pipe may say 0 or too little, and a file may still be growing. So the disk
is asked again every 64 MB as the bytes come, and the file stops and fails,
"There isn't enough room", once it would leave less than 500 MB (it may go up
to 64 MB under before that is seen) — which also stops a source that says
10 bytes and never ends. Fewer bytes than a file's own length (`exact` in
`get`'s answer; not a size the sending app only said) is a file cut short.
Three files shares come in at once; a fourth **waits its turn**, and its
card says "Waiting for IK's M1…" — someone sharing four videos one after
another is ordinary use. What a crash
or a power cut leaves (a part, a reservation still empty) is listed while it
is in flight in `send-landing.json`, beside the settings, and removed at the
next start; nothing that is not listed is touched. The list is written, and
flushed, as something is listed — a name and its part in one write — and
what is done is written with the next thing listed, or by the list's removal
once nothing is in flight: a list that still names it names a file gone or no
longer empty, which the start leaves alone. An empty file at a reservation's
name (an empty file that arrived) is written off at once. Save photos wrote
and flushed it five times a picture; now twice. A file from the phone is
**marked as downloaded** — `com.apple.quarantine` on a Mac, the
`Zone.Identifier` stream on Windows — so the system's own caution applies to
it; never the executable bit. Nothing is opened by itself.

A name is the sending app's, made safe (`apps::fetch::safe_name`, shared with
a file dragged out of a window): no folders, nothing the file system refuses,
no direction marks or invisible characters (`invoice\u{202E}fdp.exe` would
read "invoiceexe.pdf"; the soft hyphen and the word joiner go too, but the
zero-width non-joiner and joiner stay — Persian and Indic names and emoji
need them), never empty however it is cut, trailing dots and spaces dropped
in any mix as
Windows drops them — so the downloaded mark goes to the name the file really
has — and no device name in any of its forms (`con .txt`, `nul.tar.gz`,
`COM¹`, `CONIN$`). What arrives unasked also loses a leading dot
(`arrival_name`): nothing lands hidden.

The notification is this app's own (`notifications::own_toast`): "Received
report.pdf" / "Saved to Downloads." with Show in Finder and Open, "Received 5
files", "Received 3 of 5 files", "Couldn't receive report.pdf" and why,
"Opened a link from Galaxy S24" with Copy link, "Received a link from…" with
Open and Copy link,
"Copied from…" with the first 80 characters. A picture carries the phone's
512 px preview, never the file. Where the system shows none — a Mac
development run, a Linux computer, notifications turned off — the window's
own toast says it, and the Photos tab lists it either way: the last 30
arrivals, files and links across runs (`send-recent.json`), text for this
run only. Each run's numbers for them start at the time in ms, so a
notification left from yesterday never opens today's file.

## The photos are asked for, not announced

The shell's process reads the phone's pictures from the media store, reached
as an external provider as `content query` reaches it; the app's own way
(`ContentResolver.query`) is refused there ("Unable to find app for caller").
Measured on the S24 (Android 15) and on an S25 Ultra (Android 17), with the
same answers:

- **It is not told of a new picture.** `registerContentObserver` succeeds
  and is never called — not for a file written through the file system, nor
  a row inserted, published or deleted through the provider (runs of 30–45 s).
  `MediaStore.getGeneration` is refused.
- So it asks: the newest 200 ids, one query — 2–4 ms warm on the S24 (25 ms
  the first time) — once a second while the Photos tab looks, every 5 s for
  Save photos alone, not at all otherwise. inotify (`FileObserver`) on each
  folder the last look saw is a nudge to ask now: a pushed file's CREATE and
  CLOSE_WRITE arrive before `adb push` returns, a media-store save's
  `.pending-…` file then MOVED_TO of its name.
- A rename (`mv`) keeps the picture's row, still published, its folder and
  name changed at once — seen 73–245 ms after `mv` started.
- `adb push` leaves a row **pending, size 0** until something scans it
  (`MEDIA_SCANNER_SCAN_FILE`, published 173 ms after that broadcast returned).
  Tests do this; so does a person's camera, through the media store.
- What counts: images under `DCIM/` or `Pictures/Screenshots/`, published,
  not empty, no hidden file — the camera and the screenshots of a Samsung
  (`DCIM/Screenshots`) or a Pixel. Not WhatsApp's, not Downloads. **No
  videos**: a minute of 4K is hundreds of megabytes and would make every rule
  below a different feature; the wire's `kind` leaves room for them.
- Newest by when it appeared on the phone (`date_added`), not when it was
  taken: a photo that arrives today is new today. That order reads every
  image row, so from Android 11 a look first asks the media store's
  generation, which moves with every write it makes, and reads the list only
  once it has moved — or every 10 s whatever it says; the snapshot's total
  is counted again only once it has moved.
- Thumbnails are the phone's: its decoders know its formats, a camera JPEG's
  embedded preview makes one in a few ms, and 50 KB crosses where 4 MB would.
  `ThumbnailUtils` keeps the shorter side at the size asked, so a wide picture
  is scaled again to 512 on its long side (the test caught a 640×360). The
  computer holds them for the run only, emptied at start and at exit.
- A copy saved to Downloads from the tab is used again for Open, Save and
  Copy only while it is the picture the phone has, by its size and time: a
  picture edited on the phone keeps its id, and was handed back as it was
  before the edit.

How soon, on the Mac's clock (the clock starts as the command starts on the
Mac, and stops at the app's log line for the picture reaching the page —
an upper bound that includes the command's way to the phone; `adb shell
true` took 38 ms): a picture moved into `Pictures/Screenshots` was on the
page **111–1181 ms** after `mv` started, median 179 ms, over ten (`mv` itself
60–82 ms); a real screenshot (`input keyevent 120`) **1516–1815 ms** after
the key's command started, three times — most of which is the phone making
the screenshot. A camera photo needs a hand on the phone: not measured.

## Save photos

Per phone, off until turned on. The folder defaults to `Pictures/Open
Android DeX/<phone's name>/`, the phone's albums inside (`Camera/`,
`Screenshots/`), each picture under its own name, its modified time set to
when it was taken. **Change…** is the system's folder chooser — written by
hand (`send/pick.rs`): `tauri-plugin-dialog` would have moved other packages
in `Cargo.lock` (web-sys and more), which the plan's rule ruled out.

"New photos only" keeps what appeared after the moment it was turned
on, **by the phone's own clock**, which it says (`status`): no two clocks are
compared. "All photos" is the same from zero. Change… and the
choice between them reach a pass already running: it stops at the next
picture, and the next pass starts from the new folder and the new moment.

**The record is in the folder** (`.open-android-dex.json`), so it survives a
reinstall, moves with the folder, and a folder renamed by hand is found again
(each sub-folder of the chosen place is looked in for this phone's record).
Change… onto the phone's folder itself — moved to another disk, say, and
chosen there — carries on with it, its parent the place: it used to start a
second folder inside it and copy everything again.
The phone is named in it by a hash of its `ro.serialno`: no serial number
sits in a folder another program may sync. A picture is known by its size,
time and name; by its size and time under another name (renamed on the
phone); by the SHA-256 of its bytes (a phone restored, its times changed) —
looked up in an index, not by walking the record per picture. Written
through a temporary file flushed to the disk before the rename (and each
picture flushed before its own), at most every 10 s of a run and at its end;
hidden on Windows as well as by its dot. The temporary file is made anew each
time, never opened through what is at its name: a link left there in a shared
folder would otherwise have the record written into the file it names. One
that is not JSON (a power cut's empty file) is set aside as `.bad` (`.2.bad`
beside an earlier one), never written over, and one that cannot be moved
aside is treated as one that cannot be read; one that is there
and cannot be read (a permission) stops Save photos for that folder —
"Can't use this folder. Choose another one." — instead of starting it again;
one from a newer build is left alone. A record's paths are used only when
they are relative and made of ordinary names: the folder may be on a share
anyone can write to, and those paths are what Open and Show act on.

**Nothing is saved twice**, with or without a record: before a picture is
saved under a new name, a file already in its album folder under its name
(or "name (2)", …) with its size is adopted as kept — by the time Save photos
gives what it saves, without fetching a byte, or, in doubt, by its SHA-256.
By the time only when no entry of the record names that file already: one it
names is a copy of something — an earlier edit of this very picture, say, at
the same length and taken time — and only its bytes may say it is this one.
A record deleted by hand, set aside, or a run killed before its last write
costs no "(2)". A folder that is another phone's gets this phone's own beside
it, once; if that is another phone's too, it waits for Change… ("This folder
is used by another phone") instead of going round. Each picture's part
(`.oadx-incoming-<id>`, `-2` … if that is taken) is made, exclusively, before
the phone is asked for it, so a folder that cannot be written is found out
without asking the phone for every picture of a backlog; it lands as an
arrival does, over its reservation only while that is still empty. Open,
Save and Copy act on a kept copy only if its name is a picture's with the
photo's own extension — a record's entry pointing at `Camera/x.command` is
never opened — and a photo fetched for them always ends in a picture's
extension.

There is no cursor: each time, the phone's list less the record is what is
copied, newest first, one file at a time — a cable pulled half way costs the
file in flight its part. Nothing is deleted, here or on the phone, and a copy
deleted here is not fetched again. A picture this computer could not save (a
name the disk refuses, a folder gone read-only) is skipped for that pass, as
one the phone could not read, so the older ones still come; the status says
how many, and they are tried again a minute later. So is a picture that comes
shorter than the phone said it is with the link still up (the media store's
size was old): before, it counted as the phone failing, and the pass stopped
at it every minute. It **never goes round
without a wait**: up to date, it waits for the phone's word that a picture is
new (or half an hour); held, or after a pass that did not finish clean, a
minute ("Paused. Trying again in a minute.", where "Saving 3 of
193…" used to stay up through it); held for the battery, five. "All N photos saved" counts the kept files that
are in the folder, each once. Free space is asked about once every 15 s or
256 MB of a run, not per picture.

Guards: it waits while the folder's disk has less than 2 GB free, or less
than twice the next file — "Paused: this Mac is almost full", or "Paused: the
disk with this folder is almost full" where that is another disk than the one the home folder
is on. Over Wi‑Fi with the phone not charging and under
20 %, the backlog waits; a picture just taken still comes. On Windows a folder
inside OneDrive is said to be uploaded by OneDrive, beside the status line,
never instead of it. A place chosen with Change… that is not there says "Can't
find this folder." and is never made again here (a drive not
plugged in would get a folder of its name on this disk); the default place in
Pictures is made again, and one that cannot be made says "Can't save to
this folder."

The plan wanted a window's frames measured with and without a backlog over
Wi‑Fi before building any wait for windows; Wi‑Fi debugging is switched off
on this bench, so that was not measured and nothing was built for it.

## Who gets in

Nothing leaves the link between the phone and this computer: adb over the
cable, or wireless debugging's own TLS. The computer listens on nothing; it
dials the phone's server through the adb server. The server takes only the
shell and root on its socket. Port 7190 takes a knock from anyone on the
phone and gives nothing back; what a knock sets off is the server asking the
launcher over binder. The launcher's provider answers only the shell and
root, so another app can neither read a share nor post one through it.

**Any app on the phone can start a share.** That is what a share target is
for, and Android tells a share target nothing reliable about who asked: a
game in front can start the card as easily as the share sheet can. So, said
plainly, what any app on the phone can make this computer do is:

- put a file in Downloads, under a name made safe, never over another file,
  marked as downloaded, announced — and never opened or run;
- put text on the clipboard, in place of what was there;
- with "Open links automatically" on, which is the default, open an `http` or
  `https` link in the browser, at most one every 3 s — the person's session
  in that browser included. Off, a link is only offered.

It will not open or run a file, open any other kind of link, write anywhere
but Downloads, or write over anything. And it is capped, each cap counted in
`send/inbox.rs` and unit-tested: **20 shares a minute** from one phone (past
it the share is turned away, the phone's card says the computer is busy, and
this computer says so once, until a share is let in again), **3 files shares
coming in at once** (a fourth waits its turn), **10 notifications a minute**
(past it, what arrives is still listed on the Photos tab). The card names the computer,
every time. The Linux guest runs as the launcher's uid and can do as much,
which its own Send To does already. A program squatting port 7190 before the
server can only make shares fail ("Your computer didn't answer"); the Photos
tab says another app has it, and the server tries again every 10 s.

## When the launcher is there

The photos need nothing installed: the shell reads them. Only the share
entry needs the launcher, which gets onto the phone in three ways — a desktop
start, the touchpad, and the Photos tab's **Set up** / **Update**
(`adb::ensure_launcher_for_send`, the touchpad's install with its own words,
refused while the phone's Linux runs). The server reads what the phone has
from the package manager, in its own process: `ready`, `old` (no entry, or a
version it no longer speaks) or `missing`.

## The link's life

`send::start` runs a keeper: every 3 s, and at once when woken, each
attached phone's link is up if anything wants it — "Receive files from my
phone" is on, or the Photos tab looks at that phone, or Save photos is on for
it — and down otherwise, the server let go of if nothing else uses it
(`host::release_send`). It starts the server itself if none runs, never over
a cable that has not settled. A phone gone for a moment (adb's "device not
found") is tried again at the next look, not held off for 30 s — the first
run found the link made again only 30.8 s after a dropped transport, and
that was why. A link that ends wakes the keeper; what was on its way says it
was lost. The server keeps running for Send even with notifications off.
With the window hidden or minimised the Photos tab still says it is looking,
and the phone looks every 10 s instead of every second (5 s for Save photos if
that is on). That changes only how often: the phone goes on from what its
last look saw and makes no new snapshot, the list — pages from "Show older
photos" and all — stands, and the Save photos bell rings only for a picture
that is new, never for a snapshot. (In the first round a hidden window
stopped the look, and coming back made a snapshot; with the Mac's screen
locked the page then never listed at all.) A link that comes back after it
dropped asks again for the thumbnails in view, which were on their way when
it went; the tab starts afresh for another phone. The tab's **Try again**
tries now: a server that would not start is tried at the next look rather
than after its 30 s, and a list that failed is asked for afresh, so a whole
new snapshot can make it ready (`photos_watch`'s `again`). The keeper's
"phones changed" compares the attached phones as sets: with two phones it
used to fire every other round on the order of two maps.

## The wire

Framed JSON, as the rest of the app server's wire. A thumbnail is base64 in
JSON. `older`, `all` and `status` carry a number of their own (`ask`), which
their answer — or its `photos {kind:"failed", message}` — gives back: an
answer reaches only the one who asked, and one that comes after its asker
gave up is dropped, never handed to the next asker of its kind. The phone
makes them on a thread of their own (`photos-answers`), never behind a page
of thumbnails.

| Message | Way | What |
| --- | --- | --- |
| `send {v, computer, os, share}` | computer → phone | the link; sent again when the switch changes |
| `send {ok, v, sdk, launcher, entry, port, now}` | phone → computer | the hello, and the answer to every later `send` or `look` |
| `look` | computer → phone | look at the launcher again |
| `shared {share, offer, kind, text?, subject?, items?, unread?, total?, preview?}` | phone → computer | something was sent; `unread`, items the phone could not open |
| `cancelled {share}` | phone → computer | …and taken back |
| `sendDone {share, ok, did, landed, failed, message?}` | computer → phone | `did`: `saved`, `opened`, `offered`, `clipboard`; `message`: `space`, `stopped`, `lost`, `unreadable`, `busy` (a cap), `clipboard`; from the phone itself, `stuck` |
| `sendWait {share}` | computer → phone | three files shares are coming in: this one waits its turn (the card says so, `share.wait`) |
| `photos {on, limit, every}` | computer → phone | look every `every` ms, or stop |
| `photos {kind:"snapshot", items, more, total:{count, bytes}, now}` | phone → computer | what there is, newest first |
| `photos {kind:"new", items}` / `{kind:"gone", ids}` | phone → computer | since the last look; an id sent as new again has changed |
| `older {before, limit}` → `photos {kind:"older", items, more}` | both | "Show older photos" |
| `all {since, after?, limit}` → `photos {kind:"all", items, next?}` | both | the Save photos list, oldest first, a page at a time |
| `thumbs {ids}` → `thumb {id, modified, jpeg}` or `{id, failed}` | both | thumbnails, one message each |
| `status` → `status {battery, charging, now}` | both | the battery guard, the phone's clock |

A picture's original is a connection of its own: `photo {id, size,
modified, offset}`, answered `photo {ok, name, size, offset, mime}` and the
bytes from `offset`, raw — or `{ok:false, why: gone|changed|unreadable}`. A
share's file is a `get {id, i}`, answered `get {ok, name, mime, size,
exact}` and the bytes in pieces (`0x04`, in `Wire.java` and `apps/wire.rs`,
`Pieces`), then an empty piece: the end mark. A connection that ends before
it is a file cut short, with or without a size — never one kept as whole.
`exact`, whether `size` is the file's own length or only what the sending
app said. A drag's and a clip's files (`apps/fetch.rs`) come the same way.

The knock (`127.0.0.1:7190`): one byte — `P` anyone there, `S` something
waits, `C` something changed — then the server closes.

## Verified, and not yet

On a Galaxy S24 (SM-S921B, Android 15, One UI 7) on the cable, from a Mac
(M1 Pro), 2026-11-11/12. **Not run over Wi‑Fi**: wireless debugging is
switched off on this bench.

- `node test/send-test.mjs RFCY90YWS1P`: 42 of 42 after the second round of
  fixes (40 of 40 twice after the first), 2026-11-12. The second round's two:
  a file that says 1000 bytes and is 300,000, through a pipe — its size
  passed on as a hint (`exact: false`), every byte of it taken; and a file
  whose provider holds its open for 56 s — the launcher let go at 50 s, the
  share ended "unreadable", the open that came back later was closed, and a
  share made meanwhile arrived in 100 ms. The rest — the photos half (hello, a link replaced, snapshot, a
  moved picture new 177–268 ms after `mv` started, a 512×288 thumbnail, a
  pushed file out until scanned, an original byte for byte and from the
  middle, `changed`, `gone`, removed → gone, `all` and `older` paging,
  `status`, looking stopped) and the share half (the entry on and back, text,
  the throwaway app's one, three, empty, hostile-name and 64 MB files byte for
  byte; a file that says 1000 bytes and gives 300,000 — the computer is told
  1000; `EXTRA_STREAM` as a list of Strings and as an Intent — the launcher's
  process the same before and after, the card "nothing here to send"; a link
  with a picture in `ClipData` only — sent as the link; a tap outside the card
  before its files were open — the card stayed and the share arrived; 3000
  knocks and 100 silent connections from the test app in 3.9 s, then a share
  shared in 103–111 ms against 92–94 ms just before them, measured the same
  way; taken back on the card, a link dropped half way → the card's "lost",
  no computer, turned off, the entry as found).
- In the app (`tauri dev`): the link in 4–135 ms; text → the Mac's clipboard;
  a link opened (one tab on example.com, once) and a second within 3 s
  offered; a picture shared by URI in Downloads 0.3 s after the command,
  byte for byte, again as `(2)`, quarantined; three files at once; 300 MB in
  10.0 s and 1 GB in 34.6 s (about 31 MB/s over USB) byte for byte; Cancel on
  the card mid-way through 1 GB: nothing left in Downloads; the card on the
  phone and on the phone's desktop; "No computer is connected".
- Save photos, "All photos": 41 of 41 into a scratch folder, no part left,
  five picked at random identical to the phone's (SHA-256); the app quit and
  started again: nothing copied twice; a rename on the phone copied nothing; a
  copy deleted here was not fetched again. After the fixes: six into a
  scratch folder ("New photos only"), the record then deleted by hand and a
  seventh picture taken — the six adopted by their time, the seventh copied,
  no "(2)", seven in the new record.
- After the first round's fixes, in the app: a file named
  `oadx-inv\u{202E}fdp.exe. .` (a right-to-left mark in it) landed as
  `oadx-invfdp.exe`, quarantined; a file that said 1000 bytes and gave
  300,000 was then stopped at 1000 (that hard cap is gone since: the size is
  a hint, and the disk decides); 24 shares 0.8
  s apart, right after those two: 18 let in — 20 in that minute — and 6
  turned away, each card told "busy"; nothing left of
  `send-landing.json` after a clean quit. With "Receive files from my phone" off
  in send.json and the phone in `entryOn`, a start linked, the entry went off
  (hello `entry off`), `entryOn` emptied and the link let go in 1 ms.
- After the second round, in the app: five 100 MB shares one after another
  — three came in at once, the fourth and fifth waited their turn (both
  cards told so), all five landed, the two that waited byte for byte; the
  300,000-byte file that said 1000 landed whole; with the Mac's screen locked
  the Photos tab looked every 10 s and its list was there.
- The Mac's own notifications, from the app bundle, read back from the
  Mac's notification database: "Received oadx-five.jpg" / "Saved to
  Downloads." with the picture attached, "Received a link from Galaxy S24" /
  `example.com/oadx-offered`, "Copied from Galaxy S24" / the text. Quitting
  the bundle stopped the phone's server and put the phone back.
- The Save photos disk guard (`OADX_KEEP_FLOOR` above the free space): "Paused:
  this Mac is almost full.", and the new picture waited.
- The page, in a preview with mocked commands (Chrome, 720×640, 720×1500,
  600×460: many, selected, empty, loading, failed, Save photos saving, no
  launcher, an arrival, one coming in).
- Windows: type-checked only (`cargo check --target x86_64-pc-windows-msvc`).
- **After an outside audit** (2026-11-12, after the two reviews), fixed, the
  computer's half unit-tested (336 Rust tests), none of it run on the phone
  or in the app yet:
  - The reservation, which the README's "nothing is ever written over" rests
    on: a part already at `.name.part` was truncated and written into; a
    reservation filled meanwhile was renamed over, and giving up removed it;
    a rename that failed deleted what came. Now as "Where things land" says,
    and tested: someone's `.photo.jpg.part` untouched and the arrival in
    `photo (2).jpg`; a reservation written into (and, on a Mac, one swapped
    for another empty file) kept, the arrival beside it; giving up leaving
    what was put at the name. The same pattern elsewhere, each now made
    exclusively and landed as an arrival is: Save to Downloads (it copied
    with truncate into the part), an original fetched into the temporary
    folder and the Save photos incoming picture (both truncated their part and
    renamed over the name they wanted), the record's temporary file (opened
    with truncate, through any link at its name) and a second record set
    aside (it could replace an earlier `.bad`, and a set-aside that failed
    let the new record be written over the old). Export of game controls
    into Downloads likewise makes its file. Not changed, said below: the
    drag's own path in `apps/fetch.rs`.
  - `release_send` asks the notifications under the lock `retire` takes, so
    notifications turned off a moment before no longer leave the server
    running until exit.
  - The phone server's take lets the launcher's provider go (later, as after
    a take) on its refuse and error paths too.
  - A share's offer is the share's: dropped as it ends (done, taken back,
    lost) and never closed for its age, however long it waits its turn on the
    computer (`AppOffers.putHeld`). The phone's two built (`npm run apk`), not
    run.
  - Run on the phone after it: the send test 42 of 42, and a combined run 18
    of 18 with a file already at a part's name left alone and the arrival
    under the next name.
- **After a final review** (2026-11-12), fixed, unit-tested (345 Rust tests),
  none run on the phone or in the app:
  - On FAT and exFAT a part was never removed (a cut-short Keep picture's
    `.oadx-incoming-…`, a given-up `.name.part`): it was known by its number
    as an empty file. It is now known by what it is as it is closed. A test
    of that rule runs anywhere; run here with `TMPDIR` on scratch exFAT and
    FAT32 images, it failed before the change (both parts left) and passes
    after. The other landing tests there pass too, but for two whose counts
    include macOS's own `._` files on those volumes.
  - A reservation taken away meanwhile (deleted in Downloads) was made again
    and then removed by the old one's identity where a new file may be given
    it (NTFS, ext4), three times, and the arrival with it. Now it lands under
    its name; tested with the identity unknown, which is that case.
  - Windows: a rename held up by another program is tried again for about a
    second before another name (type-checked only).
  - The in-flight list, a copy saved before an edit on the phone, Change…
    onto the phone's own folder, a short picture, Try again, the phones
    compared as sets, and three status lines: as said above.

- **The known issues, fixed** (2026-11-12, after the final review: what
  "Not yet" listed as left on purpose), the computer's half unit-tested (358
  Rust tests), the phone's rules checked by a scratch run of their statics on
  the Mac's JDK, `npm run apk` built; none run on the phone or in the app yet:
  - A share's offer is never closed for the offers after it: the 32 kept are
    drags' and clips' only (`AppOffers.closes`), so thirty-two of them while a
    long share waits no longer cut it.
  - Answers on the link carry their asking's number (`ask`, "The wire") and
    come on a phone thread of their own: `all`, `status` and `older` no longer
    wait behind thumbnails, a late answer is dropped instead of reaching the
    next asker, and a page the phone could not read is its asker's error at
    once ("Couldn't load older photos"), not a 20 s wait and a failed tab.
  - "Never our own" compares the authority without a user id in front
    (`SendActivity.ours`): `content://0@<launcher authority>/…` is ours too.
  - Originals fetched for Open, Save and Copy go into this app's own cache
    (`send/originals/`, beside the thumbnails), never the shared temporary
    folder, and go with the thumbnails at exit and at the next start; Save's
    is removed once copied. A Copy pasted after this app has quit finds
    nothing.
  - `get` has an end mark ("The wire"): a file cut short is never taken for
    whole, with or without a size. Both ends, and the send test's reader.
  - Words around one web address go on the clipboard, the whole text, as the
    link opens or is offered, and the notification says so ("What a link
    is").
  - Windows' "same file" check is the volume serial number and file id
    (`GetFileInformationByHandleEx`, FileIdInfo; the 64-bit index where that
    is refused, as on FAT), not the creation time (type-checked only).
  - Parts and `.oadx-incoming-…` are hidden on Windows as well as by their
    dot, and the mark is taken off as one lands under its name
    (type-checked only).
  - "Waiting for <phone> to connect." no longer shows after Save photos is switched on
    again: a run the switch stopped says nothing of a phone to wait for, and
    switching on with the phone there starts a run at once.
  - The Save photos switch reaches the phone, not its `ip:port`, when a phone
    on Wi‑Fi drops as it is pressed: each way seen in a run is remembered
    with its phone (`phone_by`). The uhid failure is kept per phone
    (`ro.serialno`) too (doc/game-mode.md).
  - A drag carried out of a window (`apps/fetch.rs`) lands as an arrival
    does: its name and part made exclusively, never a file that was there
    truncated or renamed over — another of the drag's files, or one put in
    Downloads meanwhile.
  - The once-a-second look reads the list only when the media store's
    generation has moved (Android 11 and later), or every 10 s; the total is
    counted again only when it has moved. Not measured on a large library.

- **On the S24 over Wi‑Fi, and the merge** (2026-11-12 afternoon), the S24's
  own address and Wireless debugging port read off the phone over the cable:
  - Before the merge, the phone's half over its Wi‑Fi serial: 41 of 42 once
    (the card went at the tap outside it, though the share arrived and the
    card heard "done: saved"; not seen again), then **42 passed, 0 failed**.
  - In the app with the S24 on its cable and on Wi‑Fi, a 200 MB picture shared
    and the cable's transport dropped 1.7 s in (about 50 MB there): the share
    was lost and both ends said so ("The connection to your phone was lost.
    Send it again from your phone." / "not delivered (lost)"), its part was
    removed, and the link was made again over Wi‑Fi 3.1 s after the drop. Sent
    again, it landed over Wi‑Fi byte for byte in 53.7 s.
  - On Wi‑Fi alone (a second adb server that leaves USB alone): linked in
    54 ms, a 5.3 MB picture in 2.4 s, and one shared from the phone's Gallery
    through the share sheet in 1.5 s.
  - The app's own move to Wi‑Fi tried the file service's `adb reverse` port
    7192 as Wireless debugging's (adbd listens for a reverse on every
    address): a 10 s hang, an offline transport left behind, and a wrong "only
    once" message. Fixed (`wireless.rs`, the reversed ports left out); the
    link then came 0.6 s after the switch.
  - After the merge with Game mode's fixes (`get` in pieces with its end mark,
    `ask` numbers): the launcher installed with `-r`; the phone's half **42
    passed, 0 failed** on the cable and **42 passed, 0 failed** over Wi‑Fi;
    the combined run in the app 18 of 18 on its second try; 359 Rust tests,
    `tsc` and the Windows check clean.

Not yet:

- From the review, left as they are, each said here:
  - The link's life: the server starts for Send on every attached phone
    (shares default on) about 3 s after the app starts or 10 s after a cable
    goes in, and stays while anything wants it; no wake lock. A computer that
    vanishes over Wi‑Fi without closing leaves it until adbd notices — the
    existing lease.
  - The launcher opens any content URI under its own all-files access: it
    only moves the person's own files to their own computer.
  - Two computers: the first server holds 7190 and its switch answers for
    both; the second says the port is not its own and tries again every 10 s,
    and never shares it. Sharing the port is a design of its own, not a fix.
  - The media provider is held for the server's life: from Android 11 it
    runs shared storage's FUSE daemon and is up whatever holds it, and
    holding it saves a binder round per look.
  - Shortcut types (`.lnk`, `.url`, `.library-ms`, `.desktop`, `.terminal`)
    land under their own names; the downloaded mark is what guards them, as
    it guards a browser's download.
- The fixes not run here: a Windows run of the downloaded mark at a name that
  ended in dots, the hidden record and the chooser's owner window
  (type-checked only); kdialog after a zenity that cannot open the display
  (no Linux computer); three opens stuck at once and the share refused for it
  (one stuck open is run by the test, below); a fourth files share waiting
  its turn, in the app (unit-tested); a disk filling under the 500 MB floor
  while a file comes (unit-tested); the Photos tab asking again for
  thumbnails after a dropped link, and its slower look while hidden (the
  window could not be looked at).

- The real window's Photos tab, looked at: the Mac's front Space belonged to
  another app throughout, and a window out of sight draws nothing to capture.
- The notifications' buttons pressed (the Mac's Accessibility was not
  available to the test); a link opened from one; the window's own toast.
- "Receive files from my phone" turned off while running (the server let go),
  and the link with one app in a window open: the switch and the Apps tab
  were not pressed. With the desktop open, and with nothing open, the link
  was up.
- **Wi‑Fi**, the rest: the battery guard over Wi‑Fi, a window's frames with a
  backlog running.
- A share in flight does not carry on when the cable drops, even with Wi‑Fi
  up: by design it is lost, both ends say so, and it must be sent again.
- A **real pull** of the cable and Samsung's adbd restart after a plug-in:
  `adb reconnect` stood in, by the test and in the app (above).
- The one 41 of 42 over Wi‑Fi (the card going at a tap outside it) was not
  explained; the three runs since passed.
- The **restricted standby bucket** (A1): on the S24 the launcher stays
  exempt (bucket 5) while the desktop's deploy has it whitelisted; the knock
  worked with the battery "unplugged".
- The SHA-256 path of Save photos in the app (a picture whose name and time
  changed but whose bytes did not): unit-tested only — `adb push` keeps a
  file's time, so the same bytes pushed again were known by size and time.
- A camera photo; another maker's phone; Android 8–10 (the query's other
  branch); two computers on one phone; a work profile.
- A Linux computer (its notification and folder-chooser branches are a few
  lines each, read, not run); Windows run. Neither can run here: the Mac's one
  Windows VM (Parallels) no longer starts, its activation expired, and CI
  builds only when started by hand and runs no tests on any system.
- Dragging a photo out of the tab (M7): not built — a photo is saved or
  copied instead.
