package cn.secret.downloader

internal object FakeMediaItems {
    fun create(now: Long): List<MediaItem> {
        val titles = listOf(
            "山间日落：把今天最后一束光留在镜头里", "听海的人，和一整片慢慢醒来的海岸线",
            "在东京巷子里随意散步的一天｜从清晨咖啡店到深夜便利店的城市记录", "猫咪又占领了窗边的位置",
            "雨后的森林，连呼吸都是绿色的", "周末厨房 · 给自己做一顿认真又简单的晚餐",
            "沿着没有名字的小路，一直走到风吹来的地方", "极光之夜 Aurora Notes",
            "把云朵装进今天的口袋", "一辆列车穿过秋天：很长很长的标题用来看看卡片上的两行文字会如何自然收尾，以及跨越不同画幅时是否仍然保持整齐",
            "我们去看一场城市的蓝调时刻", "给生活留一点发呆的时间",
            "街头滑板练习 · 慢动作与一次成功的落地", "猫和纸箱之间不可告人的秘密",
            "手冲咖啡的六十秒", "一镜到底穿过老街与人间烟火",
            "原来山顶的风会把烦恼吹得很远", "Music Session：黄昏现场",
            "冬日公园散步记录", "天空渐渐变暗，晚安之前再看一眼这个世界",
        )
        val authors = listOf("小林的镜头", "海边的阿木", "Lena", "猫咪观察员", "光影实验室", null, "TAKUMI")
        val platforms = listOf("抖音", "X", "哔哩哔哩", "YouTube", "小红书")
        val dimensions = listOf(1080 to 1440, 1080 to 1350, 1080 to 1080, 1440 to 1080, 1920 to 1080)
        val durations = listOf(6_000L, 12_000L, 24_000L, 47_000L, 67_000L, 120_000L, 186_000L, 301_000L, 600_000L)
        val videos = titles.mapIndexed { index, title ->
            val duration = durations[index % durations.size]
            val seen = index % 4 != 0
            val position = when (index % 4) { 1 -> duration * 35 / 100; 2 -> duration; 3 -> duration * 82 / 100; else -> 0L }
            val (width, height) = dimensions[index % dimensions.size]
            MediaItem(id = "media-video-${(index + 1).toString().padStart(2, '0')}", title = title,
                author = authors[index % authors.size], platform = platforms[index % platforms.size],
                sourceUrl = if (index == 17) null else "https://example.com/demo/video/${index + 1}",
                durationMs = duration, width = width, height = height,
                sizeBytes = duration / 1000 * 880_000 + index * 321_000,
                downloadedAt = now - index * 9 * 3_600_000L,
                playCount = if (seen) 1 + index % 7 else 0,
                lastPlayedAt = if (seen) now - (20 - index) * 1_800_000L else null,
                lastPositionMs = position, favorite = index % 4 == 0, watchLater = index % 5 == 1,
                exists = index != 6 && index != 17)
        }
        return videos + listOf(
            MediaItem("media-gif-01", type = MediaType.GIF, title = "示例 · 云朵动图", platform = "本机创作",
                durationMs = 3_000, width = 480, height = 480, sizeBytes = 2_400_000, downloadedAt = now + 1),
            MediaItem("media-live-01", type = MediaType.LIVE_PHOTO, title = "示例 · 花园实况", platform = "本机创作",
                durationMs = 9_000, width = 1080, height = 1440, sizeBytes = 8_000_000, downloadedAt = now + 2),
        )
    }
}
