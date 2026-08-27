//! The window the user actually sees: the virtual monitor, mirrored.
//!
//! One thread owns everything here — window, Direct3D, Direct2D, capture,
//! hook — and no COM object ever crosses a thread. That is not tidiness for
//! its own sake: the alternative (render state in a shared `Mutex`, frames
//! arriving on a capture callback thread, resizes on the window thread) is
//! the design whose bugs only reproduce on the machine we do not have. A
//! 15 ms timer drains `TryGetNextFrame` instead of subscribing to
//! `FrameArrived`, which is what keeps frame delivery on this thread too.
//!
//! **The portal.** Embedding gave scrcpy windows real input for free; a
//! *captured* monitor gets none, and synthetic input into an unfocused UWP
//! window is a dead end. So input is a portal, not a forward: a click inside
//! the video hands the *real* mouse and keyboard over — focus moves to the
//! receiver, the cursor teleports to the click's spot on the virtual monitor,
//! and `ClipCursor` keeps it there so it cannot wander onto a desktop the
//! user cannot see. Windows carries every event to the receiver natively and
//! the receiver speaks UIBC to the phone; what the phone honours is the
//! phone's business (keyboard: yes; mouse: One UI often not — the panel says
//! so). Esc is the way back, via the same low-level-hook recipe as
//! `hotkeys/windows.rs` and for the same reason: the key must be *taken*,
//! because its owner-of-record is the receiver, which would forward it to
//! the phone as an Escape pressed inside DeX.
//!
//! The captured pixels include the cursor (`IsCursorCaptureEnabled`), so
//! during portal time the user watches their own pointer move inside the
//! viewer — the loop feels direct even though the cursor is parked two
//! monitors away.

use std::sync::atomic::{AtomicBool, AtomicIsize, Ordering};
use std::sync::mpsc;
use std::sync::Mutex;
use std::thread::JoinHandle;
use std::time::{Duration, Instant};

use windows::core::{w, Interface};
use windows::Graphics::Capture::{
    Direct3D11CaptureFramePool, GraphicsCaptureItem, GraphicsCaptureSession,
};
use windows::Graphics::DirectX::Direct3D11::IDirect3DDevice;
use windows::Graphics::DirectX::DirectXPixelFormat;
use windows::Win32::Foundation::{HWND, LPARAM, LRESULT, POINT, RECT, WPARAM};
use windows::Win32::Graphics::Direct2D::Common::{
    D2D1_ALPHA_MODE_IGNORE, D2D1_COLOR_F, D2D1_PIXEL_FORMAT, D2D_RECT_F,
};
use windows::Win32::Graphics::Direct2D::{
    D2D1CreateFactory, ID2D1Bitmap1, ID2D1DeviceContext, ID2D1Factory1, ID2D1SolidColorBrush,
    D2D1_BITMAP_OPTIONS_CANNOT_DRAW, D2D1_BITMAP_OPTIONS_NONE, D2D1_BITMAP_OPTIONS_TARGET,
    D2D1_BITMAP_PROPERTIES1, D2D1_DEVICE_CONTEXT_OPTIONS_NONE, D2D1_FACTORY_TYPE_SINGLE_THREADED,
    D2D1_INTERPOLATION_MODE_LINEAR,
};
use windows::Win32::Graphics::Direct3D::{D3D_DRIVER_TYPE_HARDWARE, D3D_DRIVER_TYPE_WARP};
use windows::Win32::Graphics::Direct3D11::{
    D3D11CreateDevice, ID3D11Device, ID3D11Texture2D, D3D11_BIND_RENDER_TARGET,
    D3D11_BIND_SHADER_RESOURCE, D3D11_CREATE_DEVICE_BGRA_SUPPORT, D3D11_SDK_VERSION,
    D3D11_TEXTURE2D_DESC, D3D11_USAGE_DEFAULT,
};
use windows::Win32::Graphics::DirectWrite::{
    DWriteCreateFactory, IDWriteFactory, IDWriteTextFormat, DWRITE_FACTORY_TYPE_SHARED,
    DWRITE_FONT_STRETCH_NORMAL, DWRITE_FONT_STYLE_NORMAL, DWRITE_FONT_WEIGHT_NORMAL,
    DWRITE_MEASURING_MODE_NATURAL, DWRITE_TEXT_ALIGNMENT_CENTER,
};
use windows::Win32::Graphics::Dxgi::Common::{DXGI_FORMAT_B8G8R8A8_UNORM, DXGI_SAMPLE_DESC};
use windows::Win32::Graphics::Dxgi::{
    IDXGIDevice, IDXGIFactory2, IDXGISurface, IDXGISwapChain1, DXGI_SCALING_STRETCH,
    DXGI_SWAP_CHAIN_DESC1, DXGI_SWAP_EFFECT_FLIP_DISCARD, DXGI_USAGE_RENDER_TARGET_OUTPUT,
};
use windows::Win32::Graphics::Gdi::{MonitorFromPoint, HBRUSH, MONITOR_DEFAULTTONULL};
use windows::Win32::System::LibraryLoader::GetModuleHandleW;
use windows::Win32::System::WinRT::Direct3D11::{
    CreateDirect3D11DeviceFromDXGIDevice, IDirect3DDxgiInterfaceAccess,
};
use windows::Win32::System::WinRT::Graphics::Capture::IGraphicsCaptureItemInterop;
use windows::Win32::System::WinRT::{RoInitialize, RO_INIT_MULTITHREADED};
use windows::Win32::System::Threading::{AttachThreadInput, GetCurrentThreadId};
use windows::Win32::UI::Input::KeyboardAndMouse::{
    GetAsyncKeyState, VK_CONTROL, VK_ESCAPE, VK_LWIN, VK_MENU, VK_RWIN, VK_SHIFT,
};
use windows::Win32::UI::WindowsAndMessaging::{
    CallNextHookEx, ClipCursor, CreateWindowExW, DefWindowProcW, DestroyWindow,
    DispatchMessageW, GetClientRect, GetCursorPos, GetForegroundWindow, GetMessageW,
    GetWindowLongPtrW, GetWindowThreadProcessId, IsWindow, KillTimer, LoadCursorW, PostMessageW,
    PostQuitMessage, RegisterClassW, SetCursorPos, SetForegroundWindow, SetTimer,
    SetWindowLongPtrW, SetWindowsHookExW, ShowWindow, TranslateMessage, UnhookWindowsHookEx,
    CW_USEDEFAULT, GWLP_USERDATA, HC_ACTION, HHOOK, IDC_ARROW, KBDLLHOOKSTRUCT, MSG, SW_SHOW,
    WH_KEYBOARD_LL, WM_APP, WM_CLOSE, WM_DESTROY, WM_KEYDOWN, WM_KEYUP, WM_LBUTTONDOWN,
    WM_NCDESTROY, WM_SIZE, WM_SYSKEYDOWN, WM_SYSKEYUP, WM_TIMER, WNDCLASSW, WS_EX_APPWINDOW,
    WS_OVERLAPPEDWINDOW,
};

