use std::io::{BufRead, BufReader, Write};
use std::process::{Child, ChildStdin, ChildStdout, Stdio};
use std::time::{Duration, Instant};

use tauri::AppHandle;

use crate::adb;

/// A persistent `adb shell` session for one device.
///
/// Every one-shot `adb.exe shell …` costs a process spawn plus an adb-server
/// roundtrip — 100-300ms on Windows before the command even reaches the
/// phone. The freeform enforcer polls several times per second and reacts to
/// taskbar/titlebar button presses, so it keeps ONE device shell open and
/// streams commands through it; a per-call sentinel delimits each command's
/// output. The session respawns itself once per call if the pipe died
/// (device unplugged, adb restarted) — and no more than once a
/// [`RESPAWN_AFTER`] while a fresh shell dies at once: a phone that is gone
/// answers every spawn with an exit, and its callers keep asking several
/// times a second until it is back.
pub struct ShellSession {
    serial: String,
    conn: Option<Conn>,
    seq: u64,
    /// No new shell before this, after one that died straight away.
    retry_at: Option<Instant>,
}

/// How long a session waits after a fresh shell died before it spawns another.
const RESPAWN_AFTER: Duration = Duration::from_secs(1);

struct Conn {
    child: Child,
    stdin: ChildStdin,
    stdout: BufReader<ChildStdout>,
}

impl Conn {
    /// Kill it and reap it. A child that is never waited for stays a zombie
    /// holding a process slot: one per failed call, with the phone unplugged,
    /// filled the computer's process table in under three minutes
    /// (2026-10-20) — nothing on it could start a process after that.
    fn close(mut self) {
        let _ = self.child.kill();
        let _ = self.child.wait();
    }
}

impl ShellSession {
    pub fn new(serial: String) -> Self {
        Self {
            serial,
            conn: None,
            seq: 0,
            retry_at: None,
        }
    }

    fn spawn(&self, app: &AppHandle) -> Result<Conn, String> {
        let mut cmd = adb::adb_command(app);
        // -T: never allocate a pty — no prompt, no input echo, so the
        // sentinel can only appear in output when our `echo` runs
        cmd.args(["-s", &self.serial, "shell", "-T"]);
        cmd.stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::null());
        let mut child = cmd
            .spawn()
            .map_err(|e| format!("failed to start adb shell: {e}"))?;
        let stdin = child.stdin.take().ok_or("no stdin on adb shell")?;
        let stdout = child.stdout.take().ok_or("no stdout on adb shell")?;
        Ok(Conn {
            child,
            stdin,
            stdout: BufReader::new(stdout),
        })
    }

    /// Run one (single-line) shell command and return its stdout.
    pub fn run(&mut self, app: &AppHandle, cmd: &str) -> Result<String, String> {
        let mut last_err = String::new();
        for _ in 0..2 {
            if self.conn.is_none() {
                if self.retry_at.is_some_and(|at| Instant::now() < at) {
                    return Err(if last_err.is_empty() { "adb shell unavailable".into() } else { last_err });
                }
                match self.spawn(app) {
                    Ok(conn) => self.conn = Some(conn),
                    Err(e) => {
                        self.retry_at = Some(Instant::now() + RESPAWN_AFTER);
                        return Err(e);
                    }
                }
            }
            match self.exec(cmd) {
                Ok(out) => {
                    self.retry_at = None;
                    return Ok(out);
                }
                Err(e) => {
                    // dead pipe — drop the connection and retry once fresh
                    if let Some(conn) = self.conn.take() {
                        conn.close();
                    }
                    last_err = e;
                }
            }
        }
        // A fresh shell died as well: the device, or adb, is gone for now.
        self.retry_at = Some(Instant::now() + RESPAWN_AFTER);
        Err(last_err)
    }

    fn exec(&mut self, cmd: &str) -> Result<String, String> {
        let conn = self.conn.as_mut().ok_or("no adb shell connection")?;
        self.seq += 1;
        let sentinel = format!("__OADX_DONE_{}__", self.seq);
        // `;` so the sentinel is printed even when the command itself fails
        writeln!(conn.stdin, "{cmd}; echo {sentinel}")
            .and_then(|_| conn.stdin.flush())
            .map_err(|e| format!("adb shell write failed: {e}"))?;
        let mut out = String::new();
        let mut line = String::new();
        loop {
            line.clear();
            let n = conn
                .stdout
                .read_line(&mut line)
                .map_err(|e| format!("adb shell read failed: {e}"))?;
            if n == 0 {
                return Err("adb shell closed".into());
            }
            let clean = line.trim_end_matches(['\n', '\r']);
            if clean == sentinel {
                return Ok(out);
            }
            out.push_str(clean);
            out.push('\n');
        }
    }
}

impl Drop for ShellSession {
    fn drop(&mut self) {
        if let Some(conn) = self.conn.take() {
            conn.close();
        }
    }
}
