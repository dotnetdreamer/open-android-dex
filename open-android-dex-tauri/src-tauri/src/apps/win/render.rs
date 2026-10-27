//! An app window's picture: H.264 from the phone, decoded on this computer's
//! GPU and put on screen with nothing in between (doc/apps-on-pc.md, "The
//! picture").
//!
//! The path is the one the latency rule allows (scrcpy's shape, never a
//! browser's): Media Foundation's H.264 decoder, given the window's own D3D11
//! device, so it decodes in hardware (DXVA) into a texture that never leaves
//! the GPU; a pixel shader turns that NV12 into the window's colours and size
//! in one draw (the D3D11 video processor, where the decoder's surfaces cannot
//! be sampled); a flip-model swap chain that holds one frame at most presents
//! it. The decoder runs in low-latency mode — a frame in, the
//! same frame out — and the phone never sends B-frames, so nothing waits on a
//! frame that comes later. No CPU touches a pixel.
//!
//! The title bar is part of the same back buffer, put there after the picture,
//! so the window and its bar are one surface and can never separate. Direct2D
//! draws it only when it changes (a hover, the window's focus, its width),
//! into a texture of its own; each frame copies that texture on the GPU. A
//! note under the bar — where the sound went, how a drop goes — is the same:
//! drawn once for what it says, copied while it lasts. A frame costs the CPU
//! no text, no brushes and no Direct2D at all. Full screen, the picture has
//! the whole window, and the small bar that comes down from the top is put
//! there the same way, as many of its rows as are down, over the little tab
//! that shows where it is while it is up; a note goes just under it.
//!
//! Everything here lives on the window's render thread.

use std::collections::HashMap;
use std::mem::ManuallyDrop;

use windows::core::{s, w, Interface, Result, BOOL, GUID, HSTRING, PCSTR, PCWSTR};
use windows::Win32::Foundation::{HANDLE, HMODULE, HWND, RECT};
use windows::Win32::Graphics::Direct2D::Common::{
    D2D1_ALPHA_MODE_IGNORE, D2D1_ALPHA_MODE_PREMULTIPLIED, D2D1_COLOR_F, D2D1_PIXEL_FORMAT, D2D_RECT_F,
};
use windows::Win32::Graphics::Direct2D::{
    D2D1CreateFactory, ID2D1Bitmap1, ID2D1DeviceContext, ID2D1Factory1, ID2D1SolidColorBrush,
    D2D1_BITMAP_OPTIONS_CANNOT_DRAW, D2D1_BITMAP_OPTIONS_NONE, D2D1_BITMAP_OPTIONS_TARGET, D2D1_INTERPOLATION_MODE_LINEAR,
    D2D1_BITMAP_PROPERTIES1, D2D1_DEVICE_CONTEXT_OPTIONS_NONE, D2D1_DRAW_TEXT_OPTIONS_CLIP,
    D2D1_DRAW_TEXT_OPTIONS_NONE, D2D1_FACTORY_OPTIONS, D2D1_FACTORY_TYPE_SINGLE_THREADED,
    D2D1_TEXT_ANTIALIAS_MODE_GRAYSCALE,
};
use windows::Win32::Graphics::Direct3D::Fxc::{D3DCompile, D3DCOMPILE_OPTIMIZATION_LEVEL3};
use windows::Win32::Graphics::Direct3D::{
    ID3DBlob, ID3DInclude, D3D_DRIVER_TYPE_HARDWARE, D3D_FEATURE_LEVEL, D3D_FEATURE_LEVEL_10_0,
    D3D_FEATURE_LEVEL_10_1, D3D_FEATURE_LEVEL_11_0, D3D_FEATURE_LEVEL_11_1, D3D_PRIMITIVE_TOPOLOGY_TRIANGLESTRIP,
    D3D_SRV_DIMENSION_TEXTURE2DARRAY,
};
use windows::Win32::Graphics::Direct3D11::*;
use windows::Win32::Graphics::DirectWrite::{
    DWriteCreateFactory, IDWriteFactory, IDWriteFontCollection, IDWriteTextFormat, DWRITE_FACTORY_TYPE_SHARED,
    DWRITE_FONT_STRETCH_NORMAL, DWRITE_FONT_STYLE_NORMAL, DWRITE_FONT_WEIGHT_NORMAL,
    DWRITE_FONT_WEIGHT_SEMI_BOLD, DWRITE_MEASURING_MODE_NATURAL, DWRITE_PARAGRAPH_ALIGNMENT_CENTER,
    DWRITE_TEXT_ALIGNMENT_CENTER, DWRITE_TEXT_ALIGNMENT_LEADING, DWRITE_TEXT_METRICS, DWRITE_TRIMMING,
    DWRITE_TRIMMING_GRANULARITY_CHARACTER, DWRITE_TRIMMING_GRANULARITY_WORD, DWRITE_WORD_WRAPPING_NO_WRAP,
};
use windows::Win32::Graphics::Dxgi::Common::{
    DXGI_ALPHA_MODE_IGNORE, DXGI_FORMAT, DXGI_FORMAT_B8G8R8A8_UNORM, DXGI_FORMAT_NV12, DXGI_FORMAT_R8G8_UNORM,
    DXGI_FORMAT_R8_UNORM, DXGI_FORMAT_UNKNOWN, DXGI_RATIONAL, DXGI_SAMPLE_DESC,
};
use windows::Win32::Graphics::Dwm::{DwmGetCompositionTimingInfo, DWM_TIMING_INFO};
use windows::Win32::Graphics::Dxgi::{
    IDXGIAdapter, IDXGIDevice, IDXGIFactory2, IDXGISurface, IDXGISwapChain1, IDXGISwapChain2,
    DXGI_FRAME_STATISTICS, DXGI_MWA_NO_ALT_ENTER, DXGI_PRESENT, DXGI_SCALING_STRETCH, DXGI_SWAP_CHAIN_DESC1,
    DXGI_SWAP_CHAIN_FLAG, DXGI_SWAP_CHAIN_FLAG_FRAME_LATENCY_WAITABLE_OBJECT, DXGI_SWAP_EFFECT_FLIP_DISCARD,
    DXGI_USAGE_RENDER_TARGET_OUTPUT,
};
use windows::Win32::Graphics::Imaging::{
    CLSID_WICImagingFactory, GUID_WICPixelFormat32bppPBGRA, IWICImagingFactory, WICBitmapDitherTypeNone,
    WICBitmapPaletteTypeMedianCut, WICDecodeMetadataCacheOnDemand,
};
use windows::Win32::Media::MediaFoundation::*;
use windows::Win32::System::Com::{CoCreateInstance, CLSCTX_INPROC_SERVER};
use windows::Win32::System::Threading::WaitForSingleObject;

use super::frame::{self, Bar, Hit, TopBar};

/// What the bar and the empty window say, from the window's state.
pub struct Look<'a> {
    pub label: &'a str,
    pub device: &'a str,
    pub active: bool,
    pub maximized: bool,
    pub hover: Hit,
    pub pressed: Hit,
    pub dark: bool,
    /// Said in the middle of the window while there is no picture: what is happening, or what went wrong.
    pub message: Option<&'a str>,
    /// Said for a while just under the bar: where the sound went, how a drop goes.
    pub note: Option<&'a str>,
    /// No title bar: the picture is the whole window.
    pub full_screen: bool,
    /// How far down the full-screen bar is, 0 to 1.
    pub top_bar: f32,
}

/// One decoded picture, held until the next replaces it — the window is
/// redrawn from it on a resize or a hover without asking the phone.
struct Picture {
    texture: ID3D11Texture2D,
    slice: u32,
    /// The decoder's sample, kept so its surface is not reused under us.
    _sample: Option<IMFSample>,
    /// The part of it that is the phone's display (the rest is the encoder's padding).
    content: (u32, u32),
}

pub struct Renderer {
    device: ID3D11Device,
    context: ID3D11DeviceContext,
    /// For DiscardView (D3D11.1, every Windows this app runs on).
    context1: Option<ID3D11DeviceContext1>,
    video_device: ID3D11VideoDevice,
    video_context: ID3D11VideoContext,
    swap: IDXGISwapChain2,
    waitable: HANDLE,
    size: (u32, u32),
    d2d: ID2D1DeviceContext,
    dwrite: IDWriteFactory,
    text: IDWriteTextFormat,
    subtle: IDWriteTextFormat,
    glyphs: IDWriteTextFormat,
    big: IDWriteTextFormat,
    /// A note's: wrapped, two lines at most, and an ellipsis for the rest.
    note_text: IDWriteTextFormat,
    /// The full-screen bar's name, in its middle.
    centred: IDWriteTextFormat,
    /// The text formats above are for `scale` (made once per scale, not per frame).
    formats_ready: bool,
    icon: Option<ID2D1Bitmap1>,
    /// The back buffer and its views, for as long as the swap chain keeps its size.
    target: Option<Target>,
    /// The bar as last drawn, the full-screen bar, and its tab.
    bar: Option<BarCache>,
    /// The note under the bar as last drawn.
    note: Option<NoteCache>,
    top_bar: Option<BarCache>,
    handle: Option<BarCache>,
    decoder: Option<Decoder>,
    /// The picture through a pixel shader, if this computer's GPU took it.
    shading: Option<Shading>,
    /// Said in the log once: which of the two draws the picture.
    said_path: bool,
    vp: Option<Processor>,
    picture: Option<Picture>,
    /// The phone's display: what the encoder's picture holds, cropped of its padding.
    content: (u32, u32),
    /// The phone's colours, from the stream: BT.709 rather than BT.601, and full range rather than limited.
    bt709: bool,
    full_range: bool,
    scale: f32,
}

/// The swap chain's buffer 0 — the D3D11 runtime renames it to each frame's
/// back buffer in turn — and what draws into it.
struct Target {
    back: ID3D11Texture2D,
    d2d: ID2D1Bitmap1,
    /// The video processor's view of it, and the enumerator that view was made with.
    vp_view: Option<(usize, ID3D11VideoProcessorOutputView)>,
    /// The shader's view of it.
    rtv: Option<ID3D11RenderTargetView>,
}

/// The title bar, drawn once for what it shows.
/// Which of the bars a cached texture is.
#[derive(Clone, Copy)]
enum Cached {
    Title,
    Top,
    Handle,
}

struct BarCache {
    texture: ID3D11Texture2D,
    d2d: ID2D1Bitmap1,
    size: (u32, u32),
    key: Option<BarKey>,
}

/// Everything the bar's pixels depend on.
#[derive(Clone, PartialEq)]
struct BarKey {
    width: u32,
    height: i32,
    scale: u32,
    label: String,
    device: String,
    active: bool,
    maximized: bool,
    hover: Hit,
    pressed: Hit,
    dark: bool,
}

