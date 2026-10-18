package com.weixf.svn.mergeenhance.log

import org.jetbrains.idea.svn.history.SvnChangeList
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
 * | [DATE]    | `LOGFILTER_DATE`     | 日期文本（与搜索词做子串匹配，区别于 [dateRangeEnabled]）|
 *
 * 注：[DATE] 是「文本匹配日期字符串」；而「日期区间」由独立的 [dateRangeEnabled] + [fromDate]/[toDate] 控制，
 * 对应 TortoiseSVN 的 `LOGFILTER_DATERANGE`（默认开启，勾选时才显示起止日历）。
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
 * 修订日志过滤条件，语义严格对齐 TortoiseSVN 的 `CLogDlgFilter`（[G:\gitDownload\TortoiseSVN]）。
 *
 * ## 搜索框匹配语义（核心）
 *
 * 1. 搜索框文本按**空格**拆分为多个 token；每个 token 可带前缀：
 *    - 无前缀 → 必须出现（AND）
 *    - `-` → 必须**不**出现（AND NOT）
 *    - `+` → 与同组 `+` token 取「任一出现」（OR）
 *    - `"..."` → 带空格的整段子串
 * 2. 所有被勾选字段的文本会被**拼接成一条**字符串，每个 token 在该字符串内做匹配
 *    （即「跨字段 OR、跨词 AND」——单个词只要出现在任一勾选字段即可）。
 * 3. 默认**大小写不敏感**；勾选 `Match case` 后敏感。
 * 4. 勾选 `Use regular expressions` 后，每个 token 作为独立正则，**全部**须匹配。
 * 5. 搜索框为空 → 不过滤（[isFiltering] 为 false）。
 * 6. 整段以 `!` 开头 → 结果取反（TortoiseSVN `negate`）。
 *
 * ## 日期区间优先
 *
 * [dateRangeEnabled] 且起/止非空时，先做服务端/客户端日期裁剪，再叠加上述文本匹配。
 * 版本区间（`10-20` / `10:20`，无空格）作为特例按版本号范围过滤。
 */
data class LogFilter(
    val searchText: String = "",
    val fields: Set<LogFilterField> = LogFilterField.values().toSet(),
    /**
     * 对应 `LOGFILTER_DATERANGE`：开启时显示并使用起止日历（默认开）。
     *
     * 默认 true 的原因：TortoiseSVN 的日期范围开关默认就是勾选态，且面板上日历默认可见；
     * 关掉后 `fromDate`/`toDate` 即使有值也不参与匹配（`hasDateRange()` 返回 false），
     * 模式 A 也不会触发——即"用户主动关掉日期维度"。
     */
    val dateRangeEnabled: Boolean = true,
    val fromDate: Date? = null,
    val toDate: Date? = null,
    val useRegex: Boolean = false,
    val caseSensitive: Boolean = false,
    /** Bug-ID 提取正则（T9 可在设置中修改；调用方负责在正则无效时回退默认值）。 */
    val bugIdPattern: Regex = DEFAULT_BUG_ID_PATTERN,
) {

    val hasSearchText: Boolean get() = searchText.isNotBlank()

    fun hasDateRange(): Boolean = dateRangeEnabled && (fromDate != null || toDate != null)

    fun hasRevisionRange(): Boolean =
        fields.contains(LogFilterField.REVISION) && looksLikeRevisionRange(searchText)

    fun revisionRange(): Pair<Long?, Long?> =
        if (hasRevisionRange()) parseRevisionRange(searchText) else null to null

    /** 是否存在会改变结果集的条件。 */
    fun isFiltering(): Boolean = hasDateRange() || hasRevisionRange() || hasSearchText

    fun matches(list: SvnChangeList): Boolean {
        if (dateRangeEnabled) {
            val d = list.commitDate ?: return false
            if (fromDate != null && d < fromDate) return false
            if (toDate != null && d > toDate) return false
        }
        val text = searchText.trim()
        if (text.isEmpty()) return true
        // 整段以 ! 取反
        val negate = text.startsWith("!")
        val body = if (negate) text.substring(1) else text
        val result = matchText(list, body)
        return if (negate) !result else result
    }

    /**
     * 客户端过滤入口（与 [matches] 完全等价，单独命名是为了让调用点表达意图）。
     *
     * 两个使用场景：
     * - `LogPageLoader` 模式 A：服务端已按日期裁过一遍，但仍要在**整段结果内**按文本/版本
     *   条件过滤（日期模式一次拉完，分页在内存做）；
     * - `LogPageLoader` 模式 B：服务端返回的是"从游标往后 N 条原始数据"，要在里面挑满足条件的，
     *   不足一页就循环补页。
     */
    fun matchesClientSide(list: SvnChangeList): Boolean = matches(list)

    // ----------------------------------------------------- 文本匹配（核心）

    private fun matchText(list: SvnChangeList, text: String): Boolean {
        if (hasRevisionRange()) {
            val (from, to) = revisionRange()
            val n = list.number
            return (from == null || n >= from) && (to == null || n <= to)
        }
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
                val start = i
                while (i < s.length && s[i] != '"') i++
                val word = s.substring(start, i.coerceAtMost(s.length))
                if (i < s.length) i++
                if (word.isNotBlank()) tokens.add(Token(type, word))
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
                } catch (e: Exception) {
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
                } catch (e: Exception) {
                    // 非法正则忽略
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
    fun dateText(date: Date): String = DATE_DISPLAY.format(date)

    companion object {
        /** 默认 Bug-ID 正则：`[A-Z][A-Z0-9]+-\d+`。 */
        val DEFAULT_BUG_ID_PATTERN: Regex = Regex("[A-Z][A-Z0-9]+-\\d+")

        /** 日期显示/匹配统一格式（`yyyy-MM-dd HH:mm`）。 */
        val DATE_DISPLAY = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm")

        /**
         * 解析版本区间文本：`40` / `40-50` / `40-` / `-50`（也接受 `:` `~` 与空格）。
         *
         * @return `起始修订号` 与 `截止修订号`（可为 null）
         */
        fun parseRevisionRange(text: String): Pair<Long?, Long?> {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return null to null
            val separator = trimmed.indexOfFirst { isRangeSeparator(it) }
            if (separator < 0) return trimmed.toLongOrNull() to null
            val left = trimmed.substring(0, separator).trim().toLongOrNull()
            val right = trimmed.substring(separator + 1).trim().toLongOrNull()
            return left to right
        }

        /**
         * 文本是否形如版本区间（含分隔符、无空格，且**两侧必须是数字或空**）。
         * 据此区分「单个数字=普通匹配」与「区间=按区间过滤」。
         *
         * 两侧必须为数字，否则 `ABC-123`、`JIRA-77`、`XYZ-\d+` 这类**普通文本**会被误判成区间，
         * 解析失败后按「无限制」处理 → 整个过滤失效（T10 单测暴露，已修）。
         */
        fun looksLikeRevisionRange(text: String): Boolean {
            val trimmed = text.trim()
            if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return false
            val separator = trimmed.indexOfFirst { isRangeSeparator(it) }
            if (separator < 0) return false
            val left = trimmed.substring(0, separator)
            val right = trimmed.substring(separator + 1)
            return (left.isEmpty() || left.all { it.isDigit() }) && (right.isEmpty() || right.all { it.isDigit() })
        }

        private fun isRangeSeparator(c: Char): Boolean =
            c == '-' || c == ':' || c == '~'
    }
}
