//! This app's own toasts on Windows, one banner at a time (windows.rs).
//!
//! Windows pops no banner for a toast of ours shown while another of ours is
//! on the screen: it goes straight into the notification center, where nobody
//! sees it come — two files sent from the phone a second and a half apart said
//! so on the screen once. A second toast in a group of its own did no better,
//! and one shown under the first one's tag took the first one's banner away and
//! popped nothing. Shown after the first one's banner had gone, it popped as
//! any. (Windows 11, 2026-11-13: plain toasts under this app's identity and
//! under PowerShell's, the screen's corner read every 150 ms.)
//!
//! So a toast of ours that is to pop while one of ours is up waits for that
//! one to go — into the notification center when its time is up, closed,
//! clicked, taken down — and then pops; or, should Windows never say, until
//! [`UP_AT_MOST`] after that one was shown. The phone's notifications go out
//! under their apps' own identities, and are not held here.

use std::collections::VecDeque;
use std::time::{Duration, Instant};

/// How long one of our banners is taken to be up, should Windows never say it went:
/// Windows' own is five seconds unless the person chose longer.
pub const UP_AT_MOST: Duration = Duration::from_secs(10);

/// Our banners: the one up, and the toasts waiting for it to go, in order.
pub struct Banners<T> {
    /// The toast whose banner is up — its serial, one per toast popped — and when it popped.
    up: Option<(u64, Instant)>,
    waiting: VecDeque<(String, T)>,
    serial: u64,
}

impl<T> Default for Banners<T> {
    fn default() -> Self {
        Self { up: None, waiting: VecDeque::new(), serial: 0 }
    }
}

impl<T> Banners<T> {
    /// `t`, the toast called `id`, is to pop up at `now`: `Some((serial, t))`
    /// if it may now — its banner is the one up from then — or None if it
    /// waits. One waiting under the same id already is replaced where it
    /// stands: the newer word, in the older one's turn.
    pub fn ask(&mut self, id: &str, t: T, now: Instant) -> Option<(u64, T)> {
        if let Some(w) = self.waiting.iter_mut().find(|(i, _)| i == id) {
            w.1 = t;
            return None;
        }
        if self.waiting.is_empty() && self.free(now) {
            return Some((self.start(now), t));
        }
        self.waiting.push_back((id.to_string(), t));
        None
    }

    /// The banner of the toast `serial` went: the next toast waiting, to pop now, if any.
    pub fn gone(&mut self, serial: u64, now: Instant) -> Option<(u64, String, T)> {
        if self.up.is_some_and(|(s, _)| s == serial) {
            self.up = None;
        }
        self.next(now)
    }

    /// The next toast waiting, to pop now — if the one up has gone, or has
    /// been up for [`UP_AT_MOST`].
    pub fn next(&mut self, now: Instant) -> Option<(u64, String, T)> {
        if !self.free(now) {
            return None;
        }
        let (id, t) = self.waiting.pop_front()?;
        Some((self.start(now), id, t))
    }

    /// The toast `id`, taken down before it popped: out of the line. True if it was waiting.
    pub fn forget(&mut self, id: &str) -> bool {
        let before = self.waiting.len();
        self.waiting.retain(|(i, _)| i != id);
        self.waiting.len() != before
    }

    /// None up and none waiting: the notification center was cleared.
    pub fn clear(&mut self) {
        self.up = None;
        self.waiting.clear();
    }

    fn free(&self, now: Instant) -> bool {
        self.up.map_or(true, |(_, at)| now.saturating_duration_since(at) >= UP_AT_MOST)
    }

