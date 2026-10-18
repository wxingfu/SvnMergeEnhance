package com.weixf.svn.mergeenhance

import com.weixf.svn.mergeenhance.log.LogFilterField
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 文案包（`messages/EnhanceMergeBundle*.properties`）的**结构**回归测试。
 *
 * ## 为什么需要它
 *
 * 过滤栏的文案 key 是**动态拼出来的**（`"filter.field." + field.id`），静态检查抓不到漂移：
 * - 新增一个 [LogFilterField] 却忘了加文案 → 界面抛 `MissingResourceException`；
 * - 文案里写了 `filter.field.bugid`（大小写错） → 同样抛异常；
 * - 中文包漏译某个 key → 装中文语言包的用户界面直接崩。
 *
 * 这里同时校验三件事：key 与枚举双向对齐、中文包与英文包 key 集合一致、取值非空。
 */
class BundleKeysTest {

    private fun loadProperties(name: String): Properties {
        val stream = javaClass.classLoader.getResourceAsStream(name)
        assertNotNull(stream, "找不到资源文件: $name（打包时是否漏进了 messages/ 目录？）")
        val props = Properties()
        // properties 一律按 UTF-8 读（与 EnhanceMergeBundle.Utf8Control 保持一致）
        stream.use { props.load(InputStreamReader(it, StandardCharsets.UTF_8)) }
        return props
    }

    private fun keysOf(props: Properties): Set<String> = props.stringPropertyNames()

    private val base: Properties get() = loadProperties("messages/EnhanceMergeBundle.properties")
    private val zhCn: Properties get() = loadProperties("messages/EnhanceMergeBundle_zh_CN.properties")

    @Test
    fun `every filter field has a bundle key`() {
        val props = base
        for (field in LogFilterField.values()) {
            val key = "filter.field.${field.id}"
            assertTrue(props.containsKey(key), "英文基包缺少 key: $key（LogFilterField.${field.name}）")
            assertTrue(props.getProperty(key).isNotBlank(), "key $key 的文案为空")
        }
    }

    @Test
    fun `no orphan filter field keys`() {
        // 文案里出现的 filter.field.* 必须都能对应到枚举值，防止改名后留下孤儿 key
        val known = LogFilterField.values().map { "filter.field.${it.id}" }.toSet()
        // 这两个是过滤栏的独立开关，不属于 LogFilterField
        val switches = setOf("filter.field.dateRange", "filter.field.regex", "filter.field.caseSensitive")
        val orphans = keysOf(base).filter { it.startsWith("filter.field.") } - known - switches
        assertEquals(emptyList(), orphans, "文案里有对不上枚举的孤儿 key: $orphans")
    }

    @Test
    fun `chinese bundle has exactly the same keys as the base bundle`() {
        val missingInZh = keysOf(base) - keysOf(zhCn)
        val extraInZh = keysOf(zhCn) - keysOf(base)
        assertEquals(emptySet(), missingInZh, "中文包缺少 key: $missingInZh")
        assertEquals(emptySet(), extraInZh, "中文包多出 key（英文包没有，会在切换语言时找不到）: $extraInZh")
    }

    @Test
    fun `chinese bundle has no blank values`() {
        val blank = zhCn.stringPropertyNames().filter { zhCn.getProperty(it).isBlank() }
        assertEquals(emptyList(), blank, "中文包存在空文案: $blank")
    }
}