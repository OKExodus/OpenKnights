.method public onBuy(Lcom/gamed9/platform/api/Request;)V
    .locals 5
    iget-object v0, p0, Lcom/gamed9/platform/EnjoySDK;->mActivity:Landroid/app/Activity;
    const-string v1, "GoodsId"
    invoke-virtual {p1, v1}, Lcom/gamed9/platform/api/Request;->get(Ljava/lang/String;)Ljava/lang/String;
    move-result-object v1
    const-string v2, "ProductId"
    invoke-virtual {p1, v2}, Lcom/gamed9/platform/api/Request;->get(Ljava/lang/String;)Ljava/lang/String;
    move-result-object v2
    new-instance v3, Lio/github/okexodus/openknights/client/LocalRecharge;
    invoke-direct {v3, v0, v1, v2}, Lio/github/okexodus/openknights/client/LocalRecharge;-><init>(Landroid/app/Activity;Ljava/lang/String;Ljava/lang/String;)V
    new-instance v4, Ljava/lang/Thread;
    invoke-direct {v4, v3}, Ljava/lang/Thread;-><init>(Ljava/lang/Runnable;)V
    invoke-virtual {v4}, Ljava/lang/Thread;->start()V
    return-void
.end method
