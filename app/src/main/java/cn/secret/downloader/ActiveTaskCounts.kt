package cn.secret.downloader

internal class ActiveTaskCounts {
    private val jobs = mutableMapOf<Int, Boolean>()

    val downloads: Int
        @Synchronized get() = jobs.values.count { !it }

    val conversions: Int
        @Synchronized get() = jobs.values.count { it }

    @Synchronized
    fun start(id: Int, isConversion: Boolean): Int {
        jobs[id] = isConversion
        return jobs.size
    }

    @Synchronized
    fun finish(id: Int): Int {
        jobs.remove(id)
        return jobs.size
    }

    @Synchronized
    fun clear() {
        // Shutdown discards queued jobs without running their finally blocks.
        jobs.clear()
    }
}
