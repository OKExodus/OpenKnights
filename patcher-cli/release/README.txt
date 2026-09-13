OpenKnights Patcher
===================

Turns your own copy of Pocket Knights 4.4.9 into the OpenKnights app.

1. Put your copy of Pocket Knights 4.4.9 in the "original" folder: one APK, an XAPK or APKM file, or a folder with
   the split APKs.
2. Run the patcher: double-click "OpenKnights Patcher.cmd" on Windows, or run ./openknights-patcher on Linux.
3. The app appears in the "patched" folder as OpenKnights-<version>.apk, with its report, SHA-256 and log.
4. Install it on your phone or emulator.

The patcher only reads your copy of the game; it never changes, moves or deletes it. Any other file or version is
refused with a message saying what was found, and nothing is written.

This version of the app plays against the OpenKnights server running on a PC, reached through adb reverse
(ports 17777, 17778 and 19121). The app with the server inside comes in a later version.

Your signing key
----------------
The first patch creates your personal signing key in your user profile:

  Windows: %APPDATA%\OpenKnights\openknights-signing-key.p12
  Linux:   ~/.local/share/openknights/openknights-signing-key.p12

Every newer patcher finds and reuses it, so updates install over your app and your saves stay. Android only updates
an app signed with the same key; an app signed with another key must be uninstalled first, which deletes its saves.
Back the key up:

  openknights-patcher key export <file>
  openknights-patcher key import <file>    (on a new PC)

Run "openknights-patcher --help" for all options.

OpenKnights is free software under the GNU General Public License v3.0. It is an unofficial fan preservation
project, not affiliated with or endorsed by the developers or publishers of Pocket Knights.
