//! `dexremote` — one binary, both ends.
//!
//! On the phone, LinuxService runs `serve` (and `init`, for the USB pairing
//! path that needs the identity before the daemon has ever run). On a computer
//! without the desktop app, the rest make it a complete client: `pair` writes
//! the same `~/.ssh` setup the app does, with this binary as the ProxyCommand.
//!
//! No argument parser crate: there are a handful of flags, and the binary is
//! shipped twice inside every APK.

use std::net::{IpAddr, SocketAddr};
use std::path::PathBuf;
use std::process::ExitCode;

use dexremote::computer::{self, Computer};
use dexremote::{pairing, pc, phone};
use iroh::EndpointId;

const USAGE: &str = "\
dexremote — SSH to the Linux on your phone, from anywhere

On a computer:
  dexremote pair <CODE>              pair with the phone showing CODE
                                     (on the phone: Linux → Remote access → Add a computer)
  dexremote pair-adb [--serial S]    pair with the phone adb can see (cable or wireless
                                     debugging) — no code, and remote access is switched on
  dexremote phones                   list paired phones
  dexremote forget <NAME>            forget a phone
  dexremote probe <NAME>             is it reachable, and is the path direct?
  dexremote forward <NAME> [ADDR]    a local port to its SSH server (default 127.0.0.1:2222)
  dexremote proxy <ID>               stdin/stdout to its SSH server (ssh's ProxyCommand)

  After pairing, `ssh <name>` works from any terminal, and so does VS Code's
  Remote-SSH: the pairing adds a Host for the phone to ~/.ssh/config.

On the phone (run by the app):
  dexremote serve --dir DIR --ssh-port PORT --name NAME [--dns IP,…] [--relay URL]
  dexremote init --dir DIR

  --state DIR overrides where a computer keeps its identity and phone list.
";

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let Some(command) = args.first() else {
        eprint!("{USAGE}");
        return ExitCode::from(2);
    };
    let rest = &args[1..];
    let result = match command.as_str() {
        "serve" => serve(rest),
        "init" => init(rest),
        "pair" => pair(rest),
        "pair-adb" => pair_adb(rest),
        "phones" => phones(rest),
        "forget" => forget(rest),
        "probe" => probe(rest),
        "forward" => forward(rest),
        "proxy" => return proxy(rest),
        "version" | "--version" | "-V" => {
            println!("dexremote {}", env!("CARGO_PKG_VERSION"));
            Ok(())
        }
        "help" | "--help" | "-h" => {
            print!("{USAGE}");
            Ok(())
        }
        other => Err(anyhow::anyhow!("unknown command {other:?}\n\n{USAGE}")),
    };
    match result {
        Ok(()) => ExitCode::SUCCESS,
        Err(e) => {
            eprintln!("dexremote: {e:#}");
            ExitCode::FAILURE
        }
    }
}

/// `--name value` out of `args`.
fn flag<'a>(args: &'a [String], name: &str) -> Option<&'a str> {
    args.iter().position(|a| a == name).and_then(|i| args.get(i + 1)).map(String::as_str)
}

/// The positional arguments: everything that is neither a flag nor a flag's value.
fn positional(args: &[String]) -> Vec<&str> {
    let mut out = Vec::new();
    let mut skip = false;
    for a in args {
        if skip {
            skip = false;
        } else if a.starts_with("--") {
            skip = true;
        } else {
            out.push(a.as_str());
        }
    }
    out
}

fn runtime() -> anyhow::Result<tokio::runtime::Runtime> {
    // Two workers: on a phone this is a door, not a server farm.
    Ok(tokio::runtime::Builder::new_multi_thread()
        .worker_threads(2)
        .enable_all()
        .build()?)
}

fn serve(args: &[String]) -> anyhow::Result<()> {
    dexremote::init_logging("warn,dexremote=info");
    let dir = PathBuf::from(flag(args, "--dir").ok_or_else(|| anyhow::anyhow!("--dir is required"))?);
    let ssh_port: u16 = flag(args, "--ssh-port")
        .ok_or_else(|| anyhow::anyhow!("--ssh-port is required"))?
        .parse()?;
    let name = flag(args, "--name").unwrap_or("Phone").to_string();
    let dns: Vec<IpAddr> = flag(args, "--dns")
        .map(|s| s.split(',').filter_map(|ip| ip.trim().parse().ok()).collect())
        .unwrap_or_default();
    let relay = flag(args, "--relay").map(|u| u.parse()).transpose()?;
    runtime()?.block_on(phone::serve(phone::Config { dir, ssh_port, name, dns, relay }))
}