impl BarKey {
    fn of(width: u32, height: i32, scale: f32, look: &Look) -> BarKey {
        BarKey {
            width,
            height,
            scale: scale.to_bits(),
            label: look.label.to_string(),
            device: look.device.to_string(),
            active: look.active,
            maximized: look.maximized,
            hover: look.hover,
            pressed: look.pressed,
            dark: look.dark,
        }
    }
}

/// A note, in a strip of its own just under the bar, drawn once for what it
/// says and copied each frame as the bar is: a note costs a frame no
/// Direct2D either.
struct NoteCache {
    texture: ID3D11Texture2D,
    size: (u32, u32),
    key: NoteKey,
}

/// Everything the note strip's pixels depend on.
#[derive(Clone, PartialEq)]
struct NoteKey {
    width: u32,
    scale: u32,
    text: String,
    dark: bool,
}

struct Processor {
    enumerator: ID3D11VideoProcessorEnumerator,
    processor: ID3D11VideoProcessor,
    input: (u32, u32),
    output: (u32, u32),
    views: HashMap<(usize, u32), ID3D11VideoProcessorInputView>,
}

impl Renderer {
    pub fn new(hwnd: HWND, width: u32, height: u32, icon_png: Option<&[u8]>) -> Result<Self> {
        unsafe {
            let mut device = None;
            let mut context = None;
            let levels: [D3D_FEATURE_LEVEL; 4] =
                [D3D_FEATURE_LEVEL_11_1, D3D_FEATURE_LEVEL_11_0, D3D_FEATURE_LEVEL_10_1, D3D_FEATURE_LEVEL_10_0];
            D3D11CreateDevice(
                None::<&IDXGIAdapter>,
                D3D_DRIVER_TYPE_HARDWARE,
                HMODULE::default(),
                D3D11_CREATE_DEVICE_BGRA_SUPPORT | D3D11_CREATE_DEVICE_VIDEO_SUPPORT,
                Some(&levels),
                D3D11_SDK_VERSION,
                Some(&mut device),
                None,
                Some(&mut context),
            )?;
            let device: ID3D11Device = device.unwrap();
            let context: ID3D11DeviceContext = context.unwrap();
            // Media Foundation's decoder uses the device from threads of its own.
            if let Ok(mt) = context.cast::<ID3D11Multithread>() {
                let _ = mt.SetMultithreadProtected(true);
            }
            let video_device: ID3D11VideoDevice = device.cast()?;
            let video_context: ID3D11VideoContext = context.cast()?;

            let dxgi_device: IDXGIDevice = device.cast()?;
            let adapter = dxgi_device.GetAdapter()?;
            let factory: IDXGIFactory2 = adapter.GetParent()?;
            let desc = DXGI_SWAP_CHAIN_DESC1 {
                Width: width.max(1),
                Height: height.max(1),
                Format: DXGI_FORMAT_B8G8R8A8_UNORM,
                Stereo: false.into(),
                SampleDesc: DXGI_SAMPLE_DESC { Count: 1, Quality: 0 },
                BufferUsage: DXGI_USAGE_RENDER_TARGET_OUTPUT,
                // Three, so the next back buffer is free even while the
                // compositor still holds the last two; the latency below stays
                // one frame. With two, binding it cost 0.9 M CPU cycles a frame.
                BufferCount: 3,
                // While a resize waits for its new buffers, the last picture is
                // stretched to the window rather than left in a corner.
                Scaling: DXGI_SCALING_STRETCH,
                SwapEffect: DXGI_SWAP_EFFECT_FLIP_DISCARD,
                AlphaMode: DXGI_ALPHA_MODE_IGNORE,
                Flags: DXGI_SWAP_CHAIN_FLAG_FRAME_LATENCY_WAITABLE_OBJECT.0 as u32,
            };
            let swap1: IDXGISwapChain1 = factory.CreateSwapChainForHwnd(&device, hwnd, &desc, None, None)?;
            let _ = factory.MakeWindowAssociation(hwnd, DXGI_MWA_NO_ALT_ENTER);
            let swap: IDXGISwapChain2 = swap1.cast()?;
            // One frame queued at most: the newest picture, never a backlog.
            swap.SetMaximumFrameLatency(1)?;
            let waitable = swap.GetFrameLatencyWaitableObject();

            let d2d_factory: ID2D1Factory1 =
                D2D1CreateFactory(D2D1_FACTORY_TYPE_SINGLE_THREADED, Some(&D2D1_FACTORY_OPTIONS::default()))?;
            let d2d_device = d2d_factory.CreateDevice(&dxgi_device)?;
            let d2d = d2d_device.CreateDeviceContext(D2D1_DEVICE_CONTEXT_OPTIONS_NONE)?;
            d2d.SetTextAntialiasMode(D2D1_TEXT_ANTIALIAS_MODE_GRAYSCALE);

            let dwrite: IDWriteFactory = DWriteCreateFactory(DWRITE_FACTORY_TYPE_SHARED)?;
            let glyph_family = glyph_font(&dwrite);
            let text = text_format(&dwrite, w!("Segoe UI"), DWRITE_FONT_WEIGHT_NORMAL, 12.0)?;
            let subtle = text_format(&dwrite, w!("Segoe UI"), DWRITE_FONT_WEIGHT_NORMAL, 12.0)?;
            let glyphs = text_format(&dwrite, &glyph_family, DWRITE_FONT_WEIGHT_NORMAL, 10.0)?;
            glyphs.SetTextAlignment(DWRITE_TEXT_ALIGNMENT_CENTER)?;
            let big = text_format(&dwrite, w!("Segoe UI"), DWRITE_FONT_WEIGHT_SEMI_BOLD, 15.0)?;
            big.SetTextAlignment(DWRITE_TEXT_ALIGNMENT_CENTER)?;
            big.SetWordWrapping(windows::Win32::Graphics::DirectWrite::DWRITE_WORD_WRAPPING_WRAP)?;
            let note_text = note_format(&dwrite, 1.0)?;
            let centred = text_format(&dwrite, w!("Segoe UI"), DWRITE_FONT_WEIGHT_NORMAL, 12.0)?;

            let icon = icon_png.and_then(|png| decode_png(&d2d, png).ok());
            let shading = Shading::new(&device)
                .map_err(|e| log::warn!("apps: no shader for the picture ({e}); the video processor draws it"))
                .ok();
            let context1 = context.cast::<ID3D11DeviceContext1>().ok();

            let mut r = Renderer {
                device,
                context,
                context1,
                video_device,
                video_context,
                swap,
                waitable,
                size: (width.max(1), height.max(1)),
                d2d,
                dwrite,
                text,
                subtle,
                glyphs,
                big,
                note_text,
                centred,
                formats_ready: false,
                icon,
                target: None,
                bar: None,
                note: None,
                top_bar: None,
                handle: None,
                decoder: None,
                shading,
                said_path: false,
                vp: None,
                picture: None,
                content: (0, 0),
                bt709: false,
                full_range: false,
                scale: 1.0,
            };
            r.set_scale(1.0)?;
            Ok(r)
        }
    }

    /// The window's DPI, as a multiple of 96: the bar's text and buttons follow it.
    pub fn set_scale(&mut self, scale: f32) -> Result<()> {
        if self.formats_ready && (scale - self.scale).abs() < 0.001 {
            return Ok(());
        }
        self.scale = scale;
        let family = glyph_font(&self.dwrite);
        unsafe {
            self.text = text_format(&self.dwrite, w!("Segoe UI"), DWRITE_FONT_WEIGHT_NORMAL, 12.0 * scale)?;
            self.subtle = text_format(&self.dwrite, w!("Segoe UI"), DWRITE_FONT_WEIGHT_NORMAL, 12.0 * scale)?;
            self.centred = text_format(&self.dwrite, w!("Segoe UI"), DWRITE_FONT_WEIGHT_NORMAL, 12.0 * scale)?;
            for f in [&self.text, &self.subtle, &self.centred] {
                f.SetParagraphAlignment(DWRITE_PARAGRAPH_ALIGNMENT_CENTER)?;
                f.SetTextAlignment(DWRITE_TEXT_ALIGNMENT_LEADING)?;
                f.SetWordWrapping(DWRITE_WORD_WRAPPING_NO_WRAP)?;
                let trim = DWRITE_TRIMMING { granularity: DWRITE_TRIMMING_GRANULARITY_CHARACTER, delimiter: 0, delimiterCount: 0 };
                f.SetTrimming(&trim, None)?;
            }
            self.centred.SetTextAlignment(DWRITE_TEXT_ALIGNMENT_CENTER)?;
            self.glyphs = text_format(&self.dwrite, &family, DWRITE_FONT_WEIGHT_NORMAL, 10.0 * scale)?;
            self.glyphs.SetTextAlignment(DWRITE_TEXT_ALIGNMENT_CENTER)?;
            self.glyphs.SetParagraphAlignment(DWRITE_PARAGRAPH_ALIGNMENT_CENTER)?;
            self.big = text_format(&self.dwrite, w!("Segoe UI"), DWRITE_FONT_WEIGHT_SEMI_BOLD, 15.0 * scale)?;
            self.big.SetTextAlignment(DWRITE_TEXT_ALIGNMENT_CENTER)?;
            self.note_text = note_format(&self.dwrite, scale)?;
        }
        self.formats_ready = true;
        Ok(())
    }

    /// The swap chain to the window's client size: every reference to the old buffers goes first.
    pub fn resize(&mut self, width: u32, height: u32) -> Result<()> {
        let (width, height) = (width.max(1), height.max(1));
        if (width, height) == self.size {
            return Ok(());
        }
        self.target = None;
        unsafe {
            self.d2d.SetTarget(None);
            self.context.ClearState();
            self.context.Flush();
            self.swap.ResizeBuffers(
                0,
                width,
                height,
                DXGI_FORMAT_UNKNOWN,
                DXGI_SWAP_CHAIN_FLAG(DXGI_SWAP_CHAIN_FLAG_FRAME_LATENCY_WAITABLE_OBJECT.0),
            )?;
        }
        self.size = (width, height);
        Ok(())
    }

    /// The stream says what it is: its coded size, and the part of it that is the display.
    pub fn video_format(&mut self, coded: (u32, u32), content: (u32, u32)) {
        self.content = content;
        if let Some(d) = &mut self.decoder {
            if d.coded != coded {
                // A new encoder (the window was resized): a new decoder for its size.
                self.decoder = None;
            }
        }
        if self.decoder.is_none() {
            match Decoder::new(&self.device, coded) {
                Ok(d) => self.decoder = Some(d),
                Err(e) => log::error!("apps: no H.264 decoder: {e}"),
            }
        }
    }

