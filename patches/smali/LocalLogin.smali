.class public final Lio/github/okexodus/openknights/client/LocalLogin;
.super Ljava/lang/Object;
.implements Ljava/lang/Runnable;

# Shows the local sign-in page (served by the OpenKnights server on this device's loopback address) in a dialog.
# Only pages and requests below http://127.0.0.1:17778/ are allowed; file, content, form, cookie and storage access are
# off. The page hands the session to the game through the "OpenKnights" JavaScript bridge (LocalLoginBridge).

.field public activity:Landroid/app/Activity;
.field public dialog:Landroid/app/Dialog;
.field public web:Landroid/webkit/WebView;
.field private static active:Lio/github/okexodus/openknights/client/LocalLogin;

.method public constructor <init>(Landroid/app/Activity;)V
    .locals 0
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
    iput-object p1, p0, Lio/github/okexodus/openknights/client/LocalLogin;->activity:Landroid/app/Activity;
    return-void
.end method

.method public static allowed(Ljava/lang/String;)Z
    .locals 1
    if-eqz p0, :deny
    const-string v0, "http://127.0.0.1:17778/"
    invoke-virtual {p0, v0}, Ljava/lang/String;->startsWith(Ljava/lang/String;)Z
    move-result v0
    return v0
    :deny
    const/4 v0, 0x0
    return v0
.end method

.method public run()V
    .locals 6
    sget-object v0, Lio/github/okexodus/openknights/client/LocalLogin;->active:Lio/github/okexodus/openknights/client/LocalLogin;
    if-eqz v0, :create
    iget-object v0, v0, Lio/github/okexodus/openknights/client/LocalLogin;->dialog:Landroid/app/Dialog;
    if-eqz v0, :create
    invoke-virtual {v0}, Landroid/app/Dialog;->isShowing()Z
    move-result v0
    if-nez v0, :done
    :create
    sput-object p0, Lio/github/okexodus/openknights/client/LocalLogin;->active:Lio/github/okexodus/openknights/client/LocalLogin;
    iget-object v0, p0, Lio/github/okexodus/openknights/client/LocalLogin;->activity:Landroid/app/Activity;
    new-instance v1, Landroid/webkit/WebView;
    invoke-direct {v1, v0}, Landroid/webkit/WebView;-><init>(Landroid/content/Context;)V
    iput-object v1, p0, Lio/github/okexodus/openknights/client/LocalLogin;->web:Landroid/webkit/WebView;
    invoke-virtual {v1}, Landroid/webkit/WebView;->getSettings()Landroid/webkit/WebSettings;
    move-result-object v2
    const/4 v3, 0x0
    invoke-virtual {v2, v3}, Landroid/webkit/WebSettings;->setAllowFileAccess(Z)V
    invoke-virtual {v2, v3}, Landroid/webkit/WebSettings;->setAllowContentAccess(Z)V
    invoke-virtual {v2, v3}, Landroid/webkit/WebSettings;->setAllowFileAccessFromFileURLs(Z)V
    invoke-virtual {v2, v3}, Landroid/webkit/WebSettings;->setAllowUniversalAccessFromFileURLs(Z)V
    invoke-virtual {v2, v3}, Landroid/webkit/WebSettings;->setSaveFormData(Z)V
    invoke-virtual {v2, v3}, Landroid/webkit/WebSettings;->setSavePassword(Z)V
    invoke-virtual {v2, v3}, Landroid/webkit/WebSettings;->setDomStorageEnabled(Z)V
    invoke-virtual {v2, v3}, Landroid/webkit/WebSettings;->setDatabaseEnabled(Z)V
    const/4 v4, 0x2
    invoke-virtual {v2, v4}, Landroid/webkit/WebSettings;->setCacheMode(I)V
    const/4 v4, 0x1
    invoke-virtual {v2, v4}, Landroid/webkit/WebSettings;->setJavaScriptEnabled(Z)V
    invoke-virtual {v1, v4}, Landroid/webkit/WebView;->clearCache(Z)V
    invoke-virtual {v1}, Landroid/webkit/WebView;->clearFormData()V
    invoke-static {}, Landroid/webkit/CookieManager;->getInstance()Landroid/webkit/CookieManager;
    move-result-object v2
    invoke-virtual {v2, v3}, Landroid/webkit/CookieManager;->setAcceptCookie(Z)V
    new-instance v2, Lio/github/okexodus/openknights/client/LocalLoginClient;
    invoke-direct {v2}, Lio/github/okexodus/openknights/client/LocalLoginClient;-><init>()V
    invoke-virtual {v1, v2}, Landroid/webkit/WebView;->setWebViewClient(Landroid/webkit/WebViewClient;)V
    new-instance v2, Lio/github/okexodus/openknights/client/LocalLoginBridge;
    invoke-direct {v2, p0}, Lio/github/okexodus/openknights/client/LocalLoginBridge;-><init>(Lio/github/okexodus/openknights/client/LocalLogin;)V
    const-string v3, "OpenKnights"
    invoke-virtual {v1, v2, v3}, Landroid/webkit/WebView;->addJavascriptInterface(Ljava/lang/Object;Ljava/lang/String;)V
    new-instance v2, Landroid/app/Dialog;
    invoke-direct {v2, v0}, Landroid/app/Dialog;-><init>(Landroid/content/Context;)V
    iput-object v2, p0, Lio/github/okexodus/openknights/client/LocalLogin;->dialog:Landroid/app/Dialog;
    const-string v3, "OpenKnights - local account"
    invoke-virtual {v2, v3}, Landroid/app/Dialog;->setTitle(Ljava/lang/CharSequence;)V
    invoke-virtual {v2, v1}, Landroid/app/Dialog;->setContentView(Landroid/view/View;)V
    const/4 v3, 0x0
    invoke-virtual {v2, v3}, Landroid/app/Dialog;->setCancelable(Z)V
    invoke-virtual {v2}, Landroid/app/Dialog;->show()V
    invoke-virtual {v2}, Landroid/app/Dialog;->getWindow()Landroid/view/Window;
    move-result-object v2
    const/4 v3, -0x1
    invoke-virtual {v2, v3, v3}, Landroid/view/Window;->setLayout(II)V
    const-string v2, "http://127.0.0.1:17778/"
    invoke-virtual {v1, v2}, Landroid/webkit/WebView;->loadUrl(Ljava/lang/String;)V
    :done
    return-void
.end method