/// Make the identity if it is not there, and print `<endpoint id> <host key>`.
fn init(args: &[String]) -> anyhow::Result<()> {
    let dir = PathBuf::from(flag(args, "--dir").ok_or_else(|| anyhow::anyhow!("--dir is required"))?);
    let identity = phone::ensure_identity(&dir)?;
    println!("{} {}", identity.key.public(), dexremote::b64url(&identity.host_key_line()));
    Ok(())
}

fn computer(args: &[String]) -> anyhow::Result<Computer> {
    match flag(args, "--state") {
        Some(dir) => {
            let ssh = computer::home_dir().ok_or_else(|| anyhow::anyhow!("no home directory"))?.join(".ssh");
            Ok(Computer::open(PathBuf::from(dir), ssh))
        }
        None => Computer::open_default(),
    }
}

/// The ProxyCommand this binary writes into the SSH config: itself, pointed at
/// the same state directory it was run with.
fn own_proxy(args: &[String]) -> anyhow::Result<Vec<String>> {
    let exe = std::env::current_exe()?;
    let mut proxy = vec![exe.to_string_lossy().into_owned(), "proxy".to_string()];
    if let Some(dir) = flag(args, "--state") {
        let dir = std::fs::canonicalize(dir).unwrap_or_else(|_| PathBuf::from(dir));
        proxy.push("--state".to_string());
        proxy.push(dir.to_string_lossy().into_owned());
    }
    Ok(proxy)
}

/// A phone named on the command line: its alias, its name, or its id.
fn find_phone(c: &Computer, wanted: &str) -> anyhow::Result<computer::Phone> {
    let phones = c.phones();
    phones
        .iter()
        .find(|p| p.alias == wanted || p.id == wanted || p.name.eq_ignore_ascii_case(wanted))
        .cloned()
        .ok_or_else(|| anyhow::anyhow!("no paired phone called {wanted:?} — `dexremote phones` lists them"))
}

fn pair(args: &[String]) -> anyhow::Result<()> {
    dexremote::init_logging("error");
    let code = positional(args).first().copied().ok_or_else(|| anyhow::anyhow!("which code? `dexremote pair K7QM-29XV`"))?;
    if pairing::normalize(code).is_none() {
        anyhow::bail!("{}", pc::PairFailure::NotACode);
    }
    let c = computer(args)?;
    let key = c.endpoint_key()?;
    let ssh_key = c.ssh_public_key()?;
    let name = flag(args, "--name").map(str::to_string).unwrap_or_else(computer::computer_name);
    eprintln!("Looking for the phone showing {}…", pairing::display(code));
    let paired = runtime()?.block_on(async {
        let client = pc::Client::bind(key).await?;
        let result = client.pair(code, &name, computer::os_name(), &ssh_key).await;
        client.close().await;
        result.map_err(anyhow::Error::from)
    })?;
    let phone = c.add_phone(&paired, "code", &own_proxy(args)?)?;
    eprintln!("Paired with {}.", phone.name);
    println!("ssh {}", phone.alias);
    Ok(())
}

/// Pairing over adb: the desktop app's "Set up SSH" for a computer without
/// the app. adb has proved which phone this is, so there is no code.
fn pair_adb(args: &[String]) -> anyhow::Result<()> {
    let c = computer(args)?;
    let key = c.endpoint_key()?;
    let ssh_key = c.ssh_public_key()?;
    let request = serde_json::json!({
        "id": key.public().to_string(),
        "name": flag(args, "--name").map(str::to_string).unwrap_or_else(computer::computer_name),
        "os": computer::os_name(),
        "ssh": ssh_key,
        "enable": true,
    });
    let mut adb = std::process::Command::new(flag(args, "--adb").unwrap_or("adb"));
    if let Some(serial) = flag(args, "--serial") {
        adb.args(["-s", serial]);
    }
    adb.arg("shell").args(dexremote::usb::shell_args("pair", &request));
    let out = adb.output().map_err(|e| anyhow::anyhow!("cannot run adb: {e}"))?;
    let printed = String::from_utf8_lossy(&out.stdout).into_owned() + &String::from_utf8_lossy(&out.stderr);
    let answer = dexremote::usb::parse_answer(&printed).map_err(|e| anyhow::anyhow!(e))?;
    let paired = answer.paired().map_err(|e| anyhow::anyhow!(e))?;
    let phone = c.add_phone(&paired, "usb", &own_proxy(args)?)?;
    eprintln!("Paired with {} over adb; remote access is on.", phone.name);
    if let Some(caveat) = answer.caveat() {
        eprintln!("{caveat}");
    }
    println!("ssh {}", phone.alias);
    Ok(())
}

