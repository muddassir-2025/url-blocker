$env:Path = [Environment]::GetEnvironmentVariable('Path', 'Machine') + ';' + [Environment]::GetEnvironmentVariable('Path', 'User')

Write-Output '=== device ==='
adb devices -l

Write-Output '=== existing owners ==='
adb shell dpm list-owners 2>&1

Write-Output '=== app installed? ==='
$pkg = adb shell pm list packages 2>&1 | Select-String 'url_blocker'
if ($pkg) { Write-Output $pkg } else { Write-Output 'NOT INSTALLED' }

Write-Output '=== accounts on device ==='
$accounts = adb shell dumpsys account 2>&1
($accounts | Select-String 'name=' | Select-Object -First 8)
Write-Output '--- end accounts ---'
