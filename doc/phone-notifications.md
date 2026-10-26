# The phone's notifications, as this computer's own

A notification that would have interrupted the phone's owner comes up on the
computer the phone is attached to, as one of the computer's own: on Windows a
toast under the phone app's own name and icon — "WhatsApp", stacked under
WhatsApp in the notification center — on a Mac a banner. It has the sender's
picture and what they said, a reply box where the app takes a reply, the app's
own buttons ("Mark as read"), and a call comes up as a call, ringing, with
Answer and Decline. A click opens the app where the person is working: in a
window of its own on the computer, at the conversation — or on the phone's
desktop, if that is running. Read or dismissed on the phone, it goes from the
computer too.

It is on until turned off, from a bell in the title bar of this app's window,
whose panel is the one place it is controlled: the switch, whether a toast
says what the notification says, whether calls ring here, the sound, whether
dismissing one here clears it on the phone, and each app's own switch.

This record exists for the reason the others do: several decisions below look
arbitrary until you know what was measured, or what the platform refuses.

## Shape

| Piece | File | What it owns |
| --- | --- | --- |
| The bell | `src/features/notifications/NotificationsBell.tsx`, `src/ui/kit.tsx` (`Popover`, `Switch`) | the title bar's bell, its panel, "Send a test" |
| The Apps page's badges | `src/features/apps/AppsPanel.tsx` | how many notifications each app has on the phone |
| What to show, and when | `src-tauri/src/notifications/mod.rs` | the settings (`notifications.json`), the keeper, the rules, what a click asks of the phone |
| A toast | `notifications/toast.rs` | one toast as plain data, and Windows' XML of it |
| Windows | `notifications/windows.rs` | WinRT's toast notifier, each phone app's identity |
| Mac | `notifications/macos.rs` | UNUserNotificationCenter, its categories and delegate |
| Elsewhere | `notifications/unsupported.rs` | a Linux computer: not yet, and the bell is not shown |
| The phone's half | `openandroiddex-wmd/…/AppNotifications.java`, `AppServer.java` | listening, what is news, firing the apps' own intents |
| A window opened at a notification | `AppWindow.java` (`notif`), `apps/link.rs`, `apps/mod.rs` (`open_at_notification`) | the app started at its notification, in the window ("A click") |
| The test | `test/notifications-test.mjs` | the phone's half, over the wire |

## Reading them: the shell listens

An app reads a phone's notifications by being a NotificationListenerService
the owner has granted access to, bound by the system. The launcher is one
(`DexNotifications`, the desktop's bell), but this app grants it access only
for a desktop session and takes the grant back on exit — it is a setting of
the person's, in a list their smartwatch's companion app lives in too — and
with the phone's apps in windows of their own, the launcher need not be
running at all.

So the phone's app server, which is the shell, listens itself:
`NotificationListenerService.registerAsSystemService`, the call SystemUI's own
listeners make. It asks for `STATUS_BAR_SERVICE`, which the shell holds —
on a Galaxy A13 (One UI, Android 12), a Redmi 9A (MIUI, Android 11) and the
Android 16 emulator, checked by a throwaway listener in `app_process` that
was handed the active list with the phone's own rankings, a notification as
the shell posted it, and dismissed it from the listener. Nothing on the phone
is granted or changed, and the registration dies with the process.

It listens only for the control connection that asked (`notifs`), and stops
the moment that connection goes or another takes its place: a computer back
on a new connection asks again on it. So a computer that turned them off
while the phone was out of reach — never able to say so — is not read for,
nor is a server this computer lost track of (its control connection could
not be made again while a window still held it). Its callbacks run on a
thread of their own: an avatar to scale and encode must never hold up the
server's main looper, which the encoders' callbacks share.

## What is news

The phone's own answer, not a policy of ours. A notification the phone put
silently in its shade is not news here either:

- its importance below default (a download, media, "USB debugging"), or Do
  Not Disturb holding it back, with the exceptions the owner allowed;
- an update the app marked "alert once";
- one whose app is suspended.

Then the desktop's repost rule (the launcher's `DexNotifications`): what a
notification *says* is compared, so a chat app rewriting its notification to
show someone typing is not a second alert and a second message is. And a few
of this feature's own:

