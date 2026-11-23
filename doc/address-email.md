# Email me when an address changes

LinuxOnDroid → Settings → *Email me when an address changes*. When what a person needs to reach the phone changes (the Cloudflare address of *From anywhere* after a restart, the Wi-Fi address, a switch going off or on), the phone emails them the new addresses and, unless they turned it off, a sign-in link for each, so a person far away gets back in with nobody at the phone. It covers both doors: Linux in a browser (*Open in a browser*, `linux-browser.md`) and the phone's own screen (*Remote desktop*, `phone-remote.md`).

The case it is for, in the words of the person who asked for it: "Imagine I forgot the URL and code, and next morning I woke up and no longer can access my remote phone." A quick tunnel's address is new at every start of cloudflared, so a restart at night leaves yesterday's bookmark pointing at nothing, and a browser's token belongs to the address it was given at, so even the right new address asks for a code that only the phone shows.

What a person does:

1. **Phone:** Settings → *Email me when an address changes* → on. Pick the email service that sends (Gmail, Outlook, iCloud, Yahoo or another), type the address to send to, the account to send from and its app password
2. **Send a test email.** The test is a real email with the addresses as they are now (and live sign-in links, the switch being on). Emails start by themselves only after a test went through with exactly these settings: a half-typed address never gets an email
3. From then on, nothing. An email comes when something changes, one per change, and the card says when the last one went

## Shape

| Piece | File | What it owns |
| --- | --- | --- |
| The feature | `EmailNotice.java` | the settings, what is watched (`Reach`), the rules for when (`observe`, `plan`), the job's step, the test email, the email's words; files in `files/notify/` |
| The job | `EmailNoticeJob.java` | JobScheduler's entry: one step on a thread of its own, then the next one scheduled |
| SMTP | `EmailSmtp.java` | one email through the user's own service: SSL/TLS or STARTTLS, AUTH PLAIN or LOGIN, the MIME message; pure Java |
| The password | `EmailSecret.java` | AES-256-GCM under an Android Keystore key |
| Sign-in links | `LinuxBrowser.java` (`newLinks`, `tryLink`, `dropLinks`) | the links' hashes, with the door's other secrets in `files/browser/` |
| The door | `LinuxBrowserServer.java` (`POST /link`), `assets/linux/browser/signin.html`, `signin.js`, `signin.css` | a link's secret for a token, the page a link opens |
| The card | `LinuxSettingsActivity.java` (`EmailCard`) | the switch, the form, the test, what the emails are doing |
| Entry points | `PhoneTunnel.java`, `LinuxService.java`, `RemoteResumeReceiver.java` | "something may have changed" (`EmailNotice.poke`) |
| Checks | `linuxapp/src/test`: `EmailSmtpTest`, `EmailSmtpConscryptTest`, `EmailNoticeRulesTest`, `EmailNoticeTest`, `EmailNoticeSettingsTest`, `SignInLinkTest`, `EmailWordsTest`; `test/linux-browser/LinkCheck.java`, `signin-check.mjs` (with `SigninDoor.java`) | below, "Checked" |

## What is watched

One look (`EmailNotice.Reach`) takes everything a person uses to reach the phone:

