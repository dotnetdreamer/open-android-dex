//! One notification's toast, as plain data — what both systems' notifications
//! are made from — and Windows' XML of it. Pure, so it is tested anywhere.

use std::path::{Path, PathBuf};

/// Whose a toast is.
#[derive(Clone, Debug, PartialEq)]
pub enum From {
    /// One of the phone's apps: on Windows, under its own name and icon.
    Phone { pkg: String, label: String, icon: Option<PathBuf> },
    /// This app: the toast that introduces the feature, a test, a reply that did not go.
    Us,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Kind {
    /// What a phone app posted.
    Message,
    /// A call ringing: it stays up, rings where the person asked for that, answer and decline.
    Call,
    /// This app saying something.
    Note,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Style {
    Plain,
    /// Answer: green on Windows 11.
    Go,
    /// Decline: red.
    Stop,
}

#[derive(Clone, Debug, PartialEq)]
pub struct Button {
    pub label: String,
    /// What a click on it means: an [`Act`]'s code, which survives the system's round trip.
    pub act: String,
    pub style: Style,
    /// In the toast's right-click menu on Windows rather than among its buttons.
    pub menu: bool,
}

/// A reply box, where the phone app's notification takes one.
#[derive(Clone, Debug, PartialEq)]
pub struct Reply {
    pub act: String,
    /// The app's own word for what goes in it ("Message", "Reply").
    pub placeholder: String,
    /// The app's own button for it, which a Mac shows before the box opens.
    pub label: String,
}

#[derive(Clone, Debug, PartialEq)]
pub struct Toast {
    /// Stable for one notification on one phone: an update replaces it, a removal finds it.
    pub id: String,
    /// The phone's, so its toasts can go together.
    pub group: String,
    pub from: From,
    pub kind: Kind,
    pub title: String,
    pub body: String,
    /// The phone's name, under the text.
    pub attribution: String,
    /// When the app posted it, in ms since the epoch: what the notification center shows.
    pub when: i64,
    /// The sender's picture.
    pub avatar: Option<PathBuf>,
    /// A photo the message carries, a screenshot.
    pub picture: Option<PathBuf>,
    pub reply: Option<Reply>,
    pub buttons: Vec<Button>,
    pub silent: bool,
    /// Pops up; false goes quietly into the notification center — an update to one already there.
    pub popup: bool,
}

/// What a click on a toast meant, from either system.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Act {
    /// The toast itself: open what it is about.
    Open,
    /// One of the app's own buttons, by its index in the phone's notification.
    Button(i64),
    /// A reply typed into the box of the app's reply button with that index.
    Reply(i64),
    /// "Turn off for this app": the app's notifications stop coming up here.
    Mute,
    /// …and the toast that says so takes it back.
    Unmute,
    Answer,
    Decline,
    /// The introduction's "Turn off": the whole feature.
    TurnOff,
    /// The introduction's "Settings": this app's window, its notification settings open.
    Settings,
    /// The person closed it (not the system, not a timeout).
    Dismissed,
}

impl Act {
    pub fn code(self) -> String {
        match self {
            Act::Open => "open".into(),
            Act::Button(i) => format!("b{i}"),
            Act::Reply(i) => format!("r{i}"),
            Act::Mute => "mute".into(),
            Act::Unmute => "unmute".into(),
            Act::Answer => "answer".into(),
            Act::Decline => "decline".into(),
            Act::TurnOff => "off".into(),
            Act::Settings => "settings".into(),
            Act::Dismissed => "dismissed".into(),
        }
    }

