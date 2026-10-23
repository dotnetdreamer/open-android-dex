//! H.264 as the phone's encoder writes it — Annex B: each NAL unit after a
//! start code, the parameter sets in a buffer of their own — and as macOS's
//! decoder takes it: each NAL unit after its length, the parameter sets in the
//! stream's format description instead (mac/video.rs). Pure functions, so they
//! are checked on any computer.

/// A NAL unit's type: 5 a key frame's slice, 7 and 8 the parameter sets, 9 a delimiter.
pub fn nal_type(nal: &[u8]) -> u8 {
    nal.first().map_or(0, |b| b & 0x1F)
}

/// The NAL units of an Annex B buffer, without their start codes. A unit
/// never ends in a zero byte, so the zeros before a start code are the
/// start code's (or padding), not the unit's.
pub fn nal_units(data: &[u8]) -> Vec<&[u8]> {
    let mut out = Vec::new();
    let mut start: Option<usize> = None;
    let mut i = 0;
    while i + 2 < data.len() {
        if data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1 {
            if let Some(s) = start {
                push_trimmed(&mut out, &data[s..i]);
            }
            i += 3;
            start = Some(i);
        } else {
            i += 1;
        }
    }
    if let Some(s) = start {
        push_trimmed(&mut out, &data[s..]);
    }
    out
}

fn push_trimmed<'a>(out: &mut Vec<&'a [u8]>, mut nal: &'a [u8]) {
    while let [rest @ .., 0] = nal {
        nal = rest;
    }
    if !nal.is_empty() {
        out.push(nal);
    }
}

/// The sequence and picture parameter sets among a buffer's units, if it has both.
pub fn parameter_sets(units: &[&[u8]]) -> Option<(Vec<u8>, Vec<u8>)> {
    let (mut sps, mut pps) = (None, None);
    for &nal in units {
        match nal_type(nal) {
            7 if sps.is_none() => sps = Some(nal.to_vec()),
            8 if pps.is_none() => pps = Some(nal.to_vec()),
            _ => {}
        }
    }
    Some((sps?, pps?))
}

/// Whether a decoder can start from this frame: it holds a key frame's slice.
pub fn has_idr(units: &[&[u8]]) -> bool {
    units.iter().any(|n| nal_type(n) == 5)
}

/// A frame as macOS's decoder takes it: each NAL unit after its length (four
/// bytes, big-endian), less the parameter sets, which the format description
/// carries, and the delimiters, which it does not want.
pub fn length_prefixed(units: &[&[u8]]) -> Vec<u8> {
    let mut out = Vec::with_capacity(units.iter().map(|u| u.len() + 4).sum());
    for &nal in units {
        if matches!(nal_type(nal), 7 | 8 | 9) {
            continue;
        }
        out.extend_from_slice(&(nal.len() as u32).to_be_bytes());
        out.extend_from_slice(nal);
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    const SPS: [u8; 4] = [0x67, 0x42, 0xC0, 0x1F];
    const PPS: [u8; 3] = [0x68, 0xCE, 0x3C];
    const IDR: [u8; 5] = [0x65, 0x88, 0x84, 0x00, 0x21];
    const SLICE: [u8; 3] = [0x41, 0x9A, 0x02];

    fn annex_b(units: &[&[u8]], four: bool) -> Vec<u8> {
        let mut v = Vec::new();
        for u in units {
            v.extend_from_slice(if four { &[0, 0, 0, 1] } else { &[0, 0, 1] });
            v.extend_from_slice(u);
        }
        v
    }

    #[test]
    fn units_split_on_either_start_code() {
        for four in [false, true] {
            let data = annex_b(&[&SPS, &PPS, &IDR], four);
            assert_eq!(nal_units(&data), vec![&SPS[..], &PPS[..], &IDR[..]]);
        }
        // Mixed, with padding after the last unit.
        let mut data = annex_b(&[&SPS], true);
        data.extend_from_slice(&annex_b(&[&PPS], false));
        data.extend_from_slice(&[0, 0]);
        assert_eq!(nal_units(&data), vec![&SPS[..], &PPS[..]]);
        // A zero inside a unit is the unit's.
        assert_eq!(nal_units(&annex_b(&[&IDR, &SLICE], true))[0], &IDR[..]);
        assert!(nal_units(&[0x65, 0x88]).is_empty());
    }

    #[test]
    fn the_parameter_sets_are_found() {
        let config = annex_b(&[&SPS, &PPS], true);
        assert_eq!(parameter_sets(&nal_units(&config)), Some((SPS.to_vec(), PPS.to_vec())));
        assert_eq!(parameter_sets(&nal_units(&annex_b(&[&SPS], true))), None);
        assert!(has_idr(&nal_units(&annex_b(&[&SPS, &PPS, &IDR], true))));
        assert!(!has_idr(&nal_units(&annex_b(&[&SLICE], true))));
    }

    #[test]
    fn a_frame_becomes_length_prefixed_without_its_parameter_sets() {
        let frame = annex_b(&[&[0x09, 0xF0], &SPS, &PPS, &IDR, &SLICE], true);
        let mut want = Vec::new();
        want.extend_from_slice(&5u32.to_be_bytes());
        want.extend_from_slice(&IDR);
        want.extend_from_slice(&3u32.to_be_bytes());
        want.extend_from_slice(&SLICE);
        assert_eq!(length_prefixed(&nal_units(&frame)), want);
    }
}
