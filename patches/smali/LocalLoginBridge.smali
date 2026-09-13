.class public final Lio/github/okexodus/openknights/client/LocalLoginBridge;
.super Ljava/lang/Object;

# The "OpenKnights" JavaScript bridge of the sign-in page: login(token) accepts only a session token of 32-256
# URL-safe characters and hands it to the game on the UI thread (LocalLoginComplete).

.field private owner:Lio/github/okexodus/openknights/client/LocalLogin;

.method public constructor <init>(Lio/github/okexodus/openknights/client/LocalLogin;)V
    .locals 0
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
    iput-object p1, p0, Lio/github/okexodus/openknights/client/LocalLoginBridge;->owner:Lio/github/okexodus/openknights/client/LocalLogin;
    return-void
.end method

.method public login(Ljava/lang/String;)V
    .locals 3
    .annotation runtime Landroid/webkit/JavascriptInterface;
    .end annotation
    if-eqz p1, :done
    const-string v0, "[A-Za-z0-9_-]{32,256}"
    invoke-virtual {p1, v0}, Ljava/lang/String;->matches(Ljava/lang/String;)Z
    move-result v0
    if-eqz v0, :done
    iget-object v0, p0, Lio/github/okexodus/openknights/client/LocalLoginBridge;->owner:Lio/github/okexodus/openknights/client/LocalLogin;
    new-instance v1, Lio/github/okexodus/openknights/client/LocalLoginComplete;
    invoke-direct {v1, v0, p1}, Lio/github/okexodus/openknights/client/LocalLoginComplete;-><init>(Lio/github/okexodus/openknights/client/LocalLogin;Ljava/lang/String;)V
    iget-object v2, v0, Lio/github/okexodus/openknights/client/LocalLogin;->activity:Landroid/app/Activity;
    invoke-virtual {v2, v1}, Landroid/app/Activity;->runOnUiThread(Ljava/lang/Runnable;)V
    :done
    return-void
.end method