use super::geometry;
use super::vdd::MonitorRect;
use super::{receiver, vdd};

/// Asking the viewer to leave portal mode — posted by the Esc hook.
const WM_APP_EXIT_PORTAL: u32 = WM_APP + 1;
const RENDER_TIMER: usize = 1;

// The cross-thread surface; everything else lives on the viewer thread.
static RUNNING: AtomicBool = AtomicBool::new(false);
static VIEWER_HWND: AtomicIsize = AtomicIsize::new(0);
static RECEIVER_HWND: AtomicIsize = AtomicIsize::new(0);
static PORTAL: AtomicBool = AtomicBool::new(false);
/// Set by [`stop`], read by the viewer thread. Closes the race where stop()
/// runs *before* the window exists: WM_CLOSE has nowhere to go yet, so the
/// flag is the durable request the thread checks the moment it has an hwnd.
static STOP_REQUESTED: AtomicBool = AtomicBool::new(false);
static THREAD: Mutex<Option<JoinHandle<()>>> = Mutex::new(None);

pub fn is_running() -> bool {
    RUNNING.load(Ordering::SeqCst)
}

/// Bring the viewer forward — the answer to "Start" clicked twice.
pub fn raise() {
    let hwnd = VIEWER_HWND.load(Ordering::SeqCst);
    if hwnd != 0 {
        unsafe {
            let _ = SetForegroundWindow(HWND(hwnd as *mut _));
        }
    }
}

/// Ask the viewer to close and wait for its thread's own teardown to finish.
///
/// Idempotent, and safe against every ordering: a thread still inside
/// `build_viewer` (which can outlast the 10 s start timeout after a display
/// topology change), a window that has not appeared yet, a thread already
/// gone. The handle is taken out of the mutex *first* so concurrent callers
/// (a Stop click racing app-exit's `shutdown`) do not pile up behind a held
/// lock, and the wait is **bounded** — a viewer wedged in a never-returning
/// WinRT call is detached and leaked rather than allowed to hang app exit.
pub fn stop() {
    STOP_REQUESTED.store(true, Ordering::SeqCst);
    let Some(handle) = THREAD.lock().unwrap().take() else {
        // No thread yet. Leave STOP_REQUESTED set: this may be the sliver of
        // start() between RUNNING.swap(true) and THREAD being filled, where
        // the request would otherwise be dropped and the viewer would open
        // anyway. The flag is the durable request — start() clears it at its
        // top for a genuinely fresh session, and the thread reads it once it
        // has an hwnd. Clearing it here is what created that race.
        return;
    };
    let deadline = Instant::now() + Duration::from_secs(15);
    while !handle.is_finished() {
        // Re-post every tick: the window may only now have come up, and a
        // post to an already-destroyed hwnd fails harmlessly.
        let hwnd = VIEWER_HWND.load(Ordering::SeqCst);
        if hwnd != 0 {
            unsafe {
                let _ = PostMessageW(Some(HWND(hwnd as *mut _)), WM_CLOSE, WPARAM(0), LPARAM(0));
            }
        }
        if Instant::now() >= deadline {
            log::warn!("dexcast: viewer thread did not exit in 15s — detaching it");
            // Leak the thread rather than block stop/uninstall/app-exit
            // forever. RUNNING stays whatever the wedged thread left it;
            // is_running() then reports honestly that something is still up.
            return;
        }
        std::thread::sleep(Duration::from_millis(50));
    }
    let _ = handle.join();
    STOP_REQUESTED.store(false, Ordering::SeqCst);
}

