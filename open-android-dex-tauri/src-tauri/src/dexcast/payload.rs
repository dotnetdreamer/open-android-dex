//! The pinned driver payload: what gets downloaded, what proves it, and the
//! scripts that install it.
//!
//! Everything here is data and string-building — no Windows APIs — so the
//! whole file compiles and its tests run on every host, which is how this
//! stays honest while being developed on a Mac.
//!
//! **Why pinned and not "latest".** The download happens on users' machines at
//! the moment they opt in. A moving `releases/latest` URL would mean shipping
//! whatever the upstream project publishes next, unreviewed, straight into an
//! elevated driver install. So the release tag, the file names and the SHA-256
//! of every byte we will trust are fixed here, verified before extraction, and
//! bumping them is a deliberate code change with a diff to review. The hashes
//! were computed from the assets themselves and cross-checked against the
//! digests GitHub's release API reports.

/// Upstream release of VirtualDrivers/Virtual-Display-Driver this app knows.
/// MIT-licensed; the driver binary is code-signed via SignPath (the install
/// script trusts that publisher explicitly, see [`install_script`]).
pub const VDD_VERSION: &str = "25.7.23";

/// The 132 KB driver-only archive — not the 71 MB "VDD Control" companion
/// app, whose whole job (a tray UI over the driver's control pipe) this
/// module already does itself.
pub const VDD_ZIP_URL: &str = "https://github.com/VirtualDrivers/Virtual-Display-Driver/releases/download/25.7.23/VirtualDisplayDriver-x86.Driver.Only.zip";
pub const VDD_ZIP_SHA256: &str = "e24210692b442b39af763536330ce78b423f19342b7a7792c26de3944e418b3a";

/// Nefarius' driver installer CLI, the same tool the upstream project's own
/// silent-install script uses. `pnputil` could install the INF but cannot
/// create the root-enumerated device node the virtual adapter hangs off.
pub const NEFCON_ZIP_URL: &str =
    "https://github.com/nefarius/nefcon/releases/download/v1.14.0/nefcon_v1.14.0.zip";
pub const NEFCON_ZIP_SHA256: &str =
    "a15557da24a9efca203158de3b43b0eaf982db231f0194031f1ed428bc13e669";

/// The device node the driver binds to, from MttVDD.inf.
pub const VDD_HARDWARE_ID: &str = "Root\\MttVDD";
/// The Display class GUID, also from the INF.
pub const DISPLAY_CLASS_GUID: &str = "4D36E968-E325-11CE-BFC1-08002BE10318";
/// How the virtual adapter names itself to `EnumDisplayDevices` —
/// `DeviceName="Virtual Display Driver"` in the INF's [Strings].
pub const VDD_ADAPTER_STRING: &str = "Virtual Display Driver";
/// The driver's control pipe. Created with an Everyone-ACL by the driver
/// (SDDL `D:(A;;GA;;;WD)` in Driver.cpp), which is what makes every
/// post-install step of this feature run without elevation.
pub const VDD_PIPE: &str = "\\\\.\\pipe\\MTTVirtualDisplayPipe";

/// Where the driver reads its settings, unless the `VDDPATH` registry value
/// says otherwise. Machine-wide on purpose: the driver runs as a UMDF service
/// with no idea which user is logged in.
pub const VDD_CONFIG_DIR: &str = "C:\\VirtualDisplayDriver";

/// The one mode the virtual monitor offers.
///
/// Deliberately a single 1080p60 entry where the stock file lists five
/// resolutions and six refresh rates: DeX over Miracast tops out at 1080p on
/// anything that is not a Samsung TV, and every extra mode is one more thing
/// Windows may pick while the monitor is being attached.
pub const MONITOR_W: i32 = 1920;
pub const MONITOR_H: i32 = 1080;

