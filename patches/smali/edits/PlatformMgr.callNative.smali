.method private static callNative(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
    .locals 1
    sget-boolean v0, Lcom/gamed9/platform/api/PlatformMgr;->mNativeEnabled:Z
    if-nez v0, :enabled
    const-string v0, "Result=Success"
    return-object v0
    :enabled
    invoke-static {p0, p1}, Lcom/gamed9/platform/api/PlatformMgr;->nativeCall(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
    move-result-object v0
    return-object v0
.end method
