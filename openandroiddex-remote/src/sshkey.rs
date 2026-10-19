//! OpenSSH's own file formats for Ed25519 keys, written and read here.
//!
//! Two keys are made by this crate rather than by `ssh-keygen`: the phone's SSH
//! host key, which has to exist before the guest has ever been booted (a USB
//! pairing reads it straight off the app's storage), and the computer's client
//! key, on a Windows machine that may never have run `ssh-keygen` in its life.
//! An Ed25519 key is the same 32-byte seed iroh's [`SecretKey`] is built from,
//! so the generator is iroh's and only the encoding lives here.
//!
//! Formats, from OpenSSH's PROTOCOL.key and RFC 8709:
//!
//! * public: `ssh-ed25519 <base64(string "ssh-ed25519" ‖ string pk)> comment`
//! * private: the `openssh-key-v1` container, unencrypted ("none" cipher),
//!   one key, padded to the 8-byte block size, base64 at 70 columns.
//!
//! [`SecretKey`]: iroh::SecretKey

use base64::engine::general_purpose::{STANDARD, STANDARD_NO_PAD};
use base64::Engine;
use sha2::{Digest, Sha256};

const KEY_TYPE: &[u8] = b"ssh-ed25519";
const MAGIC: &[u8] = b"openssh-key-v1\0";
const PEM_BEGIN: &str = "-----BEGIN OPENSSH PRIVATE KEY-----";
const PEM_END: &str = "-----END OPENSSH PRIVATE KEY-----";

fn put_string(buf: &mut Vec<u8>, s: &[u8]) {
    buf.extend_from_slice(&(s.len() as u32).to_be_bytes());
    buf.extend_from_slice(s);
}

/// The wire-format public key: what authorized_keys and known_hosts encode.
pub fn public_blob(public: &[u8; 32]) -> Vec<u8> {
    let mut blob = Vec::with_capacity(51);
    put_string(&mut blob, KEY_TYPE);
    put_string(&mut blob, public);
    blob
}

/// `ssh-ed25519 AAAA… comment`, one line, no newline.
pub fn public_line(public: &[u8; 32], comment: &str) -> String {
    let encoded = STANDARD.encode(public_blob(public));
    let comment = comment.trim();
    if comment.is_empty() {
        format!("ssh-ed25519 {encoded}")
    } else {
        format!("ssh-ed25519 {encoded} {comment}")
    }
}

/// `SHA256:…`, the fingerprint `ssh-keygen -l` and every ssh prompt shows.
pub fn fingerprint(public: &[u8; 32]) -> String {
    format!("SHA256:{}", STANDARD_NO_PAD.encode(Sha256::digest(public_blob(public))))
}

/// The private key file, exactly as `ssh-keygen -t ed25519 -N ''` writes it.
pub fn private_pem(seed: &[u8; 32], public: &[u8; 32], comment: &str) -> String {
    // The two check words only have to match each other; they exist so a
    // wrong passphrase is detectable, which an unencrypted key never needs.
    let mut check = [0u8; 4];
    getrandom::fill(&mut check).expect("the system random source is unavailable");

    let mut private = Vec::new();
    private.extend_from_slice(&check);
    private.extend_from_slice(&check);
    put_string(&mut private, KEY_TYPE);
    put_string(&mut private, public);
    let mut secret = [0u8; 64];
    secret[..32].copy_from_slice(seed);
    secret[32..].copy_from_slice(public);
    put_string(&mut private, &secret);
    put_string(&mut private, comment.trim().as_bytes());
    let mut pad = 1u8;
    while private.len() % 8 != 0 {
        private.push(pad);
        pad += 1;
    }

    let mut body = Vec::new();
    body.extend_from_slice(MAGIC);
    put_string(&mut body, b"none");
    put_string(&mut body, b"none");
    put_string(&mut body, b"");
    body.extend_from_slice(&1u32.to_be_bytes());
    put_string(&mut body, &public_blob(public));
    put_string(&mut body, &private);

    let encoded = STANDARD.encode(body);
    let mut pem = String::with_capacity(encoded.len() + 80);
    pem.push_str(PEM_BEGIN);
    pem.push('\n');
    for line in encoded.as_bytes().chunks(70) {
        pem.push_str(std::str::from_utf8(line).expect("base64 is ascii"));
        pem.push('\n');
    }
    pem.push_str(PEM_END);
    pem.push('\n');
    pem
}

struct Reader<'a>(&'a [u8]);

impl<'a> Reader<'a> {
    fn take(&mut self, n: usize) -> Option<&'a [u8]> {
        if self.0.len() < n {
            return None;
        }
        let (head, tail) = self.0.split_at(n);
        self.0 = tail;
        Some(head)
    }

    fn u32(&mut self) -> Option<u32> {
        self.take(4).map(|b| u32::from_be_bytes([b[0], b[1], b[2], b[3]]))
    }

    fn string(&mut self) -> Option<&'a [u8]> {
        let n = self.u32()? as usize;
        self.take(n)
    }
}

