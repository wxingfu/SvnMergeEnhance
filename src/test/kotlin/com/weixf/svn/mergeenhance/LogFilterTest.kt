package com.weixf.svn.mergeenhance

import com.weixf.svn.mergeenhance.log.LogFilter
import com.weixf.svn.mergeenhance.log.LogFilterField
import io.mockk.every
import io.mockk.mockk
import org.jetbrains.idea.svn.history.SvnChangeList
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 过滤条件语义测试（T10.1）。
 *
 * `SvnChangeList` 是 svn4idea 的数据类、构造需要 `SvnVcs`（IDE 组件），
 * 单元测试里用 mockk 只 stub 过滤用到的 getter
 * （comment / committerName / commitDate / number / affectedPaths）。
 *
 * 为什么值得为它引入 mockk：过滤语义（分词、AND/OR/NOT、取反、正则、Bug-ID、日期/版本区间）
 * 是 TortoiseSVN 兼容性的核心，且**已被真实缺陷验证过**——
 * 「含 `-` 的文本被误判为版本区间」这条就是本测试类先红、再驱动 `looksLikeRevisionRange`
 * 加上"两侧必须为数字"约束的。日期用固定时间戳（1579084800000L）避免时区/当前时间导致偶发失败。
 */
class LogFilterTest {

    private fun changeList(
        number: Long,
        comment: String,
        author: String = "alice",
        date: Date = DATE_2020_01_15,
        paths: Set<String> = setOf("/trunk/src/Main.java"),
    ): SvnChangeList = mockk<SvnChangeList>().apply {
        every { this@apply.number } returns number
        every { this@apply.comment } returns comment
        every { this@apply.committerName } returns author
        every { this@apply.commitDate } returns date
        every { this@apply.affectedPaths } returns paths
    }

    private val defaultList = changeList(
        number = 42,
        comment = "fix login bug ABC-123 and refactor",
        author = "alice",
        date = DATE_2020_01_15,
        paths = setOf("/trunk/src/Main.java", "/trunk/src/Util.java"),
    )

    // -------------------------------------------------- 空条件

    @Test
    fun `empty search text does not filter`() {
        val filter = LogFilter(searchText = "")
        assertFalse(filter.isFiltering())
        assertTrue(filter.matches(defaultList))
    }

    @Test
    fun `blank search text does not filter`() {
        val filter = LogFilter(searchText = "   ")
        assertFalse(filter.isFiltering())
        assertTrue(filter.matches(defaultList))
    }

    // -------------------------------------------------- 单条件

    @Test
    fun `single token matches anywhere in combined text`() {
        assertTrue(LogFilter(searchText = "login").matches(defaultList))
        // 作者字段也被拼接，故作者名可命中
        assertTrue(LogFilter(searchText = "alice").matches(defaultList))
        // 路径字段也被拼接
        assertTrue(LogFilter(searchText = "Util.java").matches(defaultList))
        // 版本号字段也被拼接
        assertTrue(LogFilter(searchText = "42").matches(defaultList))
    }

    @Test
    fun `non matching token is rejected`() {
        assertFalse(LogFilter(searchText = "nonexistent").matches(defaultList))
    }

    @Test
    fun `field selection limits the combined text`() {
        // 只勾选 Message：作者名不再参与匹配
        val onlyMessage = LogFilter(searchText = "alice", fields = setOf(LogFilterField.MESSAGE))
        assertFalse(onlyMessage.matches(defaultList))

        // 只勾选 Author：提交说明内容不再参与匹配
        val onlyAuthor = LogFilter(searchText = "login", fields = setOf(LogFilterField.AUTHOR))
        assertFalse(onlyAuthor.matches(defaultList))
        // 但作者名本身可以命中
        assertTrue(LogFilter(searchText = "alice", fields = setOf(LogFilterField.AUTHOR)).matches(defaultList))
    }

    // -------------------------------------------------- 多词组合

    @Test
    fun `multiple tokens are ANDed`() {
        assertTrue(LogFilter(searchText = "login ABC-123").matches(defaultList))
        assertFalse(LogFilter(searchText = "login missingword").matches(defaultList))
    }