    pub fn has_decoder(&self) -> bool {
        self.decoder.is_some()
    }

    /// One unit of the stream: decoded, and kept as the picture if it made one.
    /// True if there is a new picture to show.
    pub fn decode(&mut self, data: &[u8], pts_us: u64, config: bool) -> bool {
        let Some(d) = self.decoder.as_mut() else { return false };
        if config {
            d.config = data.to_vec();
            return false;
        }
        match d.feed(data, pts_us) {
            Ok(Some(out)) => {
                if let Some((m709, full)) = d.colours() {
                    self.bt709 = m709;
                    self.full_range = full;
                }
                self.picture = Some(Picture { texture: out.0, slice: out.1, _sample: out.2, content: self.content });
                true
            }
            Ok(None) => false,
            Err(e) => {
                log::warn!("apps: decoding: {e}");
                false
            }
        }
    }

    pub fn has_picture(&self) -> bool {
        self.picture.is_some()
    }

    /// DXGI's count of the frame just presented.
    pub fn present_count(&self) -> Option<u32> {
        unsafe { self.swap.GetLastPresentCount().ok() }
    }

    /// The latest frame Windows has put on the screen: its present count, and
    /// the refresh it went out at, in QPC ticks. None where Windows keeps no
    /// such count for the window, or has none yet.
    pub fn on_screen(&self) -> Option<(u32, i64)> {
        let mut s = DXGI_FRAME_STATISTICS::default();
        unsafe { self.swap.GetFrameStatistics(&mut s).ok()? };
        if s.PresentCount == 0 || s.SyncQPCTime == 0 {
            return None;
        }
        // The time is the latest refresh's; the frame went out at an earlier one if it was not that.
        let behind = s.SyncRefreshCount.saturating_sub(s.PresentRefreshCount) as i64;
        Some((s.PresentCount, s.SyncQPCTime - behind * refresh_period_qpc()))
    }

    /// Wait (up to `ms`) until the swap chain will take a frame without queueing it behind another.
    pub fn wait_ready(&self, ms: u32) {
        unsafe {
            let _ = WaitForSingleObject(self.waitable, ms);
        }
    }

    /// Draw the window: the picture under the bar, letterboxed to it if their
    /// shapes differ for a moment (a resize the phone has not caught up with),
    /// and the bar over it all. Full screen, the picture is the whole window,
    /// and the full-screen bar is over it as far as it has come down.
    pub fn draw(&mut self, look: &Look) -> Result<()> {
        let (w, h) = self.size;
        let bar = frame::bar(w as i32, self.scale);
        let top = frame::top_bar(w as i32, self.scale);
        // The full-screen bar's rows that are down: its lowest, as it slides.
        let down = if look.full_screen {
            ((look.top_bar.clamp(0.0, 1.0) * top.rect.bottom as f32).round() as u32).min(h)
        } else {
            0
        };
        let area = RECT { left: 0, top: if look.full_screen { 0 } else { bar.h }, right: w as i32, bottom: h as i32 };
        self.ensure_target()?;
        if !look.full_screen {
            self.ensure_bar(&bar, w, look)?;
        } else {
            self.ensure_handle(&top, look)?;
            if down > 0 {
                self.ensure_top_bar(&top, look)?;
            }
        }
        match look.note {
            Some(text) => self.ensure_note(&bar, w, text, look.dark)?,
            None => self.note = None,
        }
        let drew_picture = self.blit(area).unwrap_or_else(|e| {
            log::warn!("apps: showing the picture: {e}");
            false
        });
        let Some(target) = &self.target else { return Ok(()) };
        unsafe {
            if !drew_picture {
                self.d2d.SetTarget(&target.d2d);
                self.d2d.BeginDraw();
                let drew = self.draw_empty(area, look);
                let done = self.d2d.EndDraw(None, None);
                self.d2d.SetTarget(None);
                drew?;
                done?;
            }
            if !look.full_screen {
                if let Some(cache) = &self.bar {
                    let rows = D3D11_BOX { left: 0, top: 0, front: 0, right: cache.size.0.min(w), bottom: cache.size.1.min(h), back: 1 };
                    self.context.CopySubresourceRegion(&target.back, 0, 0, 0, 0, &cache.texture, 0, Some(&rows));
                }
            } else {
                // The tab, then as much of the bar as is down, over it.
                if let Some(cache) = &self.handle {
                    let rows = D3D11_BOX { left: 0, top: 0, front: 0, right: cache.size.0.min(w), bottom: cache.size.1.min(h), back: 1 };
                    let x = top.handle.left.max(0) as u32;
                    self.context.CopySubresourceRegion(&target.back, 0, x, 0, 0, &cache.texture, 0, Some(&rows));
                }
                if let (true, Some(cache)) = (down > 0, &self.top_bar) {
                    let down = down.min(cache.size.1);
                    let rows = D3D11_BOX { left: 0, top: cache.size.1 - down, front: 0, right: cache.size.0.min(w), bottom: cache.size.1, back: 1 };
                    let x = top.rect.left.max(0) as u32;
                    self.context.CopySubresourceRegion(&target.back, 0, x, 0, 0, &cache.texture, 0, Some(&rows));
                }
            }
            // The note: under the title bar — full screen, under where the bar comes down.
            if let Some(note) = &self.note {
                let y = if look.full_screen { top.rect.bottom } else { bar.h }.max(0) as u32;
                let below = h.saturating_sub(y);
                let rows = D3D11_BOX { left: 0, top: 0, front: 0, right: note.size.0.min(w), bottom: note.size.1.min(below), back: 1 };
                self.context.CopySubresourceRegion(&target.back, 0, 0, y, 0, &note.texture, 0, Some(&rows));
            }
            // Presented at once: the compositor shows it at its next refresh.
            let presented = self.swap.Present(0, DXGI_PRESENT(0)).ok();
            // Every pixel is drawn again next frame (the picture or the empty
            // window, then the bar), so what the buffer holds may go: without
            // this, the next frame's first use of it cost 0.9–2 M CPU cycles.
            if let (Some(c1), Some(rtv)) = (&self.context1, &target.rtv) {
                c1.DiscardView(rtv);
            }
            presented?;
        }
        Ok(())
    }

    /// The back buffer and its Direct2D bitmap, made again only after a resize.
    fn ensure_target(&mut self) -> Result<()> {
        if self.target.is_some() {
            return Ok(());
        }
        unsafe {
            let back: ID3D11Texture2D = self.swap.GetBuffer(0)?;
            let d2d = self.d2d_target(&back)?;
            let mut rtv = None;
            if let Err(e) = self.device.CreateRenderTargetView(&back, None, Some(&mut rtv)) {
                log::warn!("apps: no render target view of the window: {e}");
            }
            self.target = Some(Target { back, d2d, vp_view: None, rtv });
        }
        Ok(())
    }

    /// A Direct2D bitmap that draws into `texture`.
    unsafe fn d2d_target(&self, texture: &ID3D11Texture2D) -> Result<ID2D1Bitmap1> {
        let props = D2D1_BITMAP_PROPERTIES1 {
            pixelFormat: D2D1_PIXEL_FORMAT { format: DXGI_FORMAT_B8G8R8A8_UNORM, alphaMode: D2D1_ALPHA_MODE_IGNORE },
            dpiX: 96.0,
            dpiY: 96.0,
            bitmapOptions: D2D1_BITMAP_OPTIONS_TARGET | D2D1_BITMAP_OPTIONS_CANNOT_DRAW,
            colorContext: ManuallyDrop::new(None),
        };
        unsafe {
            let surface: IDXGISurface = texture.cast()?;
            self.d2d.CreateBitmapFromDxgiSurface(&surface, Some(&props))
        }
    }

    /// The bar's texture, drawn again if anything it shows has changed.
    fn ensure_bar(&mut self, bar: &Bar, width: u32, look: &Look) -> Result<()> {
        let key = BarKey::of(width, bar.h, self.scale, look);
        self.ensure_drawn(Cached::Title, (width.max(1), bar.h.max(1) as u32), key, |r| r.draw_bar(bar, width, look))
    }

    /// The full-screen bar's texture, likewise.
    fn ensure_top_bar(&mut self, top: &TopBar, look: &Look) -> Result<()> {
        let size = ((top.rect.right - top.rect.left).max(1) as u32, top.rect.bottom.max(1) as u32);
        let key = BarKey::of(size.0, top.rect.bottom, self.scale, look);
        self.ensure_drawn(Cached::Top, size, key, |r| r.draw_top_bar(top, look))
    }

    /// The full-screen bar's tab's texture, likewise.
    fn ensure_handle(&mut self, top: &TopBar, look: &Look) -> Result<()> {
        let r = top.handle;
        let size = ((r.right - r.left).max(1) as u32, r.bottom.max(1) as u32);
        let key = BarKey::of(size.0, r.bottom, self.scale, look);
        self.ensure_drawn(Cached::Handle, size, key, |re| re.draw_handle(size, look))
    }

    fn cache(&mut self, which: Cached) -> &mut Option<BarCache> {
        match which {
            Cached::Title => &mut self.bar,
            Cached::Top => &mut self.top_bar,
            Cached::Handle => &mut self.handle,
        }
    }

    /// One of the bars' textures — the title bar's, the full-screen bar's or
    /// its tab's — made again only when its size changes, drawn by `draw` again
    /// only when what it shows (`key`) does.
    fn ensure_drawn(
        &mut self,
        which: Cached,
        size: (u32, u32),
        key: BarKey,
        draw: impl Fn(&Renderer) -> Result<()>,
    ) -> Result<()> {
        if self.cache(which).as_ref().is_some_and(|c| c.key.as_ref() == Some(&key)) {
            return Ok(());
        }
        if self.cache(which).as_ref().is_none_or(|c| c.size != size) {
            let desc = D3D11_TEXTURE2D_DESC {
                Width: size.0,
                Height: size.1,
                MipLevels: 1,
                ArraySize: 1,
                Format: DXGI_FORMAT_B8G8R8A8_UNORM,
                SampleDesc: DXGI_SAMPLE_DESC { Count: 1, Quality: 0 },
                Usage: D3D11_USAGE_DEFAULT,
                BindFlags: D3D11_BIND_RENDER_TARGET.0 as u32,
                CPUAccessFlags: 0,
                MiscFlags: 0,
            };
            // The old one goes first: a window dragged wider makes one per step.
            *self.cache(which) = None;
            unsafe {
                let mut texture = None;
                self.device.CreateTexture2D(&desc, None, Some(&mut texture))?;
                let texture = texture.unwrap();
                let d2d = self.d2d_target(&texture)?;
                *self.cache(which) = Some(BarCache { texture, d2d, size, key: None });
            }
        }
        let Some(target) = self.cache(which).as_ref().map(|c| c.d2d.clone()) else { return Ok(()) };
        unsafe {
            self.d2d.SetTarget(&target);
            self.d2d.BeginDraw();
            let drew = draw(self);
            let done = self.d2d.EndDraw(None, None);
            self.d2d.SetTarget(None);
            drew?;
            done?;
        }
        if let Some(cache) = self.cache(which) {
            cache.key = Some(key);
        }
        Ok(())
    }