/// The 32-byte public key out of an `ssh-ed25519 …` line, if it is one.
pub fn parse_public_line(line: &str) -> Option<[u8; 32]> {
    let mut words = line.split_whitespace();
    if words.next()? != "ssh-ed25519" {
        return None;
    }
    let blob = STANDARD.decode(words.next()?).ok()?;
    let mut r = Reader(&blob);
    if r.string()? != KEY_TYPE {
        return None;
    }
    let key = r.string()?;
    if !r.0.is_empty() || key.len() != 32 {
        return None;
    }
    key.try_into().ok()
}

/// The 32-byte seed out of an unencrypted Ed25519 key file written by
/// [`private_pem`] or by `ssh-keygen`.
pub fn parse_private_pem(pem: &str) -> Option<[u8; 32]> {
    let start = pem.find(PEM_BEGIN)? + PEM_BEGIN.len();
    let end = pem.find(PEM_END)?;
    let encoded: String = pem.get(start..end)?.split_whitespace().collect();
    let body = STANDARD.decode(encoded).ok()?;
    let mut r = Reader(&body);
    if r.take(MAGIC.len())? != MAGIC {
        return None;
    }
    if r.string()? != b"none" || r.string()? != b"none" {
        return None; // encrypted: not a file this code writes
    }
    r.string()?; // kdf options
    if r.u32()? != 1 {
        return None;
    }
    r.string()?; // public key blob
    let mut p = Reader(r.string()?);
    if p.u32()? != p.u32()? {
        return None;
    }
    if p.string()? != KEY_TYPE {
        return None;
    }
    let public = p.string()?;
    let secret = p.string()?;
    if public.len() != 32 || secret.len() != 64 || &secret[32..] != public {
        return None;
    }
    secret[..32].try_into().ok()
}

#[cfg(test)]
mod tests {
    use super::*;

    // A throwaway key made by `ssh-keygen -t ed25519 -N '' -C test@local`
    // (OpenSSH_10.3p1), so the parser is checked against the real tool's output
    // and not only against our own writer.
    const KEYGEN_PRIVATE: &str = "-----BEGIN OPENSSH PRIVATE KEY-----
b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAAAMwAAAAtzc2gtZW
QyNTUxOQAAACC+ELBVlQ/uEG6hMibDksO57uveMoq2M4fQSfRwXAw9XQAAAJAR5y6JEecu
iQAAAAtzc2gtZWQyNTUxOQAAACC+ELBVlQ/uEG6hMibDksO57uveMoq2M4fQSfRwXAw9XQ
AAAECCNCVrlI91K60/HZzv06RHuh5Ln5Csu1CfZlBTF5FZnb4QsFWVD+4QbqEyJsOSw7nu
694yirYzh9BJ9HBcDD1dAAAACnRlc3RAbG9jYWwBAgM=
-----END OPENSSH PRIVATE KEY-----
";
    const KEYGEN_PUBLIC: &str =
        "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIL4QsFWVD+4QbqEyJsOSw7nu694yirYzh9BJ9HBcDD1d test@local";

    #[test]
    fn reads_what_ssh_keygen_writes() {
        let seed = parse_private_pem(KEYGEN_PRIVATE).expect("parses");
        let public = parse_public_line(KEYGEN_PUBLIC).expect("parses");
        // The seed regenerates the public half the tool wrote beside it.
        let derived = iroh::SecretKey::from_bytes(&seed).public();
        assert_eq!(derived.as_bytes(), &public);
        assert_eq!(public_line(&public, "test@local"), KEYGEN_PUBLIC);
    }

    #[test]
    fn round_trips_its_own_files() {
        let key = iroh::SecretKey::from_bytes(&[7; 32]);
        let public = *key.public().as_bytes();
        let pem = private_pem(&key.to_bytes(), &public, "phone");
        assert!(pem.starts_with(PEM_BEGIN));
        assert!(pem.lines().all(|l| l.len() <= 70 || l.starts_with("-----")));
        assert_eq!(parse_private_pem(&pem), Some(key.to_bytes()));
        assert_eq!(parse_public_line(&public_line(&public, "phone")), Some(public));
    }

    #[test]
    fn fingerprint_matches_ssh_keygen() {
        // What `ssh-keygen -lf` printed for KEYGEN_PUBLIC.
        let public = parse_public_line(KEYGEN_PUBLIC).unwrap();
        assert_eq!(
            fingerprint(&public),
            "SHA256:uWmr3YtjKWwmwUpEiOjGq1oW7LZ4qunMlKvzWO7j/68"
        );
    }

    #[test]
    fn rejects_what_is_not_an_ed25519_key() {
        assert_eq!(parse_public_line("ssh-rsa AAAAB3NzaC1yc2E= x"), None);
        assert_eq!(parse_public_line("ssh-ed25519 !!!"), None);
        assert_eq!(parse_public_line(""), None);
        assert_eq!(parse_private_pem("nope"), None);
    }
}
