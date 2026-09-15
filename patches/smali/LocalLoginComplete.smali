.class public final Lio/github/okexodus/openknights/client/LocalLoginComplete;
.super Ljava/lang/Object;
.implements Ljava/lang/Runnable;

# Hands the session token to the game's own login callback (on the UI thread) and keeps it for later top-ups
# (LocalRecharge). No WebView or dialog is involved; the token came from the in-process sign-in (LocalLogin).

.field private token:Ljava/lang/String;

.method public constructor <init>(Ljava/lang/String;)V
    .locals 0
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
    iput-object p1, p0, Lio/github/okexodus/openknights/client/LocalLoginComplete;->token:Ljava/lang/String;
    return-void
.end method

.method public run()V
    .locals 1
    iget-object v0, p0, Lio/github/okexodus/openknights/client/LocalLoginComplete;->token:Ljava/lang/String;
    sput-object v0, Lio/github/okexodus/openknights/client/LocalRecharge;->token:Ljava/lang/String;
    invoke-static {v0}, Lcom/gamed9/platform/api/PlatformMgr;->callLoginResult(Ljava/lang/String;)Ljava/lang/String;
    return-void
.end method
