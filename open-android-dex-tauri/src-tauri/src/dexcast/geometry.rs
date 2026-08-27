//! Letterbox and portal math, kept portable so its tests run everywhere.
//!
//! Two mappings and they must be exact inverses of each other in spirit: the
//! renderer draws the captured monitor *into* the viewer window with
//! [`fit`], and the portal maps a click in the viewer window *back onto* the
//! monitor with [`map_to_source`]. A half-pixel disagreement between the two
//! is a cursor that lands next to what the user aimed at, which reads as
//! "the mouse doesn't work" — the one verdict this feature cannot afford,
//! given the phone-side mouse story is already fragile.

/// Where a `src_w`×`src_h` image sits inside a `dst_w`×`dst_h` surface when
/// scaled to fit without distortion: `(x, y, w, h)` in surface coordinates.
pub fn fit(src_w: f32, src_h: f32, dst_w: f32, dst_h: f32) -> (f32, f32, f32, f32) {
    if src_w <= 0.0 || src_h <= 0.0 || dst_w <= 0.0 || dst_h <= 0.0 {
        return (0.0, 0.0, 0.0, 0.0);
    }
    let scale = (dst_w / src_w).min(dst_h / src_h);
    let w = src_w * scale;
    let h = src_h * scale;
    ((dst_w - w) / 2.0, (dst_h - h) / 2.0, w, h)
}

/// A point in the destination surface, mapped back to source pixels.
/// `None` when the point falls in the letterbox bars — a click there should
/// do nothing, not teleport the cursor to a clamped edge.
pub fn map_to_source(
    px: f32,
    py: f32,
    fit_rect: (f32, f32, f32, f32),
    src_w: f32,
    src_h: f32,
) -> Option<(i32, i32)> {
    let (fx, fy, fw, fh) = fit_rect;
    if fw <= 0.0 || fh <= 0.0 {
        return None;
    }
    if px < fx || py < fy || px >= fx + fw || py >= fy + fh {
        return None;
    }
    let sx = (px - fx) / fw * src_w;
    let sy = (py - fy) / fh * src_h;
    // Clamp to the last pixel: px == fx+fw-ε maps to src_w-ε, but rounding
    // must never hand back src_w itself, which is outside the monitor.
    Some((
        (sx as i32).clamp(0, src_w as i32 - 1),
        (sy as i32).clamp(0, src_h as i32 - 1),
    ))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn wide_window_pillarboxes() {
        // 1920x1080 into 2000x1000: height-limited, bars left and right.
        let (x, y, w, h) = fit(1920.0, 1080.0, 2000.0, 1000.0);
        assert_eq!(y, 0.0);
        assert!((h - 1000.0).abs() < 0.01);
        let expect_w = 1920.0 * (1000.0 / 1080.0);
        assert!((w - expect_w).abs() < 0.01);
        assert!((x - (2000.0 - expect_w) / 2.0).abs() < 0.01);
    }

    #[test]
    fn tall_window_letterboxes() {
        let (x, y, w, h) = fit(1920.0, 1080.0, 960.0, 1080.0);
        assert_eq!(x, 0.0);
        assert!((w - 960.0).abs() < 0.01);
        assert!((h - 540.0).abs() < 0.01);
        assert!((y - 270.0).abs() < 0.01);
    }

    #[test]
    fn round_trips_through_the_center() {
        let rect = fit(1920.0, 1080.0, 1000.0, 1000.0);
        let (cx, cy) = (500.0, 500.0);
        let (sx, sy) = map_to_source(cx, cy, rect, 1920.0, 1080.0).unwrap();
        assert!((sx - 960).abs() <= 1, "center x mapped to {sx}");
        assert!((sy - 540).abs() <= 1, "center y mapped to {sy}");
    }

    #[test]
    fn bars_swallow_clicks() {
        // Tall window: video occupies y 270..810; a click in the top bar is
        // nowhere on the monitor.
        let rect = fit(1920.0, 1080.0, 960.0, 1080.0);
        assert_eq!(map_to_source(480.0, 100.0, rect, 1920.0, 1080.0), None);
        assert!(map_to_source(480.0, 500.0, rect, 1920.0, 1080.0).is_some());
    }

    #[test]
    fn edges_stay_inside_the_monitor() {
        let rect = fit(1920.0, 1080.0, 1920.0, 1080.0);
        let (sx, sy) = map_to_source(1919.9, 1079.9, rect, 1920.0, 1080.0).unwrap();
        assert!(sx <= 1919 && sy <= 1079);
        assert_eq!(map_to_source(0.0, 0.0, rect, 1920.0, 1080.0), Some((0, 0)));
    }

    #[test]
    fn degenerate_sizes_do_not_divide_by_zero() {
        assert_eq!(fit(0.0, 0.0, 100.0, 100.0), (0.0, 0.0, 0.0, 0.0));
        assert_eq!(map_to_source(1.0, 1.0, (0.0, 0.0, 0.0, 0.0), 1920.0, 1080.0), None);
    }
}
