param([ValidateRange(5,3600)][int]$Seconds=600,[ValidateRange(1024,65535)][int]$Port=8088,[switch]$EnableHvac)
$ErrorActionPreference='Stop'
$client='ieum-review-'+[guid]::NewGuid().ToString('N')
try {
  if($EnableHvac){Invoke-RestMethod "http://127.0.0.1:$Port/vehicle/hvac/mode?mode=subscribe_hvac_base" -Method Put -TimeoutSec 10 | Out-Null}
  for($i=0;$i -lt $Seconds;$i++) {
    Invoke-RestMethod "http://127.0.0.1:$Port/browser/session?client=$client" -Method Post -TimeoutSec 3 | Out-Null
    Start-Sleep -Seconds 1
  }
} finally {
  try { Invoke-RestMethod "http://127.0.0.1:$Port/browser/session?client=$client&closing=1" -Method Post -TimeoutSec 3 | Out-Null } catch {}
}
