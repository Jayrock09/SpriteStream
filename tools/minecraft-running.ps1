# Exit 0 when a Minecraft client is running (Prism launch or a dev client), 1 otherwise.
$game = Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" |
	Where-Object { $_.CommandLine -match 'PrismLauncher|KnotClient|devlaunchinjector|net\.minecraft\.client\.main\.Main' }
if ($game) { $game | ForEach-Object { Write-Output "running: pid $($_.ProcessId)" }; exit 0 } else { exit 1 }