- everything on the phone when listening starts is seen, not news;
- an update within 1.5 s of the same notification's last alert is an update;
- after a reply from the computer, the app's own update (it adds the reply to
  its conversation) is not news for 10 s, and neither is a conversation whose
  newest message is the owner's own (MessagingStyle's rule: no sender, or
  the "self" person);
- ongoing notifications (music, navigation, a VPN) are state, not news, and
  are not sent at all; group summaries are not sent, their children are; the
  launcher's own are about a desktop or a Linux the computer already shows.

A ringing call is a message of its own (`call`): CallStyle says which kind of
call it is (`android.callType`: incoming rings; ongoing and screening do
not), and for a dialer that predates that, an answer intent in the extras or
a call's full-screen intent rings, as the desktop decides it. Each call
ringing is "ended" on its own when it stops — a cellular call and a VoIP
call can ring at once. What answers it goes with it: CallStyle's own answer
and decline, said as such (`answer`, `decline`); a dialer from before
CallStyle has only its buttons, in an order of its own — AOSP's Dialer, on
the Android 12 emulator, posts Decline first and Answer second — so they go
as they are, each by its index, in its own words and the colour it gave them
(`tint`: its label's `ForegroundColorSpan`, #a52714 and #097138 there).
Nothing on either side takes a button for "answer" by its place: on that
dialer, the second button answers.

What reaches the computer is a description, not the notification: the app,
the title, the text (a conversation as its last three messages, each with who
said it when it is a group; an inbox as its lines; a long text whole), when,
its buttons by their index, which one takes a reply and its hint, and — only
with an alert — the sender's picture (128 px PNG) and a photo it carries
(JPEG, 720 px at most).

## What a toast says

**Windows.** Each phone app is a sender of its own: an AppUserModelID named
after the app, with its icon, in `HKCU\Software\Classes\AppUserModelId` —
where Windows reads an unpackaged app's name and icon from when no Start-menu
shortcut says. It is the identity the app's own windows here already have on
the taskbar (`apps/win`, `app_user_model_id`), so WhatsApp's toasts say
"WhatsApp" at the top, stack under it in the notification center, and
Windows' own settings can turn WhatsApp off by itself — which the panel reads
and shows ("Off in Windows settings"). A registry-only identity was checked
on Windows 11 (build 26200) before any of this was built: the toast came up
with that name, that icon, its reply box and its button. This app's own
toasts (the introduction, a test) are under its own identity, the one its
Start-menu shortcut is made with.

The sender's picture is the round logo, a photo goes inline, the phone's
name is the attribution line, and the reply box has a Send button. Up to
three of the app's own buttons follow, and "Turn off for WhatsApp" is in the
toast's right-click menu: at most five actions, the menu giving way first. A
call is Windows' incoming-call toast — it stays up, rings, and its Answer and
Decline are green and red on Windows 11; a dialer from before CallStyle has
its own buttons there instead, in its order and words, green or red as it
coloured them (a hue near green or red; anything else plain). A call's toast
has no "Turn off for …": calls have their own switch on the panel — switched
off, a call ringing goes at once — and a dialer turned off in the app list
rings here no more either. Every toast expires with a restart
(what the phone said yesterday is not news), and its pictures are files named
by what is in them: Windows keeps a picture by its path, and showed a new one
under an old name as the old.

**Mac.** A Mac shows a notification under the app that posts it — this one —
so the phone app's name leads the subtitle ("WhatsApp · Galaxy A13"), the
sender's picture (else the photo, else the app's icon) is the thumbnail, and
each app's notifications are a thread of their own. A Mac declares a
notification's buttons up front, by category, so each set an app uses is a
category, made the first time it is seen. A banner is shown while this app is
in front too (`willPresentNotification`): its windows are the phone's apps.
The center needs the app bundle — `tauri dev` runs a bare binary, and the
panel says notifications come from the installed app — and asks once whether
this app may notify, the first time a phone listens.

