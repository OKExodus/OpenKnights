.class public final Lio/github/okexodus/openknights/client/LocalLogin;
.super Ljava/lang/Object;
.implements Ljava/lang/Runnable;

# In-process sign-in (no WebView, no network): asks the embedded OpenKnights server in this same app process for a
# device session token, then hands it to the game's own login callback on the UI thread (LocalLoginComplete). Runs on
# a background thread (started by EnjoySDK.onLogin) because obtaining the token touches the local database.

.field public activity:Landroid/app/Activity;

.method public constructor <init>(Landroid/app/Activity;)V
    .locals 0
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
    iput-object p1, p0, Lio/github/okexodus/openknights/client/LocalLogin;->activity:Landroid/app/Activity;
    return-void
.end method

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
