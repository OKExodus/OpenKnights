.class public final Lio/github/okexodus/openknights/client/LocalRecharge;
.super Ljava/lang/Object;
.implements Ljava/lang/Runnable;

# Free top-up: the purchase request of a tapped recharge pack is posted, with the session token, to the local server
# (http://127.0.0.1:17778/api/recharge), which grants the pack in the running game session. No payment service is
# contacted and no money is involved.

.field public static token:Ljava/lang/String;
.field private activity:Landroid/app/Activity;
.field private goods:Ljava/lang/String;
.field private product:Ljava/lang/String;

.method public constructor <init>(Landroid/app/Activity;Ljava/lang/String;Ljava/lang/String;)V
    .locals 0
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
    iput-object p1, p0, Lio/github/okexodus/openknights/client/LocalRecharge;->activity:Landroid/app/Activity;
    iput-object p2, p0, Lio/github/okexodus/openknights/client/LocalRecharge;->goods:Ljava/lang/String;
    iput-object p3, p0, Lio/github/okexodus/openknights/client/LocalRecharge;->product:Ljava/lang/String;
    return-void
.end method

.method private toast(Ljava/lang/String;)V
    .locals 2
    iget-object v0, p0, Lio/github/okexodus/openknights/client/LocalRecharge;->activity:Landroid/app/Activity;
    if-eqz v0, :done
    new-instance v1, Lio/github/okexodus/openknights/client/LocalToast;
    invoke-direct {v1, v0, p1}, Lio/github/okexodus/openknights/client/LocalToast;-><init>(Landroid/app/Activity;Ljava/lang/String;)V
    invoke-virtual {v0, v1}, Landroid/app/Activity;->runOnUiThread(Ljava/lang/Runnable;)V
    :done
    return-void
.end method

.method public run()V
    .locals 6
    sget-object v0, Lio/github/okexodus/openknights/client/LocalRecharge;->token:Ljava/lang/String;
    if-nez v0, :has_token
    const-string v1, "Log in before topping up"
    invoke-direct {p0, v1}, Lio/github/okexodus/openknights/client/LocalRecharge;->toast(Ljava/lang/String;)V
    return-void
    :has_token
    :try_start
    new-instance v1, Lorg/json/JSONObject;
    invoke-direct {v1}, Lorg/json/JSONObject;-><init>()V
    const-string v2, "token"
    invoke-virtual {v1, v2, v0}, Lorg/json/JSONObject;->put(Ljava/lang/String;Ljava/lang/Object;)Lorg/json/JSONObject;
    iget-object v2, p0, Lio/github/okexodus/openknights/client/LocalRecharge;->goods:Ljava/lang/String;
    invoke-static {v2}, Ljava/lang/Integer;->parseInt(Ljava/lang/String;)I
    move-result v2
    const-string v3, "goods_id"
    invoke-virtual {v1, v3, v2}, Lorg/json/JSONObject;->put(Ljava/lang/String;I)Lorg/json/JSONObject;
    iget-object v2, p0, Lio/github/okexodus/openknights/client/LocalRecharge;->product:Ljava/lang/String;
    if-nez v2, :has_product
    const-string v2, ""
    :has_product
    const-string v3, "product_id"
    invoke-virtual {v1, v3, v2}, Lorg/json/JSONObject;->put(Ljava/lang/String;Ljava/lang/Object;)Lorg/json/JSONObject;
    invoke-virtual {v1}, Lorg/json/JSONObject;->toString()Ljava/lang/String;
    move-result-object v1
    const-string v2, "UTF-8"
    invoke-virtual {v1, v2}, Ljava/lang/String;->getBytes(Ljava/lang/String;)[B
    move-result-object v1
    new-instance v2, Ljava/net/URL;
    const-string v3, "http://127.0.0.1:17778/api/recharge"
    invoke-direct {v2, v3}, Ljava/net/URL;-><init>(Ljava/lang/String;)V
    invoke-virtual {v2}, Ljava/net/URL;->openConnection()Ljava/net/URLConnection;
    move-result-object v2
    check-cast v2, Ljava/net/HttpURLConnection;
    const-string v3, "POST"
    invoke-virtual {v2, v3}, Ljava/net/HttpURLConnection;->setRequestMethod(Ljava/lang/String;)V
    const/4 v3, 0x1
    invoke-virtual {v2, v3}, Ljava/net/HttpURLConnection;->setDoOutput(Z)V
    const/16 v3, 0x1388
    invoke-virtual {v2, v3}, Ljava/net/HttpURLConnection;->setConnectTimeout(I)V
    const/16 v3, 0x2710
    invoke-virtual {v2, v3}, Ljava/net/HttpURLConnection;->setReadTimeout(I)V
    const-string v3, "Content-Type"
    const-string v4, "application/json"
    invoke-virtual {v2, v3, v4}, Ljava/net/HttpURLConnection;->setRequestProperty(Ljava/lang/String;Ljava/lang/String;)V
    array-length v3, v1
    invoke-virtual {v2, v3}, Ljava/net/HttpURLConnection;->setFixedLengthStreamingMode(I)V
    invoke-virtual {v2}, Ljava/net/HttpURLConnection;->getOutputStream()Ljava/io/OutputStream;
    move-result-object v3
    invoke-virtual {v3, v1}, Ljava/io/OutputStream;->write([B)V
    invoke-virtual {v3}, Ljava/io/OutputStream;->close()V
    invoke-virtual {v2}, Ljava/net/HttpURLConnection;->getResponseCode()I
    move-result v5
    invoke-virtual {v2}, Ljava/net/HttpURLConnection;->disconnect()V
    :try_end
    .catch Ljava/lang/Exception; {:try_start .. :try_end} :failed
    const/16 v4, 0xc8
    if-ne v5, v4, :not_ok
    return-void
    :not_ok
    const/16 v4, 0x199
    if-ne v5, v4, :failed
    const-string v4, "This pack cannot be topped up offline yet"
    invoke-direct {p0, v4}, Lio/github/okexodus/openknights/client/LocalRecharge;->toast(Ljava/lang/String;)V
    return-void
    :failed
    const-string v4, "Top-up failed - is the local server running?"
    invoke-direct {p0, v4}, Lio/github/okexodus/openknights/client/LocalRecharge;->toast(Ljava/lang/String;)V
    return-void
.end method