/// `vdd_settings.xml` for the driver: one monitor (its floor anyway — the
/// driver clamps 0 back to 1), one mode.
pub fn settings_xml() -> String {
    format!(
        r#"<?xml version='1.0' encoding='utf-8'?>
<vdd_settings>
    <monitors>
        <count>1</count>
    </monitors>
    <gpu>
        <friendlyname>default</friendlyname>
    </gpu>
    <global>
        <g_refresh_rate>60</g_refresh_rate>
    </global>
    <resolutions>
        <resolution>
            <width>{MONITOR_W}</width>
            <height>{MONITOR_H}</height>
            <refresh_rate>60</refresh_rate>
        </resolution>
    </resolutions>
    <options>
        <CustomEdid>false</CustomEdid>
        <PreventSpoof>false</PreventSpoof>
        <EdidCeaOverride>false</EdidCeaOverride>
        <HardwareCursor>true</HardwareCursor>
        <SDR10bit>false</SDR10bit>
        <HDRPlus>false</HDRPlus>
        <logging>false</logging>
        <debuglogging>false</debuglogging>
    </options>
</vdd_settings>
"#
    )
}

/// The name of the firewall rule, per direction, so install and uninstall
/// agree on what to add and remove.
pub fn firewall_rule_name(direction: &str) -> String {
    format!("Open Android DeX - Wireless Display ({direction})")
}

