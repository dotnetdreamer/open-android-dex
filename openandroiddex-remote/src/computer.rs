//! The computer's own files: who it is, which phones it has paired with, and
//! the SSH configuration that makes `ssh <phone>` work from every terminal and
//! every tool that reads `~/.ssh/config` — VS Code's Remote-SSH, JetBrains
//! Gateway, rsync, scp, git.
//!
//! What gets written, all of it ours except one line:
//!
//! | file | what |
//! |---|---|
//! | `<app data>/remote/computer.key` | this computer's iroh identity |
//! | `<app data>/remote/phones.json` | the paired phones |
//! | `~/.ssh/openandroiddex_ed25519` (+`.pub`) | the SSH key the phones were given |
//! | `~/.ssh/openandroiddex_known_hosts` | each phone's host key, from pairing |
//! | `~/.ssh/openandroiddex.conf` | one `Host` block per phone, regenerated whole |
//! | `~/.ssh/config` | gains a single `Include openandroiddex.conf` at the top |
//!
//! The include goes at the very top because ssh takes the first value it finds
//! for each option, and an Include placed after a `Host *` block would be
//! read inside it. Everything else is a file of our own, regenerated from
//! `phones.json` on every change, so there is nothing of the user's to merge
//! with and nothing to leave behind but those files themselves.
//!
//! The known_hosts entries are what make this safer than a first `ssh` to a
//! new box: the host key arrives inside the authenticated pairing exchange, so
//! there is no "authenticity of host can't be established" prompt to click
//! through, and `StrictHostKeyChecking yes` turns any other key into a refusal.

use std::path::{Path, PathBuf};

use anyhow::{Context, Result};
use iroh::SecretKey;
use serde::{Deserialize, Serialize};

use crate::pc::Paired;
use crate::{sshkey, unix_now};

/// The desktop app's bundle identifier. Its app-data directory is
/// `<data dir>/<identifier>`, and this crate's state lives inside it so the
/// app and a standalone `dexremote` share one identity and one list.
pub const APP_IDENTIFIER: &str = "com.ccrstech.openandroiddex";

const INCLUDE_NAME: &str = "openandroiddex.conf";
const KEY_NAME: &str = "openandroiddex_ed25519";
const KNOWN_HOSTS_NAME: &str = "openandroiddex_known_hosts";

/// `<data dir>/com.ccrstech.openandroiddex/remote` — the same place Tauri's
/// `app_data_dir()` resolves to on each OS, worked out here because the
/// ProxyCommand runs without a Tauri app around it.
pub fn default_state_dir() -> Option<PathBuf> {
    let data = if cfg!(windows) {
        std::env::var_os("APPDATA").map(PathBuf::from)
    } else if cfg!(target_os = "macos") {
        home_dir().map(|h| h.join("Library").join("Application Support"))
    } else {
        std::env::var_os("XDG_DATA_HOME")
            .map(PathBuf::from)
            .filter(|p| p.is_absolute())
            .or_else(|| home_dir().map(|h| h.join(".local").join("share")))
    }?;
    Some(data.join(APP_IDENTIFIER).join("remote"))
}

pub fn home_dir() -> Option<PathBuf> {
    let var = if cfg!(windows) { "USERPROFILE" } else { "HOME" };
    std::env::var_os(var).map(PathBuf::from).filter(|p| !p.as_os_str().is_empty())
}

/// A phone this computer has paired with.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Phone {
    /// Its permanent endpoint id, hex.
    pub id: String,
    pub name: String,
    /// The `Host` name in the SSH config: what you type after `ssh`.
    pub alias: String,
    pub user: String,
    pub host_key: String,
    pub paired_at: u64,
    /// "code" or "usb".
    pub via: String,
}

impl Phone {
    /// The known_hosts name for this phone. A HostKeyAlias rather than the
    /// alias itself, so renaming the alias never orphans the trusted key.
    pub fn host_key_alias(&self) -> String {
        format!("openandroiddex-{}", &self.id[..self.id.len().min(16)])
    }
}

#[derive(Debug, Default, Serialize, Deserialize)]
struct Registry {
    #[serde(default)]
    phones: Vec<Phone>,
}

/// This computer's side of remote access, rooted at two directories.
pub struct Computer {
    state: PathBuf,
    ssh: PathBuf,
}

