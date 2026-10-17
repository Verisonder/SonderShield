# SonderShield 1.1.1

## Windows: a proper app
- **Installer:** `SonderShield-Setup-1.1.1.exe` puts SonderShield in the Start menu, adds an optional desktop shortcut and shows it in Windows Settings › Apps, where it uninstalls cleanly and puts your DNS back.
- **Its own window** with the new SonderShield icon. It no longer opens in Edge.
- **Tray icon** with Open, protection on/off and Quit. Closing the window keeps SonderShield running in the tray.
- **Fixed: protection wouldn't turn on** when another part of Windows (connection sharing, Hyper-V, WSL, Docker) was using port 53. SonderShield now filters through its own network adapter when that happens.
- IPv6 DNS is now filtered too.
- If the PC crashes or loses power while protection is on, your DNS settings are restored at the next start.
- "Start with Windows" no longer asks for permission at every sign-in.
- Proper dialogs instead of browser pop-ups.

The portable `SonderShield-Windows-1.1.1.exe` is still there if you don't want to install. Updating from 1.1.0: Settings › Updates.

## Android
No changes apart from the version number.
