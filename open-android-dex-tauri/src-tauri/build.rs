fn main() {
    // Windows: the app's manifest, Common Controls 6, is made by the linker for
    // every binary this package links — not only the app's. Tauri's dialogs
    // import `TaskDialogIndirect`, which only that version of comctl32 has, and
    // tauri-build puts its manifest in the app's executable only, so the lib's
    // own test binary did not start (STATUS_ENTRYPOINT_NOT_FOUND) unless a
    // manifest file was put beside it by hand. tauri-build is told to leave its
    // copy out, so the app still has one manifest, the same one.
    let target = |key: &str| std::env::var(key).unwrap_or_default();
    if target("CARGO_CFG_TARGET_OS") == "windows" && target("CARGO_CFG_TARGET_ENV") == "msvc" {
        println!("cargo:rustc-link-arg=/MANIFEST:EMBED");
        println!("cargo:rustc-link-arg=/MANIFESTUAC:NO");
        println!(
            "cargo:rustc-link-arg=/MANIFESTDEPENDENCY:type='win32' name='Microsoft.Windows.Common-Controls' \
             version='6.0.0.0' processorArchitecture='*' publicKeyToken='6595b64144ccf1df' language='*'"
        );
        let windows = tauri_build::WindowsAttributes::new_without_app_manifest();
        if let Err(error) = tauri_build::try_build(tauri_build::Attributes::new().windows_attributes(windows)) {
            println!("{error:#}");
            std::process::exit(1);
        }
    } else {
        tauri_build::build()
    }
}