impl Computer {
    /// The real locations: the app-data directory and `~/.ssh`.
    pub fn open_default() -> Result<Self> {
        let state = default_state_dir().context("cannot work out where to keep remote-access settings")?;
        let ssh = home_dir().context("cannot find the home directory")?.join(".ssh");
        Ok(Self::open(state, ssh))
    }

    pub fn open(state: PathBuf, ssh: PathBuf) -> Self {
        Computer { state, ssh }
    }

    pub fn state_dir(&self) -> &Path {
        &self.state
    }

    /// This computer's iroh identity, made on first use.
    pub fn endpoint_key(&self) -> Result<SecretKey> {
        std::fs::create_dir_all(&self.state)?;
        let path = self.state.join("computer.key");
        match std::fs::read(&path) {
            Ok(bytes) if bytes.len() == 32 => Ok(SecretKey::from_bytes(bytes.as_slice().try_into().expect("checked"))),
            Ok(_) => anyhow::bail!("{} is damaged; delete it to make a new identity (you will need to pair again)", path.display()),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => {
                let key = SecretKey::generate();
                write_file(&path, &key.to_bytes(), true)?;
                Ok(key)
            }
            Err(e) => Err(e).with_context(|| format!("cannot read {}", path.display())),
        }
    }

    /// The SSH client key's public line, making the key on first use. An
    /// existing key is kept — it may already be authorized on other phones.
    pub fn ssh_public_key(&self) -> Result<String> {
        std::fs::create_dir_all(&self.ssh)?;
        restrict_dir(&self.ssh);
        let private = self.ssh.join(KEY_NAME);
        let public_path = self.ssh.join(format!("{KEY_NAME}.pub"));
        if let Ok(pem) = std::fs::read_to_string(&private) {
            let seed = sshkey::parse_private_pem(&pem)
                .with_context(|| format!("{} is not an unencrypted Ed25519 key", private.display()))?;
            let public = *SecretKey::from_bytes(&seed).public().as_bytes();
            let line = sshkey::public_line(&public, &key_comment());
            if !public_path.exists() {
                write_file(&public_path, format!("{line}\n").as_bytes(), false)?;
            }
            return Ok(line);
        }
        let key = SecretKey::generate();
        let public = *key.public().as_bytes();
        let comment = key_comment();
        write_file(&private, sshkey::private_pem(&key.to_bytes(), &public, &comment).as_bytes(), true)?;
        let line = sshkey::public_line(&public, &comment);
        write_file(&public_path, format!("{line}\n").as_bytes(), false)?;
        Ok(line)
    }

    pub fn phones(&self) -> Vec<Phone> {
        self.load().phones
    }

    fn load(&self) -> Registry {
        std::fs::read(self.state.join("phones.json"))
            .ok()
            .and_then(|b| serde_json::from_slice(&b).ok())
            .unwrap_or_default()
    }

    fn save(&self, registry: &Registry) -> Result<()> {
        std::fs::create_dir_all(&self.state)?;
        write_file(&self.state.join("phones.json"), &serde_json::to_vec_pretty(registry)?, false)
    }

    /// Remember a phone (replacing an older pairing with the same phone) and
    /// rewrite the SSH files. `proxy` is the ProxyCommand's program and leading
    /// arguments; the phone's id is appended to it.
    pub fn add_phone(&self, paired: &Paired, via: &str, proxy: &[String]) -> Result<Phone> {
        let mut registry = self.load();
        let id = paired.id.to_string();
        // Pairing again keeps the alias people have been typing.
        let previous = registry.phones.iter().position(|p| p.id == id);
        let alias = match previous {
            Some(i) => registry.phones[i].alias.clone(),
            None => unique_alias(&slug(&paired.name), &registry.phones),
        };
        let phone = Phone {
            id,
            name: paired.name.clone(),
            alias,
            user: paired.user.clone(),
            host_key: paired.host_key.trim().to_string(),
            paired_at: unix_now(),
            via: via.to_string(),
        };
        match previous {
            Some(i) => registry.phones[i] = phone.clone(),
            None => registry.phones.push(phone.clone()),
        }
        self.save(&registry)?;
        self.write_ssh_files(proxy)?;
        Ok(phone)
    }

