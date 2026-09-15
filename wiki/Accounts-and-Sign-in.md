# Accounts and Sign-in

**Definition.** The local device account and how a play session is authenticated. In the offline edition there is no online account and no password to type; the device signs itself in and the server issues a session.

## How It Works Inside

The original game authenticates through the publisher's sign-in service. The offline edition replaces that service with a local one. The app asks the local server for a device session, the server issues a session token, and the game proceeds with that token exactly as it would with an online one. The account is the single owner of this device's world; characters are chosen and created inside the game's own character list, not at sign-in.

There are two ways a session is issued:

- **Device login,** which needs no credentials. The device asks and the server issues a session for the device owner. This is the normal path.
- **Local username and password,** which exists for completeness but is not part of the normal offline flow.

The session token is also what authorizes the free top-up. See [[Cash Shop and Free Top-up]].

## Opcodes

| Opcode | Role |
| --- | --- |
| [[Opcode Index\|C3]] | The game authenticates its session with the token |

Sign-in itself is served over the local sign-in service on port 17778 as small HTTP requests, not as game frames. The device-login request returns the token; the game then presents that token on the game service.

## Persistence

A session is recorded in the account registry so it can be validated on later messages. The owner account itself lives in the registry from the moment the data root is created. See [[Save and Data Root]] and [[Accounts and Sign-in]].

## On-Device Sign-in

On the phone the whole exchange happens in-process. The patched game asks the embedded server for a device token directly, on the same serialized path the server uses for everything else, and hands it to the game's own login callback. No browser view and no visible sign-in screen appear. The earlier builds showed a small local web view for this; it was removed in favor of the in-process path.

Our client class does the whole thing, from `patches/smali/LocalLogin.smali`. It calls a static method on the embedded server (both live in the same app process), then posts the token to the game's login callback on the UI thread:

```smali
.method public run()V
    .locals 3
    invoke-static {}, Lio/github/okexodus/openknights/server/android/AndroidServerHost;->deviceLoginToken()Ljava/lang/String;
    move-result-object v0
    if-eqz v0, :done
    iget-object v1, p0, Lio/github/okexodus/openknights/client/LocalLogin;->activity:Landroid/app/Activity;
    new-instance v2, Lio/github/okexodus/openknights/client/LocalLoginComplete;
    invoke-direct {v2, v0}, Lio/github/okexodus/openknights/client/LocalLoginComplete;-><init>(Ljava/lang/String;)V
    invoke-virtual {v1, v2}, Landroid/app/Activity;->runOnUiThread(Ljava/lang/Runnable;)V
    :done
    return-void
.end method
```

See [[The Patcher]] for how this class replaces the game's original sign-in call.

## Code

The gateway that answers sign-in requests is reproduced in `server-core/src/main/kotlin/io/github/okexodus/openknights/server/session/AuthGateway.kt`. Device sessions are issued by the account registry in `server/store/AccountRegistry.kt`. The on-device, in-process path lives in the Android integration in `server-android`.

## How It Was Deciphered

The sign-in exchange was captured and reduced to the device-login request and the token it returns, then reproduced and pinned by the [[Method Differential Harness]], which includes the sign-in service's HTTP bodies among the things it compares. On-device, native testing confirmed the game accepts the in-process token and reaches its authenticated state with no web view shown.

## See Also

- [[Character Selection and Deletion]], which the session leads into.
- [[Save and Data Root]], where the owner account and sessions live.
- [[Cash Shop and Free Top-up]], which the session token authorizes.