/// Spin the viewer up and answer only once capture is genuinely running —
/// failures inside the thread come back through the channel so the command
/// can report them as its own.
pub fn start(
    _app: &tauri::AppHandle,
    monitor: MonitorRect,
    receiver_frame: isize,
) -> Result<(), String> {
    if RUNNING.swap(true, Ordering::SeqCst) {
        return Ok(());
    }
    // A previous session's stop() may have left this set (it only clears on a
    // clean join); a new run must not be born already asked to quit.
    STOP_REQUESTED.store(false, Ordering::SeqCst);
    RECEIVER_HWND.store(receiver_frame, Ordering::SeqCst);
    let (tx, rx) = mpsc::channel::<Result<(), String>>();

    let handle = std::thread::Builder::new()
        .name("dexcast-viewer".into())
        .spawn(move || {
            viewer_thread(monitor, tx);
            // However the window ended — Stop button, the user closing it,
            // an init failure — the machine goes back the way it was. Both
            // calls are idempotent, so the command layer repeating them (as
            // dexcast_stop does) is harmless.
            receiver::close();
            if let Err(e) = vdd::detach_monitor() {
                log::warn!("dexcast: detach after viewer close failed: {e}");
            }
            VIEWER_HWND.store(0, Ordering::SeqCst);
            RUNNING.store(false, Ordering::SeqCst);
        })
        .map_err(|e| {
            RUNNING.store(false, Ordering::SeqCst);
            format!("could not spawn the viewer thread: {e}")
        })?;
    *THREAD.lock().unwrap() = Some(handle);

    match rx.recv_timeout(Duration::from_secs(10)) {
        Ok(Ok(())) => Ok(()),
        Ok(Err(e)) => {
            // The thread reported a build failure and is already unwinding to
            // its epilogue (which resets RUNNING); reap it without holding the
            // lock across the join.
            let handle = THREAD.lock().unwrap().take();
            if let Some(handle) = handle {
                let _ = handle.join();
            }
            Err(e)
        }
        Err(_) => {
            // Slow build (WARP fallback, a stalled StartCapture): the thread
            // may still succeed at t>10s. Hand the caller the error and let
            // its cleanup call stop(), which now signals via STOP_REQUESTED
            // and bounds its wait — no join-under-lock, no wedge.
            Err("the viewer did not come up within 10 seconds".into())
        }
    }
}

// ── The viewer thread ───────────────────────────────────────────────────

/// Everything the window works with, owned by its thread. A raw pointer to
/// this rides in `GWLP_USERDATA` until `WM_NCDESTROY` reclaims the box — the
/// standard Win32 way to give a wndproc state without globals.
struct Viewer {
    monitor: MonitorRect,
    receiver_frame: HWND,
    hook: HHOOK,
    d3d_device: ID3D11Device,
    swapchain: IDXGISwapChain1,
    d2d_ctx: ID2D1DeviceContext,
    /// The D2D view of the swapchain's backbuffer. Dropped around every
    /// resize: `ResizeBuffers` refuses while any backbuffer reference lives.
    target: Option<ID2D1Bitmap1>,
    /// Each frame is copied here, and `staging_bitmap` is this texture's one
    /// D2D wrapper, made once — wrapping the frame pool's own rotating
    /// buffers every frame would churn allocations and tie D2D lifetime to
    /// textures the pool owns.
    staging: ID3D11Texture2D,
    staging_bitmap: ID2D1Bitmap1,
    frame_pool: Direct3D11CaptureFramePool,
    session: GraphicsCaptureSession,
    text_format: IDWriteTextFormat,
    text_brush: ID2D1SolidColorBrush,
    shadow_brush: ID2D1SolidColorBrush,
    /// Where the cursor was when the portal opened, to put it back after.
    saved_cursor: POINT,
    /// Ever drew a frame — the hint text changes once there is a picture.
    presented: bool,
}

fn viewer_thread(monitor: MonitorRect, ready: mpsc::Sender<Result<(), String>>) {
    unsafe {
        // Multithreaded rather than STA: the frame pool is free-threaded and
        // this thread pumps a plain Win32 loop, not a COM apartment.
        let _ = RoInitialize(RO_INIT_MULTITHREADED);
    }
    match unsafe { build_viewer(monitor) } {
        Ok(hwnd) => {
            VIEWER_HWND.store(hwnd.0 as isize, Ordering::SeqCst);
            let _ = ready.send(Ok(()));
            // stop() may have been requested while the build was still
            // running (its WM_CLOSE had no window to land on). Now that one
            // exists, honour the request at once rather than opening a
            // viewer nobody asked to keep.
            if STOP_REQUESTED.load(Ordering::SeqCst) {
                unsafe {
                    let _ = PostMessageW(Some(hwnd), WM_CLOSE, WPARAM(0), LPARAM(0));
                }
            }
            unsafe {
                let mut msg = MSG::default();
                // GetMessageW returns -1 (as_bool() → true) only on a bad
                // hwnd/lpMsg, neither of which is possible here; the loop
                // ends on WM_QUIT (0) from PostQuitMessage at WM_DESTROY.
                while GetMessageW(&mut msg, None, 0, 0).as_bool() {
                    let _ = TranslateMessage(&msg);
                    DispatchMessageW(&msg);
                }
            }
            // The window is gone; drop the stale handle before the epilogue's
            // seconds-long receiver::close so nothing posts to a dead hwnd or
            // reads it as "still up".
            VIEWER_HWND.store(0, Ordering::SeqCst);
        }
        Err(e) => {
            log::warn!("dexcast: viewer failed to start: {e}");
            let _ = ready.send(Err(e));
        }
    }
}

