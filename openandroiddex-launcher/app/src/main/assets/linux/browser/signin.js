// The page an email's sign-in link opens ("Email me when an address changes":
// EmailNotice.java on the phone; doc/address-email.md).
//
// The link is https://<the phone's address>/signin.html#k=<secret>&to=phone.
// What follows the # never leaves this browser by itself: not to the phone,
// not to Cloudflare, not into any server's log. This page takes the secret
// off the address bar at once, and sends the secret to the phone only when the
// person presses Sign in: a mail service's link checker that opens the page
// presses nothing, so a checker cannot use the link up. The phone answers
// with the token a code gives (POST /link), kept where the phone's page and
// Linux's page look for the token, and the page goes there.

const $ = (id) => document.getElementById(id);
const store = {
  get(k) { try { return localStorage.getItem(k); } catch { return null; } },
  set(k, v) { try { if (v == null) localStorage.removeItem(k); else localStorage.setItem(k, v); } catch { /* a private window */ } },
};

const fragment = new URLSearchParams(location.hash.slice(1));
const secret = fragment.get('k') || '';
const to = fragment.get('to') === 'linux' ? 'linux' : 'phone';
// Off the address bar, and out of this tab's history, before anything else.
if (location.hash) history.replaceState(null, '', location.pathname);
// Another link opened in this same tab changes only what follows the #, and
// a browser does not load a page again for that: the page starts over with
// the new link.
window.addEventListener('hashchange', () => location.reload());

// Linux's page is the door's own (the phone's page when Linux is not offered there).
const where = to === 'linux' ? './' : 'phone.html';
const what = to === 'linux' ? 'Linux' : "the phone's screen";

const WORDS = {
  used: 'This link was used already. Each link works once. Sign in with the code shown on the phone, or use the link in the next email from LinuxOnDroid.',
  old: 'This link is more than 24 hours old. Sign in with the code shown on the phone, or use the link in a newer email from LinuxOnDroid.',
  replaced: 'A newer email from LinuxOnDroid has a new link. Open the newest email and use the link there.',
  off: 'This link was turned off on the phone. Sign in with the code shown on the phone.',
  elsewhere: "This link is for another of the phone's addresses. Open the link from the email exactly as the email has the link.",
  wrong: 'This link does not work. Open the whole link from the email, with nothing missing at the end.',
  plain: 'The sign-in link works only over the encrypted connection. Open the link from the email exactly as the email has the link, starting with https.',
  locked: 'Too many tries from this network. Wait a few minutes, then try again.',
};

function say(title, words) {
  $('title').textContent = title;
  $('words').textContent = words;
}
const fail = (words) => { $('error').textContent = words; };
const offerCode = () => { $('code').href = where; $('code').hidden = false; };

/** This browser has a token the phone still knows: no link is needed, and none is used up. */
async function signedIn() {
  const token = store.get('lod.token');
  if (!token) return false;
  try {
    const r = await fetch('hello', { headers: { Authorization: `Bearer ${token}` }, cache: 'no-store' });
    const j = await r.json();
    return typeof j.name === 'string';
  } catch {
    return false;
  }
}

async function start() {
  if (location.protocol !== 'https:') {
    say('Open the secure link', WORDS.plain);
    return;
  }
  if (await signedIn()) {
    say('Signed in already', `This browser is signed in. Opening ${what}.`);
    location.replace(where);
    return;
  }
  if (!secret) {
    say('Open the link from the email',
      "This page needs the sign-in link from LinuxOnDroid's newest email. If the browser warned about the connection first, open the link from the email again.");
    offerCode();
    return;
  }
  say(to === 'linux' ? 'Sign in to Linux' : "Sign in to the phone's screen",
    'The link from the email lets this browser in without the code. Press Sign in to use the link. Each link lets one browser in.');
  $('go').hidden = false;
  $('small').hidden = false;
  $('go').focus();
}

$('go').addEventListener('click', async () => {
  $('go').disabled = true;
  fail('');
  let r;
  let j = {};
  try {
    r = await fetch('link', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ k: secret }),
      cache: 'no-store',
    });
    j = await r.json().catch(() => ({}));
  } catch {
    $('go').disabled = false;
    fail('The phone did not answer. Check that the phone is on and connected, then try again.');
    return;
  }
  if (r.ok && j.token) {
    store.set('lod.token', j.token);
    say('Signed in', `Opening ${what}.`);
    $('go').hidden = true;
    location.replace(where);
    return;
  }
  fail(WORDS[j.error] || WORDS.wrong);
  if (j.error === 'locked') {
    $('go').disabled = false;
    return;
  }
  $('go').hidden = true;
  offerCode();
});

start();