    fn start(&mut self, now: Instant) -> u64 {
        self.serial += 1;
        self.up = Some((self.serial, now));
        self.serial
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const S: Duration = Duration::from_secs(1);

    #[test]
    fn the_first_pops_and_one_shown_while_it_is_up_waits_for_it_to_go() {
        let t0 = Instant::now();
        let mut b = Banners::default();
        let (first, _) = b.ask("send-1", "one file", t0).expect("nothing is up: it pops");
        assert_eq!(b.ask("send-2", "another", t0 + S * 3 / 2), None, "the first is up: it waits");
        assert_eq!(b.next(t0 + S * 3), None, "still up");
        let (second, id, t) = b.gone(first, t0 + S * 6).expect("the first went: the second pops");
        assert_eq!((id.as_str(), t), ("send-2", "another"));
        assert_ne!(second, first);
        assert_eq!(b.ask("send-3", "a third", t0 + S * 7), None, "the second is up now");
    }

    #[test]
    fn nothing_waits_once_the_one_up_has_gone() {
        let t0 = Instant::now();
        let mut b = Banners::default();
        let (first, _) = b.ask("a", 1, t0).unwrap();
        assert_eq!(b.gone(first, t0 + S), None, "nothing waiting");
        assert!(b.ask("b", 2, t0 + S * 2).is_some(), "nothing up: it pops at once");
    }

    #[test]
    fn waiting_ones_pop_one_at_a_time_in_order() {
        let t0 = Instant::now();
        let mut b = Banners::default();
        let (s1, _) = b.ask("a", 1, t0).unwrap();
        assert_eq!(b.ask("b", 2, t0), None);
        assert_eq!(b.ask("c", 3, t0), None);
        let (s2, id, _) = b.gone(s1, t0 + S * 6).unwrap();
        assert_eq!(id, "b");
        assert_eq!(b.next(t0 + S * 7), None, "b is up: c waits for it");
        let (_, id, _) = b.gone(s2, t0 + S * 12).unwrap();
        assert_eq!(id, "c");
    }

    #[test]
    fn a_banner_never_said_to_go_counts_as_gone_after_up_at_most() {
        let t0 = Instant::now();
        let mut b = Banners::default();
        b.ask("a", 1, t0).unwrap();
        assert_eq!(b.ask("b", 2, t0 + S), None);
        assert_eq!(b.next(t0 + UP_AT_MOST - S), None);
        let (_, id, _) = b.next(t0 + UP_AT_MOST).expect("taken as gone");
        assert_eq!(id, "b");
        // Nothing waiting, and the one up long past its time: a new one pops at once.
        assert!(b.ask("c", 3, t0 + UP_AT_MOST * 3).is_some());
    }

    #[test]
    fn an_older_toast_going_does_not_free_the_one_up_now() {
        let t0 = Instant::now();
        let mut b = Banners::default();
        let (s1, _) = b.ask("a", 1, t0).unwrap();
        b.ask("b", 2, t0);
        let (s2, _, _) = b.gone(s1, t0 + S * 6).unwrap();
        b.ask("c", 3, t0 + S * 7);
        // The first's click, long after its banner went: the second is still up.
        assert_eq!(b.gone(s1, t0 + S * 8), None);
        assert!(b.gone(s2, t0 + S * 9).is_some());
    }

    #[test]
    fn the_same_id_again_while_waiting_takes_its_place() {
        let t0 = Instant::now();
        let mut b = Banners::default();
        let (s1, _) = b.ask("a", "first", t0).unwrap();
        b.ask("b", "old words", t0);
        b.ask("c", "c", t0);
        assert_eq!(b.ask("b", "new words", t0 + S), None);
        let (_, id, t) = b.gone(s1, t0 + S * 6).unwrap();
        assert_eq!((id.as_str(), t), ("b", "new words"), "in b's turn, with its newer words");
    }

    #[test]
    fn one_taken_down_before_it_popped_never_pops() {
        let t0 = Instant::now();
        let mut b = Banners::default();
        let (s1, _) = b.ask("a", 1, t0).unwrap();
        b.ask("b", 2, t0);
        assert!(b.forget("b"));
        assert!(!b.forget("b"));
        assert_eq!(b.gone(s1, t0 + S * 6), None);
    }

    #[test]
    fn cleared_nothing_is_up_or_waiting() {
        let t0 = Instant::now();
        let mut b = Banners::default();
        b.ask("a", 1, t0).unwrap();
        b.ask("b", 2, t0);
        b.clear();
        assert!(b.ask("c", 3, t0 + S).is_some());
        assert_eq!(b.next(t0 + UP_AT_MOST * 2), None, "b went with the clear");
    }
}
