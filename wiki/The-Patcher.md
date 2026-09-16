# The Patcher

**Definition.** The tool that turns the player's own copy of Pocket Knights into the OpenKnights app. It rewrites the manifest, redirects the game's sign-in and payment calls to the local server, embeds that server, and signs the result with a key unique to the player. It contains no game files; everything it builds comes from the copy supplied to it.

## What It Changes, and What It Does Not

The patcher redirects the client to the local server and makes focused compatibility fixes, including refreshing cached leaderboards when their screen opens.

- **Manifest.** The app's package, name, and icon; native code extracted on install; no system backup of saves; plain loopback traffic allowed; and none of the publisher's sign-in, payment, analytics, or advertising components. On-device it also points the Application class at the server boot hook and adds the restore entry point. See [[Save and Data Root]].
- **Code.** A few methods of the platform bridge are redirected to the local server, and our own client classes are added in a new DEX. Everything else is untouched.
- **Native library.** A small set of byte patches, verified against the expected source, including sign-in redirects, credential log redaction, and leaderboard cache refresh.
- **Resources.** The OpenKnights icon and the merged split resources.
- **Signature.** The finished app is signed with the player's own key. See [[Accounts and Sign-in]] for why the key is per-player.

## The Code Edits

The redirect is expressed as exact edits recorded in `patches/smali/`. The game's sign-in method is replaced so that, instead of reaching the publisher's service, it starts our local sign-in. This snippet is our replacement method body; it names the game's platform class only as the site being redirected, which is an interoperability reference, not the game's code. See [[What Is Not In This Repository]].

```smali
.method public onLogin()V
    .locals 3
    iget-object v0, p0, Lcom/gamed9/platform/EnjoySDK;->mActivity:Landroid/app/Activity;
    new-instance v1, Lio/github/okexodus/openknights/client/LocalLogin;
    invoke-direct {v1, v0}, Lio/github/okexodus/openknights/client/LocalLogin;-><init>(Landroid/app/Activity;)V
    new-instance v2, Ljava/lang/Thread;
    invoke-direct {v2, v1}, Ljava/lang/Thread;-><init>(Ljava/lang/Runnable;)V
    invoke-virtual {v2}, Ljava/lang/Thread;->start()V
    return-void
.end method
```

`LocalLogin` is one of our own added classes. It asks the embedded server for a device session token in-process and hands it to the game's own login callback, with no browser view. The full set of edits and added classes is listed in `patches/smali/game-edits.json`.

The embedded server's Kotlin libraries use a separate package namespace, applied by `patcher-core/.../dex/ServerRuntime.kt`. The original client already contains an older Kotlin runtime. Giving the server its own class names prevents Android from resolving newer server calls against that older runtime. The rewrite covers the server DEX files and their type references; the client's own runtime stays intact.

## Leaderboard Refresh

Entering the leaderboard screen clears its cached rows before registering the screen's response callbacks. Categories request current results as they are viewed; pages remain cached while the screen stays open. Leaving and reopening the screen refreshes them again. There is no background polling or save modification.

The arm64 entry helper is documented in `patches/native/leaderboard-refresh.S` and encoded in `patches/native/libhelloworld.json`. It calls the client's existing rank-cache reset, then resumes the original screen setup. Its instructions occupy the aligned tail of an already-redacted log string in the executable segment, after the replacement text's terminator. The library layout is unchanged. The patcher verifies the complete source hash, both affected sites, and the complete patched hash.

The optional developer check `python tools/check_leaderboard_refresh.py <your-arm64-library>` requires `unicorn==2.1.4`. It executes the helper and original reset against synthetic empty and populated caches, verifies all 13 categories, checks that only cache and stack memory change, and confirms that received pages can become ready normally. Device QA should confirm that a changed ranking value appears after leaving and reopening the leaderboard screen.

## The Manifest Injection

The manifest is compiled binary XML, so the patcher edits it structurally rather than as text. It can set and remove attributes and inject whole elements. The on-device build injects the restore entry point as a new activity with an intent filter, so a backup opened from a file manager reaches the app:

```
<activity android:name="...RestoreActivity" android:exported="true">
  <intent-filter>
    <action android:name="android.intent.action.VIEW"/>
    <category android:name="android.intent.category.DEFAULT"/>
    <data android:mimeType="application/zip"/>
  </intent-filter>
</activity>
```

## Signing and Updates

Each patched app is signed with a key generated for the player and kept on their machine. Android installs an update only when it is signed with the same key as the installed app, so a player's future updates install cleanly over their own app, while an app signed with a different key would have to be uninstalled first, losing its saves. The key can be exported and imported so it survives a new machine.

## Code

Reproduced in `patcher-core` and driven by `patcher-cli`. The manifest edits are in `patcher-core/.../patch/ManifestPatch.kt`, the code edits in `patch/CodePatch.kt` applying the smali in `patches/smali/`, the native patches in `patch/NativePatchSet.kt`, and signing in `patcher-core/.../signing/`.

## How It Is Used

```
openknights-patcher patch --input <your game> --output <folder>
```

The finished OpenKnights app appears in the output folder, ready to install. The on-device build adds the embedded server and its assets to the same command.

## See Also

- [[Accounts and Sign-in]], the sign-in the redirect leads into.
- [[Tools and Toolchain]], the build tools the patcher uses.
- [[Save and Data Root]], reached through the injected restore activity.
