package cn.secret.downloader

/** Unknown stages keep the last known floor until the same attempt reports progress again. */
internal class ProgressValue {
    private var key: String? = null
    private var floor = 0f
    var value: Float? = null
        private set

    fun update(resetKey: String, progress: Float?, completed: Boolean = false): Boolean {
        val reset = key != resetKey
        if (reset) {
            key = resetKey
            floor = 0f
        }
        value = if (completed) 1f else progress?.takeIf { it.isFinite() }?.coerceIn(floor, 1f)
        value?.let { floor = it }
        return reset
    }
}

internal fun progressStatusLabel(status: TaskStatus): String = when (status) {
    TaskStatus.QUEUED -> "等待处理"
    TaskStatus.PARSING -> "正在解析"
    TaskStatus.RUNNING -> "处理中"
    TaskStatus.PAUSED -> "已暂停"
    TaskStatus.COMPLETED -> "已完成"
    TaskStatus.FAILED -> "失败"
    TaskStatus.CANCELED -> "已取消"
    TaskStatus.INTERRUPTED -> "已中断"
}