**With "Show what they say" off**, a toast says only which app has something:
"Messages — New message", a call "Phone — Incoming call". No sender, no
picture, no reply box, and none of the app's own buttons — a button can carry
the message ("Copy 123456", as Messages offers for a code) — only "Turn off
for …". A ringing call keeps what answers it: those buttons are the call's.
Switched off, it redraws every toast already up the same way, at once, where
the switch was flipped — not behind whatever the phone said meanwhile —
where it is, without bringing it up again (a call still ringing comes up
again, as the call it is), deletes their pictures, and takes down a note
naming whom a reply was for. A toast on its way as the switch flips is made
as it goes up, so it comes up without them too (see "What is up"). The
phone's own log names a button by its index, never by what it says.

## A click

Where the person is working, in this order:

1. **The phone's desktop**, if one is running for the phone: the app opens
   there, a freeform window, and the desktop's window comes to the front.
2. **A window of its own here** (Windows and Mac): the app's window if one is
   open — brought forward, and the phone told to open the notification on its
   display (`notifOpen` with the window's key) — else a new window that opens
   *at* the notification (`open` with `notif`).
3. **The phone's screen**, as a tap there would.

What opens is the notification's own content intent, fired by the shell. An
app's intent is the app's to start, though, and what Android lets the shell
do with one changed with nearly every version — each found on its emulator
by checking which conversation Messages then had read:

- **Android 16**: straight onto the window's display. The shell may put an
  activity on any display (`INTERNAL_SYSTEM_WINDOW`) and start one from the
  background (`START_ACTIVITIES_FROM_BACKGROUND`), but an app's intent sent
  by another process starts from the background only on the sender's say-so
  from 14, and from 16 "allowed" lends only the sender's being visible,
  which the shell never is: its permission counts only with "allow always".
  Without it the emulator refused a cold start of Messages ("Background
  activity launch blocked") and, where Messages was running, moved its task
  silently. With it: `BAL_ALLOW_PERMISSION`, the launch 19–43 ms.
- **Android 12–15**: the app is started in the window first, as any app is
  — by the shell, which may — and then its notification's intent fired with
  no display: the app on screen there now, its own intent may start an
  activity, and the activity joins the app's task, in the window. Straight
  onto the display cannot work there. Android 12's shell can make only an
  untrusted display (doc/apps-on-pc.md), and an untrusted display takes no
  activity another app asks onto it. 13 aborts the intent while the app is
  nowhere on screen ("Abort background activity starts"), and 15 blocks it
  under "allowed" too (`BAL_BLOCK`) — yet moves a task the app already has
  onto the display, without the conversation, which looked like the
  conversation arriving. The launch 72–181 ms, measured on the emulators.

On 16, an app that keeps the conversation in its task on the phone's screen
whatever display it is asked for — nothing of its own on the window's
display in 1.5 s — is opened the second way. A click with the app's window
already open fires the intent onto that window's display (the app being on
screen there, any version lets its intent start), or — an untrusted one —
with no display, into the app's task in that window. A content intent that
is no activity (a broadcast that starts one) is fired the second way too.
Tapping a notification dismisses it when its app says so ("auto cancel"):
here too.

Opening on the phone's own screen (when no window can be opened here), and
on the phone's desktop, still fires the intent straight: on 12–15 Android
blocks that while the app is nowhere on screen.

**A reply** goes into the button's own RemoteInput, as the phone's shade sends
one, and **a button** is fired by its index only if it still says what it
said when the toast went up — a notification can be rewritten under its toast,
and "Mark as read" must not become "Delete". An intent that is not an
activity (a reply's broadcast, a service that marks a message read) has its
app let out of its background limits for 10 s first (`cmd deviceidle
tempwhitelist`): the system's own shade has its intents carry an allowlist
token, but the grace that token gives is, as the activity manager's source
reads, granted only to a sender it has a process record for — which
`app_process` is not.
**A call** is answered or declined on the phone, where a call is taken: by
CallStyle's own intents, or — a dialer from before CallStyle — by the button
pressed, by its index, if it still says what it said. Never by a button's
place: AOSP's Dialer puts Decline before Answer.

**Dismissing** a toast dismisses the notification on the phone only when the
person asked for that ("Clear them on my phone too", off by default): the
phone is where they live, and closing a toast to get it out of the way is not
"I've dealt with it".

## Tact

A notification that is news is still not shown here when:

- its app is turned off on the panel, or in Windows' own settings;
- **its app's window here is the one in front** — the person is looking at
  it, as a chat app does not notify about the conversation on screen;
- **the phone's desktop is the window in front** — it shows its own, in its
  corner and its bell (a call too: its banner).

## In sync

Read on the phone, dismissed there, answered: the toast goes from here
(`removed`), even one this run has stopped counting as up. A computer that
asks — on its first control connection, or on one made again (the cable
pulled, Wi‑Fi still there), where it asks again at once — is sent everything
the phone has (`snapshot`), and toasts whose notification went meanwhile go,
a call that stopped ringing too. On a new connection the phone says `hello`
before anything else, and only then sends notifications that way: the
computer takes the first message on a connection for the hello, and a
notification written ahead of it was lost. What an earlier run left in the
notification center is cleared at start — nothing can act on it — and this
app's toasts go when it exits, or when notifications are turned off; the
pictures they showed go with them.

## What is up

A toast goes up on the notifications' own thread, after what it needs is
fetched (the app's icon, from the phone the first time, can take seconds);
the switches flip on the panel's. So what is up changes under one lock —
this run's record of it and the system's toasts together — and a toast is
made from what the person has chosen *as it goes up*, not as its
notification arrived: the switch, the app's own, calls, and what it may say.
A toast on its way when notifications, its app or calls are switched off does
not go up; one on its way when "Show what they say" goes off goes up without
it; one taken down — read on the phone, say — is not brought back by a
redraw. A Mac posts a banner whose buttons are new only once the center has
its categories, later and on a thread of its own: it goes then only if it is
still the newest asked for under its id, not after its notification went,
nor over the one redrawn without what it says. When this app exits,
nothing goes up after its toasts are cleared, no picture is written after
their folder went, the keeper stops, and no phone's server starts after
they were all stopped.

## The keeper

Every few seconds, and at once when the switch changes: each attached phone
— one phone whichever ways it is attached, over the cable once that has
settled, else Wi‑Fi — has its app server listening while the switch is on
and this computer would show them, and none otherwise. Windows with
notifications off for everything (or by the organisation's policy), or a Mac
with them off for this app, shows none, so no phone reads them for it; with
them off for this app alone, Windows still shows the phone apps', which are
senders of their own.

- A server is started for it if none runs, but never over a cable that has
  not settled: a Samsung phone restarts adbd about 5 s after the plug goes
  in, taking everything adb started with it.
- It is started without the warm-up a first window wants (`--no-warm-up`):
  on a slow phone that would sit beside a desktop that may be starting. The
  Apps page asks for it (`warm`) when it is looked at.
- A server that would not start, or a phone that would not listen, is tried
  again after 30 s — not on every look — and the panel says why meanwhile,
  the refusal staying there through each try rather than blinking to
  "Connecting".
- It is asked on each control connection, by which one it is (the server's
  socket and the connection's number): the phone listens for that one alone,
  so one made again — by the keeper, or the Apps page, which wakes it — is
  asked again at once.
- An ask with no answer in 10 s is made again: it, or its answer, went
  missing. A phone asked while it listens says so and sends everything
  again; and a snapshot is as good as an answer, should only the answer
  have gone missing.
- Turned off, a phone is told to stop, and counted as stopped only once that
  is said, or once the connection it listened for has gone — it stopped with
  that — so one out of reach for a moment is told when it is back. Whatever a
  phone still sends while they are off is dropped, and the phone told again.
  A server started for notifications alone stops with them; one the Apps
  page or a window has used keeps running, as it did before.

A server started for notifications alone does not bring the phone's
clipboard to this computer: that starts, as it always has, once the Apps
page or a window has used the server.

## The bell

In the title bar, beside Help, on every tab — notifications are not one
tab's. Struck through when they are off; a green dot while a phone's are
coming through, an amber one when something stands in the way (the system's
notifications turned off for this app, a Mac that has not asked, a phone
that refused); it rings once as one arrives.

Its panel: the switch, and under it a live line ("On this PC, from Galaxy
A13", "Connecting to …", "Waiting for your phone", "This PC can't show them
right now"); what stands in the way,
with the way out (Windows' notification settings, System Settings, macOS's
question); the four options; then every app that has notified, or has
notifications on an attached phone — most recent first, with its icon
(fetched once from the phone for one the Apps page has not listed), when it
last did ("2 min ago", "1 on your phone") and its own switch, with a search
once there are more than six. "Send a test" has each listening phone post a
notification as the shell, which comes back here as this app's own toast:
the whole way, phone to toast, shown working.

The panel is a popover that stays open while it is used, and closes on a
click outside it or Escape — not on the window losing focus, as menus here
do: a switch pressed by a screen reader (UI Automation's Toggle) takes the
window's focus away and back, and the panel closed under the press, which
never landed. The switches are `role="switch"`.

The Apps page shows, on each app's icon, how many notifications it has on the
phone, as a launcher does — while notifications come here.

The first phone to listen is introduced with a toast of this app's own: what
this is, "Settings" (the panel) and "Turn off".

## The wire

On the app server's control connection (`doc/apps-on-pc.md`, "The wire"):

| Message | Way | What |
| --- | --- | --- |
| `notifs {on}` | computer → phone | listen for this connection, or stop; asked again on it while it listens, everything again (`snapshot`); asked on a connection another has replaced, refused |
| `notifs {on, ok, message}` | phone → computer | whether it may |
| `notif {kind: "snapshot", items, calls}` | phone → computer | everything it has now, none of it news, and every call ringing |
| `notif {kind: "posted", key, pkg, app, title, text, when, category, conversation, clearable, open, alert, actions, avatar?, picture?, test?}` | phone → computer | one notification, new or changed; `alert` says whether it is news |
| `notif {kind: "removed", key}` | phone → computer | gone from the phone |
| `notif {kind: "call", call}` / `{kind: "callEnded", key}` | phone → computer | a call ringing — `call` with `answer` and `decline` (CallStyle's), or the dialer's own `actions` with their `tint` — and no longer |
| `notifOpen {key, window? \| display?, freeform}` | computer → phone | open it: in a window (its key), on a display (a desktop's), or on the phone |
| `notifAction {key, i, label, window? \| display?}` | computer → phone | one of its buttons, if it still says `label` |
| `notifReply {key, i, label, text}` | computer → phone | a reply into button `i`'s RemoteInput |
| `notifDismiss {key}` | computer → phone | dismissed here |
| `notifCall {key, answer}` / `{key, i, label}` | computer → phone | a call answered or declined by CallStyle's own; or the dialer's button `i`, if it still says `label` |
| `notifTest {title, text}` | computer → phone | post a test notification as the shell |
| `notifDone {what, key, ok, message?}` | phone → computer | how one of those went |
| `warm` | computer → phone | the warm-up, if it has not run |

A window's `open` takes `notif`, a notification's key: its app is started at
that notification, in the window ("A click").

## Verified, and not yet

The phone's half, `node test/notifications-test.mjs [serial]` (2026-10-26),
on the dex as it is now: 53 checks on the emulators of Android 12 (the Pixel
6 image, whose phone is AOSP's Dialer, from before CallStyle), 13, 15 and 16,
each AVD started `-read-only` — what a click, a reply, a button and a call
did read back from the phone itself (see test/README.md): the conversation
opened in the window, read, another not, nothing of Messages showing on the
phone's screen; the reply in its sent messages; the call answered (the phone
in it, the call log's "answered") and declined (the call log's "declined") —
on Android 12 by AOSP's own buttons, which it posts as `0: Decline`
(#a52714) and `1: Answer` (#097138). And 26 on a Galaxy A13 (Android 12) and
a Redmi 9A (Android 11), where the shell may not post a notification with a
content intent and there is no app to text. There is no Android 14 image
here.

The opening of a notification in a window was found failing on 12, 13 and
15 by those checks — Messages' front screen in the window, the conversation
nowhere (12: an untrusted display; 13: the start aborted; 15: blocked, and
an old task moved over in its place) — which is why it opens as "A click"
says now. A check that only asked whether Messages was on the window's
display had passed all three.

The Rust: 181 unit tests (16 of them this feature's: the toasts, both kinds
of call, colours, privacy, the keeper's timing, the settings); clippy with no
warning in these files; the Mac half type-checked and linted against the
macOS bindings (`cargo clippy --all-targets --target aarch64-apple-darwin`,
with a stand-in C compiler — checked, never built or linked); the page's
TypeScript (`tsc --noEmit`).

In the app on Windows 11 (build 26200), with the Android 12 emulator, the
A13 and the Redmi attached, each toast read back from Windows' own history
(`ToastNotificationManager.History`, its XML), the panel driven through UI
Automation: each phone listening 20–55 ms after it was asked; the
introduction; an SMS's toast with its sender, its text, the reply box,
"Mark as read", "Turn off for Messages" and the sender's picture; "Show what
they say" switched off and the same toast, in place, as "Messages — New
message" with only "Turn off for Messages", its picture deleted; an SMS
arriving then as the same, no picture written; a call on AOSP's Dialer as an
incoming-call toast with `Decline` (button 0, red) and `Answer` (button 1,
green); "Calls ring here" off and that toast gone at once; the switch off —
each phone told and no longer listening, the Redmi's server, started for
notifications alone, stopping — and on: all three listening within a
second. Closing the app: gone in 0.3 s, no toast left under any of its
identities, the pictures' folder gone, no app server on any phone.

The clicks on toasts themselves were run on 2026-10-25 through the handler
a toast's click reaches, from a temporary test hook in the same process: a
toast was never clicked by hand. On this Windows build a toast is drawn by
the shell where UI Automation from an unelevated process cannot reach it,
and nothing here may move the real mouse. What a click asks of the phone is
the wire's, and the phone's half of every one of them is checked above.

Not yet:

- **A real click on a real toast**, and the reply text Windows hands back with
  it (`ToastActivatedEventArgs.UserInput`): needs a person. The in-process
  Activated event is what other unpackaged apps rely on while they run.
- **A Mac**, at all: `notifications/macos.rs` is type-checked against the
  macOS bindings, never built for a Mac or run on one — the banner put up
  late, after its categories, included.
- The phone's desktop in front holding toasts back, and a click opening on
  the desktop: no desktop session was run. Opening on the desktop, and on
  the phone's own screen, still fires the intent straight, which 12–15 block
  while the app is nowhere on screen.
- Android 14: no image here; it is given the way 12, 13 and 15 open.
- A call on a real phone, and a dialer from before CallStyle other than
  AOSP's (Samsung's, MIUI's: their buttons go as they are, whatever their
  order; MIUI's may have none, and then the toast has none).
- Two calls ringing at once: the emulator's modem rings one at a time.
- A toast going up while a switch flips: the lock that orders them is
  reasoned, not raced on purpose.
- A work profile's notifications.
- Windows' own Do not disturb: the system holds toasts back by itself.
- A Linux computer: not available; the bell is not shown there.
