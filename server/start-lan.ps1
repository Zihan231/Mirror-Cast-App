# Starts the signaling server in LAN mode (plain ws://, mDNS, browser test page at /test).
$env:MODE = 'lan'
$env:ALLOW_INSECURE_WS = 'true'
$env:ENABLE_TEST_PAGE = 'true'
Set-Location $PSScriptRoot
if (-not (Test-Path dist\index.js)) { npm run build }
$ip = (Get-NetIPAddress -AddressFamily IPv4 | Where-Object { $_.IPAddress -notmatch '^(127|169\.254)\.' -and $_.PrefixOrigin -ne 'WellKnown' } | Select-Object -First 1).IPAddress
Write-Host "Open on the PC:   http://localhost:8080/test"
Write-Host "Phone server:     ${ip}:8080"
node dist\index.js
