[Setup]
AppId={{9AC0F6A7-F9D5-4188-AFB0-109010901090}
AppName=SkyLog 1090
AppVersion=0.1.0
AppPublisher=SkyLog 1090 contributors
AppPublisherURL=https://github.com/jumo-fryna/9GRadio-ADS-B-Logger
DefaultDirName={localappdata}\Programs\SkyLog1090
DefaultGroupName=SkyLog 1090
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0.22000
OutputDir=dist
OutputBaseFilename=SkyLog1090-0.1.0-Setup-x64
SetupIconFile=assets\skylog.ico
UninstallDisplayIcon={app}\SkyLog1090.exe
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
LicenseFile=..\LICENSE
InfoAfterFile=README-WINDOWS.md
CloseApplications=yes

[Languages]
Name: "polish"; MessagesFile: "compiler:Languages\Polish.isl"
Name: "english"; MessagesFile: "compiler:Default.isl"

[Files]
Source: "dist\SkyLog1090\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{group}\SkyLog 1090"; Filename: "{app}\SkyLog1090.exe"
Name: "{group}\SkyLog 1090 — instrukcja"; Filename: "{app}\README-WINDOWS.md"
Name: "{userdesktop}\SkyLog 1090"; Filename: "{app}\SkyLog1090.exe"; Tasks: desktopicon

[Tasks]
Name: "desktopicon"; Description: "Utwórz skrót na pulpicie"; Flags: unchecked

[Run]
Filename: "{app}\SkyLog1090.exe"; Description: "Uruchom SkyLog 1090"; Flags: nowait postinstall skipifsilent
