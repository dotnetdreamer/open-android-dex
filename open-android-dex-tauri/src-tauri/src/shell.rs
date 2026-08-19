use std::io::{BufRead, BufReader, Write};
use std::process::{Child, ChildStdin, ChildStdout, Stdio};

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
/// (device unplugged, adb restarted).
pub struct ShellSession {
    serial: String,
    conn: Option<Conn>,
    seq: u64,
}

struct Conn {
    child: Child,
    stdin: ChildStdin,
    stdout: BufReader<ChildStdout>,
}

impl ShellSession {
    pub fn new(serial: String) -> Self {
        Self {
            serial,
            conn: None,
            seq: 0,
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
                self.conn = Some(self.spawn(app)?);
            }
            match self.exec(cmd) {
                Ok(out) => return Ok(out),
                Err(e) => {
                    // dead pipe — drop the connection and retry once fresh
                    if let Some(mut conn) = self.conn.take() {
                        let _ = conn.child.kill();
                    }
                    last_err = e;
                }
            }
        }
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
        if let Some(mut conn) = self.conn.take() {
            let _ = conn.child.kill();
        }
    }
}
