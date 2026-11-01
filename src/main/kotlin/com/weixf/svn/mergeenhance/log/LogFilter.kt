package com.weixf.svn.mergeenhance.log

import org.jetbrains.idea.svn.history.SvnChangeList
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date

/**
 * 修订日志过滤条件（第五轮退化版：**单一搜索词 + 全字段 + 空格 AND**）。
 *
 * ## 语义
 *
 * 1. 搜索词按空格拆分为多个 token，**全部 AND**（每个词都必须出现在字段拼接串中）；
 * 2. 子串匹配，**大小写不敏感**；
 * 3. 匹配范围 = 全字段拼接串：提交说明 + **全部变更路径** + 作者 + 修订号 + 日期文本；
 * 4. 空搜索词 = 不过滤。
 *
 * ## 为什么匹配全字段
 *
 * 用户痛点原话："版本过多、时间跨度大时只能一个个版本寻找"。只搜提交说明会漏掉
 * 按路径 / 作者 / 版本号 / 日期定位的场景（原版隐式 speed search 即只搜说明，正是其硬伤）。
 *
 * ## 纯客户端
 *
 * 过滤只作用于**已加载集合**，零远端请求；命中不足时由用户显式「+100 / +500」加载更多。
 *
 * ## 历史沿革（勿再反复讨论）
 *
 * - v2~第四轮：对齐 TortoiseSVN `CLogDlgFilter`，支持 `-`NOT / `+`OR / `"…"`短语 / `!`取反 /
 *   正则 / 大小写开关 / By 字段勾选 / Bug-ID 字段 / 日期区间（第四轮又删了版本区间）；
 * - **第五轮（2026-10-21）**：按用户决策「原版界面 + 一个显式搜索框」整体退化为本形态，
 *   与之相关的 UI（过滤栏、日期控件）、设置项、文案、单测一并删除。
 */
data class LogFilter(
    val searchText: String = "",
) {

    val hasSearchText: Boolean get() = searchText.isNotBlank()

    /**
     * 搜索词的 token 列表（**已转小写**，缓存）。
     *
     * 为什么必须缓存：判定每条修订都会用到，而扫描上万条时分词开销与匹配开销同量级。
     * 分词与小写化结果只取决于不可变的 [searchText]，解析一次即可；`lazy` 默认同步，多线程访问安全。
     */
    private val tokens: List<String> by lazy {
        searchText.trim().split(WHITESPACE).filter { it.isNotEmpty() }.map { it.lowercase() }
    }

    /** 单条日志是否命中。空搜索词恒真。 */
    fun matches(list: SvnChangeList): Boolean {
        if (tokens.isEmpty()) return true
        // ⚠️ 拼接串只小写化**一次**（2026-11-01 修复 P2-6）：此前每条修订重建拼接串，
        // 且每个 token 都要再 `lowercase()` 一遍 —— 上万条修订 + 频繁改关键字时是纯粹的浪费。
        val haystack = buildCombined(list).lowercase()
        return tokens.all { haystack.contains(it) }
    }

    /**
     * 把全部可匹配字段拼成一条串，token 在其中做子串匹配。
     *
     * 字段间以空格分隔，避免相邻字段的字符意外粘连成新词（如作者名尾 + 路径首）。
     */
    private fun buildCombined(list: SvnChangeList): String = buildString {
        append(list.comment)
        for (path in list.affectedPaths) {
            append(' ')
            append(path)
        }
        append(' ')
        append(list.committerName)
        append(' ')
        append(list.number.toString())
        list.commitDate?.let {
            append(' ')
            append(dateText(it))
        }
    }

    /**
     * 日期字段的规范匹配文本（`yyyy-MM-dd HH:mm`）。
     *
     * 用 `java.time.format.DateTimeFormatter` 而**不是** `SimpleDateFormat`：后者非线程安全
     * （可变 formatter），而本方法会被 EDT（渲染）与后台线程（过滤）同时调用。
     * `DateTimeFormatter` 不可变、线程安全。
     */
    fun dateText(date: Date): String = DATE_TIME_FORMATTER.format(date.toInstant())

    private companion object {
        /** 分词用的空白分隔（连续空白按一个处理）。 */
        private val WHITESPACE = Regex("\\s+")

        private const val DATE_TIME_PATTERN = "yyyy-MM-dd HH:mm"

        private val DATE_TIME_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern(DATE_TIME_PATTERN).withZone(ZoneId.systemDefault())
    }
}