    /// Forget a phone here. (The phone keeps this computer's key until it is
    /// removed there too; without this side's identity it is no use to anyone.)
    pub fn remove_phone(&self, id: &str, proxy: &[String]) -> Result<bool> {
        let mut registry = self.load();
        let before = registry.phones.len();
        registry.phones.retain(|p| p.id != id);
        let removed = registry.phones.len() != before;
        if removed {
            self.save(&registry)?;
            self.write_ssh_files(proxy)?;
        }
        Ok(removed)
    }

    /// Regenerate the include file and known_hosts from the registry, and
    /// make sure `~/.ssh/config` includes them. Run on every change and on
    /// every app start, so a moved or updated app fixes its own ProxyCommand.
    pub fn write_ssh_files(&self, proxy: &[String]) -> Result<()> {
        let phones = self.phones();
        std::fs::create_dir_all(&self.ssh)?;
        restrict_dir(&self.ssh);

        let mut known = String::from(
            "# Written by Open Android DeX from its pairings. Changes are overwritten.\n",
        );
        for p in &phones {
            known.push_str(&format!("{} {}\n", p.host_key_alias(), key_without_comment(&p.host_key)));
        }
        write_file(&self.ssh.join(KNOWN_HOSTS_NAME), known.as_bytes(), false)?;

        let mut conf = String::from(
            "# Written by Open Android DeX — one block per paired phone. This file is\n\
             # regenerated on every change; put your own settings in ~/.ssh/config.\n\
             #\n\
             # `ssh <name>` reaches the phone's Linux from any network. The ProxyCommand\n\
             # finds the phone by its key and carries the connection, end-to-end encrypted.\n",
        );
        let proxy_prefix = proxy.iter().map(|a| quote_arg(a)).collect::<Vec<_>>().join(" ");
        for p in &phones {
            conf.push_str(&format!(
                "\n# {name}\n\
                 Host {alias}\n\
                 \x20   User {user}\n\
                 \x20   ProxyCommand {proxy} {id}\n\
                 \x20   HostKeyAlias {hka}\n\
                 \x20   UserKnownHostsFile ~/.ssh/{KNOWN_HOSTS_NAME}\n\
                 \x20   StrictHostKeyChecking yes\n\
                 \x20   IdentityFile ~/.ssh/{KEY_NAME}\n\
                 \x20   IdentitiesOnly yes\n\
                 \x20   ServerAliveInterval 20\n\
                 \x20   ServerAliveCountMax 3\n",
                name = one_line(&p.name),
                alias = p.alias,
                user = p.user,
                proxy = proxy_prefix,
                id = p.id,
                hka = p.host_key_alias(),
            ));
        }
        write_file(&self.ssh.join(INCLUDE_NAME), conf.as_bytes(), false)?;
        self.ensure_include()
    }

    /// Put `Include openandroiddex.conf` at the top of `~/.ssh/config`, once.
    fn ensure_include(&self) -> Result<()> {
        let path = self.ssh.join("config");
        let existing = match std::fs::read(&path) {
            Ok(b) => b,
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Vec::new(),
            Err(e) => return Err(e).context("cannot read ~/.ssh/config"),
        };
        let text = String::from_utf8_lossy(&existing);
        let already = text.lines().any(|l| {
            let l = l.trim();
            l.len() > 7 && l[..7].eq_ignore_ascii_case("include") && l.contains(INCLUDE_NAME)
        });
        if already {
            return Ok(());
        }
        // Keep whatever the file already is: a byte-order mark first (Notepad
        // writes one), and the line endings it already uses.
        let (bom, body) = match text.strip_prefix('\u{feff}') {
            Some(rest) => ("\u{feff}", rest),
            None => ("", text.as_ref()),
        };
        let nl = if body.contains("\r\n") { "\r\n" } else { "\n" };
        let mut out = String::new();
        out.push_str(bom);
        out.push_str(&format!(
            "# Added by Open Android DeX: `ssh <phone>` for each paired phone.{nl}\
             # It must stay above every Host and Match block.{nl}\
             Include {INCLUDE_NAME}{nl}"
        ));
        if !body.is_empty() {
            out.push_str(nl);
            out.push_str(body);
        }
        let mode = std::fs::metadata(&path).ok().map(|m| m.permissions());
        write_file(&path, out.as_bytes(), mode.is_none())?;
        if let Some(perms) = mode {
            let _ = std::fs::set_permissions(&path, perms);
        }
        Ok(())
    }
}