/// Build the whole pipeline in dependency order: D3D and capture first (no
/// window needed), then the window, then everything that wants its hwnd.
/// Nothing is shown until the last step, so every failure here is invisible.
unsafe fn build_viewer(monitor: MonitorRect) -> Result<HWND, String> {
    let hmodule = GetModuleHandleW(None).map_err(|e| e.to_string())?;

    // ── Direct3D ──
    let mut d3d_device: Option<ID3D11Device> = None;
    let mut created = D3D11CreateDevice(
        None,
        D3D_DRIVER_TYPE_HARDWARE,
        Default::default(),
        D3D11_CREATE_DEVICE_BGRA_SUPPORT,
        None,
        D3D11_SDK_VERSION,
        Some(&mut d3d_device),
        None,
        None,
    );
    if created.is_err() {
        // Mid-GPU-driver-update machines land here; software is slow but a
        // picture beats an error.
        created = D3D11CreateDevice(
            None,
            D3D_DRIVER_TYPE_WARP,
            Default::default(),
            D3D11_CREATE_DEVICE_BGRA_SUPPORT,
            None,
            D3D11_SDK_VERSION,
            Some(&mut d3d_device),
            None,
            None,
        );
    }
    created.map_err(|e| format!("D3D11CreateDevice failed: {e}"))?;
    let d3d_device = d3d_device.ok_or("D3D11 device missing after create")?;
    let dxgi_device: IDXGIDevice = d3d_device.cast().map_err(|e| e.to_string())?;

    // ── Capture ──
    let inspectable = CreateDirect3D11DeviceFromDXGIDevice(&dxgi_device)
        .map_err(|e| format!("WinRT D3D bridge failed: {e}"))?;
    let winrt_device: IDirect3DDevice = inspectable.cast().map_err(|e| e.to_string())?;

    // The capture item is the *monitor*, not the receiver's window: monitor
    // capture survives the receiver recreating its window mid-session, and
    // the receiver is the only thing on that monitor by construction.
    let hmon = MonitorFromPoint(
        POINT {
            x: monitor.x + monitor.w / 2,
            y: monitor.y + monitor.h / 2,
        },
        MONITOR_DEFAULTTONULL,
    );
    if hmon.is_invalid() {
        return Err("the virtual monitor vanished before capture started".into());
    }
    let interop = windows::core::factory::<GraphicsCaptureItem, IGraphicsCaptureItemInterop>()
        .map_err(|e| e.to_string())?;
    let item: GraphicsCaptureItem = interop
        .CreateForMonitor(hmon)
        .map_err(|e| format!("could not open the virtual monitor for capture: {e}"))?;

    let size = item.Size().map_err(|e| e.to_string())?;
    let frame_pool = Direct3D11CaptureFramePool::CreateFreeThreaded(
        &winrt_device,
        DirectXPixelFormat::B8G8R8A8UIntNormalized,
        2,
        size,
    )
    .map_err(|e| format!("frame pool: {e}"))?;
    let session = frame_pool.CreateCaptureSession(&item).map_err(|e| e.to_string())?;
    // Best-effort niceties on newer builds: the portal depends on *seeing*
    // the cursor, and the yellow capture border would sit on a monitor
    // nobody can see anyway.
    let _ = session.SetIsCursorCaptureEnabled(true);
    let _ = session.SetIsBorderRequired(false);
    session.StartCapture().map_err(|e| format!("StartCapture: {e}"))?;

    // ── The window ──
    let class = w!("OadDexcastViewer");
    // A class with no cursor leaves whatever shape the pointer had when it
    // entered — a resize arrow off the window edge, say — stuck over the
    // video. Load the standard arrow so the viewer looks like a window.
    let cursor = LoadCursorW(None, IDC_ARROW).unwrap_or_default();
    let wc = WNDCLASSW {
        lpfnWndProc: Some(wndproc),
        hInstance: hmodule.into(),
        lpszClassName: class,
        hbrBackground: HBRUSH(std::ptr::null_mut()),
        hCursor: cursor,
        ..Default::default()
    };
    // A second session re-registers the identical class; the "already
    // exists" failure that answers is not a failure.
    let _ = RegisterClassW(&wc);

    // 75% of the monitor: big enough to read DeX in, clearly still a window.
    let win_w = monitor.w * 3 / 4;
    let win_h = monitor.h * 3 / 4;
    let hwnd = CreateWindowExW(
        WS_EX_APPWINDOW,
        class,
        w!("Wireless DeX"),
        WS_OVERLAPPEDWINDOW,
        CW_USEDEFAULT,
        CW_USEDEFAULT,
        win_w,
        win_h,
        None,
        None,
        Some(hmodule.into()),
        None,
    )
    .map_err(|e| format!("CreateWindowExW: {e}"))?;

    // Destroy the window if any step below fails (`?`) before the state box
    // is installed — otherwise a swapchain/D2D/hook failure would leak one
    // never-shown top-level window per failed start. Disarmed once the window
    // is committed. DestroyWindow here fires WM_(NC)DESTROY with GWLP_USERDATA
    // still 0, which every wndproc arm null-guards.
    struct WindowGuard(HWND);
    impl Drop for WindowGuard {
        fn drop(&mut self) {
            unsafe {
                let _ = DestroyWindow(self.0);
            }
        }
    }
    let window_guard = WindowGuard(hwnd);

    // ── Swapchain + Direct2D, now that a surface exists to bind to ──
    let dxgi_factory: IDXGIFactory2 = dxgi_device
        .GetAdapter()
        .and_then(|a| a.GetParent())
        .map_err(|e| format!("DXGI factory: {e}"))?;
    let mut client = RECT::default();
    let _ = GetClientRect(hwnd, &mut client);
    let desc = DXGI_SWAP_CHAIN_DESC1 {
        Width: (client.right - client.left).max(1) as u32,
        Height: (client.bottom - client.top).max(1) as u32,
        Format: DXGI_FORMAT_B8G8R8A8_UNORM,
        SampleDesc: DXGI_SAMPLE_DESC { Count: 1, Quality: 0 },
        BufferUsage: DXGI_USAGE_RENDER_TARGET_OUTPUT,
        BufferCount: 2,
        Scaling: DXGI_SCALING_STRETCH,
        SwapEffect: DXGI_SWAP_EFFECT_FLIP_DISCARD,
        ..Default::default()
    };
    let swapchain = dxgi_factory
        .CreateSwapChainForHwnd(&d3d_device, hwnd, &desc, None, None)
        .map_err(|e| format!("swapchain: {e}"))?;

    let d2d_factory: ID2D1Factory1 = D2D1CreateFactory(D2D1_FACTORY_TYPE_SINGLE_THREADED, None)
        .map_err(|e| format!("D2D factory: {e}"))?;
    let d2d_device = d2d_factory.CreateDevice(&dxgi_device).map_err(|e| e.to_string())?;
    let d2d_ctx = d2d_device
        .CreateDeviceContext(D2D1_DEVICE_CONTEXT_OPTIONS_NONE)
        .map_err(|e| e.to_string())?;

    // The staging texture frames are copied into, wrapped for D2D once.
    let staging_desc = D3D11_TEXTURE2D_DESC {
        Width: monitor.w as u32,
        Height: monitor.h as u32,
        MipLevels: 1,
        ArraySize: 1,
        Format: DXGI_FORMAT_B8G8R8A8_UNORM,
        SampleDesc: DXGI_SAMPLE_DESC { Count: 1, Quality: 0 },
        Usage: D3D11_USAGE_DEFAULT,
        BindFlags: (D3D11_BIND_SHADER_RESOURCE.0 | D3D11_BIND_RENDER_TARGET.0) as u32,
        CPUAccessFlags: 0,
        MiscFlags: 0,
    };
    let mut staging: Option<ID3D11Texture2D> = None;
    d3d_device
        .CreateTexture2D(&staging_desc, None, Some(&mut staging))
        .map_err(|e| format!("staging texture: {e}"))?;
    let staging = staging.ok_or("staging texture missing")?;
    let staging_surface: IDXGISurface = staging.cast().map_err(|e| e.to_string())?;
    let source_props = D2D1_BITMAP_PROPERTIES1 {
        pixelFormat: D2D1_PIXEL_FORMAT {
            format: DXGI_FORMAT_B8G8R8A8_UNORM,
            alphaMode: D2D1_ALPHA_MODE_IGNORE,
        },
        dpiX: 96.0,
        dpiY: 96.0,
        bitmapOptions: D2D1_BITMAP_OPTIONS_NONE,
        colorContext: std::mem::ManuallyDrop::new(None),
    };
    let staging_bitmap = d2d_ctx
        .CreateBitmapFromDxgiSurface(&staging_surface, Some(&source_props))
        .map_err(|e| format!("staging bitmap: {e}"))?;

    // ── Text ──
    let dwrite: IDWriteFactory =
        DWriteCreateFactory(DWRITE_FACTORY_TYPE_SHARED).map_err(|e| e.to_string())?;
    let text_format = dwrite
        .CreateTextFormat(
            w!("Segoe UI"),
            None,
            DWRITE_FONT_WEIGHT_NORMAL,
            DWRITE_FONT_STYLE_NORMAL,
            DWRITE_FONT_STRETCH_NORMAL,
            15.0,
            w!("en-us"),
        )
        .map_err(|e| e.to_string())?;
    let _ = text_format.SetTextAlignment(DWRITE_TEXT_ALIGNMENT_CENTER);
    let text_brush = d2d_ctx
        .CreateSolidColorBrush(&D2D1_COLOR_F { r: 1.0, g: 1.0, b: 1.0, a: 0.92 }, None)
        .map_err(|e| e.to_string())?;
    let shadow_brush = d2d_ctx
        .CreateSolidColorBrush(&D2D1_COLOR_F { r: 0.0, g: 0.0, b: 0.0, a: 0.75 }, None)
        .map_err(|e| e.to_string())?;

    let mut viewer = Box::new(Viewer {
        monitor,
        receiver_frame: HWND(RECEIVER_HWND.load(Ordering::SeqCst) as *mut _),
        hook: HHOOK::default(),
        d3d_device,
        swapchain,
        d2d_ctx,
        target: None,
        staging,
        staging_bitmap,
        frame_pool,
        session,
        text_format,
        text_brush,
        shadow_brush,
        saved_cursor: POINT::default(),
        presented: false,
    });
    let _ = viewer.bind_target();

    // Esc must be interceptable while the *receiver* is focused, which only
    // a low-level hook can do (hotkeys/windows.rs has the whole argument).
    // Installed on this thread: the callback runs here while the loop pumps.
    viewer.hook = SetWindowsHookExW(WH_KEYBOARD_LL, Some(esc_hook), None, 0)
        .map_err(|e| format!("keyboard hook: {e}"))?;

    // The wndproc owns the state from here on; reclaimed at WM_NCDESTROY. The
    // window is now committed, so disarm the guard — from here the window's
    // lifetime belongs to its own WM_DESTROY, not to this function's error
    // paths (there are none left).
    SetWindowLongPtrW(hwnd, GWLP_USERDATA, Box::into_raw(viewer) as isize);
    std::mem::forget(window_guard);

    let _ = SetTimer(Some(hwnd), RENDER_TIMER, 15, None);
    let _ = ShowWindow(hwnd, SW_SHOW);
    let _ = SetForegroundWindow(hwnd);
    Ok(hwnd)
}

