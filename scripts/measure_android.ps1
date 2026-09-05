param(
    [string]$Adb = ".\.android-sdk\platform-tools\adb.exe",
    [string]$Package = "org.sih.offlinespeech",
    [string]$Apk = ".\android\app\build\outputs\apk\debug\app-debug.apk"
)

$ErrorActionPreference = "Stop"
if (-not (Test-Path $Adb)) { throw "adb not found: $Adb" }
if ((& $Adb get-state 2>$null) -ne "device") { throw "No authorized Android device connected" }

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$output = Join-Path "artifacts\android_device" $stamp
New-Item -ItemType Directory -Force $output | Out-Null

$meminfo = (& $Adb shell dumpsys meminfo $Package | Out-String)
$logcat = (& $Adb logcat -d -s "SIH_METRIC:I" "*:S" | Out-String)
$packageInfo = (& $Adb shell dumpsys package $Package | Out-String)
$meminfo | Set-Content -Encoding UTF8 (Join-Path $output "meminfo.txt")
$logcat | Set-Content -Encoding UTF8 (Join-Path $output "inference.log")

$total = [regex]::Match($meminfo, "TOTAL PSS:\s+(\d+)\s+TOTAL RSS:\s+(\d+)\s+TOTAL SWAP PSS:\s+(\d+)")
if (-not $total.Success) { throw "No running $Package process found; open the app and retry" }
$runs = foreach ($line in $logcat -split "`r?`n") {
    if ($line -notmatch "SIH_METRIC") { continue }
    $row = [ordered]@{}
    foreach ($match in [regex]::Matches($line, "(\w+)=([^\s]+)")) {
        $row[$match.Groups[1].Value] = $match.Groups[2].Value
    }
    [pscustomobject]$row
}

$manifest = "artifacts\android_models\manifest.json"
$report = [ordered]@{
    captured_at = (Get-Date).ToString("o")
    device = (& $Adb shell getprop ro.product.model).Trim()
    android = (& $Adb shell getprop ro.build.version.release).Trim()
    package = $Package
    version_name = [regex]::Match($packageInfo, "versionName=([^\s]+)").Groups[1].Value
    apk_bytes = if (Test-Path $Apk) { (Get-Item $Apk).Length } else { $null }
    apk_sha256 = if (Test-Path $Apk) { (Get-FileHash -Algorithm SHA256 $Apk).Hash } else { $null }
    model_bytes = if (Test-Path $manifest) { (Get-Content -Raw $manifest | ConvertFrom-Json).bytes } else { $null }
    total_pss_kb = if ($total.Success) { [long]$total.Groups[1].Value } else { $null }
    total_rss_kb = if ($total.Success) { [long]$total.Groups[2].Value } else { $null }
    swap_pss_kb = if ($total.Success) { [long]$total.Groups[3].Value } else { $null }
    inference_runs = @($runs)
}
$report | ConvertTo-Json -Depth 5 | Set-Content -Encoding UTF8 (Join-Path $output "report.json")
$report | ConvertTo-Json -Depth 5
Write-Host "Saved Android evidence to $output"