/// This computer's name, for the phone's list of computers.
pub fn computer_name() -> String {
    let name = if cfg!(windows) {
        std::env::var("COMPUTERNAME").ok()
    } else if cfg!(target_os = "macos") {
        std::process::Command::new("scutil")
            .args(["--get", "ComputerName"])
            .output()
            .ok()
            .filter(|o| o.status.success())
            .map(|o| String::from_utf8_lossy(&o.stdout).trim().to_string())
    } else {
        std::fs::read_to_string("/proc/sys/kernel/hostname")
            .or_else(|_| std::fs::read_to_string("/etc/hostname"))
            .ok()
            .map(|s| s.trim().to_string())
    };
    name.filter(|n| !n.is_empty()).unwrap_or_else(|| "This computer".to_string())
}

pub fn os_name() -> &'static str {
    if cfg!(windows) {
        "windows"
    } else if cfg!(target_os = "macos") {
        "macos"
    } else {
        "linux"
    }
}

fn key_comment() -> String {
    let name: String = computer_name()
        .chars()
        .map(|c| if c.is_ascii_alphanumeric() || c == '-' || c == '.' { c } else { '-' })
        .collect();
    format!("openandroiddex@{name}")
}

/// "Galaxy S25 Ultra" → "galaxy-s25-ultra".
fn slug(name: &str) -> String {
    let mut out = String::new();
    for c in name.chars() {
        if c.is_ascii_alphanumeric() {
            out.push(c.to_ascii_lowercase());
        } else if !out.ends_with('-') && !out.is_empty() {
            out.push('-');
        }
    }
    let out: String = out.trim_matches('-').chars().take(32).collect();
    let out = out.trim_end_matches('-').to_string();
    if out.is_empty() { "phone".to_string() } else { out }
}

fn unique_alias(base: &str, phones: &[Phone]) -> String {
    let taken = |a: &str| phones.iter().any(|p| p.alias == a);
    if !taken(base) {
        return base.to_string();
    }
    (2..).map(|n| format!("{base}-{n}")).find(|a| !taken(a)).expect("unbounded")
}

fn one_line(s: &str) -> String {
    s.chars().map(|c| if c.is_control() { ' ' } else { c }).collect()
}

fn key_without_comment(line: &str) -> String {
    line.split_whitespace().take(2).collect::<Vec<_>>().join(" ")
}

/// Quote a ProxyCommand word if it needs it. Forward slashes on Windows: ssh's
/// config parser reads a backslash as an escape in some positions, and Windows
/// takes either separator.
fn quote_arg(arg: &str) -> String {
    let arg = if cfg!(windows) { arg.replace('\\', "/") } else { arg.to_string() };
    if arg.chars().any(|c| c.is_whitespace() || c == '"' || c == '\'') {
        format!("\"{}\"", arg.replace('"', "\\\""))
    } else {
        arg
    }
}

/// Replace a file in one step. `secret`: owner-only, as ssh insists for keys.
fn write_file(path: &Path, bytes: &[u8], secret: bool) -> Result<()> {
    let tmp = path.with_extension("oadx-tmp");
    {
        let mut opts = std::fs::OpenOptions::new();
        opts.write(true).create(true).truncate(true);
        #[cfg(unix)]
        {
            use std::os::unix::fs::OpenOptionsExt;
            opts.mode(if secret { 0o600 } else { 0o644 });
        }
        // Windows OpenSSH checks the key's ACL instead, and a file created in
        // the profile inherits one it accepts: the user, SYSTEM, Administrators.
        #[cfg(not(unix))]
        let _ = secret;
        let mut f = opts.open(&tmp).with_context(|| format!("cannot write {}", tmp.display()))?;
        std::io::Write::write_all(&mut f, bytes)?;
        f.sync_all()?;
    }
    std::fs::rename(&tmp, path).with_context(|| format!("cannot replace {}", path.display()))?;
    Ok(())
}

/// ssh refuses a `~/.ssh` others can write to. Only ever tightens.
fn restrict_dir(dir: &Path) {
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        if let Ok(meta) = std::fs::metadata(dir) {
            let mode = meta.permissions().mode();
            if mode & 0o022 != 0 {
                let _ = std::fs::set_permissions(dir, std::fs::Permissions::from_mode(mode & !0o022));
            }
        }
    }
    #[cfg(not(unix))]
    let _ = dir;
}