impl Viewer {
    /// Point the D2D context at the swapchain's current backbuffer.
    fn bind_target(&mut self) -> Result<(), String> {
        unsafe {
            let surface: IDXGISurface =
                self.swapchain.GetBuffer(0).map_err(|e| format!("backbuffer: {e}"))?;
            let props = D2D1_BITMAP_PROPERTIES1 {
                pixelFormat: D2D1_PIXEL_FORMAT {
                    format: DXGI_FORMAT_B8G8R8A8_UNORM,
                    alphaMode: D2D1_ALPHA_MODE_IGNORE,
                },
                dpiX: 96.0,
                dpiY: 96.0,
                bitmapOptions: D2D1_BITMAP_OPTIONS_TARGET | D2D1_BITMAP_OPTIONS_CANNOT_DRAW,
                colorContext: std::mem::ManuallyDrop::new(None),
            };
            let target = self
                .d2d_ctx
                .CreateBitmapFromDxgiSurface(&surface, Some(&props))
                .map_err(|e| format!("target bitmap: {e}"))?;
            self.d2d_ctx.SetTarget(&target);
            self.target = Some(target);
            Ok(())
        }
    }

    fn resize(&mut self, hwnd: HWND) {
        unsafe {
            self.d2d_ctx.SetTarget(None);
            self.target = None;
            let mut rect = RECT::default();
            let _ = GetClientRect(hwnd, &mut rect);
            let w = (rect.right - rect.left).max(1) as u32;
            let h = (rect.bottom - rect.top).max(1) as u32;
            if self
                .swapchain
                .ResizeBuffers(2, w, h, DXGI_FORMAT_B8G8R8A8_UNORM, Default::default())
                .is_ok()
            {
                let _ = self.bind_target();
            }
        }
    }

