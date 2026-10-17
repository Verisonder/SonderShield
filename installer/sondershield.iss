; SonderShield for Windows: installer (Inno Setup 6).
; Built by CI: iscc /DAppVersion=1.2.0 /DSrc=<folder with SonderShield.exe and wintun.dll> installer\sondershield.iss

#ifndef AppVersion
  #define AppVersion "0.0.0"
#endif
#ifndef Src
  #define Src "..\dist"
#endif

[Setup]
AppId={{6B1F3C2A-5D8E-4E3B-9A7C-5E0D4B2F8A11}
AppName=SonderShield
AppVersion={#AppVersion}
AppVerName=SonderShield {#AppVersion}
AppPublisher=Verisonder
AppPublisherURL=https://github.com/Verisonder/SonderShield
AppSupportURL=https://github.com/Verisonder/SonderShield/issues
AppUpdatesURL=https://github.com/Verisonder/SonderShield/releases
DefaultDirName={autopf}\SonderShield
DisableProgramGroupPage=yes
PrivilegesRequired=admin
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0
SetupIconFile=..\engine\cmd\sondershield\res\app.ico
UninstallDisplayIcon={app}\SonderShield.exe
UninstallDisplayName=SonderShield
WizardStyle=modern
OutputBaseFilename=SonderShield-Setup-{#AppVersion}
OutputDir=..\out
Compression=lzma2/max
SolidCompression=yes
CloseApplications=force
RestartApplications=no
LicenseFile=..\LICENSE

[Tasks]
Name: "desktopicon"; Description: "Create a desktop shortcut"; GroupDescription: "Shortcuts:"
Name: "autostart"; Description: "Start SonderShield with Windows"; GroupDescription: "Startup:"

[Files]
Source: "{#Src}\SonderShield.exe"; DestDir: "{app}"; Flags: ignoreversion
Source: "{#Src}\wintun.dll"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\LICENSE"; DestDir: "{app}"; DestName: "LICENSE.txt"; Flags: ignoreversion
Source: "..\NOTICE"; DestDir: "{app}"; DestName: "NOTICE.txt"; Flags: ignoreversion

[Icons]
Name: "{autoprograms}\SonderShield"; Filename: "{app}\SonderShield.exe"; AppUserModelID: "Verisonder.SonderShield"
Name: "{autodesktop}\SonderShield"; Filename: "{app}\SonderShield.exe"; AppUserModelID: "Verisonder.SonderShield"; Tasks: desktopicon

[Run]
Filename: "{app}\SonderShield.exe"; Parameters: "--autostart on"; Flags: runhidden waituntilterminated; Tasks: autostart
Filename: "{app}\SonderShield.exe"; Description: "Open SonderShield"; Flags: postinstall nowait skipifsilent

[UninstallRun]
; Puts Windows' DNS back, removes SonderShield's firewall rules and its startup task.
Filename: "{app}\SonderShield.exe"; Parameters: "--uninstall"; Flags: runhidden waituntilterminated; RunOnceId: "Cleanup"

[UninstallDelete]
Type: files; Name: "{app}\SonderShield.exe.old"
Type: filesandordirs; Name: "{localappdata}\SonderShield"