    /// The note's strip, drawn again only when what it says, the width, the
    /// scale or the theme changes: a line, or two for a longer note, lined up
    /// with the app's name in the bar above it.
    fn ensure_note(&mut self, bar: &Bar, width: u32, text: &str, dark: bool) -> Result<()> {
        let key = NoteKey { width, scale: self.scale.to_bits(), text: text.to_string(), dark };
        if self.note.as_ref().is_some_and(|n| n.key == key) {
            return Ok(());
        }
        let theme = Theme::of(dark);
        let s = self.scale;
        let left = bar.text_x as f32;
        let pad = (7.0 * s).round();
        let wide: Vec<u16> = text.encode_utf16().collect();
        self.note = None;
        unsafe {
            let max_w = (width as f32 - left - (12.0 * s).round()).max(40.0);
            // Measured unbounded for its lines; drawn into two at most, the ellipsis ending the second.
            let layout = self.dwrite.CreateTextLayout(&wide, &self.note_text, max_w, f32::MAX)?;
            let mut m = DWRITE_TEXT_METRICS::default();
            layout.GetMetrics(&mut m)?;
            let line = m.height / m.lineCount.max(1) as f32;
            let text_h = (line * m.lineCount.clamp(1, 2) as f32).ceil();
            let size = (width.max(1), (text_h + 2.0 * pad) as u32);
            let desc = D3D11_TEXTURE2D_DESC {
                Width: size.0,
                Height: size.1,
                MipLevels: 1,
                ArraySize: 1,
                Format: DXGI_FORMAT_B8G8R8A8_UNORM,
                SampleDesc: DXGI_SAMPLE_DESC { Count: 1, Quality: 0 },
                Usage: D3D11_USAGE_DEFAULT,
                BindFlags: D3D11_BIND_RENDER_TARGET.0 as u32,
                CPUAccessFlags: 0,
                MiscFlags: 0,
            };
            let mut texture = None;
            self.device.CreateTexture2D(&desc, None, Some(&mut texture))?;
            let texture = texture.unwrap();
            let target = self.d2d_target(&texture)?;
            self.d2d.SetTarget(&target);
            self.d2d.BeginDraw();
            self.d2d.FillRectangle(&rectf(0, 0, size.0 as i32, size.1 as i32), &self.brush(theme.note)?);
            let rect = D2D_RECT_F { left, top: pad, right: left + max_w, bottom: pad + text_h };
            self.d2d.DrawText(&wide, &self.note_text, &rect, &self.brush(theme.ink)?, D2D1_DRAW_TEXT_OPTIONS_CLIP,
                DWRITE_MEASURING_MODE_NATURAL);
            let done = self.d2d.EndDraw(None, None);
            self.d2d.SetTarget(None);
            done?;
            self.note = Some(NoteCache { texture, size, key });
        }
        Ok(())
    }

    fn blit(&mut self, area: RECT) -> Result<bool> {
        let Some(pic) = &self.picture else { return Ok(false) };
        let (texture, slice, content) = (pic.texture.clone(), pic.slice, pic.content);
        let area_w = (area.right - area.left).max(1) as u32;
        let area_h = (area.bottom - area.top).max(1) as u32;
        let mut tdesc = D3D11_TEXTURE2D_DESC::default();
        unsafe { texture.GetDesc(&mut tdesc) };
        let input = (tdesc.Width, tdesc.Height);
        let (cw, ch) = if content.0 > 0 && content.1 > 0 {
            (content.0.min(input.0), content.1.min(input.1))
        } else {
            input
        };
        // Fit, keeping the shape: one pixel for one when the display is the area's size, which it is
        // except for the moment a resize takes to reach the phone and come back.
        let fit = fit_rect(cw, ch, area_w, area_h);
        let dest = RECT {
            left: area.left + fit.0,
            top: area.top + fit.1,
            right: area.left + fit.0 + fit.2 as i32,
            bottom: area.top + fit.1 + fit.3 as i32,
        };
        let sampled = tdesc.BindFlags & D3D11_BIND_SHADER_RESOURCE.0 as u32 != 0;
        if self.shading.is_some() && sampled {
            match self.shade(&texture, slice, input, (cw, ch), dest, area) {
                Ok(()) => {
                    self.say_path("a pixel shader");
                    return Ok(true);
                }
                Err(e) => {
                    log::warn!("apps: the picture's shader failed ({e}); the video processor draws it from now on");
                    self.shading = None;
                }
            }
        }
        self.say_path(if sampled { "the video processor" } else { "the video processor (the decoder's surfaces cannot be sampled)" });
        self.process(&texture, slice, input, (cw, ch), dest, area)?;
        Ok(true)
    }

    fn say_path(&mut self, how: &str) {
        if !self.said_path {
            self.said_path = true;
            log::info!("apps: the picture is drawn by {how}");
        }
    }

    /// NV12 to the back buffer in one draw.
    fn shade(&mut self, texture: &ID3D11Texture2D, slice: u32, input: (u32, u32), (cw, ch): (u32, u32), dest: RECT, area: RECT) -> Result<()> {
        let (w, h) = self.size;
        let rows = ycbcr_rows(self.bt709, self.full_range);
        let (Some(sh), Some(target)) = (self.shading.as_mut(), self.target.as_mut()) else { return Ok(()) };
        unsafe {
            let key = (texture.as_raw() as usize, slice);
            if !sh.views.contains_key(&key) {
                let view = |format: DXGI_FORMAT| -> Result<Option<ID3D11ShaderResourceView>> {
                    let desc = D3D11_SHADER_RESOURCE_VIEW_DESC {
                        Format: format,
                        ViewDimension: D3D_SRV_DIMENSION_TEXTURE2DARRAY,
                        Anonymous: D3D11_SHADER_RESOURCE_VIEW_DESC_0 {
                            Texture2DArray: D3D11_TEX2D_ARRAY_SRV { MostDetailedMip: 0, MipLevels: 1, FirstArraySlice: slice, ArraySize: 1 },
                        },
                    };
                    let mut v = None;
                    self.device.CreateShaderResourceView(texture, Some(&desc), Some(&mut v))?;
                    Ok(v)
                };
                let planes = [view(DXGI_FORMAT_R8_UNORM)?, view(DXGI_FORMAT_R8G8_UNORM)?];
                // A decoder cycles through a handful of surfaces; more than that means it made new ones.
                if sh.views.len() > 32 {
                    sh.views.clear();
                }
                sh.views.insert(key, planes);
            }
            let Some(rtv) = target.rtv.as_ref() else {
                return Err(windows::core::Error::new(windows::Win32::Foundation::E_FAIL, "no render target view"));
            };
            let Some(planes) = sh.views.get(&key) else { return Ok(()) };
            let (fw, fh) = (w as f32, h as f32);
            let c = Constants {
                dest: [
                    dest.left as f32 / fw * 2.0 - 1.0,
                    1.0 - dest.top as f32 / fh * 2.0,
                    dest.right as f32 / fw * 2.0 - 1.0,
                    1.0 - dest.bottom as f32 / fh * 2.0,
                ],
                src: [0.0, 0.0, cw as f32 / input.0 as f32, ch as f32 / input.1 as f32],
                rows,
            };
            let ctx = &self.context;
            if sh.written != Some(c) {
                ctx.UpdateSubresource(&sh.constants, 0, None, &c as *const Constants as *const _, 0, 0);
                sh.written = Some(c);
            }
            if dest != area {
                // Letterboxed for a moment: the rest of the area black (the bar is copied over its part).
                ctx.ClearRenderTargetView(rtv, &[0.0, 0.0, 0.0, 1.0]);
            }
            ctx.OMSetRenderTargets(Some(&[Some(rtv.clone())]), None::<&ID3D11DepthStencilView>);
            ctx.OMSetBlendState(None::<&ID3D11BlendState>, None, u32::MAX);
            ctx.RSSetViewports(Some(&[D3D11_VIEWPORT { TopLeftX: 0.0, TopLeftY: 0.0, Width: fw, Height: fh, MinDepth: 0.0, MaxDepth: 1.0 }]));
            ctx.RSSetState(&sh.raster);
            ctx.IASetInputLayout(None::<&ID3D11InputLayout>);
            ctx.IASetPrimitiveTopology(D3D_PRIMITIVE_TOPOLOGY_TRIANGLESTRIP);
            ctx.VSSetShader(&sh.vs, None);
            ctx.VSSetConstantBuffers(0, Some(&[Some(sh.constants.clone())]));
            ctx.PSSetShader(&sh.ps, None);
            ctx.PSSetConstantBuffers(0, Some(&[Some(sh.constants.clone())]));
            ctx.PSSetShaderResources(0, Some(planes));
            ctx.PSSetSamplers(0, Some(&[Some(sh.sampler.clone())]));
            ctx.Draw(4, 0);
            // The decoder writes into these surfaces again: they are not left bound.
            ctx.PSSetShaderResources(0, Some(&[None, None]));
            ctx.OMSetRenderTargets(None, None::<&ID3D11DepthStencilView>);
        }
        Ok(())
    }

