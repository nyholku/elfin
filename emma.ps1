# Run a finished ElFin image on Emma 02, the image named on the command line.
# The Windows version of emma.sh; emma.cmd runs this.
#
#   emma.cmd IMAGE.hex [MACHINE] [-Clock MHz] [-Emma PATH] [-EmmaData DIR] [-DryRun]
#
#   IMAGE.hex  an image make-image.sh built, e.g. images\chess.hex
#   MACHINE    32k, elf or mini; left out, it is the smallest that holds the
#              image - the 32K Elf below 8000, the 1802/Mini below F000, the
#              64K Elf otherwise
#   -Clock     run at that clock instead of the XML's.  At 1.76 MHz the editor
#              takes many seconds to repaint; 100 makes it quick.  Do not go
#              far above 100: at 500 Emma stops executing and looks hung.
#   -Emma      the Emma 02 program, if it is not where this looks
#   -EmmaData  Emma's data directory, if this cannot work it out
#   -DryRun    say what would be copied and run, and do neither
#
# The machine's XML and the image are copied into Emma's data directory every
# time - the image under the name the XML loads RAM from - and Emma is
# started on that machine.  See emma.sh for why.
#
# Where Emma keeps its data on Windows is %APPDATA%\Emma 02, unless it has
# been moved in Emma's settings, which is written as DataDir= in
# %APPDATA%\Emma 02 Config\emma_02.ini - so that is read first.
param(
    [Parameter(Mandatory = $true, Position = 0)] [string] $Image,
    [Parameter(Position = 1)] [string] $Machine = "",
    [string] $Clock = "",
    [string] $Emma = "",
    [string] $EmmaData = "",
    [switch] $DryRun
)
$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path

if (-not (Test-Path -LiteralPath $Image -PathType Leaf)) { Write-Error "no such image: $Image" }
$Image = (Resolve-Path -LiteralPath $Image).Path

if ($Machine -eq "") {
    # the highest address the hex writes: its data records' last bytes
    $top = 0
    foreach ($l in Get-Content -LiteralPath $Image) {
        if ($l.Length -ge 9 -and $l.Substring(7, 2) -eq "00") {
            $end = [Convert]::ToInt32($l.Substring(3, 4), 16) + [Convert]::ToInt32($l.Substring(1, 2), 16) - 1
            if ($end -gt $top) { $top = $end }
        }
    }
    if ($top -lt 0x8000) { $Machine = "32k" }
    elseif ($top -lt 0xF000) { $Machine = "mini" }
    else { $Machine = "elf" }
}
switch ($Machine) {
    "32k"  { $xml = "elfin-32k.xml" }
    "elf"  { $xml = "elfin.xml" }
    "mini" { $xml = "elfin-mini.xml" }
    default { Write-Error "unknown machine ${Machine}: 32k, elf or mini" }
}

if ($EmmaData -eq "") {
    $EmmaData = Join-Path $env:APPDATA "Emma 02"
    $ini = Join-Path $env:APPDATA "Emma 02 Config\emma_02.ini"
    if (Test-Path -LiteralPath $ini) {
        $lines = Get-Content -LiteralPath $ini
        $dir = ($lines | Where-Object { $_ -like "DataDir=*" } | Select-Object -First 1)
        $rel = ($lines | Where-Object { $_ -like "DataDirRelative=*" } | Select-Object -First 1)
        if ($rel -eq "DataDirRelative=1") { Write-Error "Emma keeps its data relative to where it is installed; give -EmmaData" }
        if ($dir) {
            # wxWidgets writes a backslash in a value doubled
            $EmmaData = $dir.Substring(8).Replace("\\", "\")
        }
    }
}
$EmmaData = $EmmaData.TrimEnd([char[]]@('\', '/'))
if (-not (Test-Path -LiteralPath (Join-Path $EmmaData "Xml\Elf"))) {
    Write-Error "no $EmmaData\Xml\Elf - start Emma 02 once so it installs its data, or give -EmmaData"
}

if ($Emma -eq "") {
    $candidates = @()
    foreach ($root in @($env:ProgramFiles, ${env:ProgramFiles(x86)}, $env:LOCALAPPDATA)) {
        if ($root) {
            $candidates += Join-Path $root "Emma 02\Emma 02.exe"
            $candidates += Join-Path $root "Emma 02\emma_02.exe"
            $candidates += Join-Path $root "Programs\Emma 02\Emma 02.exe"
        }
    }
    $onPath = Get-Command "emma_02.exe", "Emma 02.exe" -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($onPath) { $candidates = @($onPath.Source) + $candidates }
    $Emma = $candidates | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
}
if (-not $Emma -or -not (Test-Path -LiteralPath $Emma)) { Write-Error "Emma 02 not found; give -Emma with the program" }

# The name the machine's RAM is loaded from: the first <filename> in its XML.
$xmlSource = Join-Path $here "asm\emma\$xml"
$text = Get-Content -LiteralPath $xmlSource -Raw
$m = [regex]::Match($text, '<filename[^>]*>([^<]*)</filename>')
if (-not $m.Success) { Write-Error "$xml names no file for its RAM" }
$ramFile = $m.Groups[1].Value

$target = Join-Path $EmmaData "Elf\$ramFile"
Write-Output "machine  $Machine ($xml)$(if ($Clock) { ", $Clock MHz" })"
Write-Output "image    $Image -> $target"
Write-Output "emma     $Emma"
if ($DryRun) { exit 0 }

if ($Clock) { $text = [regex]::Replace($text, '<clock>[0-9.]*</clock>', "<clock>$Clock</clock>") }
[System.IO.File]::WriteAllText((Join-Path $EmmaData "Xml\Elf\$xml"), $text)
Copy-Item -LiteralPath $Image -Destination $target -Force
# The Mini's ROM is stood in for by 4K of FF: what matters is that it is ROM.
if ($Machine -eq "mini") {
    $rom = New-Object byte[] 4096
    for ($i = 0; $i -lt $rom.Length; $i++) { $rom[$i] = 0xFF }
    [System.IO.File]::WriteAllBytes((Join-Path $EmmaData "Elf\elfin-minirom.bin"), $rom)
}

& $Emma "-c=elf" "-x=$xml" "-u" "-ns"