    /// One tick: drain the pool to the newest frame, draw it letterboxed
    /// with the hint line on top. Skips quietly when nothing new arrived —
    /// Miracast is 30 fps on a good day, and re-presenting an unchanged
    /// frame would only burn battery.
    fn render(&mut self, hwnd: HWND) {
        unsafe {
            let mut newest = None;
            while let Ok(frame) = self.frame_pool.TryGetNextFrame() {
                newest = Some(frame);
            }
            match newest {
                Some(frame) => {
                    let Ok(surface) = frame.Surface() else { return };
                    let Ok(access) = surface.cast::<IDirect3DDxgiInterfaceAccess>() else { return };
                    let Ok(texture) = access.GetInterface::<ID3D11Texture2D>() else { return };
                    let Ok(ctx) = self.d3d_device.GetImmediateContext() else { return };
                    ctx.CopyResource(&self.staging, &texture);
                    self.presented = true;
                }
                // No frame yet. Before the first one arrives (the monitor is
                // black until the phone casts) keep painting so the window is
                // not a blank rectangle; once a frame has landed, hold the
                // last one instead of re-presenting an unchanged picture.
                None if self.presented => return,
                None => {}
            }

            if self.target.is_none() && self.bind_target().is_err() {
                return;
            }

            let mut rect = RECT::default();
            let _ = GetClientRect(hwnd, &mut rect);
            let (dw, dh) = ((rect.right - rect.left) as f32, (rect.bottom - rect.top) as f32);

            self.d2d_ctx.BeginDraw();
            self.d2d_ctx
                .Clear(Some(&D2D1_COLOR_F { r: 0.035, g: 0.043, b: 0.063, a: 1.0 }));
            if self.presented {
            let fit = geometry::fit(self.monitor.w as f32, self.monitor.h as f32, dw, dh);
            let dest = D2D_RECT_F {
                left: fit.0,
                top: fit.1,
                right: fit.0 + fit.2,
                bottom: fit.1 + fit.3,
            };
            self.d2d_ctx.DrawBitmap(
                &self.staging_bitmap,
                Some(&dest),
                1.0,
                D2D1_INTERPOLATION_MODE_LINEAR,
                None,
                None,
            );
            }

            let hint = if !self.presented {
                "Waiting for the phone  ·  Quick settings → DeX → DeX on TV or monitor → pick this PC"
            } else if PORTAL.load(Ordering::SeqCst) {
                "Esc releases the mouse and keyboard"
            } else {
                "Click inside to control  ·  keyboard works; if the mouse doesn't, use the phone as a touchpad"
            };
            let wide: Vec<u16> = hint.encode_utf16().collect();
            let line = D2D_RECT_F { left: 0.0, top: 8.0, right: dw, bottom: 34.0 };
            let shadow = D2D_RECT_F { left: 1.0, top: 9.0, right: dw + 1.0, bottom: 35.0 };
            self.d2d_ctx.DrawText(
                &wide,
                &self.text_format,
                &shadow,
                &self.shadow_brush,
                Default::default(),
                DWRITE_MEASURING_MODE_NATURAL,
            );
            self.d2d_ctx.DrawText(
                &wide,
                &self.text_format,
                &line,
                &self.text_brush,
                Default::default(),
                DWRITE_MEASURING_MODE_NATURAL,
            );
            // EndDraw's error is usually D2DERR_RECREATE_TARGET, which the
            // next bind_target handles; Present's is the one that means the
            // GPU went away. On a genuine device-removed, the frame pool and
            // swapchain are both dead and re-initialising them here is more
            // machinery than a rare event earns — close the viewer so the
            // user restarts, which rebuilds everything cleanly.
            let _ = self.d2d_ctx.EndDraw(None, None);
            let hr = self.swapchain.Present(1, Default::default());
            if hr.is_err() && self.d3d_device.GetDeviceRemovedReason().is_err() {
                log::warn!("dexcast: GPU device lost ({hr:?}) — closing the viewer");
                let _ = PostMessageW(Some(hwnd), WM_CLOSE, WPARAM(0), LPARAM(0));
            }
            // `presented` is set only where a real frame was copied in (the
            // Some(frame) arm) — NOT here. Setting it after a waiting-screen
            // paint would flip the next tick into the "hold last frame" early
            // return, freezing the waiting screen (and a resize in that state
            // would show black, since nothing repaints it).
        }
    }

