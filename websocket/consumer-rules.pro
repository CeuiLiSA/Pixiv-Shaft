# 本模块不做反射 / 序列化，没有需要 keep 的东西。
# 收发的报文都是 String / ByteString，解析成业务模型是消费方的事（见 chat/api），
# 该 keep 的是消费方自己的模型类，规则归消费方写。okhttp 自带 consumer rules。