/// The elevated install script.
///
/// Two properties matter more than brevity here, and shape the whole thing:
///
/// * **No path is ever parsed as script.** The script lives in the staging
///   directory, so it takes that directory from `$PSScriptRoot` and builds
///   every path by double-quoted variable expansion. Nothing user-controlled
///   (a profile folder like `C:\Users\O'Brien\…`) is spliced into a literal,
///   so a quote in the path can neither break nor rewrite a command. Only
///   fixed constants (`VDD_CONFIG_DIR`, the hardware id, the class GUID) are
///   interpolated from Rust.
///
/// * **Trust is re-established inside the elevation.** The pins verified at
///   download time do not protect the elevated *read*: a user-level attacker
///   could swap the extracted `nefconc.exe`/INF in the staging directory
///   between the un-elevated stage and this admin script, turning user code
///   into admin code. So this script copies the two archives into a work
///   directory under `%SystemRoot%\Temp` — which a standard user cannot write
///   to — re-verifies their SHA-256 against the pins baked in here (which
///   come from the app binary, not from disk), and installs only from what it
///   extracted there. The user-writable staging copies are never executed.
///
/// It otherwise mirrors upstream's silent-install.ps1: trust the SignPath
/// publisher cert from the signed catalog, seed the config file (never
/// overwriting an existing one — that means VDD was set up by hand), create
/// the device node, install the driver. Plus a firewall allow-rule for the
/// Wireless Display receiver, without which receiving spins forever for
/// standard users on Windows 11 24H2/25H2.
pub fn install_script() -> String {
    format!(
        r#"$ErrorActionPreference = 'Stop'
$staging = $PSScriptRoot
Start-Transcript -Path "$staging\install.log" -Force | Out-Null
$work = Join-Path $env:SystemRoot ("Temp\OADdexcast-" + [guid]::NewGuid().ToString('N'))
try {{
    # An admin-only scratch dir: %SystemRoot%\Temp is not writable by standard
    # users, so nothing swapped into $staging can reach what we execute.
    New-Item -ItemType Directory -Path $work -Force | Out-Null

    function Confirm-Hash($file, $want) {{
        $got = (Get-FileHash -Algorithm SHA256 -Path $file).Hash.ToLower()
        if ($got -ne $want) {{ throw "checksum mismatch for $file (got $got)" }}
    }}

    # Copy first, then verify+extract from the copy — so the bytes checked are
    # the bytes used (no time-of-check/time-of-use gap on the staging file).
    Copy-Item "$staging\vdd-driver.zip" "$work\vdd-driver.zip"
    Copy-Item "$staging\nefcon.zip"     "$work\nefcon.zip"
    Confirm-Hash "$work\vdd-driver.zip" '{vdd_sha}'
    Confirm-Hash "$work\nefcon.zip"     '{nefcon_sha}'
    Expand-Archive "$work\vdd-driver.zip" -DestinationPath $work -Force
    Expand-Archive "$work\nefcon.zip"     -DestinationPath "$work\nefcon" -Force

    $driverDir = "$work\VirtualDisplayDriver"
    $nefcon = "$work\nefcon\x64\nefconc.exe"

    # Trust the driver's SignPath publisher certificate, taken from the signed
    # catalog itself (exactly what upstream's silent-install.ps1 does).
    Write-Host 'Trusting the driver publisher certificate...'
    $catBytes = [System.IO.File]::ReadAllBytes("$driverDir\mttvdd.cat")
    $certs = New-Object System.Security.Cryptography.X509Certificates.X509Certificate2Collection
    $certs.Import($catBytes)
    foreach ($cert in $certs) {{
        $path = Join-Path $work "$($cert.Thumbprint).cer"
        [System.IO.File]::WriteAllBytes($path, $cert.Export([System.Security.Cryptography.X509Certificates.X509ContentType]::Cert))
        Import-Certificate -FilePath $path -CertStoreLocation 'Cert:\LocalMachine\TrustedPublisher' | Out-Null
    }}

    # The driver reads its config from this machine-wide folder. Never
    # overwrite an existing file: it means VDD was already set up by hand.
    if (-not (Test-Path '{config_dir}')) {{
        New-Item -ItemType Directory -Path '{config_dir}' -Force | Out-Null
    }}
    if (-not (Test-Path '{config_dir}\vdd_settings.xml')) {{
        Copy-Item "$staging\vdd_settings.xml" '{config_dir}\vdd_settings.xml'
    }}

    Write-Host 'Creating the virtual display device...'
    & $nefcon --create-device-node --hardware-id '{hwid}' --class-name Display --class-guid '{class_guid}' --no-duplicates
    if ($LASTEXITCODE -ne 0 -and $LASTEXITCODE -ne 3010) {{ throw "nefcon create-device-node failed: $LASTEXITCODE" }}

    Write-Host 'Installing the driver...'
    & $nefcon --install-driver --inf-path "$driverDir\MttVDD.inf"
    if ($LASTEXITCODE -ne 0 -and $LASTEXITCODE -ne 3010) {{ throw "nefcon install-driver failed: $LASTEXITCODE" }}

    # Windows 11 24H2/25H2 ships a firewall state in which the Wireless
    # Display receiver works for administrators and spins forever for
    # standard users; an explicit allow rule for Receiver.exe is the fix.
    Write-Host 'Allowing the Wireless Display receiver through the firewall...'
    $exe = "$env:windir\SystemApps\Microsoft.PPIProjection_cw5n1h2txyewy\Receiver.exe"
    $rules = @(@('Inbound', '{fw_in}'), @('Outbound', '{fw_out}'))
    foreach ($r in $rules) {{
        if (-not (Get-NetFirewallRule -DisplayName $r[1] -ErrorAction SilentlyContinue)) {{
            New-NetFirewallRule -DisplayName $r[1] -Direction $r[0] -Program $exe -Action Allow -Profile Any | Out-Null
        }}
    }}

    Set-Content -Path "$staging\install-ok" -Value '{vdd_version}'
    Write-Host 'Done.'
    exit 0
}} catch {{
    Write-Host "FAILED: $_"
    exit 1
}} finally {{
    Remove-Item -Path $work -Recurse -Force -ErrorAction SilentlyContinue
    Stop-Transcript | Out-Null
}}
"#,
        config_dir = VDD_CONFIG_DIR,
        hwid = VDD_HARDWARE_ID,
        class_guid = DISPLAY_CLASS_GUID,
        vdd_version = VDD_VERSION,
        vdd_sha = VDD_ZIP_SHA256,
        nefcon_sha = NEFCON_ZIP_SHA256,
        fw_in = firewall_rule_name("Inbound"),
        fw_out = firewall_rule_name("Outbound"),
    )
}

/// The Wireless Display receiver's executable, whose firewall rule the
/// install adds — factored out so the standalone firewall script and the full
/// install name the same path.
pub const RECEIVER_EXE_ENV: &str =
    "$env:windir\\SystemApps\\Microsoft.PPIProjection_cw5n1h2txyewy\\Receiver.exe";

