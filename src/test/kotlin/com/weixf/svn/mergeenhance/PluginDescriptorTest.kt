package com.weixf.svn.mergeenhance

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.InputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `META-INF/plugin.xml`（插件描述文件）的**结构**回归测试。
 *
 * ## 为什么需要它
 *
 * 插件描述文件里的属性写错，**编译期与单测都发现不了**，只在运行时炸，且往往要用户
 * 打开对应界面才触发（表现为 IDE 弹「插件内部错误」）。本文件守护三类已踩过的坑：
 *
 * 1. **不应再注册 `Configurable`**：设置功能已整体移除（行为改为代码内固定默认值），
 *    若将来重新引入设置页，EP 必须有 `displayName` 或 `key`
 *    （2026-11-01 用户报障）：2024+ 的平台在 `ConfigurableWrapper.getDisplayName()` 里发现
 *    EP 两样都没写时，会 `LOG.error(PluginException("No display name specified in plugin
 *    descriptor XML file for configurable ..."))` → **打开「设置」对话框即报插件错误**。
 *    2022.1 同一段代码只打 DEBUG 日志，故旧版复现不了。
 *    ⚠️ 不能改用 `key`：那会让平台按 `DynamicBundle`（未装语言包时 = 操作系统语言）解析，
 *    与本插件「按 IDE 语言包判定、对未知语言强制回退英文」的语言策略冲突。
 * 2. **2022.1 不认识的元素会让插件直接加载失败**：`license` / `support` / `privacy-policy`
 *    在 IDEA 2022.1 上报 `Unknown element: license` → 插件加载失败（本项目 since-build=221，必须兼容）。
 * 3. **`since-build` 不得丢失**：丢失会被 IPGP/平台按默认值改写，导致兼容区间失真。
 */
class PluginDescriptorTest {

    private fun descriptor(): Element {
        val stream: InputStream = assertNotNull(
            javaClass.classLoader.getResourceAsStream("META-INF/plugin.xml"),
            "找不到 META-INF/plugin.xml（打包时是否漏掉了 resources 目录？）",
        )
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        val doc = stream.use { factory.newDocumentBuilder().parse(it) }
        return doc.documentElement
    }

    private fun Element.childElementList(): List<Element> {
        val result = mutableListOf<Element>()
        val nodes = childNodes
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            if (node.nodeType == Node.ELEMENT_NODE) result += node as Element
        }
        return result
    }

    private fun Element.childElements(name: String): List<Element> =
        childElementList().filter { it.nodeName == name }

    /** 递归查找所有同名后代（DOM 的 `nodeName` 不支持通配符，只能自己遍历）。 */
    private fun Element.allElements(name: String): List<Element> {
        val result = mutableListOf<Element>()
        for (child in childElementList()) {
            if (child.nodeName == name) result += child
            result += child.allElements(name)
        }
        return result
    }

    @Test
    fun `no configurable extension is registered`() {
        // 设置功能已整体移除（行为改为代码内固定默认值），因此**不应**再注册 Configurable。
        // 保留本用例是为了防止将来误加回设置页时又踩「EP 缺 displayName」的坑。
        val configurables = descriptor().allElements("applicationConfigurable") +
            descriptor().allElements("projectConfigurable")
        assertTrue(
            configurables.isEmpty(),
            "插件不再提供设置页，不应注册 Configurable：${configurables.map { it.getAttribute("id") }}",
        )
    }

    @Test
    fun `any configurable extension would declare a display name or a key`() {
        // 规范守护：若将来真的重新引入设置页，EP 必须写 displayName 或 key，
        // 否则 2024+ 平台在打开「设置」对话框时会抛 PluginException（用户可见的红色报错）。
        val configurables = descriptor().allElements("applicationConfigurable") +
            descriptor().allElements("projectConfigurable")
        for (configurable in configurables) {
            val id = configurable.getAttribute("id")
            val hasName = configurable.hasAttribute("displayName") && configurable.getAttribute("displayName").isNotBlank()
            val hasKey = configurable.hasAttribute("key") && configurable.getAttribute("key").isNotBlank()
            assertTrue(
                hasName || hasKey,
                "Configurable '$id' 既没有 displayName 也没有 key —— " +
                    "2024+ 平台会抛 PluginException(\"No display name specified in plugin descriptor XML file\"): " +
                    "打开设置对话框即报插件错误",
            )
        }
    }

    @Test
    fun `descriptor has no elements unknown to IDEA 2022_1`() {
        // 2022.1 对未知元素直接让插件加载失败（实测：SEVERE - PluginManager - Unknown element: license）
        for (forbidden in listOf("license", "support", "privacy-policy")) {
            val found = descriptor().allElements(forbidden)
            assertTrue(
                found.isEmpty(),
                "plugin.xml 含 IDEA 2022.1 不认识的元素 <$forbidden> → 插件加载失败（since-build=221 必须兼容）",
            )
        }
    }

    @Test
    fun `descriptor keeps the since build and the required dependencies`() {
        val root = descriptor()
        val ideaVersion = assertNotNull(
            root.allElements("idea-version").firstOrNull(),
            "plugin.xml 缺少 <idea-version>（since-build 由 build.gradle.kts 的 patchPluginXml 写入）",
        )
        assertEquals("221", ideaVersion.getAttribute("since-build"))

        val depends = root.childElements("depends").map { it.textContent.trim() }
        for (required in listOf("com.intellij.modules.vcs", "Subversion")) {
            assertTrue(required in depends, "plugin.xml 缺少 <depends>$required</depends>")
        }
    }
}