fn phones(args: &[String]) -> anyhow::Result<()> {
    let c = computer(args)?;
    let list = c.phones();
    if list.is_empty() {
        eprintln!("No paired phones. Pair one with `dexremote pair <CODE>`.");
    }
    for p in list {
        println!("{:<24} {:<28} {}", p.alias, p.name, p.id);
    }
    Ok(())
}

fn forget(args: &[String]) -> anyhow::Result<()> {
    let c = computer(args)?;
    let wanted = positional(args).first().copied().ok_or_else(|| anyhow::anyhow!("which phone?"))?;
    let phone = find_phone(&c, wanted)?;
    c.remove_phone(&phone.id, &own_proxy(args)?)?;
    eprintln!("Forgot {}. Remove this computer on the phone too: Linux → Remote access.", phone.name);
    Ok(())
}

fn probe(args: &[String]) -> anyhow::Result<()> {
    dexremote::init_logging("error");
    let c = computer(args)?;
    let wanted = positional(args).first().copied().ok_or_else(|| anyhow::anyhow!("which phone?"))?;
    let phone = find_phone(&c, wanted)?;
    let id: EndpointId = phone.id.parse()?;
    let key = c.endpoint_key()?;
    let probe = runtime()?.block_on(async {
        let client = pc::Client::bind(key).await?;
        let r = client.probe(id).await;
        client.close().await;
        r.map_err(anyhow::Error::from)
    })?;
    println!(
        "{}: reachable, {} ({} ms), SSH server {}, {} session(s) open",
        probe.status.name,
        if probe.direct { "direct" } else { "relayed" },
        probe.rtt_ms,
        probe.status.ssh,
        probe.status.sessions
    );
    Ok(())
}

fn forward(args: &[String]) -> anyhow::Result<()> {
    dexremote::init_logging("warn,dexremote=info");
    let c = computer(args)?;
    let pos = positional(args);
    let wanted = pos.first().copied().ok_or_else(|| anyhow::anyhow!("which phone?"))?;
    let listen: SocketAddr = pos.get(1).copied().unwrap_or("127.0.0.1:2222").parse()?;
    let phone = find_phone(&c, wanted)?;
    let id: EndpointId = phone.id.parse()?;
    let key = c.endpoint_key()?;
    eprintln!("{} is on {listen} — e.g. `ssh -p {} {}@{}`", phone.name, listen.port(), phone.user, listen.ip());
    runtime()?.block_on(async {
        let client = std::sync::Arc::new(pc::Client::bind(key).await?);
        client.forward(id, listen).await
    })
}

/// ssh's ProxyCommand. Exits as soon as the connection does: stdin is read on a
/// thread that a returning `main` would otherwise wait on.
fn proxy(args: &[String]) -> ExitCode {
    pc::ignore_hangup();
    dexremote::init_logging("error");
    let run = || -> anyhow::Result<()> {
        let id: EndpointId = positional(args)
            .first()
            .ok_or_else(|| anyhow::anyhow!("which phone? (an endpoint id)"))?
            .parse()?;
        let c = computer(args)?;
        let key = c.endpoint_key()?;
        runtime()?.block_on(async {
            let client = pc::Client::bind(key).await?;
            let r = client.proxy_stdio(id).await;
            client.close().await;
            r.map_err(anyhow::Error::from)
        })
    };
    let code = match run() {
        Ok(()) => 0,
        Err(e) => {
            eprintln!("dexremote: {e}");
            255
        }
    };
    std::process::exit(code)
}
