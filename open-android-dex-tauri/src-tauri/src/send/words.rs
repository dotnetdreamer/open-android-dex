//! Every sentence "Send to computer" and "Keep a copy" say on this computer:
//! a notification's title and body, the Photos tab's status line. In one
//! place, plain, and tested — the README's voice: short, says what happened.

/// What this computer is called in a sentence.
pub fn this_computer() -> &'static str {
    if cfg!(target_os = "macos") {
        "this Mac"
    } else {
        "this PC"
    }
}

/// The folder viewer, as a button names it.
pub fn show_in_folder() -> &'static str {
    if cfg!(target_os = "macos") {
        "Show in Finder"
    } else {
        "Show in folder"
    }
}

/// Why files did not arrive, in a sentence.
pub fn why(message: &str) -> String {
    match message {
        "space" => format!("There isn't enough room on {}.", this_computer()),
        "lost" => "The connection to your phone was lost.".into(),
        "unreadable" => "Your phone couldn't read it.".into(),
        "folder" => format!("{} has no Downloads folder.", capital(this_computer())),
        "" => format!("It couldn't be saved on {}.", this_computer()),
        other => other.to_string(),
    }
}

fn capital(s: &str) -> String {
    let mut c = s.chars();
    match c.next() {
        Some(f) => f.to_uppercase().chain(c).collect(),
        None => String::new(),
    }
}

/// A notification: its title and its body.
#[derive(Clone, Debug, PartialEq)]
pub struct Said {
    pub title: String,
    pub body: String,
}

fn said(title: impl Into<String>, body: impl Into<String>) -> Said {
    Said { title: title.into(), body: body.into() }
}

/// Files that landed: `landed` of `total`, the first called `first`.
pub fn files(first: &str, landed: usize, total: usize) -> Said {
    if landed == 0 {
        return said(format!("Couldn't receive {first}"), "Send it again from your phone.");
    }
    if landed < total {
        return said(format!("Received {landed} of {total} files"), "Send the rest again from your phone.");
    }
    if landed == 1 {
        return said(format!("Received {first}"), "Saved to Downloads.");
    }
    said(format!("Received {landed} files"), "Saved to Downloads.")
}

/// Nothing arrived, and why.
pub fn none(first: &str, total: usize, message: &str) -> Said {
    let (what, again) = if total > 1 { (format!("{total} files"), "them") } else { (first.to_string(), "it") };
    said(format!("Couldn't receive {what}"), format!("{} Send {again} again from your phone.", why(message)))
}

/// A link that was opened, or only offered; `copied`, the words that came with it are on the clipboard.
pub fn link(url: &str, opened: bool, copied: bool, phone: &str) -> Said {
    let mut shown = short_url(url);
    if copied {
        shown.push_str("\nThe text that came with it is copied.");
    }
    if opened {
        said(format!("Opened a link from {phone}"), shown)
    } else {
        said(format!("Received a link from {phone}"), shown)
    }
}

/// Text that could not be put on the clipboard.
pub fn not_copied(phone: &str) -> Said {
    said(format!("Couldn't copy text from {phone}"), "Send it again from your phone.")
}

/// Which cap turned a share away (inbox.rs, the caps).
#[derive(Clone, Copy, Debug, PartialEq)]
pub enum Busy {
    /// More shares in a minute than one phone may send.
    Shares,
}

/// A share turned away, said once.
pub fn busy(why: Busy, phone: &str) -> Said {
    match why {
        Busy::Shares => said(format!("{phone} sent too much at once"), "Wait a minute, then send again."),
    }
}

/// Text on the clipboard: its first 80 characters.
pub fn text(text: &str, phone: &str) -> Said {
    let words: String = text.split_whitespace().collect::<Vec<_>>().join(" ");
    let shown: String = words.chars().take(80).collect();
    let more = if words.chars().count() > 80 { "…" } else { "" };
    said(format!("Copied from {phone}"), format!("“{shown}{more}” Paste it anywhere on {}.", this_computer()))
}

