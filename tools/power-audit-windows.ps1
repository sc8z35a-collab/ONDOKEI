param(
    [ValidateSet("Normal", "Turbo")]
    [string]$Mode = "Normal",
    [ValidateRange(2, 60)]
    [int]$DurationMinutes = 10,
    [ValidateRange(5, 60)]
    [int]$SampleSeconds = 10
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$Package = "jp.rstlab.batteryrelay"
$Adb = (Get-Command adb -ErrorAction Stop).Source
if ((& $Adb get-state 2>$null) -ne "device") {
    throw "ADB端末が1台だけ接続・承認された状態にしてください。"
}
if (-not ((& $Adb shell pm path $Package 2>$null) -match "package:")) {
    throw "Battery Relay ($Package) が端末にインストールされていません。"
}

$Stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$Output = Join-Path $PSScriptRoot "power-audit-$($Mode.ToLowerInvariant())-$Stamp"
New-Item -ItemType Directory -Path $Output | Out-Null

@"
Battery Relay power/thermal audit
Mode: $Mode
Duration: $DurationMinutes minutes
Interval: $SampleSeconds seconds
Before continuing, open Battery Relay and select $Mode mode.
This script resets only Android batterystats diagnostics; it does not erase app data.
"@ | Set-Content -Encoding UTF8 (Join-Path $Output "README.txt")

& $Adb shell dumpsys batterystats --reset | Out-File -Encoding UTF8 (Join-Path $Output "batterystats-reset.txt")
& $Adb shell am start -n "$Package/.MainActivity" | Out-File -Encoding UTF8 (Join-Path $Output "launch.txt")
Read-Host "画面で $Mode を選択したら Enter"

$Rows = [System.Collections.Generic.List[object]]::new()
$Iterations = [Math]::Ceiling(($DurationMinutes * 60) / $SampleSeconds)
for ($Index = 0; $Index -lt $Iterations; $Index++) {
    $Now = Get-Date
    $Battery = (& $Adb shell dumpsys battery) -join "`n"
    $Cpu = (& $Adb shell dumpsys cpuinfo $Package) -join "`n"
    $Thermal = (& $Adb shell dumpsys thermalservice) -join "`n"

    $LevelMatch = [regex]::Match($Battery, '(?m)^\s*level:\s*(\d+)')
    $TempMatch = [regex]::Match($Battery, '(?m)^\s*temperature:\s*(-?\d+)')
    $CpuMatch = [regex]::Match($Cpu, '([0-9.]+)%\s+\d+/' + [regex]::Escape($Package))
    $ThermalMatch = [regex]::Match($Thermal, '(?m)^\s*(?:mStatus|Status):\s*(\d+)')

    $Rows.Add([pscustomobject]@{
        timestamp = $Now.ToString("o")
        battery_percent = if ($LevelMatch.Success) { [int]$LevelMatch.Groups[1].Value } else { $null }
        battery_temperature_c = if ($TempMatch.Success) { [int]$TempMatch.Groups[1].Value / 10.0 } else { $null }
        process_cpu_percent = if ($CpuMatch.Success) { [double]$CpuMatch.Groups[1].Value } else { $null }
        thermal_status = if ($ThermalMatch.Success) { [int]$ThermalMatch.Groups[1].Value } else { $null }
    })

    $RawName = "raw-{0:D4}.txt" -f $Index
    "BATTERY`n$Battery`n`nCPU`n$Cpu`n`nTHERMAL`n$Thermal" |
        Set-Content -Encoding UTF8 (Join-Path $Output $RawName)
    Write-Progress -Activity "Battery Relay $Mode audit" `
        -Status "$($Index + 1) / $Iterations" `
        -PercentComplete ((($Index + 1) / $Iterations) * 100)
    if ($Index + 1 -lt $Iterations) { Start-Sleep -Seconds $SampleSeconds }
}

$Rows | Export-Csv -NoTypeInformation -Encoding UTF8 (Join-Path $Output "samples.csv")
& $Adb shell dumpsys batterystats $Package | Out-File -Encoding UTF8 (Join-Path $Output "batterystats-final.txt")
& $Adb shell dumpsys package $Package | Out-File -Encoding UTF8 (Join-Path $Output "package-final.txt")
& $Adb shell dumpsys activity services "$Package/.service.MonitorService" |
    Out-File -Encoding UTF8 (Join-Path $Output "service-final.txt")

$ValidCpu = @($Rows | Where-Object { $null -ne $_.process_cpu_percent } | ForEach-Object { $_.process_cpu_percent })
$ValidTemp = @($Rows | Where-Object { $null -ne $_.battery_temperature_c } | ForEach-Object { $_.battery_temperature_c })
$Summary = [pscustomobject]@{
    mode = $Mode
    samples = $Rows.Count
    average_process_cpu_percent = if ($ValidCpu.Count) { ($ValidCpu | Measure-Object -Average).Average } else { $null }
    peak_process_cpu_percent = if ($ValidCpu.Count) { ($ValidCpu | Measure-Object -Maximum).Maximum } else { $null }
    start_battery_temperature_c = if ($ValidTemp.Count) { $ValidTemp[0] } else { $null }
    end_battery_temperature_c = if ($ValidTemp.Count) { $ValidTemp[-1] } else { $null }
    peak_battery_temperature_c = if ($ValidTemp.Count) { ($ValidTemp | Measure-Object -Maximum).Maximum } else { $null }
}
$Summary | Format-List | Tee-Object -FilePath (Join-Path $Output "summary.txt")
Write-Host "監査結果: $Output"