    /// NV12 to the back buffer through the D3D11 video processor.
    fn process(&mut self, texture: &ID3D11Texture2D, slice: u32, input: (u32, u32), (cw, ch): (u32, u32), dest: RECT, area: RECT) -> Result<()> {
        self.ensure_processor(input, self.size)?;
        let (bt709, full) = (self.bt709, self.full_range);
        let vp = self.vp.as_mut().unwrap();
        let key = (texture.as_raw() as usize, slice);
        if !vp.views.contains_key(&key) {
            let desc = D3D11_VIDEO_PROCESSOR_INPUT_VIEW_DESC {
                FourCC: 0,
                ViewDimension: D3D11_VPIV_DIMENSION_TEXTURE2D,
                Anonymous: D3D11_VIDEO_PROCESSOR_INPUT_VIEW_DESC_0 {
                    Texture2D: D3D11_TEX2D_VPIV { MipSlice: 0, ArraySlice: slice },
                },
            };
            let mut view = None;
            unsafe {
                self.video_device.CreateVideoProcessorInputView(texture, &vp.enumerator, &desc, Some(&mut view))?;
            }
            // A decoder cycles through a handful of surfaces; more than that means it made new ones.
            if vp.views.len() > 32 {
                vp.views.clear();
            }
            vp.views.insert(key, view.unwrap());
        }
        let in_view = vp.views.get(&key).unwrap().clone();
        let Some(target) = self.target.as_mut() else { return Ok(()) };
        // Made once per processor: the view follows buffer 0 from frame to frame.
        let made_with = vp.enumerator.as_raw() as usize;
        if target.vp_view.as_ref().is_none_or(|(e, _)| *e != made_with) {
            let out_desc = D3D11_VIDEO_PROCESSOR_OUTPUT_VIEW_DESC {
                ViewDimension: D3D11_VPOV_DIMENSION_TEXTURE2D,
                Anonymous: D3D11_VIDEO_PROCESSOR_OUTPUT_VIEW_DESC_0 { Texture2D: D3D11_TEX2D_VPOV { MipSlice: 0 } },
            };
            let mut out_view = None;
            unsafe {
                self.video_device.CreateVideoProcessorOutputView(&target.back, &vp.enumerator, &out_desc, Some(&mut out_view))?;
            }
            target.vp_view = Some((made_with, out_view.unwrap()));
        }
        let out_view = target.vp_view.as_ref().unwrap().1.clone();
        let src = RECT { left: 0, top: 0, right: cw as i32, bottom: ch as i32 };
        unsafe {
            let vc = &self.video_context;
            let p = &vp.processor;
            vc.VideoProcessorSetStreamFrameFormat(p, 0, D3D11_VIDEO_FRAME_FORMAT_PROGRESSIVE);
            vc.VideoProcessorSetStreamAutoProcessingMode(p, 0, false);
            vc.VideoProcessorSetStreamSourceRect(p, 0, true, Some(&src));
            vc.VideoProcessorSetStreamDestRect(p, 0, true, Some(&dest));
            vc.VideoProcessorSetOutputTargetRect(p, true, Some(&area));
            let black = D3D11_VIDEO_COLOR { Anonymous: D3D11_VIDEO_COLOR_0 { RGBA: D3D11_VIDEO_COLOR_RGBA { R: 0.0, G: 0.0, B: 0.0, A: 1.0 } } };
            vc.VideoProcessorSetOutputBackgroundColor(p, false, &black);
            vc.VideoProcessorSetStreamColorSpace(p, 0, &colour_space(true, bt709, full));
            vc.VideoProcessorSetOutputColorSpace(p, &colour_space(false, false, true));
            let mut stream = D3D11_VIDEO_PROCESSOR_STREAM {
                Enable: BOOL(1),
                pInputSurface: ManuallyDrop::new(Some(in_view)),
                ..Default::default()
            };
            let r = vc.VideoProcessorBlt(p, &out_view, 0, std::slice::from_ref(&stream));
            ManuallyDrop::drop(&mut stream.pInputSurface);
            r?;
        }
        Ok(())
    }

    fn ensure_processor(&mut self, input: (u32, u32), output: (u32, u32)) -> Result<()> {
        if let Some(vp) = &self.vp {
            if vp.input == input && vp.output == output {
                return Ok(());
            }
        }
        let desc = D3D11_VIDEO_PROCESSOR_CONTENT_DESC {
            InputFrameFormat: D3D11_VIDEO_FRAME_FORMAT_PROGRESSIVE,
            InputFrameRate: DXGI_RATIONAL { Numerator: 60, Denominator: 1 },
            InputWidth: input.0,
            InputHeight: input.1,
            OutputFrameRate: DXGI_RATIONAL { Numerator: 60, Denominator: 1 },
            OutputWidth: output.0,
            OutputHeight: output.1,
            Usage: D3D11_VIDEO_USAGE_PLAYBACK_NORMAL,
        };
        unsafe {
            let enumerator = self.video_device.CreateVideoProcessorEnumerator(&desc)?;
            let processor = self.video_device.CreateVideoProcessor(&enumerator, 0)?;
            self.vp = Some(Processor { enumerator, processor, input, output, views: HashMap::new() });
        }
        Ok(())
    }

    fn brush(&self, c: u32) -> Result<ID2D1SolidColorBrush> {
        unsafe { self.d2d.CreateSolidColorBrush(&rgb(c), None) }
    }

    fn draw_bar(&self, bar: &Bar, width: u32, look: &Look) -> Result<()> {
        let theme = Theme::of(look.dark);
        let s = self.scale;
        unsafe {
            let bg = self.brush(theme.bar)?;
            self.d2d.FillRectangle(&rectf(0, 0, width as i32, bar.h), &bg);
            let ink = self.brush(if look.active { theme.ink } else { theme.ink_inactive })?;
            let dim = self.brush(theme.ink_dim)?;
            // the buttons' backgrounds, under their glyphs
            for (hit, r) in [
                (Hit::Back, bar.back),
                (Hit::More, bar.more),
                (Hit::Sound, bar.sound),
                (Hit::Min, bar.min),
                (Hit::Max, bar.max),
                (Hit::Close, bar.close),
            ] {
                let on = look.hover == hit || look.pressed == hit;
                if !on {
                    continue;
                }
                let colour = match (hit, look.pressed == hit) {
                    (Hit::Close, true) => 0xF1707A,
                    (Hit::Close, false) => 0xC42B1C,
                    (_, true) => theme.pressed,
                    _ => theme.hover,
                };
                self.d2d.FillRectangle(&rectf(r.left, r.top, r.right, r.bottom), &self.brush(colour)?);
            }
            let white = self.brush(0xFFFFFF)?;
            let glyph = |hit: Hit, ch: u16, r: RECT| -> Result<()> {
                let b = if hit == Hit::Close && (look.hover == hit || look.pressed == hit) { &white } else { &ink };
                self.d2d.DrawText(&[ch], &self.glyphs, &rectf(r.left, r.top, r.right, r.bottom), b,
                    D2D1_DRAW_TEXT_OPTIONS_NONE, DWRITE_MEASURING_MODE_NATURAL);
                Ok(())
            };
            glyph(Hit::Back, 0xE72B, bar.back)?;
            glyph(Hit::More, 0xE700, bar.more)?;
            glyph(Hit::Sound, 0xE767, bar.sound)?;
            glyph(Hit::Min, 0xE921, bar.min)?;
            glyph(Hit::Max, if look.maximized { 0xE923 } else { 0xE922 }, bar.max)?;
            glyph(Hit::Close, 0xE8BB, bar.close)?;
            if let Some(icon) = &self.icon {
                let r = rectf(bar.icon.left, bar.icon.top, bar.icon.right, bar.icon.bottom);
                self.d2d.DrawBitmap(icon, Some(&r), if look.active { 1.0 } else { 0.7 },
                    D2D1_INTERPOLATION_MODE_LINEAR, None, None);
            }
            // "<app>" and, dimmer, "<phone>": a person with two phones on the cable can tell them apart.
            let name: Vec<u16> = look.label.encode_utf16().collect();
            let right = bar.sound.left - (8.0 * s) as i32;
            if right <= bar.text_x {
                // A window at its narrowest has room for its buttons only.
                return Ok(());
            }
            let label_rect = rectf(bar.text_x, 0, right, bar.h);
            let layout = self.dwrite.CreateTextLayout(&name, &self.text, (right - bar.text_x).max(0) as f32, bar.h as f32)?;
            let mut metrics = Default::default();
            layout.GetMetrics(&mut metrics)?;
            self.d2d.DrawText(&name, &self.text, &label_rect, &ink, D2D1_DRAW_TEXT_OPTIONS_CLIP, DWRITE_MEASURING_MODE_NATURAL);
            if !look.device.is_empty() {
                let x = bar.text_x + metrics.widthIncludingTrailingWhitespace.ceil() as i32 + (8.0 * s) as i32;
                if x < right {
                    let dev: Vec<u16> = format!("·  {}", look.device).encode_utf16().collect();
                    self.d2d.DrawText(&dev, &self.subtle, &rectf(x, 0, right, bar.h), &dim,
                        D2D1_DRAW_TEXT_OPTIONS_CLIP, DWRITE_MEASURING_MODE_NATURAL);
                }
            }
        }
        Ok(())
    }

    /// The full-screen bar: Back and More, the app's name in the middle, then
    /// where the sound plays, minimise, leave full screen and close; with an
    /// edge along its three
    /// open sides, so it stands apart from a picture of its own colour.
    fn draw_top_bar(&self, top: &TopBar, look: &Look) -> Result<()> {
        let theme = Theme::of(look.dark);
        let (w, h) = (top.rect.right - top.rect.left, top.rect.bottom);
        unsafe {
            self.d2d.FillRectangle(&rectf(0, 0, w, h), &self.brush(theme.bar)?);
            let ink = self.brush(theme.ink)?;
            let white = self.brush(0xFFFFFF)?;
            let buttons: [(Hit, u16, RECT); 6] = [
                (Hit::Back, 0xE72B, top.back),
                (Hit::More, 0xE700, top.more),
                (Hit::Sound, 0xE767, top.sound),
                (Hit::Min, 0xE921, top.min),
                (Hit::Windowed, 0xE73F, top.windowed),
                (Hit::Close, 0xE8BB, top.close),
            ];
            for (hit, glyph, r) in buttons {
                let pressed = look.pressed == hit;
                let on = pressed || look.hover == hit;
                if on {
                    let colour = match (hit, pressed) {
                        (Hit::Close, true) => 0xF1707A,
                        (Hit::Close, false) => 0xC42B1C,
                        (_, true) => theme.pressed,
                        _ => theme.hover,
                    };
                    self.d2d.FillRectangle(&rectf(r.left, r.top, r.right, r.bottom), &self.brush(colour)?);
                }
                let b = if hit == Hit::Close && on { &white } else { &ink };
                self.d2d.DrawText(&[glyph], &self.glyphs, &rectf(r.left, r.top, r.right, r.bottom), b,
                    D2D1_DRAW_TEXT_OPTIONS_NONE, DWRITE_MEASURING_MODE_NATURAL);
            }
            if top.label.right > top.label.left {
                let name: Vec<u16> = look.label.encode_utf16().collect();
                self.d2d.DrawText(&name, &self.centred, &rectf(top.label.left, 0, top.label.right, h), &ink,
                    D2D1_DRAW_TEXT_OPTIONS_CLIP, DWRITE_MEASURING_MODE_NATURAL);
            }
            let edge = self.brush(theme.edge)?;
            let px = self.scale.round().max(1.0) as i32;
            for r in [rectf(0, 0, px, h), rectf(w - px, 0, w, h), rectf(0, h - px, w, h)] {
                self.d2d.FillRectangle(&r, &edge);
            }
        }
        Ok(())
    }