- Linux's *Open in a browser*, Remote desktop, and Remote desktop's *From anywhere*: on or off
- The door: up, its port taken by another app, failed, or down (LinuxOnDroid's `:linux` process not running, or the door starting)
- The door's addresses on the phone's own networks (Wi-Fi, hotspot, cable, VPN), up to four, best first
- The tunnel's address, believed only while `:linux` runs: a file left by a process Android killed names a tunnel that is gone
- Remote access (the desktop app's way in): on or off. Its identity is a key that never changes, so nothing there is an address to email; a computer added once keeps finding the phone. Remote access going off is still a change in how to reach the phone, and is said

`Reach.key()` is the part that decides "the same information": a phone renamed, a computer added, or a restart that changed nothing send no email. The Wi-Fi addresses count only while a door is on, the tunnel only while *From anywhere* is.

## When

Anything that may have changed something calls `EmailNotice.poke`, which only records what it saw and schedules the job. The job sends when the information is not what the last email said, has been still for a while, and is complete:

| Rule | Value | Why |
| --- | --- | --- |
| Still for | 45 s | A switch turned off and on again by mistake makes one email, or none when it ends where it was |
| Off the Wi-Fi, still for | 3 min | A router restarting is back in a minute or two |
| Something wanted and not there yet (the tunnel's address, the door starting) | waited for up to 5 min, looked at every 15 s | After a restart, the email with the new address, not one without it and another after. Past five minutes the email says what is missing ("Waiting for an address from Cloudflare"), and the next email brings the address |
| A failure that may pass (no route, a timeout, a busy server) | tried again after 1, 2, 4, 8, 16, then every 30 min | The job also waits for any network at all (JobScheduler's `NETWORK_TYPE_ANY`) |
| A failure only new settings fix (password refused, no encryption offered, certificate wrong) | not tried again until the settings change | Said on the card and in a notification; no endless retries against a refused password |

**Never the same information twice.** The state (`files/notify/state`) keeps the key of the last email that went. A look that finds the same key does nothing; back to what was sent before an email went (a flap), nothing is sent at all. Each send is claimed in the state first, under the lock both processes share, and sends happen only in the app's own process, one at a time; a send that waited its turn looks again and goes only if no other said the same meanwhile. A look in `:linux` only records and schedules. A claim older than three minutes is a process that died and is not waited for. One edge stays: a server that accepted the message, and the phone dying before it wrote that down, sends it once more.

**Why, when known.** A boot is seen by the boot count changing (`Settings.Global.BOOT_COUNT`), an update by the package's `lastUpdateTime`: neither needs a callback. LinuxOnDroid having stopped is seen by Android restarting the service, or by the job finding `:linux` gone. A tunnel that started again is the tunnel's own poke. The email says the likeliest (a restart before an update before the rest), and nothing when a switch explains itself.

### Without a callback

Android may never tell the app. On the Galaxy S25 (Android 17), an update installed while the phone dozed, unplugged and locked, sent no `MY_PACKAGE_REPLACED` for eight minutes, nothing of the app ran, and the door, the tunnel and the daemon stayed down until the receiver's own `ACTION_RESUME` was sent over adb (2026-11-22, the coordinator's run). So this feature relies on:

- **A persisted JobScheduler job every 15 minutes** (`JOB_WATCH`, network-bound), scheduled while the feature is on and tested. Android keeps scheduled jobs through restarts (once the phone is unlocked) and through updates, and the look needs nothing from the app to have run before. When the door or remote access should be up and `:linux` is not running (or the door has been down for five minutes), it asks LinuxOnDroid back the way a boot does (`LinuxService.browserResume`, `remoteResume`, and `RemoteResumeReceiver.offerResume`'s notification when Android refuses the start), at most every two minutes. Then the tunnel's new address arrives through the pokes below
- **A persisted one-shot job** (`JOB_STEP`) for each wait, so a process killed in the middle of a settle loses nothing: the state is in files
- Pokes, which only make it quicker: the tunnel finding or losing an address (`PhoneTunnel`, in `:linux`); every start command of `LinuxService` (every switch goes through it, and every start of `:linux`, including a sticky restart and the control service's connect, which calls `phoneRemoteChanged`); `RemoteResumeReceiver` (boot, update, the desktop app's clean-up); a network callback in `:linux` while a door is up; the card itself

`PhoneListenService` and the control service add nothing of their own: both reach `LinuxService`, and the poke is there.

Android stops all of it on **Force stop** (a Xiaomi phone's *Clear all* does one): jobs are cancelled with everything else until the app is opened again. And after a restart, Android starts apps only once the phone has been unlocked once (an unattended system update is the exception on phones that unlock themselves for it): until then there is no tunnel and no email. Both are said here because both are the person's to know, not the app's to fix.

The resume is part of this feature, and only while it is on. It may be worth having for everyone who leaves Remote desktop on; that is a decision for the door's owners.

## The email

Plain text and HTML of the same words (`multipart/alternative`), in the phone's language (all nine), readable on a phone: one column at most 560 px wide, a box per way in, large *Sign in without a code* buttons, every colour written into its element (mail apps keep few style sheets), right to left in Arabic with every address left to right.

- **Subject:** the phone's name and the change: "Galaxy S25 Ultra: a new address from anywhere", "…: Remote desktop is off", "…: a new Wi-Fi address", "…: how to reach this phone" (the first), "…: a test email from LinuxOnDroid"
- **What changed**, a line each, and **why** when known ("the phone restarted")
- **Every way in that is there now:** the phone's screen from anywhere (`https://….trycloudflare.com/phone.html`), Linux in a browser on the Wi-Fi (`https://192.168.1.20:7443`), the phone's screen on the Wi-Fi (`…/phone.html`), each with its sign-in link; a second network (a hotspot, a VPN such as Tailscale) as one more line with its own link. When a door is not up, why ("another app on the phone uses port 7443"), and no address at all: through a taken port the tunnel would lead to that other app
- **Computers with Open Android DeX** find the phone by themselves, when any were added
- **How to sign in:** the link, or the code (which needs someone at the phone); that a browser signed in before at an address stays signed in there, and that a new address from anywhere needs a new sign-in; the certificate warning on a Wi-Fi address
- "Keep this email to yourself", when there are links; why the email came and how to stop it
- Headers: `From: "LinuxOnDroid on <phone>" <the account signed in with>` (Gmail, iCloud and Yahoo send only from the account), `Auto-Submitted: auto-generated` and `X-Auto-Response-Suppress: All`, so out-of-office replies do not answer it; non-ASCII as RFC 2047 encoded words; bodies in base64, so no line is longer than 76 characters and nothing on the wire is not ASCII

## Getting back in from far away

The door lets a browser in for the six-digit code, which works only while the phone's card shows it (and ten minutes after), and only ten wrong tries in its life (`linux-browser.md`, "Who gets in"). That is what keeps a stranger who has the tunnel's address out, and exactly what keeps the owner out in the morning. Widening the code (shown in the email, or working while an email is unread) would put six digits behind an address anyone at Cloudflare's edge can see; so the code stays as it is, and the email carries something else.

**A sign-in link.** `https://<address>/signin.html#k=<secret>&to=phone`:

- **The secret is 256 random bits**, one per address in the email (the tunnel's name, each Wi-Fi address). The phone keeps only its SHA-256, with the address it works at and until when (`files/browser/links`); compared in constant time against every link, as the browsers' tokens are
- **After the `#`.** A browser never sends what follows the `#`: it is not in the request Cloudflare's edge logs, not in the door's log, not in a `Referer` (the door also sends `Referrer-Policy: no-referrer`). `signin.js` takes it off the address bar and out of the tab's history at once, and sends it only in the body of `POST /link`, over TLS, from the page's own origin
- **Only when a person presses *Sign in*.** Mail services open links to check them (Microsoft's Safe Links, a company's gateway, some of Gmail's); a checker that runs the page presses nothing, so it can neither use the link up nor be let in
- **Once.** Used, the secret goes and the browser has a token like a code's, which the browser's *Forget* and the card's *Sign out every browser* end as they end any other
- **At its own address.** A Wi-Fi link does nothing through the tunnel, and the tunnel's link nothing on the Wi-Fi ("This link is for another of the phone's addresses")
- **For 24 hours.** The email that matters is the one a restart sends at three in the morning, read at breakfast; ten minutes would have run out, and a week would be a week of an email that opens the phone
- **Replaced by the next email's links once that email has gone**, so only the newest delivered email's links work, at most one per address. An email that does not go takes only its own links with it: the links in the email the person already has work on
- **Ended** by *Sign out every browser* on the card (the sign-out of every browser), by switching the emails or their links off, and by *Forget the email settings*
- **TLS only:** the door refuses `/link` over plain http, where the secret would cross the network in the clear
- **No budget of wrong tries.** The code has ten, because six digits can be guessed. 256 bits cannot be, and a budget would only let a stranger who knows the address use up the owner's way back in. The door's lockout by address still holds for links as for codes, and counts both together: five wrong from one address (through the tunnel, the address Cloudflare saw), and that address waits 30 s, doubling to ten minutes. A link that is real but no longer works (used, replaced, old) is said and not held against anyone

`signin.js` first asks the door whether this browser is signed in already (`/hello`); if it is, it goes straight to the page and leaves the link unused. Otherwise it shows *Sign in*; after the press, the token goes where the phone's page and Linux's page keep theirs (`lod.token`, this origin's storage), and the page opens. Every way a link fails has its own words, and *Sign in with the code instead*.

**What the link weakens, said plainly.** With links on, whoever can read the mailbox within a day can open the phone's screen once, and its files where Remote desktop offers them (not the phone's lock screen: a locked phone stays locked to them as to anyone at a computer). That is the same bargain as every "forgot my password" email, and the card says it in those words: "Anyone who can read the email can use the link, so keep the emails to yourself". Who else sees the secret: the mail services that carry and store the email (they could read the email anyway), a link checker that rewrites links (Safe Links puts the whole URL, fragment included, into its own), and Cloudflare, whose edge ends TLS for a quick tunnel and so sees the POST's body, as it already sees the code, the token and the picture through that route (`phone-remote.md`, "No server to pay for"). Nobody else: not a log, not a referrer, not the history.

**The safest choice is one switch away.** *Put a sign-in link in each email* (on by default, since a link is what the person asked for) off: the emails carry the addresses alone, the last email's links stop at once, and getting back in needs the code, that is, someone at the phone. A computer added in Open Android DeX needs neither: its way in has no address to change.

Not built: a button on the sign-in page to email a fresh link (to the configured address only) when the last one ran out. It would make a 24-hour link less of a limit, and adds a way for anyone with the address to make the phone send email; left for later.

## What is stored, and how

All of it in the app's private files, which only the app's uid can read:

| File | What | How |
| --- | --- | --- |
| `files/notify/settings` | on, links, the service, the addresses, the server, port and security, the fingerprint of the settings a test went through with | a properties file |
| `files/notify/password` | the app password | AES-256-GCM under an Android Keystore key (`linuxondroid-email-password`), the IV the Keystore's own, bound to its purpose (AAD); base64 of the sealed bytes |
| `files/notify/state` | the key and line of the last email, what is being waited for, failures, when LinuxOnDroid was last asked back | a properties file |
| `files/browser/links`, `links-dead` | each link's SHA-256, its address and expiry; the last 64 dead links' hashes and why | lines, under the door store's lock |

- **The key never leaves the phone** (the Keystore's hardware where the phone has it). It is not tied to the screen lock: the email that matters goes while the phone is locked, after a restart, and a key that needs an unlocked phone would refuse it then
- **The password is never in plain text** on disk, in a log, in the card (the field shows dots, and a saved password is never shown again), or in the page's saved state (the field is kept out of it). It exists only as the AUTH line's base64, inside TLS
- **Logs** say a fault's kind ("the email did not go: LOGIN") and a masked address ("a…@example.com"), never a password, a secret or a token; the door's log names a browser let in by a link as it names one let in by a code
- **Backups:** Android's own backup can copy these files to a new phone; the sealed password cannot be opened there (no key), and the card says "Type the app password again". The links' hashes are useless without the emails
- *Forget the email settings* deletes every file here, the Keystore key, and the links

## SMTP, written here

Android has no mail library, so `EmailSmtp` is the small part of SMTP that sends one email:

- **SSL/TLS** (implicit, port 465) or **STARTTLS** (port 587): TLS 1.2 or 1.3. A STARTTLS server that does not offer STARTTLS is refused: nothing is sent, not even the name. There is no plain-text login, and no way to choose one
- **The certificate** must chain to an authority the phone trusts and name the server: checked by the platform (endpoint identification) and again by `EmailSmtp.nameMatches` (RFC 6125: IP addresses against IP SANs, names against DNS SANs, a wildcard only as a whole first label, the common name never), the same on every Android version. A server with a self-signed certificate is refused; there is no "trust anyway"
- **AUTH PLAIN**, else **AUTH LOGIN**. No OAuth (XOAUTH2): a service that allows only OAuth is said as "offers no sign-in by password"
- A deadline for the whole send (90 s on a phone) as well as each read's, so a server that trickles a byte at a time cannot hold the job
- Each refusal read into a kind a person can act on, with the server's own words under it: Gmail's "Application-specific password required" (534 5.7.9), Microsoft's "basic authentication is disabled" (535 5.7.139), a refused password, address or message, SSL/TLS against a STARTTLS port and the other way round (worded differently by the JDK and by Conscrypt, Android's TLS, and both are read)

The services, checked November 2026:

| Service | Server | Note on the card |
| --- | --- | --- |
| Gmail | smtp.gmail.com, 465, SSL/TLS | an app password (2-Step Verification on); the normal password is refused |
| Outlook | smtp.office365.com, 587, STARTTLS | Microsoft turned app passwords off for personal Outlook and Hotmail addresses in September 2024, and for many work accounts. The card says so, and that an Outlook address can still receive: send with another service |
| iCloud | smtp.mail.me.com, 587, STARTTLS | an app-specific password; the full iCloud address |
| Yahoo | smtp.mail.yahoo.com, 465, SSL/TLS | an app password |
| Other | typed in | always encrypted, certificate always checked |

## Limits

- No email before the first unlock after a restart, and none after a Force stop until the app is opened (above)
- Jobs of an app that is not exempt from battery optimisation wait for Doze's maintenance windows; the Remote access window asks for that exemption
- *From anywhere*'s address is Cloudflare's to give, and a quick tunnel has no uptime promise
- Found while building this, not changed here: with port 7443 taken by another app, `LinuxService` still starts the tunnel (it follows the switch, not the door), and cloudflared would then forward the world to that other app's port. The email never gives the tunnel's address in that state

## Checked

On this computer, 2026-11-22, no phone, no real mail service; every address example.com's. The stand-in mail server (`FakeSmtp`) has a certificate made for the test (with the door's own maker, `LinuxBrowserCert`) and trusted only by the test's own factory; a test checks the platform does not trust it.

- `EmailSmtpTest` (25, plain JUnit): SSL/TLS and STARTTLS sends, AUTH PLAIN, LOGIN and PLAIN asked again; the message read back (headers, encoded words, both parts, ASCII, line lengths); only EHLO and STARTTLS in the clear; no STARTTLS offered (nothing sent); untrusted certificates (directly and after STARTTLS); a certificate for another name; SSL/TLS against a STARTTLS port; refused login, Gmail's app password, Microsoft's basic auth, refused recipient and message, a busy server; a silent server (timeout) and one that trickles (the deadline); nothing listening; name matching, address and header rules, dot-stuffing
- `EmailSmtpConscryptTest` (4): the TLS cases with the client on Conscrypt, the TLS a phone uses. It found that Conscrypt words SSL/TLS against a STARTTLS port as "Unable to parse TLS packet header", which the first rule missed
- `EmailNoticeRulesTest` (21, plain JUnit, its own clock): the key, settling, waiting for the tunnel and for no more than five minutes, a switch off and on, the Wi-Fi gone for a moment and for longer, backoff, a blocked failure, a claim under way, causes without callbacks, when LinuxOnDroid is asked back
- `EmailNoticeTest` (21, Robolectric, its own clock): the test email as the real email with a working link per address; a new tunnel address after the quiet, once; a switch off and on (nothing), *From anywhere* off and on (one email), a switch off (said, no reason made up); a restart with no callback (LinuxOnDroid asked back, the old address never emailed, one email with the new one and "the phone restarted"); no address for five minutes; the server unreachable and back; a refused password (no retries, a notification, sent after new settings); a send that died halfway; a password that no longer opens; links off; off or untested; a port taken; Forget; the phone's name escaped; a second network's address said as what it is; Arabic and German. With `-PbrowserShots=<folder>` it also writes the email after a restart (HTML and text, secrets blanked) in English and Arabic, which were looked at in a browser at a phone's width
- `EmailNoticeSettingsTest` (9, Robolectric, pictures with `-PbrowserShots=<folder>`): the card off, on, each service, what is typed kept and sealed, the test against the stand-in server, its refusal in plain words, the links' switch, Forget
- `SignInLinkTest` (12, plain JUnit): once, at its own address, a day, replaced once the next email went, an email that did not go leaving the last email's links working (red with the first version, which replaced them before the send), a sign-out, switched off, wrong and malformed secrets, many wrong ones not using the link up, hashes only on disk
- `EmailWordsTest` (5): all nine languages have every word with the same placeholders, no dashes, lines without a full stop and paragraphs with one, no pronoun standing for a thing in English, nor in the sign-in page
- `test/linux-browser/LinkCheck.java` (26, the door on this computer's JVM with real TLS sockets, through the tunnel's way in): the page and its policy, a link once, at its address, TLS only, the page's own origin only, not by GET, replaced, the lockout by address, a sign-out, a day old, no secret or token in the door's log. Run it with the two commands at the top of the file
- `test/linux-browser/signin-check.mjs` (19, `node test/linux-browser/signin-check.mjs`): the sign-in page in a real browser (Edge, headless, a profile of its own, as `linux-browser-test.mjs` runs one) against the door on this JVM (`SigninDoor.java`), under the tunnel's name with every other name resolving to nothing: the secret off the address bar at once, nothing used before *Sign in*, the token where the phone's page reads it and known to the door, the secret in no request's address and only in the POST's body, a used link's words and the code offered, a browser signed in already going straight on without using a link, the page with no link, the plain http page. It found that a second link opened in a tab already showing the page changed only the `#` and was never read; the page now starts over when the `#` changes (red before, green after)

Each guard was also taken out in turn, in a copy of the sources (never the working tree, where others build), and the matching checks were seen failing: the door taking `/link` over plain http, a link not used up, a link working at any address, a sign-out leaving the links (`LinkCheck`); STARTTLS skipped when the server does not offer it, and both name checks gone (`EmailSmtpTest`), while the platform's name check alone taken out left every check green, which is this file's own check doing its work, as it must on an Android version whose TLS checks no names; the same information sent twice, and no wait for the tunnel's address (`EmailNoticeRulesTest`). The whole of LinuxOnDroid's unit tests (`gradlew :linuxapp:testDebugUnitTest`) pass with the card on the Settings page.

Not tried: anything on a phone (the Keystore, JobScheduler under Doze, the resume after an update, the network callback, the card on a real screen), a real mail service, a real mail app showing the email (Gmail, Outlook, Apple Mail, dark mode), the certificate warning keeping the `#` of a Wi-Fi link (a browser that drops it shows "open the link from the email again"), and a link checker opening a link.