    @Test
    fun `minus prefix excludes`() {
        val filter = LogFilter(searchText = "login -refactor")
        assertFalse(filter.matches(defaultList)) // 同时含 refactor → 被排除
        assertTrue(filter.matches(changeList(1, "fix login only")))
    }

    @Test
    fun `plus prefix forms an OR group`() {
        val filter = LogFilter(searchText = "refactor +missingword")
        // AND 部分命中、OR 组无命中 → 不匹配
        assertFalse(filter.matches(defaultList))
        // AND 与 OR 组都命中 → 匹配
        assertTrue(filter.matches(changeList(1, "refactor missingword")))
    }

    @Test
    fun `quoted phrase matches substring with space`() {
        assertTrue(LogFilter(searchText = "\"bug ABC\"").matches(defaultList))
        assertFalse(LogFilter(searchText = "\"bug ABC-124\"").matches(defaultList))
    }

    @Test
    fun `leading exclamation negates the whole expression`() {
        assertTrue(LogFilter(searchText = "!login").matches(changeList(1, "unrelated")))
        assertFalse(LogFilter(searchText = "!login").matches(defaultList))
    }

    @Test
    fun `case sensitivity is off by default and on when requested`() {
        assertTrue(LogFilter(searchText = "LOGIN").matches(defaultList))
        assertFalse(LogFilter(searchText = "LOGIN", caseSensitive = true).matches(defaultList))
        assertTrue(LogFilter(searchText = "login", caseSensitive = true).matches(defaultList))
    }

    @Test
    fun `regex mode treats each token as a pattern`() {
        assertTrue(LogFilter(searchText = "ABC-\\d+", useRegex = true).matches(defaultList))
        assertFalse(LogFilter(searchText = "XYZ-\\d+", useRegex = true).matches(defaultList))
        // 非法正则不应抛异常，只是不命中
        assertFalse(LogFilter(searchText = "[unclosed", useRegex = true).matches(defaultList))
    }

    // -------------------------------------------------- Bug-ID 字段

    @Test
    fun `bug id field extracts ids with the default pattern`() {
        val filter = LogFilter(searchText = "ABC-123", fields = setOf(LogFilterField.BUG_ID))
        assertTrue(filter.matches(defaultList))
        assertFalse(filter.matches(changeList(1, "no id here")))
    }

    @Test
    fun `bug id field honours a custom pattern`() {
        // 小写 jira-77：默认正则要求大写开头，提取不到；自定义正则 [a-z]+-\d+ 可以
        val list = changeList(1, "addresses jira-77")
        val filter = LogFilter(
            searchText = "jira-77",
            fields = setOf(LogFilterField.BUG_ID),
            bugIdPattern = Regex("[a-z]+-\\d+"),
        )
        assertTrue(filter.matches(list))
        assertFalse(LogFilter(searchText = "jira-77", fields = setOf(LogFilterField.BUG_ID)).matches(list))
    }

    @Test
    fun `text containing a dash is not treated as a revision range`() {
        // "ABC-123" 含 '-'，但两侧不是纯数字 → 普通文本（T10 暴露的缺陷修复）
        assertFalse(LogFilter.looksLikeRevisionRange("ABC-123"))
        assertFalse(LogFilter.looksLikeRevisionRange("jira-77"))
        assertFalse(LogFilter.looksLikeRevisionRange("XYZ-\\d+"))
        // 真正的区间仍可识别
        assertTrue(LogFilter.looksLikeRevisionRange("10-20"))
        assertTrue(LogFilter.looksLikeRevisionRange("10-"))
        assertTrue(LogFilter.looksLikeRevisionRange("-20"))
        // 含空格的复合词也不算区间
        assertFalse(LogFilter.looksLikeRevisionRange("fix bug ABC-123"))
    }

    // -------------------------------------------------- 日期区间

