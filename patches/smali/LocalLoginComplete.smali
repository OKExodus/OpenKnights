.class public final Lio/github/okexodus/openknights/client/LocalLoginComplete;
.super Ljava/lang/Object;
.implements Ljava/lang/Runnable;

# Closes the sign-in dialog, clears the page's state, keeps the session token for top-ups (LocalRecharge) and gives it
# to the game's own login callback.

.field private owner:Lio/github/okexodus/openknights/client/LocalLogin;
.field private token:Ljava/lang/String;

.method public constructor <init>(Lio/github/okexodus/openknights/client/LocalLogin;Ljava/lang/String;)V
    .locals 0
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
    iput-object p1, p0, Lio/github/okexodus/openknights/client/LocalLoginComplete;->owner:Lio/github/okexodus/openknights/client/LocalLogin;
    iput-object p2, p0, Lio/github/okexodus/openknights/client/LocalLoginComplete;->token:Ljava/lang/String;
    return-void
.end method

.method public run()V
    .locals 4
    iget-object v0, p0, Lio/github/okexodus/openknights/client/LocalLoginComplete;->owner:Lio/github/okexodus/openknights/client/LocalLogin;
    iget-object v1, v0, Lio/github/okexodus/openknights/client/LocalLogin;->dialog:Landroid/app/Dialog;
    invoke-virtual {v1}, Landroid/app/Dialog;->isShowing()Z
    move-result v2
    if-eqz v2, :done
    iget-object v2, v0, Lio/github/okexodus/openknights/client/LocalLogin;->web:Landroid/webkit/WebView;
    invoke-virtual {v2}, Landroid/webkit/WebView;->getUrl()Ljava/lang/String;
    move-result-object v3
    invoke-static {v3}, Lio/github/okexodus/openknights/client/LocalLogin;->allowed(Ljava/lang/String;)Z
    move-result v3
    if-eqz v3, :done
    invoke-virtual {v1}, Landroid/app/Dialog;->dismiss()V
    const-string v1, "OpenKnights"
    invoke-virtual {v2, v1}, Landroid/webkit/WebView;->removeJavascriptInterface(Ljava/lang/String;)V
    invoke-virtual {v2}, Landroid/webkit/WebView;->stopLoading()V
    const/4 v1, 0x1
    invoke-virtual {v2, v1}, Landroid/webkit/WebView;->clearCache(Z)V
    invoke-virtual {v2}, Landroid/webkit/WebView;->clearHistory()V
    invoke-virtual {v2}, Landroid/webkit/WebView;->clearFormData()V
    invoke-virtual {v2}, Landroid/webkit/WebView;->destroy()V
    iget-object v1, p0, Lio/github/okexodus/openknights/client/LocalLoginComplete;->token:Ljava/lang/String;
    const/4 v2, 0x0
    iput-object v2, p0, Lio/github/okexodus/openknights/client/LocalLoginComplete;->token:Ljava/lang/String;
    sput-object v1, Lio/github/okexodus/openknights/client/LocalRecharge;->token:Ljava/lang/String;
    invoke-static {v1}, Lcom/gamed9/platform/api/PlatformMgr;->callLoginResult(Ljava/lang/String;)Ljava/lang/String;
    :done
    return-void
.end method