/// A link as a person reads it: where it goes first — its host, never what
/// sits before an "@" ("accounts.google.com@evil.example" goes to
/// evil.example) — then its path; no scheme, no trailing slash, not too long.
pub fn short_url(url: &str) -> String {
    let s = url.trim();
    let s = s.strip_prefix("https://").or_else(|| s.strip_prefix("http://")).unwrap_or(s);
    let end = s.find(['/', '?', '#']).unwrap_or(s.len());
    let (authority, rest) = s.split_at(end);
    let host = authority.rsplit('@').next().unwrap_or(authority);
    let host = host.strip_prefix("www.").unwrap_or(host);
    let shown = format!("{host}{rest}");
    let s = shown.strip_suffix('/').unwrap_or(&shown);
    if s.chars().count() <= 80 {
        return s.to_string();
    }
    let cut: String = s.chars().take(79).collect();
    format!("{cut}…")
}

/// What the strip on the Photos tab says while a file comes.
pub fn receiving(name: &str, n: usize, total: usize, pct: Option<u64>) -> String {
    let pct = pct.map(|p| format!(" {p}%")).unwrap_or_default();
    if total > 1 {
        format!("Receiving {} of {total}…{pct}", n + 1)
    } else {
        format!("Receiving {name}…{pct}")
    }
}

/// …and once it has landed.
pub fn arrived(first: &str, landed: usize) -> String {
    if landed == 1 {
        format!("Received {first}")
    } else {
        format!("Received {landed} files")
    }
}

// ── Keep a copy ──

/// What Keep a copy is doing, as its one status line says it.
#[derive(Clone, Debug, PartialEq)]
pub enum Keeping {
    Saving { done: u64, total: u64 },
    /// `kept`: the kept copies that are in the folder, each counted once.
    UpToDate { kept: u64 },
    Waiting { phone: String },
    /// `elsewhere`: the folder is on another disk than this computer's own, and that is the low one.
    LowDisk { elsewhere: bool },
    /// A pass that stopped short (a phone that stopped answering, a picture that changed): the minute's wait.
    Again,
    LowBattery { level: i64 },
    Unreadable { n: u64 },
    FolderGone,
    /// The folder is there and cannot be written to.
    CantWrite,
    /// The folder's record is there and cannot be read: nothing is copied over it.
    RecordUnreadable,
    /// The phone's folder, and the one beside it, hold another phone's copies.
    OtherPhone,
    /// Pictures that this computer could not save, though the phone gave them.
    NotSaved { n: u64 },
    Newer,
    OneDrive,
    Starting,
}

pub fn keeping(k: &Keeping) -> String {
    match k {
        Keeping::Saving { done, total } => format!("Saving {} of {total}…", (done + 1).min(*total)),
        Keeping::UpToDate { kept: 0 } => "Up to date.".into(),
        Keeping::UpToDate { kept: 1 } => "1 photo saved.".into(),
        Keeping::UpToDate { kept } => format!("All {kept} photos saved."),
        Keeping::Waiting { phone } => format!("Waiting for {phone} to connect."),
        Keeping::LowDisk { elsewhere: false } => format!("Paused: {} is almost full.", this_computer()),
        Keeping::LowDisk { elsewhere: true } => "Paused: the disk with this folder is almost full.".into(),
        Keeping::Again => "Paused. Trying again in a minute.".into(),
        Keeping::LowBattery { level } => format!("Paused: your phone's battery is at {level}%. Plug it in to continue."),
        Keeping::Unreadable { n } => format!("Couldn't read {n} {} on your phone. Trying again in a minute.", photos(*n)),
        Keeping::FolderGone => "Can't find this folder. Choose another one.".into(),
        Keeping::CantWrite => "Can't save to this folder. Choose another one.".into(),
        Keeping::RecordUnreadable => "Can't use this folder. Choose another one.".into(),
        Keeping::OtherPhone => "This folder is used by another phone. Choose another one.".into(),
        Keeping::NotSaved { n } => format!("Couldn't save {n} {}. Trying again in a minute.", photos(*n)),
        Keeping::Newer => "This folder needs a newer version of Open Android DeX.".into(),
        Keeping::OneDrive => "OneDrive uploads photos saved in this folder. To keep them on this PC only, choose another folder.".into(),
        Keeping::Starting => "Checking this folder…".into(),
    }
}