/// Just the firewall allow-rules, elevated, for the path where the driver is
/// already installed (adopted) so the full install script never runs. Without
/// this, a standard user on Windows 11 24H2/25H2 who already had VDD gets a
/// receiver that spins forever. Idempotent — it checks before adding.
pub fn firewall_script() -> String {
    format!(
        r#"$ErrorActionPreference = 'Stop'
try {{
    $exe = "{receiver_exe}"
    $rules = @(@('Inbound', '{fw_in}'), @('Outbound', '{fw_out}'))
    foreach ($r in $rules) {{
        if (-not (Get-NetFirewallRule -DisplayName $r[1] -ErrorAction SilentlyContinue)) {{
            New-NetFirewallRule -DisplayName $r[1] -Direction $r[0] -Program $exe -Action Allow -Profile Any | Out-Null
        }}
    }}
    exit 0
}} catch {{
    Write-Host "FAILED: $_"
    exit 1
}}
"#,
        receiver_exe = RECEIVER_EXE_ENV,
        fw_in = firewall_rule_name("Inbound"),
        fw_out = firewall_rule_name("Outbound"),
    )
}

/// The elevated uninstall script. Removes the device node and the driver from
/// the store (one nefcon verb does both) and the firewall rules the install
/// added; leaves the TrustedPublisher cert in place (it may vouch for a VDD
/// the user installed themselves, and a trusted *publisher* entry is inert
/// without a signed binary to match). nefcon is re-verified and re-extracted
/// into an admin-only work dir for the same reason install does.
pub fn uninstall_script() -> String {
    format!(
        r#"$ErrorActionPreference = 'Stop'
$staging = $PSScriptRoot
Start-Transcript -Path "$staging\uninstall.log" -Force | Out-Null
$work = Join-Path $env:SystemRoot ("Temp\OADdexcast-" + [guid]::NewGuid().ToString('N'))
try {{
    New-Item -ItemType Directory -Path $work -Force | Out-Null
    Copy-Item "$staging\nefcon.zip" "$work\nefcon.zip"
    $got = (Get-FileHash -Algorithm SHA256 -Path "$work\nefcon.zip").Hash.ToLower()
    if ($got -ne '{nefcon_sha}') {{ throw "checksum mismatch for nefcon.zip (got $got)" }}
    Expand-Archive "$work\nefcon.zip" -DestinationPath "$work\nefcon" -Force
    $nefcon = "$work\nefcon\x64\nefconc.exe"

    & $nefcon --remove-device-node --hardware-id '{hwid}' --class-guid '{class_guid}'
    if ($LASTEXITCODE -ne 0 -and $LASTEXITCODE -ne 3010) {{ throw "nefcon remove-device-node failed: $LASTEXITCODE" }}

    foreach ($name in @('{fw_in}', '{fw_out}')) {{
        Get-NetFirewallRule -DisplayName $name -ErrorAction SilentlyContinue | Remove-NetFirewallRule -ErrorAction SilentlyContinue
    }}
    Remove-Item -Path "$staging\install-ok" -ErrorAction SilentlyContinue
    exit 0
}} catch {{
    Write-Host "FAILED: $_"
    exit 1
}} finally {{
    Remove-Item -Path $work -Recurse -Force -ErrorAction SilentlyContinue
    Stop-Transcript | Out-Null
}}
"#,
        hwid = VDD_HARDWARE_ID,
        class_guid = DISPLAY_CLASS_GUID,
        nefcon_sha = NEFCON_ZIP_SHA256,
        fw_in = firewall_rule_name("Inbound"),
        fw_out = firewall_rule_name("Outbound"),
    )
}