#[cfg(test)]
mod tests {
    use super::*;

    fn scratch(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("dexremote-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    fn paired(name: &str, seed: u8) -> Paired {
        let host = SecretKey::from_bytes(&[seed.wrapping_add(100); 32]);
        Paired {
            id: SecretKey::from_bytes(&[seed; 32]).public(),
            name: name.into(),
            user: "root".into(),
            host_key: sshkey::public_line(host.public().as_bytes(), "openandroiddex"),
        }
    }

    #[test]
    fn slugs_make_typeable_aliases() {
        assert_eq!(slug("Galaxy S25 Ultra"), "galaxy-s25-ultra");
        assert_eq!(slug("  Pixel (8) Pro!! "), "pixel-8-pro");
        assert_eq!(slug("Телефон"), "phone");
        assert!(slug(&"a b ".repeat(30)).len() <= 32);
    }

    #[test]
    fn pairing_writes_a_working_ssh_setup_and_keeps_the_users_config() {
        let root = scratch("ssh");
        let ssh = root.join(".ssh");
        std::fs::create_dir_all(&ssh).unwrap();
        std::fs::write(ssh.join("config"), "Host work\r\n    HostName work.example\r\n").unwrap();
        let c = Computer::open(root.join("state"), ssh.clone());

        let line = c.ssh_public_key().unwrap();
        assert!(line.starts_with("ssh-ed25519 "));
        assert_eq!(c.ssh_public_key().unwrap(), line, "the key is made once");

        let proxy = vec!["/Applications/Open Android DeX.app/Contents/MacOS/app".into(), "ssh-proxy".into()];
        let a = c.add_phone(&paired("Galaxy S25 Ultra", 1), "code", &proxy).unwrap();
        let b = c.add_phone(&paired("Galaxy S25 Ultra", 2), "usb", &proxy).unwrap();
        assert_eq!(a.alias, "galaxy-s25-ultra");
        assert_eq!(b.alias, "galaxy-s25-ultra-2", "two phones of the same model get two names");
        // Pairing the first phone again keeps its alias.
        let again = c.add_phone(&paired("Galaxy S25 Ultra", 1), "code", &proxy).unwrap();
        assert_eq!(again.alias, "galaxy-s25-ultra");
        assert_eq!(c.phones().len(), 2);

        let config = std::fs::read_to_string(ssh.join("config")).unwrap();
        assert!(config.starts_with("# Added by Open Android DeX"));
        assert!(config.contains("\r\nInclude openandroiddex.conf\r\n"), "keeps the file's CRLF");
        assert!(config.ends_with("Host work\r\n    HostName work.example\r\n"), "the user's config is untouched");
        // Idempotent: the include is never added twice.
        c.write_ssh_files(&proxy).unwrap();
        let again = std::fs::read_to_string(ssh.join("config")).unwrap();
        assert_eq!(again.matches("Include openandroiddex.conf").count(), 1);

        let conf = std::fs::read_to_string(ssh.join("openandroiddex.conf")).unwrap();
        assert!(conf.contains("Host galaxy-s25-ultra\n"));
        assert!(conf.contains(&format!(
            "ProxyCommand \"/Applications/Open Android DeX.app/Contents/MacOS/app\" ssh-proxy {}",
            a.id
        )));
        let known = std::fs::read_to_string(ssh.join("openandroiddex_known_hosts")).unwrap();
        assert!(known.contains(&format!("{} ssh-ed25519 ", a.host_key_alias())));
        assert!(!known.contains("openandroiddex\n"), "known_hosts carries no key comments");

        assert!(c.remove_phone(&b.id, &proxy).unwrap());
        assert!(!std::fs::read_to_string(ssh.join("openandroiddex.conf")).unwrap().contains("galaxy-s25-ultra-2"));
        let _ = std::fs::remove_dir_all(&root);
    }

    #[cfg(unix)]
    #[test]
    fn the_private_key_is_owner_only() {
        use std::os::unix::fs::PermissionsExt;
        let root = scratch("perm");
        let c = Computer::open(root.join("state"), root.join(".ssh"));
        c.ssh_public_key().unwrap();
        let mode = std::fs::metadata(root.join(".ssh").join(KEY_NAME)).unwrap().permissions().mode();
        assert_eq!(mode & 0o077, 0);
        let _ = std::fs::remove_dir_all(&root);
    }
}