fn photos(n: u64) -> &'static str {
    if n == 1 {
        "photo"
    } else {
        "photos"
    }
}

/// "193 photos (612 MB)": what "All photos" would copy; nothing while none are counted.
pub fn everything(count: u64, bytes: u64) -> String {
    if count == 0 {
        return String::new();
    }
    let size = if bytes >= 1_000_000_000 {
        format!("{:.1} GB", bytes as f64 / 1e9)
    } else {
        format!("{} MB", (bytes as f64 / 1e6).round() as u64)
    };
    format!("{count} {} ({size})", photos(count))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn one_file_one_picture_many_part_none() {
        assert_eq!(files("report.pdf", 1, 1), said("Received report.pdf", "Saved to Downloads."));
        assert_eq!(files("IMG_1234.jpg", 1, 1).title, "Received IMG_1234.jpg");
        assert_eq!(files("a.jpg", 5, 5), said("Received 5 files", "Saved to Downloads."));
        assert_eq!(files("a.jpg", 3, 5), said("Received 3 of 5 files", "Send the rest again from your phone."));
        assert_eq!(files("a.jpg", 0, 1), said("Couldn't receive a.jpg", "Send it again from your phone."));
        let n = none("report.pdf", 1, "space");
        assert_eq!(n.title, "Couldn't receive report.pdf");
        assert_eq!(n.body, format!("There isn't enough room on {}. Send it again from your phone.", this_computer()));
        assert_eq!(none("a.jpg", 3, "lost").title, "Couldn't receive 3 files");
        assert_eq!(none("a.jpg", 3, "lost").body, "The connection to your phone was lost. Send them again from your phone.");
        assert_eq!(none("a.jpg", 1, "lost").body, "The connection to your phone was lost. Send it again from your phone.");
    }

    #[test]
    fn a_link_opened_and_offered() {
        assert_eq!(
            link("https://www.example.com/some/page/", true, false, "Galaxy S24"),
            said("Opened a link from Galaxy S24", "example.com/some/page")
        );
        assert_eq!(link("http://example.com", false, false, "Galaxy S24"), said("Received a link from Galaxy S24", "example.com"));
        // The words that came with it went on the clipboard: said, not lost in silence.
        assert_eq!(
            link("http://example.com", true, true, "Galaxy S24"),
            said("Opened a link from Galaxy S24", "example.com\nThe text that came with it is copied.")
        );
        let long = format!("https://example.com/{}", "a".repeat(200));
        assert_eq!(short_url(&long).chars().count(), 80);
        assert!(short_url(&long).ends_with('…'));
        // Where it goes, never what is written before an "@".
        assert_eq!(short_url("https://accounts.google.com@evil.example/login"), "evil.example/login");
        assert_eq!(short_url("https://user:pw@www.example.com/"), "example.com");
        assert_eq!(short_url("http://a@b@example.org?x=1"), "example.org?x=1");
        assert_eq!(short_url("https://example.com/p@th"), "example.com/p@th");
    }

    #[test]
    fn a_share_turned_away_says_why() {
        let b = busy(Busy::Shares, "Galaxy S24");
        assert_eq!(b.title, "Galaxy S24 sent too much at once");
        assert_eq!(b.body, "Wait a minute, then send again.");
        assert_eq!(not_copied("P"), said("Couldn't copy text from P", "Send it again from your phone."));
    }

    #[test]
    fn text_is_cut_at_80_characters() {
        let t = text("hello   there\nfriend", "Galaxy S24");
        assert_eq!(t.title, "Copied from Galaxy S24");
        assert_eq!(t.body, format!("“hello there friend” Paste it anywhere on {}.", this_computer()));
        let long = "x".repeat(100);
        let b = text(&long, "P").body;
        assert!(b.starts_with(&format!("“{}…”", "x".repeat(80))), "{b}");
    }

    #[test]
    fn finder_on_a_mac_and_folder_elsewhere() {
        if cfg!(target_os = "macos") {
            assert_eq!(show_in_folder(), "Show in Finder");
            assert_eq!(this_computer(), "this Mac");
        } else {
            assert_eq!(show_in_folder(), "Show in folder");
            assert_eq!(this_computer(), "this PC");
        }
    }

    #[test]
    fn every_status_line_of_keep_a_copy() {
        assert_eq!(keeping(&Keeping::Saving { done: 11, total: 193 }), "Saving 12 of 193…");
        assert_eq!(keeping(&Keeping::UpToDate { kept: 193 }), "All 193 photos saved.");
        assert_eq!(keeping(&Keeping::UpToDate { kept: 1 }), "1 photo saved.");
        assert_eq!(keeping(&Keeping::UpToDate { kept: 0 }), "Up to date.");
        assert_eq!(keeping(&Keeping::Waiting { phone: "Galaxy S24".into() }), "Waiting for Galaxy S24 to connect.");
        assert_eq!(keeping(&Keeping::LowDisk { elsewhere: false }), format!("Paused: {} is almost full.", this_computer()));
        assert_eq!(keeping(&Keeping::LowDisk { elsewhere: true }), "Paused: the disk with this folder is almost full.");
        assert_eq!(keeping(&Keeping::Again), "Paused. Trying again in a minute.");
        assert_eq!(keeping(&Keeping::LowBattery { level: 15 }), "Paused: your phone's battery is at 15%. Plug it in to continue.");
        assert_eq!(keeping(&Keeping::Unreadable { n: 3 }), "Couldn't read 3 photos on your phone. Trying again in a minute.");
        assert_eq!(keeping(&Keeping::Unreadable { n: 1 }), "Couldn't read 1 photo on your phone. Trying again in a minute.");
        assert_eq!(keeping(&Keeping::FolderGone), "Can't find this folder. Choose another one.");
        assert_eq!(keeping(&Keeping::Newer), "This folder needs a newer version of Open Android DeX.");
        assert!(keeping(&Keeping::OneDrive).starts_with("OneDrive uploads photos saved in this folder."));
        assert_eq!(keeping(&Keeping::CantWrite), "Can't save to this folder. Choose another one.");
        assert_eq!(keeping(&Keeping::RecordUnreadable), "Can't use this folder. Choose another one.");
        assert_eq!(keeping(&Keeping::OtherPhone), "This folder is used by another phone. Choose another one.");
        assert_eq!(keeping(&Keeping::NotSaved { n: 2 }), "Couldn't save 2 photos. Trying again in a minute.");
        assert_eq!(keeping(&Keeping::Starting), "Checking this folder…");
        assert_eq!(everything(193, 612_000_000), "193 photos (612 MB)");
        assert_eq!(everything(1, 2_500_000_000), "1 photo (2.5 GB)");
        assert_eq!(everything(0, 0), "");
    }

    #[test]
    fn the_strip_says_how_far() {
        assert_eq!(receiving("holiday.jpg", 0, 1, Some(42)), "Receiving holiday.jpg… 42%");
        assert_eq!(receiving("a", 1, 3, None), "Receiving 2 of 3…");
        assert_eq!(arrived("report.pdf", 1), "Received report.pdf");
        assert_eq!(arrived("report.pdf", 4), "Received 4 files");
    }
}
