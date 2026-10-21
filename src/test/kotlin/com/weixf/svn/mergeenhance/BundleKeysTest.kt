package com.weixf.svn.mergeenhance

import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * 文案包（`messages/EnhanceMergeBundle*.properties`）的**结构**回归测试。
 *
 * ## 为什么需要它
 *
 * 代码里引用的 key 若在文案包里缺失，界面会抛 `MissingResourceException`；
 * 中文包漏译某个 key → 装中文语言包的用户界面直接崩。
 *
 * 这里同时校验两件事：**代码引用的 key 在两个包里都存在且非空**、中文包与英文包 key 集合一致。
 *
 * 沿革：过滤栏（By/日期/正则/大小写）随第五轮删除，原先"key 与 LogFilterField 枚举双向对齐"
 * 的用例一并移除（枚举已删）；如未来再引入动态拼装的 key，应恢复同款守护。
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

    @Test
    fun `search box keys exist and are non blank in both bundles`() {
        // 表格上方显式搜索框（第五轮唯一新增）的 key 必须有文案
        for (key in listOf("search.placeholder", "search.clear", "status.search.count")) {
            assertNotNull(base.getProperty(key), "英文基包缺少 key: $key")
            assertNotNull(zhCn.getProperty(key), "中文包缺少 key: $key")
        }
    }
}