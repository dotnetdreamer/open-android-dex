//! Pairing: getting a computer and a phone to trust each other with nothing
//! between them but eight characters read off the phone's screen.
//!
//! The hard part is not the trust, it is the *finding*. The phone may be on
//! mobile data behind carrier-grade NAT and the computer on a hotel's Wi-Fi;
//! neither has an address the other could type, and there is no account or
//! server of ours to meet at. So the code is turned into a place to meet:
//!
//! 1. Both sides stretch the code (PBKDF2, see [`PairKeys::derive`]) into 64
//!    bytes. The first 32 are an Ed25519 secret key; the last 32 a MAC key.
//! 2. The phone brings up a second, short-lived iroh endpoint with that secret
//!    key. iroh publishes it the way it publishes any endpoint, so the code's
//!    public key is now dialable from anywhere — by whoever knows the code, and
//!    only by them, since nobody else can compute the key.
//! 3. The computer dials that key *from its own permanent identity*, so the
//!    phone learns the computer's real endpoint id from the TLS handshake
//!    rather than from anything the computer merely claims.
//! 4. Each side proves it holds the code with a MAC over the transcript, and
//!    the phone answers with its own permanent id and its SSH host key.
//!
//! After that the code is spent: the pairing endpoint closes, and the two
//! permanent identities are all either side ever uses again.
//!
//! Why the stretch is slow on purpose: the code's public key is published to a
//! public DNS server for as long as pairing is open. 40 bits is far beyond what
//! anyone can enumerate in the ten minutes a code lives, but 100,000 PBKDF2
//! rounds per guess (22 ms on an Apple-silicon Mac, measured; a little more on a
//! phone, and paid once per pairing) costs us nothing and puts offline guessing
//! out of reach even for a published record.

use iroh::{EndpointId, SecretKey};

use crate::crypto::{ct_eq, hmac_sha256, pbkdf2_sha256};

/// Characters in a code. 8 × 5 bits = 40 bits.
pub const CODE_LEN: usize = 8;

/// Crockford's base32: no I, L, O or U, so nothing on the screen can be misread
/// as something else. [`normalize`] folds the lookalikes back in.
const ALPHABET: &[u8; 32] = b"0123456789ABCDEFGHJKMNPQRSTVWXYZ";

const KDF_SALT: &[u8] = b"openandroiddex/remote/pair/v1";
const KDF_ROUNDS: u32 = 100_000;

/// A fresh random code, canonical form (no separator).
pub fn generate() -> String {
    let mut raw = [0u8; CODE_LEN];
    getrandom::fill(&mut raw).expect("the system random source is unavailable");
    raw.iter()
        .map(|b| ALPHABET[(*b & 31) as usize] as char)
        .collect()
}

/// Canonical form of whatever the user typed, or `None` if it is not a code.
///
/// Case, spaces and dashes are ignored, and the characters Crockford leaves out
/// are read as the ones they look like (O→0, I and L→1). U is not folded: it is
/// left out of the alphabet so that no code reads as a word, not because it
/// resembles anything.
pub fn normalize(input: &str) -> Option<String> {
    let mut out = String::with_capacity(CODE_LEN);
    for c in input.chars() {
        let c = match c.to_ascii_uppercase() {
            ' ' | '-' | '_' | '.' => continue,
            'O' => '0',
            'I' | 'L' => '1',
            c => c,
        };
        if !c.is_ascii() || !ALPHABET.contains(&(c as u8)) {
            return None;
        }
        out.push(c);
    }
    (out.len() == CODE_LEN).then_some(out)
}

/// How a code is shown: two groups of four, easy to read out and to check.
pub fn display(code: &str) -> String {
    match normalize(code) {
        Some(c) => format!("{}-{}", &c[..4], &c[4..]),
        None => code.to_string(),
    }
}

/// Everything a code stands for.
pub struct PairKeys {
    /// The secret key of the phone's short-lived pairing endpoint.
    pub endpoint: SecretKey,
    mac_key: [u8; 32],
}

impl PairKeys {
    /// Stretch a code into its keys. `None` if `code` is not a code.
    pub fn derive(code: &str) -> Option<Self> {
        let code = normalize(code)?;
        let mut okm = [0u8; 64];
        pbkdf2_sha256(code.as_bytes(), KDF_SALT, KDF_ROUNDS, &mut okm);
        let mut seed = [0u8; 32];
        seed.copy_from_slice(&okm[..32]);
        let mut mac_key = [0u8; 32];
        mac_key.copy_from_slice(&okm[32..]);
        Some(PairKeys {
            endpoint: SecretKey::from_bytes(&seed),
            mac_key,
        })
    }

