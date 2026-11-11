param([string]$Python = 'python', [string]$Iscc = '')
$ErrorActionPreference = 'Stop'
Push-Location (Split-Path $PSScriptRoot -Parent)
try {
    & $Python scripts/prepare_vendor.py
    if ($LASTEXITCODE) { throw 'Vendor preparation failed' }
    & $Python -m pytest tests -q
    if ($LASTEXITCODE) { throw 'Tests failed' }
    # Do not collect unrelated Qt/ICU DLLs from other software's PATH. In
    # particular Poppler's icuuc.dll has versioned exports incompatible with Qt.
    $Python = (Get-Command $Python -ErrorAction Stop).Source
    $PreviousPath = $env:PATH
    try {
        $env:PATH = "$(Split-Path $Python);$env:SystemRoot\System32;$env:SystemRoot"
        & $Python -m PyInstaller --noconfirm --clean --windowed --onedir --name SkyLog1090 --icon assets/skylog.ico --add-data 'assets;assets' --add-data 'drivers;drivers' --add-data 'licenses;licenses' --add-data 'vendor-sources;vendor-sources' --add-data 'THIRD-PARTY.md;.' --add-data '../LICENSE;.' run.py
    } finally { $env:PATH = $PreviousPath }
    if ($LASTEXITCODE) { throw 'Build failed' }
    Copy-Item README-WINDOWS.md dist/SkyLog1090/README-WINDOWS.md
    Copy-Item THIRD-PARTY.md dist/SkyLog1090/THIRD-PARTY.md
    Copy-Item vendor-manifest.json dist/SkyLog1090/vendor-manifest.json
    Compress-Archive -Path dist/SkyLog1090 -DestinationPath dist/SkyLog1090-0.1.1-win-x64.zip -Force
    if ($Iscc) {
        & $Iscc installer.iss
        if ($LASTEXITCODE) { throw 'Installer compilation failed' }
    }
    Get-FileHash dist/*.zip,dist/*.exe -Algorithm SHA256 -ErrorAction SilentlyContinue | Format-Table
} finally { Pop-Location }
