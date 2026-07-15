param(
    [string]$InputDirectory = (Join-Path $PSScriptRoot '..\unicode-data\15.0.0'),
    [string]$OutputFile = (Join-Path $PSScriptRoot '..\src\main\kotlin\dev\riddle\magicpaper\paper\Unicode15GraphemeTables.kt')
)

$ErrorActionPreference = 'Stop'
$propertyNames = @('OTHER','CR','LF','CONTROL','EXTEND','ZWJ','REGIONAL_INDICATOR','PREPEND','SPACING_MARK','L','V','T','LV','LVT')

function Read-Ranges([string]$Path, [scriptblock]$Include) {
    $ranges = [System.Collections.Generic.List[object]]::new()
    foreach ($raw in [System.IO.File]::ReadLines($Path)) {
        $body = $raw.Split('#')[0].Trim()
        if (-not $body) { continue }
        $parts = $body.Split(';')
        $property = $parts[1].Trim()
        if (-not (& $Include $property)) { continue }
        $ends = $parts[0].Trim().Split('..')
        $ranges.Add([pscustomobject]@{
            Start = [Convert]::ToInt32($ends[0], 16)
            End = [Convert]::ToInt32($ends[-1], 16)
            Property = $property
        })
    }
    return $ranges
}

$breaks = @(Read-Ranges (Join-Path $InputDirectory 'GraphemeBreakProperty.txt') { param($name) $true }) |
    ForEach-Object {
        if ($_.Property -eq 'SpacingMark') { $_.Property = 'SPACING_MARK' }
        $_
    } |
    Where-Object { $propertyNames -contains $_.Property.ToUpperInvariant() } |
    Sort-Object Start
$emoji = @(Read-Ranges (Join-Path $InputDirectory 'emoji-data.txt') { param($name) $name -eq 'Extended_Pictographic' }) | Sort-Object Start

function Format-BreakRange($range) {
    $name = $range.Property.ToUpperInvariant()
    return ('        0x{0:X}, 0x{1:X}, GraphemeProperty.{2}.ordinal,' -f $range.Start, $range.End, $name)
}
function Format-PlainRange($range) {
    return ('        0x{0:X}, 0x{1:X},' -f $range.Start, $range.End)
}

$content = @"
// Generated from unmodified Unicode 15.0.0 data by tools/generate-unicode15-grapheme-tables.ps1.
// Do not edit by hand; source URLs and SHA-256 values are recorded in unicode-data/15.0.0/MANIFEST.sha256.
package dev.riddle.magicpaper.paper

internal object Unicode15GraphemeTables {
    val breakRanges = intArrayOf(
$([string]::Join("`n", ($breaks | ForEach-Object { Format-BreakRange $_ })))
    )

    val extendedPictographicRanges = intArrayOf(
$([string]::Join("`n", ($emoji | ForEach-Object { Format-PlainRange $_ })))
    )
}
"@

$parent = [System.IO.Path]::GetDirectoryName($OutputFile)
[System.IO.Directory]::CreateDirectory($parent) | Out-Null
[System.IO.File]::WriteAllText($OutputFile, $content, [System.Text.UTF8Encoding]::new($false))