    /// Where the phone listens while this code is open.
    pub fn endpoint_id(&self) -> EndpointId {
        self.endpoint.public()
    }

    /// The computer's proof: it knows the code, it is the endpoint that dialled,
    /// and these are the name and key it wants trusted.
    pub fn computer_proof(&self, computer: &EndpointId, name: &str, os: &str, ssh_key: &str) -> [u8; 32] {
        self.mac(&[
            b"computer",
            computer.as_bytes(),
            self.endpoint_id().as_bytes(),
            name.as_bytes(),
            os.as_bytes(),
            ssh_key.as_bytes(),
        ])
    }

    /// The phone's answer: it knows the code too, and this is the permanent id
    /// and host key the computer should trust from now on.
    pub fn phone_proof(&self, phone: &EndpointId, computer: &EndpointId, host_key: &str) -> [u8; 32] {
        self.mac(&[
            b"phone",
            phone.as_bytes(),
            computer.as_bytes(),
            host_key.as_bytes(),
        ])
    }

    /// Check a hex-encoded proof against the expected one.
    pub fn verify(expected: &[u8; 32], got_hex: &str) -> bool {
        match data_encoding::HEXLOWER_PERMISSIVE.decode(got_hex.as_bytes()) {
            Ok(got) => ct_eq(expected, &got),
            Err(_) => false,
        }
    }

    /// A MAC over length-prefixed fields, so no two different transcripts can
    /// concatenate to the same bytes ("ab"+"c" vs "a"+"bc").
    fn mac(&self, fields: &[&[u8]]) -> [u8; 32] {
        let mut framed: Vec<u8> = Vec::new();
        framed.extend_from_slice(b"oadx-pair-v1");
        for f in fields {
            framed.extend_from_slice(&(f.len() as u32).to_be_bytes());
            framed.extend_from_slice(f);
        }
        hmac_sha256(&self.mac_key, &[&framed])
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn generated_codes_are_canonical() {
        for _ in 0..200 {
            let c = generate();
            assert_eq!(c.len(), CODE_LEN);
            assert_eq!(normalize(&c).as_deref(), Some(c.as_str()));
        }
    }

    #[test]
    fn normalize_is_forgiving_about_what_people_type() {
        assert_eq!(normalize("k7qm-29xv").as_deref(), Some("K7QM29XV"));
        assert_eq!(normalize(" K7QM 29XV ").as_deref(), Some("K7QM29XV"));
        // Lookalikes fold to the digit they resemble.
        assert_eq!(normalize("OILo-1234").as_deref(), Some("01101234"));
        // Too short, too long, not in the alphabet.
        assert_eq!(normalize("K7QM29X"), None);
        assert_eq!(normalize("K7QM29XVV"), None);
        assert_eq!(normalize("K7QM29XU"), None);
        assert_eq!(normalize("K7QM29X€"), None);
    }

    #[test]
    fn display_groups_in_fours() {
        assert_eq!(display("k7qm29xv"), "K7QM-29XV");
    }

    #[test]
    fn both_sides_derive_the_same_keys_from_what_they_typed() {
        let phone = PairKeys::derive("K7QM29XV").unwrap();
        let computer = PairKeys::derive("k7qm-29xv").unwrap();
        assert_eq!(phone.endpoint_id(), computer.endpoint_id());
        assert_ne!(
            PairKeys::derive("K7QM29XW").unwrap().endpoint_id(),
            phone.endpoint_id()
        );
    }

    #[test]
    fn proofs_bind_every_field() {
        let keys = PairKeys::derive("K7QM29XV").unwrap();
        let a = SecretKey::from_bytes(&[1; 32]).public();
        let b = SecretKey::from_bytes(&[2; 32]).public();
        let p = keys.computer_proof(&a, "DESKTOP", "windows", "ssh-ed25519 AAA");
        assert!(PairKeys::verify(&p, &data_encoding::HEXLOWER.encode(&p)));
        assert_ne!(p, keys.computer_proof(&b, "DESKTOP", "windows", "ssh-ed25519 AAA"));
        assert_ne!(p, keys.computer_proof(&a, "DESKTOP2", "windows", "ssh-ed25519 AAA"));
        assert_ne!(p, keys.computer_proof(&a, "DESKTOP", "macos", "ssh-ed25519 AAA"));
        assert_ne!(p, keys.computer_proof(&a, "DESKTOP", "windows", "ssh-ed25519 AAB"));
        // The phone's proof is a different statement even over the same ids.
        assert_ne!(p, keys.phone_proof(&a, &b, "ssh-ed25519 AAA"));
        assert!(!PairKeys::verify(&p, "00"));
        assert!(!PairKeys::verify(&p, "not hex"));
    }
}
