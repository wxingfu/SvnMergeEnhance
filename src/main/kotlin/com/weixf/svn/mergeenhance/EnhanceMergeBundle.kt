package com.weixf.svn.mergeenhance

import com.intellij.DynamicBundle
import com.intellij.openapi.diagnostic.Logger
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.text.MessageFormat
import java.util.Locale
import java.util.MissingResourceException
import java.util.PropertyResourceBundle
import java.util.ResourceBundle

/**
 * 本插件自绘界面的文案 bundle（项目规则：用户可见字符串一律走资源文件，不硬编码）。
 *
 * 复用原对话框（`SelectBranchPopup`、`SvnIntegrateChangesTask` 等）的文案由 IDEA 自带 bundle
 * 提供，无需在此维护。
 *
 * ## 语言判定：**零反射**，只用公开 API
 *
 * 2026-11-01 改造：此前靠**反射**探测"平台是否装了语言包"
 * （`DynamicBundle.findLanguageBundle()` / `com.intellij.l10n.LocalizationUtil.findLanguageBundle()`），
 * 再据此决定用 `_zh_CN` 还是英文基包。两条问题：
 *
 * 1. **反射是 Marketplace 审核的高风险项**（本项目已因此做到"零反射"，不应再留唯一一处）；
 * 2. **2026.x 上两条反射路径都不通**（实测 `javap`）：
 *    - `DynamicBundle.findLanguageBundle()` 在 2026.2.3 **已被移除**；
 *    - `com.intellij.l10n.LocalizationUtil` 在 2026.2.3 **根本没有** `findLanguageBundle` 方法
 *      （只有 `getDefaultLocale()` / `getLocale(boolean)` / `getLocaleOrNullForDefault()`）。
 *    → `findLanguageBundle()` 恒返回 null → `languagePackInstalled=false`
 *    → **2026.x 上"语言包感知"其实已静默失效，恒走英文基包**。
 *
 * 现在的做法：**直接读 IDE 当前界面语言**——`DynamicBundle.getLocale()`。
 * 该方法在 **2022.1 与 2026.2.3 都是 `public static`**（javap 核对），且两端语义一致：
 * - 2022.1：`Locale.forLanguageTag(ourLangTag)`（`ourLangTag` = 平台语言包语言标签）；
 * - 2026.2.3：`LocalizationUtil.INSTANCE.getLocale()`。
 * 即"装了中文语言包的 IDE → 中文；否则 → 英文"，**无需反射、无需猜测**。
 *
 * ## 两个必须保留的坑位处理
 *
 * 1. **英文一律用 [Locale.ROOT] 请求**：JDK `ResourceBundle.getBundle` 的候选链里含
 *    "系统默认 locale"——中文 Windows 上请求 `Locale.ENGLISH` 仍会命中 `_zh_CN`
 *    （JDK 的经典 fallback）。只有 `Locale.ROOT` 的候选链是纯净的（只有基包）。
 * 2. **回退要校验语言**：装了日文语言包但本插件没有 `_ja` 时，JDK 会 fallback 到系统 locale
 *    → 拿到 `_zh_CN`（串语种）。故取到 bundle 后校验 `found.locale.language == target.language`，
 *    不符则回退英文基包。
 *
 * ## 为什么不用平台的 `<resource-bundle>`
 *
 * 声明 `<resource-bundle>` 会让平台改用 `DynamicBundle` 的 `Locale.getDefault()`（**操作系统语言**）
 * 解析，在"中文系统 + 英文 IDE（2022.1 无语言包机制，界面恒英文）"时会出现
 * 「英文 IDE 界面里只有本插件是中文」的不一致（svn4idea 自带 bundle 也没有 `_zh_CN`，它是英文）。
 * 故本插件**自行判定 locale**，不声明 `<resource-bundle>`、也不在 EP 上写 `key`/`displayName`。
 */
object EnhanceMergeBundle {

    private const val BASE_NAME = "messages.EnhanceMergeBundle"

    /** 基准（英文）语言：IDE 语言为它、或无法判定时，一律使用英文基包。 */
    private const val BASE_LANGUAGE = "en"

    private val LOG = Logger.getInstance(EnhanceMergeBundle::class.java)

    /** 生效的文案包（懒加载：首次用到时才读平台语言，避免在类初始化期触碰平台 API）。 */
    private val bundle: ResourceBundle by lazy { loadBundleFor(currentIdeLocale()) }

    /**
     * 供日期控件等第三方组件使用的有效 locale：与自绘界面文案保持一致
     * （未装语言包时为英文；装了语言包跟随 IDE 语言）。
     */
    val locale: Locale
        get() = bundle.locale.let { if (it == Locale.ROOT) Locale.ENGLISH else it }