    /// A click in the video: hand the real input over.
    fn enter_portal(&mut self, hwnd: HWND, cx: f32, cy: f32) {
        unsafe {
            let mut rect = RECT::default();
            let _ = GetClientRect(hwnd, &mut rect);
            let (dw, dh) = ((rect.right - rect.left) as f32, (rect.bottom - rect.top) as f32);
            let fit = geometry::fit(self.monitor.w as f32, self.monitor.h as f32, dw, dh);
            let Some((sx, sy)) =
                geometry::map_to_source(cx, cy, fit, self.monitor.w as f32, self.monitor.h as f32)
            else {
                return; // letterbox bar — not the video
            };
            // A receiver window that has gone (the phone dropped, the app was
            // closed on the hidden monitor) must not become a place we fence
            // the cursor into with no way back. Confirm it is still a window
            // before handing control over.
            if !IsWindow(Some(self.receiver_frame)).as_bool() {
                log::warn!("dexcast: receiver window gone; not opening the portal");
                return;
            }
            let _ = GetCursorPos(&mut self.saved_cursor);
            force_foreground(self.receiver_frame);
            // Only fence the cursor once focus actually reached the receiver
            // — otherwise the click trapped the pointer on an invisible
            // monitor with the keyboard still here, the worst of both.
            if GetForegroundWindow() != self.receiver_frame {
                log::warn!("dexcast: receiver did not take focus; portal not opened");
                return;
            }
            self.clip_to_monitor();
            let _ = SetCursorPos(self.monitor.x + sx, self.monitor.y + sy);
            PORTAL.store(true, Ordering::SeqCst);
            log::info!("dexcast: portal opened at {sx},{sy}");
        }
    }

    /// Fence the cursor to the virtual monitor. Called on entry and re-called
    /// each tick while the portal holds: the receiver's own activation (it
    /// finishes coming to the foreground a beat after the click) clears any
    /// clip set before it, so a one-shot `ClipCursor` does not survive.
    fn clip_to_monitor(&self) {
        let clip = RECT {
            left: self.monitor.x,
            top: self.monitor.y,
            right: self.monitor.x + self.monitor.w,
            bottom: self.monitor.y + self.monitor.h,
        };
        unsafe {
            let _ = ClipCursor(Some(&clip));
        }
    }

    /// Esc (or focus loss): take the input back.
    fn exit_portal(&mut self, hwnd: HWND, refocus: bool) {
        if !PORTAL.swap(false, Ordering::SeqCst) {
            return;
        }
        unsafe {
            let _ = ClipCursor(None);
            let _ = SetCursorPos(self.saved_cursor.x, self.saved_cursor.y);
            if refocus {
                // Plain SetForegroundWindow is refused here: the receiver is
                // the foreground process and this call comes from a swallowed
                // key, so none of the foreground-lock exemptions apply. The
                // AttachThreadInput dance is the documented way to move focus
                // between the two — without it the keyboard stays on the
                // receiver while the UI says input was released.
                force_foreground(hwnd);
            }
            log::info!("dexcast: portal closed");
        }
    }
}

/// Move the foreground to `target`, defeating the foreground lock by briefly
/// sharing an input queue with whoever currently holds it — the standard
/// AttachThreadInput technique, also why `embed/windows.rs` reaches for
/// PowerShell's AppActivate for its own out-of-context focus change.
fn force_foreground(target: HWND) {
    unsafe {
        if SetForegroundWindow(target).as_bool() {
            return;
        }
        let fg = GetForegroundWindow();
        if fg.is_invalid() {
            return;
        }
        let fg_tid = GetWindowThreadProcessId(fg, None);
        let me = GetCurrentThreadId();
        if fg_tid == 0 || fg_tid == me {
            return;
        }
        let attached = AttachThreadInput(me, fg_tid, true);
        let _ = SetForegroundWindow(target);
        if attached.as_bool() {
            let _ = AttachThreadInput(me, fg_tid, false);
        }
    }
}

// ── Messages ────────────────────────────────────────────────────────────

