package com.weixf.svn.mergeenhance.log

import org.jetbrains.idea.svn.history.SvnChangeList
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date

/**
 * 可勾选的过滤维度，对齐 TortoiseSVN「被过滤信息」下拉（`LOGFILTER_*` 位标）。
 *
 * | 枚举 | TortoiseSVN 位标 | 含义 |
 * |---|---|---|
 * | [MESSAGE] | `LOGFILTER_MESSAGES` | 提交说明 |
 * | [PATH]    | `LOGFILTER_PATHS`    | 变更路径 |
 * | [AUTHOR]  | `LOGFILTER_AUTHORS`  | 作者 |
 * | [REVISION]| `LOGFILTER_REVS`     | 版本号 |
 * | [BUG_ID]  | `LOGFILTER_BUGID`    | Bug-ID（提交说明中形如 `PROJECT-123` 的片段）|
 * | [DATE]    | `LOGFILTER_DATE`     | 日期文本（与搜索词做子串匹配，区别于日期区间）|
 *
 * 注：[DATE] 是「文本匹配日期字符串」；日期区间由独立的 [dateRangeEnabled] + [fromDate]/[toDate]
 * 控制，对应 TortoiseSVN 的 `LOGFILTER_DATERANGE`。
 *
 * ## 三个维度彼此独立（对齐 TortoiseSVN）
 *
 * 1. **搜索框文本**：只做子串/正则匹配，**永不**被解析成版本区间。
 *    早期实现会用「文本形如 `10-20`」来推断版本区间，于是搜 `2026-10`（想按月份搜）
 *    被静默改写成「版本 [10, 2026]」，结果恒为空且无任何提示。TortoiseSVN 没有这种语法：
 *    它的版本区间在独立的「Show range」对话框里（`m_startRev`/`m_endRev`），
 *    搜索框里修订号只是被当作**普通文本**参与匹配（`LogDlgFilter.cpp` 的 `LOGFILTER_REVS`）。
 *    本类用 [revFrom]/[revTo] 两个显式字段承载该语义。
 * 2. **日期区间**：纯**客户端**过滤，**不参与**任何远端查询。TortoiseSVN 同样如此——
 *    `m_tFrom`/`m_tTo` 只写进 `CLogDlgFilter`，而 filter 只用于 `m_logEntries.Filter(...)`
 *    内存过滤（`LogDlg.cpp` 的 `RecalculateShownList`）。
 * 3. **版本区间**：唯一会改变「去服务端取哪一段」的维度，由 [LogPageLoader] 消费。
 */
enum class LogFilterField(val id: String) {
    MESSAGE("message"),
    PATH("path"),
    AUTHOR("author"),
    REVISION("revision"),
    BUG_ID("bugId"),
    DATE("date"),
}

/**
 * 修订日志过滤条件，语义严格对齐 TortoiseSVN 的 `CLogDlgFilter`。
 *
 * ## 搜索框匹配语义
 *
 * 1. 搜索框文本按**空格**拆分为多个 token；每个 token 可带前缀：
 *    - 无前缀 → 必须出现（AND）
 *    - `-` → 必须**不**出现（AND NOT）
 *    - `+` → 与同组 `+` token 取「任一出现」（OR）
 *    - `"..."` → 带空格的整段子串（内部 `""` 转义为一个 `"`）
 * 2. 所有被勾选字段的文本会被**拼接成一条**字符串，每个 token 在该字符串内做匹配
 *    （即「跨字段 OR、跨词 AND」——单个词只要出现在任一勾选字段即可）。
 * 3. 默认**大小写不敏感**；勾选 `Match case` 后敏感。
 * 4. 勾选 `Use regular expressions` 后，每个 token 作为独立正则，**全部**须匹配。
 * 5. 搜索框为空 → 文本维度不过滤。
 * 6. 整段以 `!` 开头 → 文本匹配结果取反（TortoiseSVN `negate`）。
 *
 * ## 判定顺序
 *
 * [matches] 与 TortoiseSVN 的 `CLogDlgFilter::operator()` 一致：**先硬边界、后文本**——
 * 版本区间 → 日期区间 → 文本匹配。硬边界不满足时直接排除，不再做昂贵的文本匹配。
 */