    /**
     * IDE 当前界面语言（语言包语言）。
     *
     * 用的是**公开 API**（`DynamicBundle.getLocale()`，2022.1 ~ 2026.x 均有），
     * 全程零反射。理论上它不会抛异常，但 2022.1 的实现会对未初始化的语言标签插入 null 检查
     * （`Locale.forLanguageTag(null)` → NPE），故兜一个 `RuntimeException` → 退回基包（英文）。
     */
    private fun currentIdeLocale(): Locale = try {
        DynamicBundle.getLocale()
    } catch (e: RuntimeException) {
        LOG.warn("DynamicBundle.getLocale() is not usable on this IDE build, falling back to the base bundle", e)
        Locale.ROOT
    }

    /**
     * 纯函数：IDE 语言 → 向 `ResourceBundle` 请求的 locale。
     *
     * - 英文（`en`）或无法判定（`Locale.ROOT`，`language` 为空）→ [Locale.ROOT]（**只可能命中基包**）；
     * - 其它语言 → 原样请求（命中 `_zh_CN` 等变体）。
     *
     * 为什么英文不能用 `Locale.ENGLISH` 请求：JDK 的候选链含系统默认 locale，
     * 中文 Windows 上会命中 `_zh_CN`（串语种）。详见类文档「坑位处理 1」。
     */
    internal fun selectLocale(ideLocale: Locale): Locale {
        val language = ideLocale.language
        return if (language.isEmpty() || language == BASE_LANGUAGE) Locale.ROOT else ideLocale
    }

    /**
     * 纯函数：取到的 bundle 语言是否就是请求的语言。
     *
     * 用于拦截 JDK 的"fallback 到系统 locale"——请求 `ja` 而系统是 `zh_CN` 时，
     * JDK 会返回 `_zh_CN`（语言不符 → 串语种），此时应回退英文基包。
     */
    internal fun isLocaleAccepted(found: Locale, target: Locale): Boolean =
        found.language == target.language

    /**
     * 按给定 IDE 语言加载文案包（**测试入口**：可注入确定的 locale，不必依赖平台状态）。
     *
     * @param ideLocale IDE 当前界面语言（语言包语言）
     */
    internal fun loadBundleFor(ideLocale: Locale): ResourceBundle {
        val loader = EnhanceMergeBundle::class.java.classLoader
        val target = selectLocale(ideLocale)
        val base = { ResourceBundle.getBundle(BASE_NAME, Locale.ROOT, loader, Utf8Control) }
        return try {
            val found = ResourceBundle.getBundle(BASE_NAME, target, loader, Utf8Control)
            if (isLocaleAccepted(found.locale, target)) found else base()
        } catch (e: MissingResourceException) {
            // 指定语言的资源不存在（装了语言包但本插件没有对应翻译）→ 退回英文基包
            LOG.warn("Failed to load EnhanceMerge bundle for $target, falling back to base bundle", e)
            base()
        }
    }

    /** 触发初始化并返回本地化判定结果，便于启动时在日志中确认实际生效的语言。 */
    fun localizationDiagnostics(): String =
        "ideLocale=${currentIdeLocale()}, bundleLocale=${bundle.locale}"

    fun message(key: String, vararg params: Any?): String {
        // ⚠️ 缺 key 不让异常冒出去（2026-11-01 修复 P2-2）：`message()` 会在 Swing 事件回调里被调用，
        // `MissingResourceException` 冒出去会变成插件内部错误日志，用户看到的是界面半途出错。
        // 回退显示 key 本身，一眼能看出漏了哪个文案。
        val pattern = try {
            bundle.getString(key)
        } catch (e: MissingResourceException) {
            LOG.warn("Missing bundle key: $key")
            return key
        }
        return if (params.isEmpty()) pattern else MessageFormat.format(pattern, *params)
    }

    /** IntelliJ 的 properties 资源是 UTF-8，标准 `ResourceBundle` 默认按 ISO-8859-1 读取，需自行解码。 */
    private object Utf8Control : ResourceBundle.Control() {
        override fun newBundle(
            baseName: String,
            locale: Locale,
            format: String,
            loader: ClassLoader,
            reload: Boolean,
        ): ResourceBundle? {
            if (format != "java.properties") {
                return super.newBundle(baseName, locale, format, loader, reload)
            }
            val resourceName = toBundleName(baseName, locale).replace('.', '/') + ".properties"
            val stream = loader.getResourceAsStream(resourceName) ?: return null
            return stream.use { PropertyResourceBundle(InputStreamReader(it, StandardCharsets.UTF_8)) }
        }
    }
}