    /// The full-screen bar's tab: an arrow pointing down, with the bar's edge.
    fn draw_handle(&self, (w, h): (u32, u32), look: &Look) -> Result<()> {
        let theme = Theme::of(look.dark);
        let (w, h) = (w as i32, h as i32);
        unsafe {
            self.d2d.FillRectangle(&rectf(0, 0, w, h), &self.brush(theme.bar)?);
            // ChevronDown, in the bars' own glyphs.
            self.d2d.DrawText(&[0xE70D], &self.glyphs, &rectf(0, 0, w, h), &self.brush(theme.ink)?,
                D2D1_DRAW_TEXT_OPTIONS_NONE, DWRITE_MEASURING_MODE_NATURAL);
            let edge = self.brush(theme.edge)?;
            let px = self.scale.round().max(1.0) as i32;
            for r in [rectf(0, 0, px, h), rectf(w - px, 0, w, h), rectf(0, h - px, w, h)] {
                self.d2d.FillRectangle(&r, &edge);
            }
        }
        Ok(())
    }

    /// No picture yet, or none coming: the app's icon and name, and what is happening.
    fn draw_empty(&self, area: RECT, look: &Look) -> Result<()> {
        let theme = Theme::of(look.dark);
        let s = self.scale;
        unsafe {
            self.d2d.FillRectangle(&rectf(area.left, area.top, area.right, area.bottom), &self.brush(theme.empty)?);
            let cx = (area.left + area.right) / 2;
            let cy = area.top + (area.bottom - area.top) * 2 / 5;
            let size = (64.0 * s) as i32;
            if let Some(icon) = &self.icon {
                let r = rectf(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2);
                self.d2d.DrawBitmap(icon, Some(&r), 1.0, D2D1_INTERPOLATION_MODE_LINEAR, None, None);
            }
            let ink = self.brush(theme.ink)?;
            let dim = self.brush(theme.ink_dim)?;
            let name: Vec<u16> = look.label.encode_utf16().collect();
            let top = cy + size / 2 + (16.0 * s) as i32;
            let pad = (24.0 * s) as i32;
            self.d2d.DrawText(&name, &self.big, &rectf(area.left + pad, top, area.right - pad, top + (24.0 * s) as i32),
                &ink, D2D1_DRAW_TEXT_OPTIONS_NONE, DWRITE_MEASURING_MODE_NATURAL);
            if let Some(msg) = look.message {
                let m: Vec<u16> = msg.encode_utf16().collect();
                let fmt = &self.big;
                let y = top + (32.0 * s) as i32;
                let small = text_format(&self.dwrite, w!("Segoe UI"), DWRITE_FONT_WEIGHT_NORMAL, 13.0 * s)?;
                small.SetTextAlignment(DWRITE_TEXT_ALIGNMENT_CENTER)?;
                let _ = fmt;
                self.d2d.DrawText(&m, &small, &rectf(area.left + pad, y, area.right - pad, area.bottom - pad),
                    &dim, D2D1_DRAW_TEXT_OPTIONS_NONE, DWRITE_MEASURING_MODE_NATURAL);
            }
        }
        Ok(())
    }
}

/// The window's colours, Windows 11's own caption's: dark or light as the system is.
struct Theme {
    bar: u32,
    /// The full-screen bar's edge.
    edge: u32,
    empty: u32,
    ink: u32,
    ink_inactive: u32,
    ink_dim: u32,
    hover: u32,
    pressed: u32,
    /// The note's strip under the bar: a shade off the bar, so it reads as the bar's.
    note: u32,
}

impl Theme {
    fn of(dark: bool) -> Theme {
        if dark {
            Theme { bar: 0x202020, edge: 0x404040, empty: 0x161616, ink: 0xFFFFFF, ink_inactive: 0x9A9A9A, ink_dim: 0x9A9A9A, hover: 0x2D2D2D, pressed: 0x292929, note: 0x2B2B2B }
        } else {
            Theme { bar: 0xF3F3F3, edge: 0xC8C8C8, empty: 0xFAFAFA, ink: 0x1A1A1A, ink_inactive: 0x8A8A8A, ink_dim: 0x707070, hover: 0xE5E5E5, pressed: 0xCCCCCC, note: 0xE8E8E8 }
        }
    }
}

fn rgb(c: u32) -> D2D1_COLOR_F {
    D2D1_COLOR_F {
        r: ((c >> 16) & 0xFF) as f32 / 255.0,
        g: ((c >> 8) & 0xFF) as f32 / 255.0,
        b: (c & 0xFF) as f32 / 255.0,
        a: 1.0,
    }
}

fn rectf(l: i32, t: i32, r: i32, b: i32) -> D2D_RECT_F {
    D2D_RECT_F { left: l as f32, top: t as f32, right: r as f32, bottom: b as f32 }
}

/// Where a `cw`x`ch` picture goes in an `aw`x`ah` area, its shape kept: x, y, width, height.
pub fn fit_rect(cw: u32, ch: u32, aw: u32, ah: u32) -> (i32, i32, u32, u32) {
    if cw == 0 || ch == 0 {
        return (0, 0, aw, ah);
    }
    if cw == aw && ch == ah {
        return (0, 0, aw, ah);
    }
    let scale = (aw as f64 / cw as f64).min(ah as f64 / ch as f64);
    let w = ((cw as f64 * scale).round() as u32).min(aw).max(1);
    let h = ((ch as f64 * scale).round() as u32).min(ah).max(1);
    (((aw - w) / 2) as i32, ((ah - h) / 2) as i32, w, h)
}

/// D3D11_VIDEO_PROCESSOR_COLOR_SPACE's bits: Usage (0), RGB_Range (1: 16–235),
/// YCbCr_Matrix (2: BT.709), YCbCr_xvYCC (3), Nominal_Range (4–5: 1 = 16–235, 2 = 0–255).
fn colour_space(ycbcr: bool, bt709: bool, full: bool) -> D3D11_VIDEO_PROCESSOR_COLOR_SPACE {
    let mut bits = 0u32;
    if ycbcr {
        if bt709 {
            bits |= 1 << 2;
        }
        bits |= (if full { 2 } else { 1 }) << 4;
    }
    D3D11_VIDEO_PROCESSOR_COLOR_SPACE { _bitfield: bits }
}

// ── the picture through a pixel shader ──

/// NV12 to the window's colours in one draw: the decoder's two planes sampled
/// as textures, the stream's colour matrix applied, scaled if the window and
/// the display differ for a moment. The video processor does the same in one
/// call, but with a Galaxy A13's stream on an Intel UHD that call cost the CPU
/// 1.6–2.8 M cycles a frame — most of a window's whole cost; a draw costs a
/// few thousand.
struct Shading {
    vs: ID3D11VertexShader,
    ps: ID3D11PixelShader,
    sampler: ID3D11SamplerState,
    raster: ID3D11RasterizerState,
    constants: ID3D11Buffer,
    /// What `constants` holds: written again only when it changes.
    written: Option<Constants>,
    /// The decoder's surfaces as luma and chroma, per (texture, slice).
    views: HashMap<(usize, u32), [Option<ID3D11ShaderResourceView>; 2]>,
}

/// The shader's constant buffer, as HLSL lays it out.
#[repr(C)]
#[derive(Clone, Copy, PartialEq)]
struct Constants {
    /// Where the picture goes, in clip space: left, top, right, bottom.
    dest: [f32; 4],
    /// The part of the texture that is the display, 0–1: left, top, right, bottom.
    src: [f32; 4],
    /// R, G and B, each from (Y', Cb, Cr, 1).
    rows: [[f32; 4]; 3],
}

const SHADER: &str = r#"
cbuffer C : register(b0) { float4 dest; float4 src; float4 r; float4 g; float4 b; };
struct V { float4 pos : SV_Position; float2 uv : TEXCOORD0; };
V vs(uint id : SV_VertexID) {
    float2 t = float2(id & 1, id >> 1);
    V o;
    o.pos = float4(lerp(dest.x, dest.z, t.x), lerp(dest.y, dest.w, t.y), 0, 1);
    o.uv = lerp(src.xy, src.zw, t);
    return o;
}
Texture2DArray<float> luma : register(t0);
Texture2DArray<float2> chroma : register(t1);
SamplerState linear_clamp : register(s0);
float4 ps(V i) : SV_Target {
    float4 ycc = float4(luma.Sample(linear_clamp, float3(i.uv, 0)), chroma.Sample(linear_clamp, float3(i.uv, 0)), 1);
    return float4(saturate(float3(dot(r, ycc), dot(g, ycc), dot(b, ycc))), 1);
}
"#;

/// The two shaders' bytecode, compiled once for every window this process opens.
fn shader_code() -> Option<&'static (Vec<u8>, Vec<u8>)> {
    static CODE: std::sync::OnceLock<Option<(Vec<u8>, Vec<u8>)>> = std::sync::OnceLock::new();
    CODE.get_or_init(|| {
        let bytes = |b: &ID3DBlob| unsafe { std::slice::from_raw_parts(b.GetBufferPointer() as *const u8, b.GetBufferSize()).to_vec() };
        let compile = |entry: PCSTR, target: PCSTR| -> Option<Vec<u8>> {
            let mut code = None;
            let mut errors = None;
            let r = unsafe {
                D3DCompile(
                    SHADER.as_ptr() as *const _,
                    SHADER.len(),
                    s!("picture"),
                    None,
                    None::<&ID3DInclude>,
                    entry,
                    target,
                    D3DCOMPILE_OPTIMIZATION_LEVEL3,
                    0,
                    &mut code,
                    Some(&mut errors),
                )
            };
            if let Err(e) = r {
                let why = errors.as_ref().map(|b| String::from_utf8_lossy(&bytes(b)).into_owned()).unwrap_or_default();
                log::warn!("apps: the picture's shader did not compile: {e} {why}");
                return None;
            }
            code.as_ref().map(bytes)
        };
        Some((compile(s!("vs"), s!("vs_4_0"))?, compile(s!("ps"), s!("ps_4_0"))?))
    })
    .as_ref()
}

