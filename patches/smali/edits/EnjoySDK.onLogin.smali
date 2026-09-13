.method public onLogin()V
    .locals 2
    iget-object v0, p0, Lcom/gamed9/platform/EnjoySDK;->mActivity:Landroid/app/Activity;
    new-instance v1, Lio/github/okexodus/openknights/client/LocalLogin;
    invoke-direct {v1, v0}, Lio/github/okexodus/openknights/client/LocalLogin;-><init>(Landroid/app/Activity;)V
    invoke-virtual {v0, v1}, Landroid/app/Activity;->runOnUiThread(Ljava/lang/Runnable;)V
    return-void
.end method
