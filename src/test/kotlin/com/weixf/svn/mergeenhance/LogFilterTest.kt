package com.weixf.svn.mergeenhance

import com.weixf.svn.mergeenhance.log.LogFilter
import io.mockk.every
import io.mockk.mockk
import org.jetbrains.idea.svn.history.SvnChangeList
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 过滤条件语义测试（第五轮退化版）。
 *
 * `SvnChangeList` 是 svn4idea 的数据类、构造需要 `SvnVcs`（IDE 组件），
 * 单元测试里用 mockk 只 stub 过滤用到的 getter
 * （comment / committerName / commitDate / number / affectedPaths）。
 *
 * 第五轮起语义收敛为：**单一搜索词 + 全字段拼接 + 空格 AND + 大小写不敏感**。
 * 历史版本（TortoiseSVN 式：`-`NOT / `+`OR / 引号短语 / `!`取反 / 正则 / 大小写开关 /
 * By 字段勾选 / Bug-ID / 日期区间）的用例已随 `LogFilter` 重写整体删除，
 * 沿革说明见 `LogFilter` 的 KDoc，勿再恢复。
 * 日期用固定时间戳（1579084800000L）避免时区/当前时间导致偶发失败。
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
        assertFalse(filter.hasSearchText)
        assertTrue(filter.matches(defaultList))
    }

    @Test
    fun `blank search text does not filter`() {
        val filter = LogFilter(searchText = "   ")
        assertFalse(filter.hasSearchText)
        assertTrue(filter.matches(defaultList))
    }

    // -------------------------------------------------- 单条件（全字段）

    @Test
    fun `single token matches anywhere in combined text`() {
        // 提交说明
        assertTrue(LogFilter(searchText = "login").matches(defaultList))
        // 作者字段
        assertTrue(LogFilter(searchText = "alice").matches(defaultList))
        // 变更路径字段
        assertTrue(LogFilter(searchText = "Util.java").matches(defaultList))
        // 修订号字段
        assertTrue(LogFilter(searchText = "42").matches(defaultList))
        // 日期文本字段（格式 yyyy-MM-dd HH:mm，本地时区）
        assertTrue(LogFilter(searchText = "2020-01-15").matches(defaultList))
    }

    @Test
    fun `non matching token is rejected`() {
        assertFalse(LogFilter(searchText = "nonexistent").matches(defaultList))
    }

    @Test
    fun `plain number is a text match not a range`() {
        // 版本号只是普通文本：42 在字段里 → 命中；43 不在 → 不命中
        assertTrue(LogFilter(searchText = "42").matches(defaultList))
        assertFalse(LogFilter(searchText = "43").matches(defaultList))
        // 含 '-' 的普通文本同样按文本处理（"2026-10" 不会被解析成任何区间）
        val list = changeList(number = 20261001, comment = "2026-10 release")
        assertTrue(LogFilter(searchText = "2026-10").matches(list))
        assertFalse(LogFilter(searchText = "2026-11").matches(list))
    }

    // -------------------------------------------------- 多词组合（AND）

    @Test
    fun `multiple tokens are ANDed`() {
        assertTrue(LogFilter(searchText = "login ABC-123").matches(defaultList))
        // 缺任何一个词都不命中
        assertFalse(LogFilter(searchText = "login missingword").matches(defaultList))
        assertFalse(LogFilter(searchText = "missingword ABC-123").matches(defaultList))
    }

    @Test
    fun `extra whitespace between tokens is ignored`() {
        assertTrue(LogFilter(searchText = "  login   ABC-123  ").matches(defaultList))
    }

    // -------------------------------------------------- 大小写

    @Test
    fun `matching is case insensitive`() {
        assertTrue(LogFilter(searchText = "LOGIN").matches(defaultList))
        assertTrue(LogFilter(searchText = "Login").matches(defaultList))
        assertTrue(LogFilter(searchText = "login").matches(defaultList))
        // 大小写不敏感同样作用于作者与路径字段
        assertTrue(LogFilter(searchText = "ALICE").matches(defaultList))
        assertTrue(LogFilter(searchText = "main.JAVA").matches(defaultList))
    }

    // -------------------------------------------------- 辅助

    private companion object {
        /** 固定日期，避免时区/当前时间导致的偶发失败。 */
        val DATE_2020_01_15: Date = Date(1579084800000L) // 2020-01-15T16:00:00Z
    }
}