impl Shading {
    fn new(device: &ID3D11Device) -> Result<Shading> {
        let Some((vs_code, ps_code)) = shader_code() else {
            return Err(windows::core::Error::new(windows::Win32::Foundation::E_FAIL, "the shader did not compile"));
        };
        unsafe {
            let mut vs = None;
            device.CreateVertexShader(vs_code, None, Some(&mut vs))?;
            let mut ps = None;
            device.CreatePixelShader(ps_code, None, Some(&mut ps))?;
            let sampler_desc = D3D11_SAMPLER_DESC {
                Filter: D3D11_FILTER_MIN_MAG_MIP_LINEAR,
                AddressU: D3D11_TEXTURE_ADDRESS_CLAMP,
                AddressV: D3D11_TEXTURE_ADDRESS_CLAMP,
                AddressW: D3D11_TEXTURE_ADDRESS_CLAMP,
                MipLODBias: 0.0,
                MaxAnisotropy: 1,
                ComparisonFunc: D3D11_COMPARISON_NEVER,
                BorderColor: [0.0; 4],
                MinLOD: 0.0,
                MaxLOD: D3D11_FLOAT32_MAX,
            };
            let mut sampler = None;
            device.CreateSamplerState(&sampler_desc, Some(&mut sampler))?;
            let raster_desc = D3D11_RASTERIZER_DESC {
                FillMode: D3D11_FILL_SOLID,
                CullMode: D3D11_CULL_NONE,
                FrontCounterClockwise: false.into(),
                DepthBias: 0,
                DepthBiasClamp: 0.0,
                SlopeScaledDepthBias: 0.0,
                DepthClipEnable: true.into(),
                ScissorEnable: false.into(),
                MultisampleEnable: false.into(),
                AntialiasedLineEnable: false.into(),
            };
            let mut raster = None;
            device.CreateRasterizerState(&raster_desc, Some(&mut raster))?;
            let buffer_desc = D3D11_BUFFER_DESC {
                ByteWidth: std::mem::size_of::<Constants>() as u32,
                Usage: D3D11_USAGE_DEFAULT,
                BindFlags: D3D11_BIND_CONSTANT_BUFFER.0 as u32,
                CPUAccessFlags: 0,
                MiscFlags: 0,
                StructureByteStride: 0,
            };
            let mut constants = None;
            device.CreateBuffer(&buffer_desc, None, Some(&mut constants))?;
            Ok(Shading {
                vs: vs.unwrap(),
                ps: ps.unwrap(),
                sampler: sampler.unwrap(),
                raster: raster.unwrap(),
                constants: constants.unwrap(),
                written: None,
                views: HashMap::new(),
            })
        }
    }
}

/// The compositor's refresh period, in QPC ticks.
fn refresh_period_qpc() -> i64 {
    static P: std::sync::OnceLock<i64> = std::sync::OnceLock::new();
    *P.get_or_init(|| {
        let mut t = DWM_TIMING_INFO { cbSize: std::mem::size_of::<DWM_TIMING_INFO>() as u32, ..Default::default() };
        // Windows 8.1 on: the whole desktop's timing, so no window is named.
        match unsafe { DwmGetCompositionTimingInfo(HWND::default(), &mut t) } {
            Ok(()) => {
                // A packed struct: its fields are copied out, never borrowed.
                let (period, rate) = (t.qpcRefreshPeriod, t.rateRefresh);
                let hz = rate.uiNumerator as f64 / rate.uiDenominator.max(1) as f64;
                log::info!("apps: Windows composes the screen every {period} QPC ticks ({hz:.1} Hz)");
                period as i64
            }
            Err(e) => {
                log::info!("apps: no compositor timing ({e}): a frame's time on screen is the latest refresh's");
                0
            }
        }
    })
}

/// The rows that turn (Y', Cb, Cr, 1) — each as the texture holds it, 0–1 —
/// into R, G and B: BT.601 or BT.709, limited (16–235) or full range.
fn ycbcr_rows(bt709: bool, full: bool) -> [[f32; 4]; 3] {
    let (kr, kb) = if bt709 { (0.2126, 0.0722) } else { (0.299, 0.114) };
    let kg = 1.0 - kr - kb;
    // Y = ys·y + yo, and each chroma difference = cs·c + co.
    let (ys, yo, cs, co): (f64, f64, f64, f64) =
        if full { (1.0, 0.0, 1.0, -128.0 / 255.0) } else { (255.0 / 219.0, -16.0 / 219.0, 255.0 / 224.0, -128.0 / 224.0) };
    let row = |pb: f64, pr: f64| [ys as f32, (pb * cs) as f32, (pr * cs) as f32, (yo + (pb + pr) * co) as f32];
    [
        row(0.0, 2.0 * (1.0 - kr)),
        row(-2.0 * (1.0 - kb) * kb / kg, -2.0 * (1.0 - kr) * kr / kg),
        row(2.0 * (1.0 - kb), 0.0),
    ]
}

unsafe fn text_format(
    dwrite: &IDWriteFactory,
    family: impl windows::core::Param<PCWSTR>,
    weight: windows::Win32::Graphics::DirectWrite::DWRITE_FONT_WEIGHT,
    size: f32,
) -> Result<IDWriteTextFormat> {
    unsafe {
        dwrite.CreateTextFormat(
            family,
            None::<&IDWriteFontCollection>,
            weight,
            DWRITE_FONT_STYLE_NORMAL,
            DWRITE_FONT_STRETCH_NORMAL,
            size,
            w!("en-us"),
        )
    }
}

/// A note's text: wrapped at words, and an ellipsis where a third line would begin.
fn note_format(dwrite: &IDWriteFactory, scale: f32) -> Result<IDWriteTextFormat> {
    unsafe {
        let f = text_format(dwrite, w!("Segoe UI"), DWRITE_FONT_WEIGHT_NORMAL, 12.0 * scale)?;
        f.SetWordWrapping(windows::Win32::Graphics::DirectWrite::DWRITE_WORD_WRAPPING_WRAP)?;
        f.SetTextAlignment(DWRITE_TEXT_ALIGNMENT_LEADING)?;
        let sign = dwrite.CreateEllipsisTrimmingSign(&f)?;
        let trim = DWRITE_TRIMMING { granularity: DWRITE_TRIMMING_GRANULARITY_WORD, delimiter: 0, delimiterCount: 0 };
        f.SetTrimming(&trim, &sign)?;
        Ok(f)
    }
}

/// Windows 11's icon font, else Windows 10's: the caption buttons' own glyphs.
fn glyph_font(dwrite: &IDWriteFactory) -> HSTRING {
    unsafe {
        let mut fonts = None;
        if dwrite.GetSystemFontCollection(&mut fonts, false).is_ok() {
            if let Some(fonts) = fonts {
                let mut index = 0u32;
                let mut exists = BOOL(0);
                if fonts.FindFamilyName(w!("Segoe Fluent Icons"), &mut index, &mut exists).is_ok() && exists.as_bool() {
                    return HSTRING::from("Segoe Fluent Icons");
                }
            }
        }
    }
    HSTRING::from("Segoe MDL2 Assets")
}

fn decode_png(d2d: &ID2D1DeviceContext, png: &[u8]) -> Result<ID2D1Bitmap1> {
    unsafe {
        let wic: IWICImagingFactory = CoCreateInstance(&CLSID_WICImagingFactory, None, CLSCTX_INPROC_SERVER)?;
        let stream = wic.CreateStream()?;
        stream.InitializeFromMemory(png)?;
        let decoder = wic.CreateDecoderFromStream(&stream, std::ptr::null(), WICDecodeMetadataCacheOnDemand)?;
        let frame = decoder.GetFrame(0)?;
        let converter = wic.CreateFormatConverter()?;
        converter.Initialize(&frame, &GUID_WICPixelFormat32bppPBGRA, WICBitmapDitherTypeNone, None, 0.0, WICBitmapPaletteTypeMedianCut)?;
        let props = D2D1_BITMAP_PROPERTIES1 {
            pixelFormat: D2D1_PIXEL_FORMAT { format: DXGI_FORMAT_B8G8R8A8_UNORM, alphaMode: D2D1_ALPHA_MODE_PREMULTIPLIED },
            dpiX: 96.0,
            dpiY: 96.0,
            bitmapOptions: D2D1_BITMAP_OPTIONS_NONE,
            colorContext: ManuallyDrop::new(None),
        };
        d2d.CreateBitmapFromWicBitmap(&converter, Some(&props))
    }
}

// ── the decoder ──

fn mf_started() -> bool {
    static STARTED: std::sync::OnceLock<bool> = std::sync::OnceLock::new();
    *STARTED.get_or_init(|| unsafe { MFStartup(MF_VERSION, MFSTARTUP_NOSOCKET).is_ok() })
}

/// Media Foundation's own H.264 decoder, on the window's device.
pub struct Decoder {
    mft: IMFTransform,
    _manager: Option<IMFDXGIDeviceManager>,
    coded: (u32, u32),
    /// SPS and PPS, sent once per encoder ahead of its first frame: given to the decoder with that frame.
    config: Vec<u8>,
    /// The decoder hands out its own samples (it is on the GPU); else this side makes them.
    provides: bool,
    out_size: u32,
    typed: bool,
    software: bool,
    staging: Option<ID3D11Texture2D>,
    device: ID3D11Device,
}

impl Decoder {
    fn new(device: &ID3D11Device, coded: (u32, u32)) -> Result<Self> {
        if !mf_started() {
            return Err(windows::core::Error::new(windows::Win32::Foundation::E_FAIL, "Media Foundation did not start"));
        }
        unsafe {
            let mft: IMFTransform = CoCreateInstance(&CLSID_MSH264DecoderMFT, None, CLSCTX_INPROC_SERVER)?;
            if let Ok(attrs) = mft.GetAttributes() {
                // A frame in, that frame out: no reordering is waited for.
                let _ = attrs.SetUINT32(&MF_LOW_LATENCY, 1);
                let _ = attrs.SetUINT32(&CODECAPI_AVDecVideoAcceleration_H264, 1);
            }
            let mut token = 0u32;
            let mut manager = None;
            let mut software = true;
            if MFCreateDXGIDeviceManager(&mut token, &mut manager).is_ok() {
                if let Some(m) = &manager {
                    if m.ResetDevice(device, token).is_ok()
                        && mft.ProcessMessage(MFT_MESSAGE_SET_D3D_MANAGER, m.as_raw() as usize).is_ok()
                    {
                        software = false;
                        // Surfaces a shader can sample, as well as decode into: the
                        // picture is then one draw (Renderer::shade).
                        if let Ok(out) = mft.GetOutputStreamAttributes(0) {
                            let _ = out.SetUINT32(&MF_SA_D3D11_BINDFLAGS, (D3D11_BIND_DECODER.0 | D3D11_BIND_SHADER_RESOURCE.0) as u32);
                        }
                    }
                }
            }
            if software {
                log::warn!("apps: the H.264 decoder will not take this computer's GPU; decoding in software");
            }
            let input = MFCreateMediaType()?;
            input.SetGUID(&MF_MT_MAJOR_TYPE, &MFMediaType_Video)?;
            input.SetGUID(&MF_MT_SUBTYPE, &MFVideoFormat_H264)?;
            input.SetUINT64(&MF_MT_FRAME_SIZE, ((coded.0 as u64) << 32) | coded.1 as u64)?;
            input.SetUINT32(&MF_MT_INTERLACE_MODE, MFVideoInterlace_Progressive.0 as u32)?;
            input.SetUINT64(&MF_MT_FRAME_RATE, (60u64 << 32) | 1)?;
            mft.SetInputType(0, &input, 0)?;
            let mut d = Decoder {
                mft,
                _manager: manager,
                coded,
                config: Vec::new(),
                provides: false,
                out_size: 0,
                typed: false,
                software,
                staging: None,
                device: device.clone(),
            };
            // Usually refused until the stream has begun; the first frame's
            // MF_E_TRANSFORM_STREAM_CHANGE sets it then.
            let _ = d.set_output_type();
            d.mft.ProcessMessage(MFT_MESSAGE_NOTIFY_BEGIN_STREAMING, 0)?;
            d.mft.ProcessMessage(MFT_MESSAGE_NOTIFY_START_OF_STREAM, 0)?;
            log::info!("apps: H.264 decoder for {}x{} ({})", coded.0, coded.1, if software { "software" } else { "GPU" });
            Ok(d)
        }
    }

