//! One phone app in a window of its own, without the rest of the app — for
//! trying the window by hand on a phone that is already on adb:
//!
//!   cargo run --example app_window -- <serial> <package> [label]
//!
//! Starts the phone's app server itself (pushing the dex from resources/bin),
//! as the app's own host.rs does, and opens one window on it. Logs to stderr.
//! A developer's tool only: nothing ships it.

#[cfg(windows)]
fn main() {
    use std::io::{BufRead, BufReader};
    use std::process::{Command, Stdio};

    struct Log;
    impl log::Log for Log {
        fn enabled(&self, m: &log::Metadata) -> bool {
            m.level() <= log::Level::Info
        }
        fn log(&self, r: &log::Record) {
            if self.enabled(r.metadata()) {
                eprintln!("{:>5} {}", r.level(), r.args());
            }
        }
        fn flush(&self) {}
    }
    static LOGGER: Log = Log;
    let _ = log::set_logger(&LOGGER).map(|()| log::set_max_level(log::LevelFilter::Info));

    let args: Vec<String> = std::env::args().collect();
    let (Some(serial), Some(pkg)) = (args.get(1), args.get(2)) else {
        eprintln!("usage: app_window <serial> <package> [label]");
        std::process::exit(2);
    };
    let label = args.get(3).cloned().unwrap_or_else(|| pkg.clone());
    let bin = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("resources").join("bin");
    let adb = bin.join("adb.exe");
    let dex = bin.join("openandroiddex-wmd.dex");
    let run = |a: &[&str]| Command::new(&adb).args(a).status().expect("adb");
    run(&["-s", serial, "push", &dex.to_string_lossy(), "/data/local/tmp/oadx-apps.dex.new"]);
    run(&["-s", serial, "shell", "mv -f /data/local/tmp/oadx-apps.dex.new /data/local/tmp/oadx-apps.dex"]);
    let socket = format!("openandroiddex-apps-probe-{}", std::process::id());
    let mut child = Command::new(&adb)
        .args([
            "-s",
            serial,
            "shell",
            &format!("CLASSPATH=/data/local/tmp/oadx-apps.dex exec app_process / com.ccrstech.openandroiddex.wmd.AppServer {socket}"),
        ])
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .spawn()
        .expect("adb shell");
    let out = child.stdout.take().unwrap();
    let (tx, rx) = std::sync::mpsc::channel();
    std::thread::spawn(move || {
        let mut tx = Some(tx);
        for line in BufReader::new(out).lines().map_while(Result::ok) {
            eprintln!("phone {line}");
            if line.contains("warmed up") || line.contains("warming up") {
                if let Some(t) = tx.take() {
                    let _ = t.send(());
                }
            }
        }
    });
    rx.recv_timeout(std::time::Duration::from_secs(15)).expect("the phone's app server did not start");
    let r = open_android_dex_tauri_lib::app_window_probe(serial, &socket, pkg, &label, None);
    eprintln!("window closed: {r:?}");
    drop(child.stdin.take());
    std::thread::sleep(std::time::Duration::from_millis(800));
    let _ = child.kill();
}

#[cfg(not(windows))]
fn main() {
    eprintln!("the app window is Windows-only; elsewhere scrcpy's window is used");
}