    pub fn parse(code: &str) -> Option<Act> {
        Some(match code {
            "" | "open" => Act::Open,
            "mute" => Act::Mute,
            "unmute" => Act::Unmute,
            "answer" => Act::Answer,
            "decline" => Act::Decline,
            "off" => Act::TurnOff,
            "settings" => Act::Settings,
            "dismissed" => Act::Dismissed,
            _ => {
                let (kind, n) = code.split_at(1);
                let i = n.parse().ok()?;
                match kind {
                    "b" => Act::Button(i),
                    "r" => Act::Reply(i),
                    _ => return None,
                }
            }
        })
    }
}

/// The most of each a toast takes: a notification center has no room for a
/// whole e-mail, and Windows refuses a toast with more than five actions.
const TITLE_CHARS: usize = 120;
const BODY_CHARS: usize = 600;
const ACTIONS: usize = 5;

/// What a phone app said, fit for a toast: control characters out (a line
/// break stays), and cut at a length a notification center shows.
pub fn tidy(text: &str, most: usize) -> String {
    let clean: String = text.chars().filter(|c| *c == '\n' || !c.is_control()).collect();
    let clean = clean.trim();
    if clean.chars().count() <= most {
        return clean.to_string();
    }
    let mut cut: String = clean.chars().take(most.saturating_sub(1)).collect();
    cut.truncate(cut.trim_end().len());
    cut.push('…');
    cut
}

pub fn tidy_title(text: &str) -> String {
    tidy(&text.replace('\n', " "), TITLE_CHARS)
}

pub fn tidy_body(text: &str) -> String {
    tidy(text, BODY_CHARS)
}

/// The toast as Windows takes it: ToastGeneric, the sender's picture in the
/// round logo's place, a photo inline, the phone's name as the attribution,
/// the reply box and the app's own buttons — at most five actions, the
/// right-click menu's last.
#[cfg_attr(not(windows), allow(dead_code))]
pub fn windows_xml(t: &Toast) -> String {
    let mut x = String::with_capacity(1024);
    x.push_str(r#"<toast launch="open" activationType="foreground""#);
    if let Some(at) = iso8601(t.when) {
        attr(&mut x, "displayTimestamp", &at);
    }
    if t.kind == Kind::Call {
        x.push_str(r#" scenario="incomingCall" useButtonStyle="true""#);
    }
    x.push_str(r#"><visual><binding template="ToastGeneric">"#);
    x.push_str(r#"<text hint-maxLines="2">"#);
    x.push_str(&escape(&t.title));
    x.push_str("</text>");
    if !t.body.is_empty() {
        x.push_str("<text>");
        x.push_str(&escape(&t.body));
        x.push_str("</text>");
    }
    if let Some(avatar) = &t.avatar {
        x.push_str(r#"<image placement="appLogoOverride" hint-crop="circle""#);
        attr(&mut x, "src", &file_uri(avatar));
        x.push_str("/>");
    }
    if let Some(picture) = &t.picture {
        x.push_str("<image");
        attr(&mut x, "src", &file_uri(picture));
        x.push_str("/>");
    }
    if !t.attribution.is_empty() {
        x.push_str(r#"<text placement="attribution">"#);
        x.push_str(&escape(&t.attribution));
        x.push_str("</text>");
    }
    x.push_str("</binding></visual>");

    let mut actions = String::new();
    let mut count = 0;
    if let Some(r) = &t.reply {
        actions.push_str(r#"<input id="reply" type="text""#);
        attr(&mut actions, "placeHolderContent", &r.placeholder);
        actions.push_str("/>");
        actions.push_str(r#"<action content="Send" hint-inputId="reply" activationType="foreground""#);
        attr(&mut actions, "arguments", &r.act);
        actions.push_str("/>");
        count += 1;
    }
    // The buttons first, the menu's after: when there are too many, the menu gives way.
    for b in t.buttons.iter().filter(|b| !b.menu).chain(t.buttons.iter().filter(|b| b.menu)) {
        if count == ACTIONS {
            break;
        }
        actions.push_str("<action");
        attr(&mut actions, "content", &b.label);
        attr(&mut actions, "arguments", &b.act);
        actions.push_str(r#" activationType="foreground""#);
        match b.style {
            Style::Go => actions.push_str(r#" hint-buttonStyle="Success""#),
            Style::Stop => actions.push_str(r#" hint-buttonStyle="Critical""#),
            Style::Plain => {}
        }
        if b.menu {
            actions.push_str(r#" placement="contextMenu""#);
        }
        actions.push_str("/>");
        count += 1;
    }
    if !actions.is_empty() {
        x.push_str("<actions>");
        x.push_str(&actions);
        x.push_str("</actions>");
    }
    if t.silent {
        x.push_str(r#"<audio silent="true"/>"#);
    } else if t.kind == Kind::Call {
        x.push_str(r#"<audio src="ms-winsoundevent:Notification.Looping.Call" loop="true"/>"#);
    }
    x.push_str("</toast>");
    x
}

fn attr(x: &mut String, name: &str, value: &str) {
    x.push(' ');
    x.push_str(name);
    x.push_str("=\"");
    x.push_str(&escape(value));
    x.push('"');
}

fn escape(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for c in s.chars() {
        match c {
            '&' => out.push_str("&amp;"),
            '<' => out.push_str("&lt;"),
            '>' => out.push_str("&gt;"),
            '"' => out.push_str("&quot;"),
            '\'' => out.push_str("&apos;"),
            _ => out.push(c),
        }
    }
    out
}

/// The style a call's own button takes from the colour its app gave its words
/// (`#rrggbb`): green answers, red declines — how a dialer from before
/// CallStyle marks them (AOSP's #097138 and #A52714). Grey, any other hue,
/// or none: plain. Only ever the colour — what the button does is its own.
pub fn style_of(tint: &str) -> Style {
    let Some(rgb) = tint.strip_prefix('#').filter(|h| h.len() == 6).and_then(|h| u32::from_str_radix(h, 16).ok()) else {
        return Style::Plain;
    };
    let [r, g, b] = [(rgb >> 16) & 0xff, (rgb >> 8) & 0xff, rgb & 0xff].map(|c| c as f32 / 255.0);
    let (max, min) = (r.max(g).max(b), r.min(g).min(b));
    // Too grey, or too dark, to say anything.
    if max < 0.2 || max - min < 0.25 * max {
        return Style::Plain;
    }
    let hue = if max == r {
        60.0 * ((g - b) / (max - min)).rem_euclid(6.0)
    } else if max == g {
        60.0 * ((b - r) / (max - min) + 2.0)
    } else {
        60.0 * ((r - g) / (max - min) + 4.0)
    };
    match hue {
        h if (75.0..=165.0).contains(&h) => Style::Go,
        h if !(20.0..340.0).contains(&h) => Style::Stop,
        _ => Style::Plain,
    }
}

/// A local file as a `file:///` URI, what a toast's image wants: forward
/// slashes, and anything but a path's own characters percent-encoded (a
/// user name with a space in it is in the path).
pub fn file_uri(path: &Path) -> String {
    let p = path.to_string_lossy().replace('\\', "/");
    let mut out = String::from(if p.starts_with('/') { "file://" } else { "file:///" });
    for b in p.bytes() {
        if b.is_ascii_alphanumeric() || b"/:._-~".contains(&b) {
            out.push(b as char);
        } else {
            out.push_str(&format!("%{b:02X}"));
        }
    }
    out
}

/// ms since the epoch as RFC 3339, in UTC — what a toast's timestamp is written in.
fn iso8601(ms: i64) -> Option<String> {
    if ms <= 0 {
        return None;
    }
    time::OffsetDateTime::from_unix_timestamp_nanos(ms as i128 * 1_000_000)
        .ok()?
        .format(&time::format_description::well_known::Rfc3339)
        .ok()
}

/// A short, stable name for one notification on one phone — a Windows toast's
/// tag may be no longer than 64 characters, and a phone's key is often more.
pub fn id_for(phone: &str, key: &str) -> String {
    format!("{:016x}", fnv(&[phone.as_bytes(), b"\n", key.as_bytes()]))
}

/// The group a phone's toasts share.
pub fn group_for(phone: &str) -> String {
    format!("p{:08x}", fnv(&[phone.as_bytes()]) as u32)
}

fn fnv(parts: &[&[u8]]) -> u64 {
    let mut h: u64 = 0xcbf2_9ce4_8422_2325;
    for part in parts {
        for b in *part {
            h ^= *b as u64;
            h = h.wrapping_mul(0x0000_0100_0000_01b3);
        }
    }
    h
}

#[cfg(test)]
mod tests {
    use super::*;

    fn message() -> Toast {
        Toast {
            id: id_for("R58T51N3G2X", "0|com.whatsapp|1|null|10234"),
            group: group_for("R58T51N3G2X"),
            from: From::Phone { pkg: "com.whatsapp".into(), label: "WhatsApp".into(), icon: None },
            kind: Kind::Message,
            title: "Mom".into(),
            body: "Are you coming? <3 & bring \"bread\"".into(),
            attribution: "Galaxy A13".into(),
            when: 1_792_886_400_000,
            avatar: Some(PathBuf::from(r"C:\Users\Jo Doe\AppData\Local\x\a b.png")),
            picture: None,
            reply: Some(Reply { act: Act::Reply(1).code(), placeholder: "Message".into(), label: "Reply".into() }),
            buttons: vec![
                Button { label: "Mark as read".into(), act: Act::Button(0).code(), style: Style::Plain, menu: false },
                Button { label: "Turn off for WhatsApp".into(), act: Act::Mute.code(), style: Style::Plain, menu: true },
            ],
            silent: false,
            popup: true,
        }
    }

    #[test]
    fn a_message_has_its_text_its_sender_its_reply_box_and_its_buttons() {
        let x = windows_xml(&message());
        assert!(x.starts_with(r#"<toast launch="open" activationType="foreground" displayTimestamp="2026-10-25T00:00:00Z">"#), "{x}");
        assert!(x.contains(r#"<text hint-maxLines="2">Mom</text>"#));
        // Everything the app said is text, never markup.
        assert!(x.contains("<text>Are you coming? &lt;3 &amp; bring &quot;bread&quot;</text>"));
        assert!(x.contains(r#"<image placement="appLogoOverride" hint-crop="circle" src="file:///C:/Users/Jo%20Doe/AppData/Local/x/a%20b.png"/>"#), "{x}");
        assert!(x.contains(r#"<text placement="attribution">Galaxy A13</text>"#));
        assert!(x.contains(r#"<input id="reply" type="text" placeHolderContent="Message"/><action content="Send" hint-inputId="reply" activationType="foreground" arguments="r1"/>"#), "{x}");
        assert!(x.contains(r#"<action content="Mark as read" arguments="b0" activationType="foreground"/>"#));
        assert!(x.contains(r#"<action content="Turn off for WhatsApp" arguments="mute" activationType="foreground" placement="contextMenu"/>"#));
        // The computer's own sound, unless asked for none.
        assert!(!x.contains("<audio"));
        let mut quiet = message();
        quiet.silent = true;
        assert!(windows_xml(&quiet).contains(r#"<audio silent="true"/>"#));
    }

    #[test]
    fn a_call_rings_and_its_buttons_are_coloured() {
        let mut t = message();
        t.kind = Kind::Call;
        t.reply = None;
        t.buttons = vec![
            Button { label: "Answer".into(), act: Act::Answer.code(), style: Style::Go, menu: false },
            Button { label: "Decline".into(), act: Act::Decline.code(), style: Style::Stop, menu: false },
        ];
        let x = windows_xml(&t);
        assert!(x.contains(r#" scenario="incomingCall" useButtonStyle="true">"#));
        assert!(x.contains(r#"<action content="Answer" arguments="answer" activationType="foreground" hint-buttonStyle="Success"/>"#));
        assert!(x.contains(r#"hint-buttonStyle="Critical""#));
        assert!(x.contains(r#"<audio src="ms-winsoundevent:Notification.Looping.Call" loop="true"/>"#));
    }

    #[test]
    fn a_dialers_own_colours_say_green_and_red_and_nothing_else() {
        // AOSP's dialer before CallStyle, and Material's own green and red.
        assert_eq!(style_of("#097138"), Style::Go);
        assert_eq!(style_of("#4caf50"), Style::Go);
        assert_eq!(style_of("#a52714"), Style::Stop);
        assert_eq!(style_of("#f44336"), Style::Stop);
        // Blue, grey, near black, nothing, nonsense: plain.
        for tint in ["#1a73e8", "#808080", "#0a0a0a", "", "#12", "097138", "#zzzzzz"] {
            assert_eq!(style_of(tint), Style::Plain, "{tint}");
        }
    }

    #[test]
    fn no_more_than_five_actions_and_the_menu_gives_way_first() {
        let mut t = message();
        t.buttons = (0..4)
            .map(|i| Button { label: format!("B{i}"), act: Act::Button(i).code(), style: Style::Plain, menu: false })
            .chain(std::iter::once(Button { label: "Off".into(), act: Act::Mute.code(), style: Style::Plain, menu: true }))
            .collect();
        let x = windows_xml(&t);
        assert_eq!(x.matches("<action ").count(), 5);
        assert!(!x.contains("contextMenu"));
    }

    #[test]
    fn a_click_comes_back_as_what_it_meant() {
        for act in [
            Act::Open,
            Act::Button(3),
            Act::Reply(0),
            Act::Mute,
            Act::Unmute,
            Act::Answer,
            Act::Decline,
            Act::TurnOff,
            Act::Settings,
            Act::Dismissed,
        ] {
            assert_eq!(Act::parse(&act.code()), Some(act));
        }
        // Windows hands the toast's own launch argument back for a click on its body.
        assert_eq!(Act::parse(""), Some(Act::Open));
        assert_eq!(Act::parse("x9"), None);
        assert_eq!(Act::parse("bx"), None);
    }

    #[test]
    fn text_is_tidied_to_what_a_notification_center_shows() {
        assert_eq!(tidy_title("Mom\nand Dad"), "Mom and Dad");
        assert_eq!(tidy_body("line one\nline\u{0007} two "), "line one\nline two");
        let long = "x".repeat(700);
        let cut = tidy_body(&long);
        assert_eq!(cut.chars().count(), 600);
        assert!(cut.ends_with('…'));
    }

    #[test]
    fn ids_are_short_stable_and_the_phones_own() {
        let a = id_for("R58T51N3G2X", "0|com.whatsapp|1|null|10234");
        assert_eq!(a.len(), 16);
        assert_eq!(a, id_for("R58T51N3G2X", "0|com.whatsapp|1|null|10234"));
        assert_ne!(a, id_for("YPKRA6VS754L6XTO", "0|com.whatsapp|1|null|10234"));
        assert!(group_for("R58T51N3G2X").len() <= 64);
    }
}
