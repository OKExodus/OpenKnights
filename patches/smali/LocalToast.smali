.class public final Lio/github/okexodus/openknights/client/LocalToast;
.super Ljava/lang/Object;
.implements Ljava/lang/Runnable;

# Shows a short message on the UI thread.

.field private activity:Landroid/app/Activity;
.field private text:Ljava/lang/String;

.method public constructor <init>(Landroid/app/Activity;Ljava/lang/String;)V
    .locals 0
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
    iput-object p1, p0, Lio/github/okexodus/openknights/client/LocalToast;->activity:Landroid/app/Activity;
    iput-object p2, p0, Lio/github/okexodus/openknights/client/LocalToast;->text:Ljava/lang/String;
    return-void
.end method

.method public run()V
    .locals 3
    iget-object v0, p0, Lio/github/okexodus/openknights/client/LocalToast;->activity:Landroid/app/Activity;
    iget-object v1, p0, Lio/github/okexodus/openknights/client/LocalToast;->text:Ljava/lang/String;
    const/4 v2, 0x1
    invoke-static {v0, v1, v2}, Landroid/widget/Toast;->makeText(Landroid/content/Context;Ljava/lang/CharSequence;I)Landroid/widget/Toast;
    move-result-object v0
    invoke-virtual {v0}, Landroid/widget/Toast;->show()V
    return-void
.end method