data class LogFilter(
    val searchText: String = "",
    val fields: Set<LogFilterField> = LogFilterField.values().toSet(),
    /**
     * 对应 `LOGFILTER_DATERANGE`。
     *
     * 默认 **true**（对齐 TortoiseSVN 的 `m_selectedFilters(LOGFILTER_ALL)`：日期区间位默认就是开的）。
     * 因为本插件的日期区间是**纯客户端**过滤、且默认值恒等于「已加载数据的 min/max 日期」，
     * 「开着」与「关着」在首屏是等价的，不存在"控件显示已过滤、实际未过滤"的错位。
     * 关闭开关则彻底不按日期裁剪（[matches] 里跳过日期判定）。
     */
    val dateRangeEnabled: Boolean = true,
    val fromDate: Date? = null,
    val toDate: Date? = null,
    /**
     * 版本区间下界（含），null 表示不限。
     *
     * 来自独立的「版本区间」对话框（对齐 TortoiseSVN 的 Show range），
     * **不再**从搜索框文本推断——搜索框只负责文本匹配。
     */
    val revFrom: Long? = null,
    /** 版本区间上界（含），null 表示不限。 */
    val revTo: Long? = null,
    val useRegex: Boolean = false,
    val caseSensitive: Boolean = false,
    /** Bug-ID 提取正则（可在设置中修改；调用方负责在正则无效时回退默认值）。 */
    val bugIdPattern: Regex = DEFAULT_BUG_ID_PATTERN,
) {

    val hasSearchText: Boolean get() = searchText.isNotBlank()

    fun hasDateRange(): Boolean = dateRangeEnabled && (fromDate != null || toDate != null)

    fun hasRevisionRange(): Boolean = revFrom != null || revTo != null

    /** 是否存在会改变结果集的条件。 */
    fun isFiltering(): Boolean = hasDateRange() || hasRevisionRange() || hasSearchText

    /**
     * 单条日志是否命中。判定顺序：版本区间 → 日期区间 → 文本。
     *
     * 版本区间/日期区间都是**客户端**判定，因此无论它们由谁设置、什么时候设置，
     * 都只影响"已加载数据的可见子集"，不会引起重新查询（见类文档「三个维度彼此独立」）。
     */
    fun matches(list: SvnChangeList): Boolean {
        // 1. 版本区间（硬边界）
        if (revFrom != null && list.number < revFrom) return false
        if (revTo != null && list.number > revTo) return false
        // 2. 日期区间（硬边界）
        if (dateRangeEnabled) {
            val d = list.commitDate ?: return false
            if (fromDate != null && d < fromDate) return false
            if (toDate != null && d > toDate) return false
        }
        // 3. 文本匹配
        val text = searchText.trim()
        if (text.isEmpty()) return true
        // 整段以 ! 取反
        val negate = text.startsWith("!")
        val body = if (negate) text.substring(1) else text
        val result = matchText(list, body)
        return if (negate) !result else result
    }

    // ----------------------------------------------------- 文本匹配（核心）

    private fun matchText(list: SvnChangeList, text: String): Boolean {
        val tokens = tokenize(text)
        if (tokens.isEmpty()) return true
        val combined = buildCombined(list)
        return matchCombined(combined, tokens)
    }

    /** 把勾选字段的文本拼成一条，token 在其中做子串/正则匹配。 */
    private fun buildCombined(list: SvnChangeList): String {
        val sb = StringBuilder()
        fun append(s: String) {
            if (s.isNotBlank()) {
                sb.append(' ')
                sb.append(s)
            }
        }
        if (fields.contains(LogFilterField.BUG_ID)) {
            append(bugIdPattern.findAll(list.comment).joinToString(" ") { it.value })
        }
        if (fields.contains(LogFilterField.MESSAGE)) append(list.comment)
        if (fields.contains(LogFilterField.PATH)) list.affectedPaths.forEach { append(it) }
        if (fields.contains(LogFilterField.AUTHOR)) append(list.committerName)
        if (fields.contains(LogFilterField.DATE)) list.commitDate?.let { append(dateText(it)) }
        if (fields.contains(LogFilterField.REVISION)) append(list.number.toString())
        return sb.toString()
    }

    private enum class TokenType { AND, AND_NOT, OR }

    private data class Token(val type: TokenType, val text: String)

    private fun tokenize(raw: String): List<Token> {
        val tokens = mutableListOf<Token>()
        val s = raw.trim()
        var i = 0
        while (i < s.length) {
            while (i < s.length && s[i] == ' ') i++
            if (i >= s.length) break
            var type = TokenType.AND
            if (s[i] == '-') {
                type = TokenType.AND_NOT
                i++
            } else if (s[i] == '+') {
                type = TokenType.OR
                i++
            }
            if (i < s.length && s[i] == '"') {
                i++
                val sb = StringBuilder()
                while (i < s.length) {
                    if (s[i] == '"') {
                        // "" 转义为一个 '"'（TortoiseSVN 同款处理）
                        if (i + 1 < s.length && s[i + 1] == '"') {
                            sb.append('"')
                            i += 2
                            continue
                        }
                        i++
                        break
                    }
                    sb.append(s[i])
                    i++
                }
                if (sb.isNotBlank()) tokens.add(Token(type, sb.toString()))
            } else {
                val start = i
                while (i < s.length && s[i] != ' ') i++
                val word = s.substring(start, i)
                if (word.isNotBlank()) tokens.add(Token(type, word))
            }
        }
        return tokens
    }

    private fun matchCombined(combined: String, tokens: List<Token>): Boolean {
        if (useRegex) {
            val opts = if (caseSensitive) emptySet<RegexOption>() else setOf(RegexOption.IGNORE_CASE)
            return tokens.all { t ->
                try {
                    Regex(t.text, opts).containsMatchIn(combined)
                } catch (e: IllegalArgumentException) {
                    // 用户写了非法正则：该 token 视为不匹配（而不是让整个过滤崩掉）
                    false
                }
            }
        }
        val hay = if (caseSensitive) combined else combined.lowercase()
        val ands = tokens.filter { it.type == TokenType.AND }
        val nots = tokens.filter { it.type == TokenType.AND_NOT }
        val ors = tokens.filter { it.type == TokenType.OR }
        val needle: (String) -> String = { w -> if (caseSensitive) w else w.lowercase() }
        for (t in ands) if (!hay.contains(needle(t.text))) return false
        for (t in nots) if (hay.contains(needle(t.text))) return false
        if (ors.isNotEmpty() && ors.none { hay.contains(needle(it.text)) }) return false
        return true
    }

    // ----------------------------------------------------- 命中高亮

    /** 该字段是否命中（决定是否对该字段着色）。 */
    fun isFieldMatched(list: SvnChangeList, field: LogFilterField): Boolean {
        if (!fields.contains(field)) return false
        val text = fieldText(list, field)
        return text.isNotEmpty() && highlightRanges(text).isNotEmpty()
    }

    /** 在给定文本中找出所有命中区间，供渲染器按段着色（合并相邻/重叠区间）。 */
    fun highlightRanges(text: String): List<IntRange> {
        val tokens = tokenize(searchText)
        if (tokens.isEmpty()) return emptyList()
        val ranges = mutableListOf<IntRange>()
        for (t in tokens) {
            if (t.type == TokenType.AND_NOT) continue
            if (useRegex) {
                try {
                    val opts = if (caseSensitive) emptySet<RegexOption>() else setOf(RegexOption.IGNORE_CASE)
                    ranges.addAll(Regex(t.text, opts).findAll(text).map { it.range })
                } catch (e: IllegalArgumentException) {
                    // 非法正则忽略（该 token 不产生高亮）
                }
            } else {
                ranges.addAll(substringRanges(text, t.text))
            }
        }
        return mergeRanges(ranges)
    }

    private fun substringRanges(text: String, token: String): List<IntRange> {
        if (token.isEmpty()) return emptyList()
        val hay = if (caseSensitive) text else text.lowercase()
        val needle = if (caseSensitive) token else token.lowercase()
        val out = mutableListOf<IntRange>()
        var idx = hay.indexOf(needle)
        while (idx >= 0) {
            out.add(idx until idx + needle.length)
            idx = hay.indexOf(needle, idx + needle.length)
        }
        return out
    }

    private fun mergeRanges(ranges: List<IntRange>): List<IntRange> {
        if (ranges.size <= 1) return ranges
        val sorted = ranges.sortedBy { it.first }
        val merged = mutableListOf<IntRange>()
        var cur = sorted[0]
        for (r in sorted.drop(1)) {
            if (r.first <= cur.last + 1) {
                cur = cur.first..maxOf(cur.last, r.last)
            } else {
                merged.add(cur)
                cur = r
            }
        }
        merged.add(cur)
        return merged
    }

    private fun fieldText(list: SvnChangeList, field: LogFilterField): String = when (field) {
        LogFilterField.MESSAGE -> list.comment
        LogFilterField.PATH -> list.affectedPaths.joinToString(" ")
        LogFilterField.AUTHOR -> list.committerName
        LogFilterField.REVISION -> list.number.toString()
        LogFilterField.BUG_ID -> bugIdPattern.findAll(list.comment).joinToString(" ") { it.value }
        LogFilterField.DATE -> list.commitDate?.let { dateText(it) } ?: ""
    }

    /** 日期字段的规范显示/匹配文本（与渲染器保持一致，确保高亮命中）。 */
    fun dateText(date: Date): String = DATE_TIME_FORMATTER.format(date.toInstant())

    companion object {
        /** 默认 Bug-ID 正则：`[A-Z][A-Z0-9]+-\d+`。 */
        val DEFAULT_BUG_ID_PATTERN: Regex = Regex("[A-Z][A-Z0-9]+-\\d+")

        /**
         * 日期显示/匹配统一格式（`yyyy-MM-dd HH:mm`）。
         *
         * 用 `java.time.format.DateTimeFormatter` 而**不是** `SimpleDateFormat`：后者非线程安全
         * （可变 formatter），而 [dateText] 会被 EDT（渲染/高亮）与后台线程（分页过滤）同时调用，
         * 共用一个静态实例会随机抛错甚至串格式。`DateTimeFormatter` 不可变、线程安全。
         */
        private const val DATE_TIME_PATTERN = "yyyy-MM-dd HH:mm"

        private val DATE_TIME_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern(DATE_TIME_PATTERN).withZone(ZoneId.systemDefault())
    }
}
