# 本模块不做反射 / 序列化，没有需要 keep 的东西。
# BannerHostOwner 只被 `is` 检查，R8 会照常保留；BannerRequest.metadata 是普通 Map，
# 谁往里塞了需要反序列化的东西，keep 规则归谁写。