unsafe fn viewer_from(hwnd: HWND) -> *mut Viewer {
    GetWindowLongPtrW(hwnd, GWLP_USERDATA) as *mut Viewer
}

extern "system" fn wndproc(hwnd: HWND, msg: u32, wparam: WPARAM, lparam: LPARAM) -> LRESULT {
    unsafe {
        match msg {
            WM_TIMER if wparam.0 == RENDER_TIMER => {
                let v = viewer_from(hwnd);
                if !v.is_null() {
                    let v = &mut *v;
                    // The portal dies with the receiver's focus: if the user
                    // alt-tabbed elsewhere, un-clip quietly instead of
                    // holding the cursor hostage on an invisible monitor.
                    if PORTAL.load(Ordering::SeqCst) {
                        let fg = GetForegroundWindow();
                        if fg != v.receiver_frame && fg != hwnd {
                            v.exit_portal(hwnd, false);
                        } else if fg == v.receiver_frame {
                            // Re-assert the fence: the receiver's own
                            // activation, and any later focus wobble, clears
                            // a clip set at click time.
                            v.clip_to_monitor();
                        }
                    }
                    v.render(hwnd);
                }
                LRESULT(0)
            }
            WM_SIZE => {
                let v = viewer_from(hwnd);
                if !v.is_null() {
                    (*v).resize(hwnd);
                }
                LRESULT(0)
            }
            WM_LBUTTONDOWN => {
                let v = viewer_from(hwnd);
                if !v.is_null() {
                    let x = (lparam.0 & 0xffff) as i16 as f32;
                    let y = ((lparam.0 >> 16) & 0xffff) as i16 as f32;
                    (*v).enter_portal(hwnd, x, y);
                }
                LRESULT(0)
            }
            WM_APP_EXIT_PORTAL => {
                let v = viewer_from(hwnd);
                if !v.is_null() {
                    (*v).exit_portal(hwnd, true);
                }
                LRESULT(0)
            }
            WM_CLOSE => {
                let _ = DestroyWindow(hwnd);
                LRESULT(0)
            }
            WM_DESTROY => {
                let v = viewer_from(hwnd);
                if !v.is_null() {
                    let v = &mut *v;
                    v.exit_portal(hwnd, false);
                    let _ = KillTimer(Some(hwnd), RENDER_TIMER);
                    if !v.hook.is_invalid() {
                        let _ = UnhookWindowsHookEx(v.hook);
                        v.hook = HHOOK::default();
                    }
                    let _ = v.session.Close();
                    let _ = v.frame_pool.Close();
                }
                PostQuitMessage(0);
                LRESULT(0)
            }
            WM_NCDESTROY => {
                let v = viewer_from(hwnd);
                if !v.is_null() {
                    SetWindowLongPtrW(hwnd, GWLP_USERDATA, 0);
                    drop(Box::from_raw(v));
                }
                DefWindowProcW(hwnd, msg, wparam, lparam)
            }
            _ => DefWindowProcW(hwnd, msg, wparam, lparam),
        }
    }
}

// ── The Esc hook ────────────────────────────────────────────────────────

/// A taken key-down must take its key-up too, or the receiver sees a release
/// with no press — same bookkeeping as hotkeys/windows.rs, same reason.
static SWALLOW_UP: AtomicBool = AtomicBool::new(false);

extern "system" fn esc_hook(code: i32, wparam: WPARAM, lparam: LPARAM) -> LRESULT {
    unsafe {
        if code == HC_ACTION as i32 {
            let kb = &*(lparam.0 as *const KBDLLHOOKSTRUCT);
            let msg = wparam.0 as u32;
            let is_down = msg == WM_KEYDOWN || msg == WM_SYSKEYDOWN;
            let is_up = msg == WM_KEYUP || msg == WM_SYSKEYUP;
            if kb.vkCode == VK_ESCAPE.0 as u32 {
                // The matching release is swallowed on the strength of having
                // swallowed the press — checked FIRST, before the PORTAL and
                // foreground gates, because exit_portal has by now cleared
                // PORTAL and moved focus, so re-testing those gates would let
                // this up leak (a release with no press) and strand
                // SWALLOW_UP true to eat some later, unrelated Esc release.
                if is_up && SWALLOW_UP.swap(false, Ordering::SeqCst) {
                    return LRESULT(1);
                }
                if is_down && PORTAL.load(Ordering::SeqCst) {
                    let fg = GetForegroundWindow().0 as isize;
                    if fg == RECEIVER_HWND.load(Ordering::SeqCst) {
                        // Bare Esc only: Ctrl+Esc is the Start menu, and
                        // chords are not ours to eat.
                        let modifier = [VK_CONTROL, VK_MENU, VK_SHIFT, VK_LWIN, VK_RWIN]
                            .iter()
                            .any(|vk| (GetAsyncKeyState(vk.0 as i32) as u16) & 0x8000 != 0);
                        if !modifier {
                            SWALLOW_UP.store(true, Ordering::SeqCst);
                            let viewer = VIEWER_HWND.load(Ordering::SeqCst);
                            if viewer != 0 {
                                let _ = PostMessageW(
                                    Some(HWND(viewer as *mut _)),
                                    WM_APP_EXIT_PORTAL,
                                    WPARAM(0),
                                    LPARAM(0),
                                );
                            }
                            return LRESULT(1);
                        }
                    }
                }
            }
        }
        CallNextHookEx(None, code, wparam, lparam)
    }
}