    fn set_output_type(&mut self) -> Result<()> {
        unsafe {
            let mut i = 0;
            loop {
                let t = self.mft.GetOutputAvailableType(0, i)?;
                if t.GetGUID(&MF_MT_SUBTYPE)? == MFVideoFormat_NV12 {
                    self.mft.SetOutputType(0, &t, 0)?;
                    break;
                }
                i += 1;
            }
            let info = self.mft.GetOutputStreamInfo(0)?;
            self.provides = (info.dwFlags & (MFT_OUTPUT_STREAM_PROVIDES_SAMPLES.0 as u32 | MFT_OUTPUT_STREAM_CAN_PROVIDE_SAMPLES.0 as u32)) != 0;
            self.out_size = info.cbSize;
            self.typed = true;
        }
        Ok(())
    }

    /// The stream's colours, if it says: (BT.709, full range).
    fn colours(&self) -> Option<(bool, bool)> {
        unsafe {
            let t = self.mft.GetOutputCurrentType(0).ok()?;
            let matrix = t.GetUINT32(&MF_MT_YUV_MATRIX).unwrap_or(0);
            let range = t.GetUINT32(&MF_MT_VIDEO_NOMINAL_RANGE).unwrap_or(0);
            Some((matrix == MFVideoTransferMatrix_BT709.0 as u32, range == MFNominalRange_0_255.0 as u32))
        }
    }

    /// One access unit in; the picture it made, if it made one.
    fn feed(&mut self, data: &[u8], pts_us: u64) -> Result<Option<(ID3D11Texture2D, u32, Option<IMFSample>)>> {
        unsafe {
            let mut unit = Vec::new();
            let bytes: &[u8] = if self.config.is_empty() {
                data
            } else {
                unit.extend_from_slice(&self.config);
                unit.extend_from_slice(data);
                self.config.clear();
                &unit
            };
            let buffer = MFCreateMemoryBuffer(bytes.len() as u32)?;
            let mut ptr = std::ptr::null_mut();
            buffer.Lock(&mut ptr, None, None)?;
            std::ptr::copy_nonoverlapping(bytes.as_ptr(), ptr, bytes.len());
            buffer.Unlock()?;
            buffer.SetCurrentLength(bytes.len() as u32)?;
            let sample = MFCreateSample()?;
            sample.AddBuffer(&buffer)?;
            sample.SetSampleTime(pts_us as i64 * 10)?;
            let mut newest = None;
            if let Err(e) = self.mft.ProcessInput(0, &sample, 0) {
                if e.code() != MF_E_NOTACCEPTING {
                    return Err(e);
                }
                // Full: take what it has, then hand it this one again.
                newest = self.drain()?;
                self.mft.ProcessInput(0, &sample, 0)?;
            }
            if let Some(p) = self.drain()? {
                newest = Some(p);
            }
            Ok(newest)
        }
    }

    fn drain(&mut self) -> Result<Option<(ID3D11Texture2D, u32, Option<IMFSample>)>> {
        let mut newest = None;
        unsafe {
            loop {
                let own = if self.provides || !self.typed {
                    None
                } else {
                    let s = MFCreateSample()?;
                    let b = MFCreateMemoryBuffer(self.out_size.max(1))?;
                    s.AddBuffer(&b)?;
                    Some(s)
                };
                let mut out = [MFT_OUTPUT_DATA_BUFFER {
                    dwStreamID: 0,
                    pSample: ManuallyDrop::new(own),
                    dwStatus: 0,
                    pEvents: ManuallyDrop::new(None),
                }];
                let mut status = 0u32;
                let r = self.mft.ProcessOutput(0, &mut out, &mut status);
                let sample = ManuallyDrop::take(&mut out[0].pSample);
                ManuallyDrop::drop(&mut out[0].pEvents);
                match r {
                    Ok(()) => {
                        if let Some(s) = sample {
                            if let Some(p) = self.picture_of(s)? {
                                newest = Some(p);
                            }
                        }
                    }
                    Err(e) if e.code() == MF_E_TRANSFORM_NEED_MORE_INPUT => break,
                    Err(e) if e.code() == MF_E_TRANSFORM_STREAM_CHANGE => {
                        self.set_output_type()?;
                        continue;
                    }
                    Err(e) => return Err(e),
                }
            }
        }
        Ok(newest)
    }

    /// The texture a decoded sample is in: the decoder's own on the GPU, else
    /// a copy of system memory into one of ours.
    fn picture_of(&mut self, sample: IMFSample) -> Result<Option<(ID3D11Texture2D, u32, Option<IMFSample>)>> {
        unsafe {
            let buffer = sample.GetBufferByIndex(0)?;
            if let Ok(dxgi) = buffer.cast::<IMFDXGIBuffer>() {
                let mut raw = std::ptr::null_mut();
                dxgi.GetResource(&ID3D11Texture2D::IID, &mut raw)?;
                let texture = ID3D11Texture2D::from_raw(raw);
                let slice = dxgi.GetSubresourceIndex()?;
                return Ok(Some((texture, slice, Some(sample))));
            }
            if !self.software {
                return Ok(None);
            }
            // Software: NV12 in memory, its size the output type's.
            let t = self.mft.GetOutputCurrentType(0)?;
            let size = t.GetUINT64(&MF_MT_FRAME_SIZE)?;
            let (w, h) = ((size >> 32) as u32, (size & 0xFFFF_FFFF) as u32);
            let stride = t.GetUINT32(&MF_MT_DEFAULT_STRIDE).map(|s| s as i32 as u32).unwrap_or(w);
            let staging = match &self.staging {
                Some(s) => {
                    let mut d = D3D11_TEXTURE2D_DESC::default();
                    s.GetDesc(&mut d);
                    if d.Width == w && d.Height == h {
                        s.clone()
                    } else {
                        self.new_nv12(w, h)?
                    }
                }
                None => self.new_nv12(w, h)?,
            };
            self.staging = Some(staging.clone());
            let mut ptr = std::ptr::null_mut();
            let mut len = 0u32;
            buffer.Lock(&mut ptr, None, Some(&mut len))?;
            if let Ok(ctx) = self.device.GetImmediateContext() {
                ctx.UpdateSubresource(&staging, 0, None, ptr as *const _, stride, stride * h);
            }
            buffer.Unlock()?;
            Ok(Some((staging, 0, None)))
        }
    }

    fn new_nv12(&self, w: u32, h: u32) -> Result<ID3D11Texture2D> {
        let desc = D3D11_TEXTURE2D_DESC {
            Width: w,
            Height: h,
            MipLevels: 1,
            ArraySize: 1,
            Format: DXGI_FORMAT_NV12,
            SampleDesc: DXGI_SAMPLE_DESC { Count: 1, Quality: 0 },
            Usage: D3D11_USAGE_DEFAULT,
            BindFlags: D3D11_BIND_SHADER_RESOURCE.0 as u32,
            CPUAccessFlags: 0,
            MiscFlags: 0,
        };
        let mut t = None;
        unsafe { self.device.CreateTexture2D(&desc, None, Some(&mut t))? };
        Ok(t.unwrap())
    }
}

#[allow(dead_code)]
const _UNUSED: GUID = GUID::zeroed();

#[cfg(test)]
mod tests {
    use super::fit_rect;

    #[test]
    fn the_colour_rows_turn_black_white_and_grey_into_themselves() {
        let apply = |rows: [[f32; 4]; 3], y: f32, cb: f32, cr: f32| rows.map(|r| r[0] * y + r[1] * cb + r[2] * cr + r[3]);
        for bt709 in [false, true] {
            let limited = super::ycbcr_rows(bt709, false);
            for (y8, want) in [(16.0, 0.0), (235.0, 1.0), (125.5, 0.5)] {
                for v in apply(limited, y8 / 255.0, 128.0 / 255.0, 128.0 / 255.0) {
                    assert!((v - want).abs() < 1e-3, "limited {bt709}: Y {y8} gave {v}");
                }
            }
            let full = super::ycbcr_rows(bt709, true);
            for v in apply(full, 1.0, 128.0 / 255.0, 128.0 / 255.0) {
                assert!((v - 1.0).abs() < 1e-3);
            }
        }
    }

    #[test]
    fn pure_red_comes_back_red() {
        // BT.709 limited red: Y 63, Cb 102, Cr 240.
        let rows = super::ycbcr_rows(true, false);
        let [r, g, b] = rows.map(|row| row[0] * 63.0 / 255.0 + row[1] * 102.0 / 255.0 + row[2] * 240.0 / 255.0 + row[3]);
        assert!(r > 0.97 && g < 0.03 && b < 0.03, "{r} {g} {b}");
    }

    #[test]
    fn a_picture_the_areas_size_is_one_for_one() {
        assert_eq!(fit_rect(412, 892, 412, 892), (0, 0, 412, 892));
    }

    #[test]
    fn a_picture_of_another_shape_is_letterboxed() {
        // the window made wider, the phone not there yet: bars at the sides
        assert_eq!(fit_rect(400, 800, 800, 800), (200, 0, 400, 800));
        // taller: bars above and below
        assert_eq!(fit_rect(800, 400, 800, 800), (0, 200, 800, 400));
    }
}