/// Pull a SHA-256 out of `certutil -hashfile` output.
///
/// certutil is used because it is on every Windows since Vista and needs no
/// PowerShell startup, but its output is localised and has changed shape over
/// the years (hex pairs separated by spaces on older builds, one solid run on
/// newer). So: ignore the prose, take the first line that is hex once its
/// spaces are removed and is exactly 64 digits long.
pub fn parse_certutil_sha256(output: &str) -> Option<String> {
    for line in output.lines() {
        let compact: String = line
            .chars()
            .filter(|c| !c.is_whitespace())
            .collect::<String>()
            .to_ascii_lowercase();
        if compact.len() == 64 && compact.chars().all(|c| c.is_ascii_hexdigit()) {
            return Some(compact);
        }
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_pins_are_exact() {
        // A near-miss URL 404s at the user's machine, not in CI — and a hash
        // of the wrong case would refuse a perfectly good download.
        assert!(VDD_ZIP_URL.contains(VDD_VERSION));
        assert!(VDD_ZIP_URL.ends_with("VirtualDisplayDriver-x86.Driver.Only.zip"));
        assert_eq!(VDD_ZIP_SHA256.len(), 64);
        assert_eq!(NEFCON_ZIP_SHA256.len(), 64);
        assert!(VDD_ZIP_SHA256.chars().all(|c| c.is_ascii_hexdigit()));
        assert_eq!(VDD_ZIP_SHA256, VDD_ZIP_SHA256.to_ascii_lowercase());
        assert_eq!(NEFCON_ZIP_SHA256, NEFCON_ZIP_SHA256.to_ascii_lowercase());
    }

    #[test]
    fn settings_xml_matches_the_drivers_schema() {
        // Element names come from Driver.cpp's XML reader: <count> inside
        // <monitors>, <width>/<height>/<refresh_rate> per <resolution>.
        let xml = settings_xml();
        assert!(xml.contains("<count>1</count>"));
        assert!(xml.contains("<width>1920</width>"));
        assert!(xml.contains("<height>1080</height>"));
        assert!(xml.contains("<refresh_rate>60</refresh_rate>"));
        // The driver clamps 0 to 1 (Driver.cpp), so asking for 0 would be a
        // silent lie in a config file.
        assert!(!xml.contains("<count>0</count>"));
    }

    #[test]
    fn install_script_verifies_before_it_installs() {
        let s = install_script();
        assert!(s.contains("MttVDD.inf"));
        assert!(s.contains("--no-duplicates"));
        assert!(s.contains("Root\\MttVDD"));
        assert!(s.contains("TrustedPublisher"));
        assert!(s.contains("Receiver.exe"));
        // 3010 is ERROR_SUCCESS_REBOOT_REQUIRED, which is a success.
        assert!(s.contains("3010"));
        // The user's own settings survive a reinstall.
        assert!(s.contains("-not (Test-Path 'C:\\VirtualDisplayDriver\\vdd_settings.xml')"));
        // Trust is anchored inside the elevation: both pins are re-checked
        // against the admin-only copy before anything runs.
        assert!(s.contains(VDD_ZIP_SHA256));
        assert!(s.contains(NEFCON_ZIP_SHA256));
        assert!(s.contains("Get-FileHash"));
        assert!(s.contains("$env:SystemRoot"));
        // No user path is spliced into a literal — the dir comes from
        // $PSScriptRoot, so an apostrophe in a profile path cannot break it.
        assert!(s.contains("$staging = $PSScriptRoot"));
    }

    #[test]
    fn uninstall_script_names_the_same_device_and_undoes_the_firewall() {
        let s = uninstall_script();
        assert!(s.contains("--remove-device-node"));
        assert!(s.contains(VDD_HARDWARE_ID));
        assert!(s.contains(DISPLAY_CLASS_GUID));
        assert!(s.contains("Remove-NetFirewallRule"));
        assert!(s.contains(&firewall_rule_name("Inbound")));
        assert!(s.contains("$staging = $PSScriptRoot"));
    }

    #[test]
    fn certutil_output_parses_in_both_vintages() {
        // Modern build: one solid hex run.
        let modern = "SHA256 hash of file.zip:\ne24210692b442b39af763536330ce78b423f19342b7a7792c26de3944e418b3a\nCertUtil: -hashfile command completed successfully.";
        assert_eq!(
            parse_certutil_sha256(modern).as_deref(),
            Some("e24210692b442b39af763536330ce78b423f19342b7a7792c26de3944e418b3a")
        );
        // Old build: spaced pairs, uppercase prose around it.
        let old = "SHA256-Hash von file.zip:\ne2 42 10 69 2b 44 2b 39 af 76 35 36 33 0c e7 8b 42 3f 19 34 2b 7a 77 92 c2 6d e3 94 4e 41 8b 3a\nCertUtil: -hashfile erfolgreich.";
        assert_eq!(
            parse_certutil_sha256(old).as_deref(),
            Some("e24210692b442b39af763536330ce78b423f19342b7a7792c26de3944e418b3a")
        );
        // Prose lines never hash-shaped.
        assert_eq!(parse_certutil_sha256("no hash here"), None);
    }
}
