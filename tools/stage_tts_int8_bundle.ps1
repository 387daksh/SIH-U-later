param(
    [Parameter(Mandatory = $true)]
    [string]$ZipPath,
    [string]$Output = (Join-Path $PSScriptRoot "..\models\tts"),
    [ValidateSet("en-hi", "bn", "gu", "kn", "ml", "mr", "or", "ta", "te")]
    [string[]]$Languages = @("en-hi")
)

$ErrorActionPreference = "Stop"
$resolvedZip = (Resolve-Path -LiteralPath $ZipPath).Path
$resolvedOutput = [System.IO.Path]::GetFullPath($Output)

if (-not (Test-Path -LiteralPath $resolvedOutput)) {
    New-Item -ItemType Directory -Path $resolvedOutput -Force | Out-Null
}

Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($resolvedZip)
try {
    $wanted = @("tts_int8/shared/hifigan.onnx")
    foreach ($language in ($Languages | Select-Object -Unique)) {
        $wanted += "tts_int8/$language/fastpitch.onnx"
        $wanted += "tts_int8/$language/meta.json"
    }

    foreach ($entryName in $wanted) {
        $entry = $zip.GetEntry($entryName)
        if ($null -eq $entry) {
            throw "Missing ZIP entry: $entryName"
        }

        $relative = $entryName.Substring("tts_int8/".Length).Replace("/", [IO.Path]::DirectorySeparatorChar)
        $destination = Join-Path $resolvedOutput $relative
        $parent = Split-Path -Parent $destination
        New-Item -ItemType Directory -Path $parent -Force | Out-Null

        $input = $entry.Open()
        $outputStream = [IO.File]::Open($destination, [IO.FileMode]::Create, [IO.FileAccess]::Write, [IO.FileShare]::None)
        try {
            $input.CopyTo($outputStream)
        } finally {
            $outputStream.Dispose()
            $input.Dispose()
        }
        Write-Host ("Staged {0} ({1:N1} MiB)" -f $relative, ((Get-Item -LiteralPath $destination).Length / 1MB))
    }
} finally {
    $zip.Dispose()
}

$total = (Get-ChildItem -LiteralPath $resolvedOutput -Recurse -File | Measure-Object -Property Length -Sum).Sum
Write-Host ("TTS staging complete: {0:N1} MiB at {1}" -f ($total / 1MB), $resolvedOutput)
