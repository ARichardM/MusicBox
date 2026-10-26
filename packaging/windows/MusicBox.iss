#ifndef AppVersion
  #define AppVersion "2.3.2"
#endif

[Setup]
AppId={{D909E282-C9EB-4C33-8969-C891BEDA1621}
AppName=MusicBox
AppVersion={#AppVersion}
AppPublisher=MusicBox
AppPublisherURL=https://github.com/ARichardM/MusicBox
AppSupportURL=https://github.com/ARichardM/MusicBox/issues
AppUpdatesURL=https://github.com/ARichardM/MusicBox/releases
DefaultDirName={autopf}\MusicBox
DefaultGroupName=MusicBox
DisableProgramGroupPage=yes
OutputDir={#SourcePath}\..\..\dist\installer
OutputBaseFilename=MusicBox-Windows-Setup
SetupIconFile={#SourcePath}\..\..\apps\desktop\assets\logo_app.ico
UninstallDisplayIcon={app}\MusicBox.exe
Compression=lzma2/max
SolidCompression=yes
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
PrivilegesRequired=admin
WizardStyle=modern
CloseApplications=yes
RestartApplications=no
VersionInfoVersion={#AppVersion}.0
VersionInfoProductName=MusicBox
VersionInfoDescription=Instalador seguro de MusicBox para Windows

[Languages]
Name: "spanish"; MessagesFile: "compiler:Languages\Spanish.isl"

[Tasks]
Name: "desktopicon"; Description: "Crear un acceso directo en el escritorio"; GroupDescription: "Accesos directos:"; Flags: unchecked

[Files]
Source: "{#SourcePath}\..\..\dist\MusicBox\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\MusicBox"; Filename: "{app}\MusicBox.exe"; WorkingDir: "{app}"
Name: "{autodesktop}\MusicBox"; Filename: "{app}\MusicBox.exe"; WorkingDir: "{app}"; Tasks: desktopicon

[Run]
Filename: "{app}\MusicBox.exe"; Description: "Abrir MusicBox"; Flags: nowait postinstall skipifsilent
Filename: "{app}\MusicBox.exe"; Flags: nowait runasoriginaluser; Check: WizardSilent

[UninstallDelete]
Type: filesandordirs; Name: "{app}"
