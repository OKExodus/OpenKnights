.class public final Lio/github/okexodus/openknights/client/LocalLoginClient;
.super Landroid/webkit/WebViewClient;

# Keeps the sign-in dialog on the local server: other pages are not opened and other requests get an empty answer.

.method public constructor <init>()V
    .locals 0
    invoke-direct {p0}, Landroid/webkit/WebViewClient;-><init>()V
    return-void
.end method

.method public shouldOverrideUrlLoading(Landroid/webkit/WebView;Ljava/lang/String;)Z
    .locals 1
    invoke-static {p2}, Lio/github/okexodus/openknights/client/LocalLogin;->allowed(Ljava/lang/String;)Z
    move-result v0
    xor-int/lit8 v0, v0, 0x1
    return v0
.end method

.method public shouldInterceptRequest(Landroid/webkit/WebView;Ljava/lang/String;)Landroid/webkit/WebResourceResponse;
    .locals 4
    invoke-static {p2}, Lio/github/okexodus/openknights/client/LocalLogin;->allowed(Ljava/lang/String;)Z
    move-result v0
    if-eqz v0, :block
    const/4 v0, 0x0
    return-object v0
    :block
    new-instance v0, Landroid/webkit/WebResourceResponse;
    const-string v1, "text/plain"
    const-string v2, "UTF-8"
    const/4 v3, 0x0
    invoke-direct {v0, v1, v2, v3}, Landroid/webkit/WebResourceResponse;-><init>(Ljava/lang/String;Ljava/lang/String;Ljava/io/InputStream;)V
    return-object v0
.end method
