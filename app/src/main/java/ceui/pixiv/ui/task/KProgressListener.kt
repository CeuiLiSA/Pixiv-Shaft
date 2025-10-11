package ceui.pixiv.ui.task

// 进度回调接口
interface KProgressListener {
    /**
     * @param bytesRead 已读字节数（累计）
     * @param contentLength 总长度（-1 表示未知）
     * @param done 是否完成（true 当 read 返回 -1）
     */
    fun update(bytesRead: Long, contentLength: Long, done: Boolean)
}
