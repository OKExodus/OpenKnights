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