    @Test
    fun `date range includes boundaries`() {
        val filter = LogFilter(
            dateRangeEnabled = true,
            fromDate = DATE_2020_01_15,
            toDate = Date(DATE_2020_01_15.time + 60_000),
        )
        assertTrue(filter.hasDateRange())
        assertTrue(filter.matches(defaultList))

        // 早于 from → 排除
        assertFalse(filter.matches(changeList(1, "old", date = Date(DATE_2020_01_15.time - 60_000))))
        // 晚于 to → 排除
        assertFalse(filter.matches(changeList(1, "new", date = Date(DATE_2020_01_15.time + 120_000))))
    }

    @Test
    fun `date range is ignored when disabled`() {
        val filter = LogFilter(
            dateRangeEnabled = false,
            fromDate = Date(DATE_2020_01_15.time + 60_000),
        )
        assertFalse(filter.hasDateRange())
        assertTrue(filter.matches(defaultList))
    }

    @Test
    fun `list without commit date is rejected when date range active`() {
        val noDate: SvnChangeList = mockk()
        every { noDate.commitDate } returns null
        every { noDate.comment } returns "x"
        assertFalse(LogFilter(dateRangeEnabled = true, fromDate = DATE_2020_01_15).matches(noDate))
    }

    // -------------------------------------------------- 版本区间

    @Test
    fun `revision range variants`() {
        val list = changeList(number = 25, comment = "x")
        assertTrue(LogFilter(searchText = "10-30").matches(list))
        assertTrue(LogFilter(searchText = "10:").matches(list))
        assertTrue(LogFilter(searchText = "-30").matches(list))
        assertTrue(LogFilter(searchText = "25").matches(list))
        assertFalse(LogFilter(searchText = "30-40").matches(list))
    }

    @Test
    fun `revision range bounds are inclusive`() {
        val list = changeList(number = 30, comment = "x")
        assertTrue(LogFilter(searchText = "30-40").matches(list))
        assertTrue(LogFilter(searchText = "10-30").matches(list))
    }

    @Test
    fun `plain number is a text match not a range`() {
        // 无分隔符 → 按普通文本匹配（ Combined 文本里含 "42"）
        assertTrue(LogFilter(searchText = "42").matches(defaultList))
        // 而 43 不在任何字段里
        assertFalse(LogFilter(searchText = "43").matches(defaultList))
    }

    // -------------------------------------------------- 高亮

    @Test
    fun `highlight ranges cover token occurrences`() {
        val filter = LogFilter(searchText = "login")
        val ranges = filter.highlightRanges("fix login bug")
        assertContentEquals(listOf(4 until 9), ranges)
    }

    @Test
    fun `isFieldMatched is false for unselected fields`() {
        val filter = LogFilter(searchText = "login", fields = setOf(LogFilterField.MESSAGE))
        assertTrue(filter.isFieldMatched(defaultList, LogFilterField.MESSAGE))
        assertFalse(filter.isFieldMatched(defaultList, LogFilterField.AUTHOR))
    }

    // -------------------------------------------------- 工具方法

    @Test
    fun `looksLikeRevisionRange detects separators without spaces`() {
        assertTrue(LogFilter.looksLikeRevisionRange("10-20"))
        assertTrue(LogFilter.looksLikeRevisionRange("10:20"))
        assertFalse(LogFilter.looksLikeRevisionRange("10 20"))
        assertFalse(LogFilter.looksLikeRevisionRange("42"))
        assertFalse(LogFilter.looksLikeRevisionRange(""))
    }

    @Test
    fun `parseRevisionRange parses all forms`() {
        assertTrue(LogFilter.parseRevisionRange("40") == (40L to null))
        assertTrue(LogFilter.parseRevisionRange("40-50") == (40L to 50L))
        assertTrue(LogFilter.parseRevisionRange("40-") == (40L to null))
        assertTrue(LogFilter.parseRevisionRange("-50") == (null to 50L))
        assertTrue(LogFilter.parseRevisionRange("") == (null to null))
    }

    // -------------------------------------------------- 辅助

    private companion object {
        /** 固定日期，避免时区/当前时间导致的偶发失败。 */
        val DATE_2020_01_15: Date = Date(1579084800000L) // 2020-01-15T16:00:00Z
    }
}
