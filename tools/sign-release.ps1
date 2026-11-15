param([Parameter(Mandatory)][string]$BuildTools,[Parameter(Mandatory)][string]$KeyStore,
 [string]$Alias='ieum-release',[string]$UnsignedApk='app/build/outputs/apk/release/app-release-unsigned.apk',
 [string]$OutputApk='apk/IEUM_IEUM_release.apk')
$ErrorActionPreference='Stop'
if(-not $env:IEUM_KEYSTORE_PASSWORD){throw 'Set IEUM_KEYSTORE_PASSWORD locally; never commit it.'}
if(Test-Path -LiteralPath $OutputApk){throw 'Preserve the supplied APK. Choose a new output path.'}
if(-not (Test-Path -LiteralPath $KeyStore) -or -not (Test-Path -LiteralPath $UnsignedApk)){throw 'Input missing'}
$out=[IO.Path]::GetFullPath($OutputApk);[void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($out))
$aligned=$out+'.aligned.tmp'
if(Test-Path -LiteralPath $aligned){throw 'Preserve existing staging file'}
& "$BuildTools/zipalign.exe" -P 16 4 $UnsignedApk $aligned
if($LASTEXITCODE -ne 0){throw 'Alignment failed'}
& "$BuildTools/apksigner.bat" sign --ks $KeyStore --ks-key-alias $Alias --ks-pass env:IEUM_KEYSTORE_PASSWORD --key-pass env:IEUM_KEYSTORE_PASSWORD --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true --out $out $aligned
if($LASTEXITCODE -ne 0){throw 'Signing failed'}
& "$BuildTools/apksigner.bat" verify --verbose $out
if($LASTEXITCODE -ne 0){throw 'Signature verification failed'}
Remove-Item -LiteralPath $aligned
