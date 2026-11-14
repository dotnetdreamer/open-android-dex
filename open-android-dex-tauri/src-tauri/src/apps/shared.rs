//! What "Send to computer" and the photos (send/) take from the app windows'
//! own modules: the way to the phone's server, its wire, and how a file from
//! the phone is named and kept here. Re-exported rather than made public, so
//! the windows' modules stay theirs.

pub(crate) use super::adbsock::connect;
#[cfg(any(windows, target_os = "macos"))]
pub(crate) use super::clipboard::set_files;
pub(crate) use super::fetch::{arrival_name, downloads, short};
#[cfg(windows)]
pub(crate) use super::fetch::windows_keeps;
pub(crate) use super::wire::{json, read, Pieces, JSON};
