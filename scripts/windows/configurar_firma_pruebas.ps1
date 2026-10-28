param(
    [Parameter(Mandatory = $true)]
    [string]$ClaveDelNotebook
)

$ErrorActionPreference = 'Stop'
# Huella de MusicBox pruebas instalada: impide adoptar por error otra clave.
$huellaEsperada = 'F6016B5EA4F16F199746F8AC3D5740B5170D76FCB7FF7988229445698B819456'
$origen = (Resolve-Path -LiteralPath $ClaveDelNotebook).Path
$keytool = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/keytool.exe' } else { 'keytool' }
$ErrorActionPreference = 'Continue' # keytool puede emitir avisos JKS por stderr con salida 0.
$salida = & $keytool -list -v -keystore $origen -alias androiddebugkey -storepass android 2>&1
$codigoKeytool = $LASTEXITCODE
$ErrorActionPreference = 'Stop'
if ($codigoKeytool -ne 0) { throw 'No se pudo leer la clave debug. No se ha cambiado la configuración.' }
$huella = [regex]::Match(($salida -join "`n"), 'SHA256:\s*([0-9A-Fa-f:]+)').Groups[1].Value.Replace(':', '').ToUpperInvariant()
if ($huella -ne $huellaEsperada) {
    throw 'Esta clave no coincide con MusicBox pruebas instalada. Se conserva la clave anterior.'
}
$raizGradle = if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else { Join-Path $env:USERPROFILE '.gradle' }
$carpeta = Join-Path $raizGradle 'musicbox'
$destino = Join-Path $carpeta 'preview.keystore'
if (Test-Path -LiteralPath $destino) {
    if ((Get-FileHash -LiteralPath $destino).Hash -ne (Get-FileHash -LiteralPath $origen).Hash) {
        throw "Ya existe otra clave en $destino. No se sobrescribió."
    }
} else {
    New-Item -ItemType Directory -Path $carpeta -Force | Out-Null
    Copy-Item -LiteralPath $origen -Destination $destino
}
Write-Output "Firma compartida configurada en $destino"
Write-Output 'Ejecuta este script con el mismo archivo en ambos equipos. No lo subas a Git.'
