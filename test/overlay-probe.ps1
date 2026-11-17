# The desktop's taskbar under a window that hides overlays (issue #11): one probe of a
# phone with a desktop session running.
#
#   .\test\overlay-probe.ps1 -Serial <serial> -Label <name> [-Adb <adb.exe>] [-Out <folder>]
#
# Saves the raw window dump, the launcher's log and a screenshot of each virtual display
# that `dumpsys SurfaceFlinger --display-id` lists (Android 12 and older list none: capture
# the desktop's window on the computer there) as <Label>-*, then lists, top of the stack first:
#   - every window that asks Android to hide overlays (a permission prompt, an app lock's
#     PIN screen): Android hides every ordinary app overlay, on every display, while one shows;
#   - the launcher's overlay windows. The taskbar is the full-width one 52dp tall
#     ("fillx52" at 160 dpi); the 1x1 accessibility overlay is CaptionService's anchor;
#     "Touchpad pointer" is the touchpad's arrow. A window Android keeps off the screen gets
#     a HIDDEN line: mIsForceHiddenNonSystemOverlayWindow=true (Android 17) or
#     mForceHideNonSystemOverlayWindow=true (16 and older) is the hiding rule.
#
# The hide flag is bit 0x80000 of a window's private flags (addSystemFlags puts the
# system flags there). Some builds print those flags by name and some as hex: a Galaxy
# S25 Ultra (One UI, Android 17) prints "pfl=40080800" for a permission prompt. Both
# are read. Give it the running app's adb (-Adb), so its adb server is not restarted.

param([Parameter(Mandatory)][string]$Serial, [Parameter(Mandatory)][string]$Label,
      [string]$Adb = 'adb', [string]$Out = '.\overlay-probe')
New-Item -ItemType Directory -Force $Out | Out-Null
$pkg = 'com.ccrstech.openandroiddex.launcher'
$raw = & $Adb -s $Serial shell dumpsys window windows
$raw | Set-Content -Encoding UTF8 (Join-Path $Out "$Label-windows.txt")
& $Adb -s $Serial logcat -d -s 'OpenDeX:*' 'DexCaption:*' | Set-Content -Encoding UTF8 (Join-Path $Out "$Label-logcat.txt")
foreach ($l in (& $Adb -s $Serial shell dumpsys SurfaceFlinger --display-id)) {
  if ($l -match '^Display (\d+) \(Virtual display\)') {
    $png = Join-Path $Out "$Label-display-$($Matches[1]).png"
    Start-Process -FilePath $Adb -ArgumentList '-s',$Serial,'exec-out','screencap','-d',$Matches[1],'-p' -RedirectStandardOutput $png -NoNewWindow -Wait } }

# Whether a window's dump block asks to hide overlays: the flag by name, or its bit in pfl's hex.
function Test-HidesOverlays([string]$block) {
  if ($block -match 'HIDE_NON_SYSTEM_OVERLAY_WINDOWS') { return $true }
  if ($block -match 'pfl=(?:0x)?([0-9a-fA-F]+)(?=\s|\}|$)') {
    return ([Convert]::ToInt64($Matches[1], 16) -band 0x80000) -ne 0
  }
  return $false
}

"== $Label  $(Get-Date -Format 'HH:mm:ss')  (top of the stack first)"
foreach ($b in (($raw -join "`n") -split '(?m)^  Window #')) {
  $title = if ($b -match '^\d+ Window\{\w+ u0 ([^}]+)\}') { $Matches[1] } else { '?' }
  $disp = if ($b -match 'mDisplayId=(\d+)') { $Matches[1] } else { '?' }
  $on = if ($b -match 'isOnScreen=(\w+)') { $Matches[1] } else { '?' }
  if (Test-HidesOverlays $b) {
    $pfl = if ($b -match 'pfl=(\S+)') { " pfl=$($Matches[1].TrimEnd('}'))" } else { '' }
    "  ASKS TO HIDE OVERLAYS: $title (display $disp, onScreen=$on$pfl)" }
  if ($b -notmatch [regex]::Escape($pkg)) { continue }
  $ty = if ($b -match ' ty=(\w+)') { $Matches[1] } else { '?' }
  if ($ty -notmatch 'OVERLAY') { continue }
  $size = if ($b -match 'mAttrs=\{\([^)]*\)\(([^)]*)\)') { $Matches[1] } else { '?' }
  $hid = if ($b -match '(mPolicyVisibility=[^\n]+)') { "`n      HIDDEN: " + $Matches[1].Trim() } else { '' }
  "  display $disp  $ty  size=$size  onScreen=$on  $title$hid" }
