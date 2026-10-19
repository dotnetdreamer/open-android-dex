//! The two primitives pairing needs that iroh's dependency tree does not carry
//! already: HMAC-SHA256 and PBKDF2 over it.
//!
//! Both are a dozen lines on top of `sha2`, which is in the tree for iroh's own
//! use, and both are checked below against the published test vectors (RFC 4231
//! for HMAC, the RFC 7914 §11 vectors for PBKDF2-HMAC-SHA256). Writing them here
//! is cheaper than keeping two more crates' digest versions in step with iroh's.

use sha2::{Digest, Sha256};
use subtle::ConstantTimeEq;

const BLOCK: usize = 64;

/// The inner and outer hash states with the key already absorbed. PBKDF2
/// computes hundreds of thousands of MACs under one key, and starting each one
/// from these clones halves the hashing it does.
#[derive(Clone)]
struct Keyed {
    inner: Sha256,
    outer: Sha256,
}

impl Keyed {
    fn new(key: &[u8]) -> Self {
        let mut k = [0u8; BLOCK];
        if key.len() > BLOCK {
            k[..32].copy_from_slice(&Sha256::digest(key));
        } else {
            k[..key.len()].copy_from_slice(key);
        }
        let mut ipad = [0x36u8; BLOCK];
        let mut opad = [0x5cu8; BLOCK];
        for i in 0..BLOCK {
            ipad[i] ^= k[i];
            opad[i] ^= k[i];
        }
        let mut inner = Sha256::new();
        inner.update(ipad);
        let mut outer = Sha256::new();
        outer.update(opad);
        Keyed { inner, outer }
    }

    fn mac(&self, parts: &[&[u8]]) -> [u8; 32] {
        let mut inner = self.inner.clone();
        for p in parts {
            inner.update(p);
        }
        let mut outer = self.outer.clone();
        outer.update(inner.finalize());
        let mut out = [0u8; 32];
        out.copy_from_slice(&outer.finalize());
        out
    }
}

/// HMAC-SHA256 over the concatenation of `parts`.
pub fn hmac_sha256(key: &[u8], parts: &[&[u8]]) -> [u8; 32] {
    Keyed::new(key).mac(parts)
}

/// PBKDF2-HMAC-SHA256, filling all of `out`.
pub fn pbkdf2_sha256(password: &[u8], salt: &[u8], rounds: u32, out: &mut [u8]) {
    let keyed = Keyed::new(password);
    for (i, chunk) in out.chunks_mut(32).enumerate() {
        let index = (i as u32 + 1).to_be_bytes();
        let mut u = keyed.mac(&[salt, &index]);
        let mut t = u;
        for _ in 1..rounds {
            u = keyed.mac(&[&u]);
            for (a, b) in t.iter_mut().zip(u.iter()) {
                *a ^= b;
            }
        }
        chunk.copy_from_slice(&t[..chunk.len()]);
    }
}

/// Compare two MACs without leaking where they first differ.
pub fn ct_eq(a: &[u8], b: &[u8]) -> bool {
    a.len() == b.len() && bool::from(a.ct_eq(b))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn hex(b: &[u8]) -> String {
        data_encoding::HEXLOWER.encode(b)
    }

    // RFC 4231, test cases 1, 2 and 6 (6 is the key longer than one block).
    #[test]
    fn hmac_rfc4231() {
        assert_eq!(
            hex(&hmac_sha256(&[0x0b; 20], &[b"Hi There"])),
            "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7"
        );
        assert_eq!(
            hex(&hmac_sha256(b"Jefe", &[b"what do ya want ", b"for nothing?"])),
            "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843"
        );
        assert_eq!(
            hex(&hmac_sha256(
                &[0xaa; 131],
                &[b"Test Using Larger Than Block-Size Key - Hash Key First"]
            )),
            "60e431591ee0b67f0d8a26aacbf5b77f8e0bc6213728c5140546040f0ee37f54"
        );
    }

    // RFC 7914 §11: PBKDF2-HMAC-SHA256 with P="passwd" S="salt" c=1 dkLen=64,
    // and P="Password" S="NaCl" c=80000 dkLen=64.
    #[test]
    fn pbkdf2_rfc7914() {
        let mut out = [0u8; 64];
        pbkdf2_sha256(b"passwd", b"salt", 1, &mut out);
        assert_eq!(
            hex(&out),
            "55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc\
             49ca9cccf179b645991664b39d77ef317c71b845b1e30bd509112041d3a19783"
        );
        pbkdf2_sha256(b"Password", b"NaCl", 80000, &mut out);
        assert_eq!(
            hex(&out),
            "4ddcd8f60b98be21830cee5ef22701f9641a4418d04c0414aeff08876b34ab56\
             a1d425a1225833549adb841b51c9b3176a272bdebba1d078478f62b397f33c8d"
        );
    }

    #[test]
    fn constant_time_compare() {
        assert!(ct_eq(b"abc", b"abc"));
        assert!(!ct_eq(b"abc", b"abd"));
        assert!(!ct_eq(b"abc", b"ab"));
    }
}
