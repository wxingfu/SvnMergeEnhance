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
 * ## 为什么不用「直接继承 DynamicBundle」的默认行为
 *
 * `com.intellij.DynamicBundle` 解析资源时用的是 **`Locale.getDefault()`（操作系统语言）**，
 * 而 **IDEA 2022.1 没有 UI 语言包机制**（`since-build=221`，界面恒英文）。
 * 实测（中文 Windows + 英文 IDE）：直接提供 `_zh_CN` 资源会解析成中文，出现
 * 「英文 IDE 界面里只有本插件弹中文」的不一致（svn4idea 自带 bundle 也没有 `_zh_CN`，所以它是英文）。
 *
 * ## 适配策略（支持后续安装中文/其它语言插件）
 *
 * 用平台扩展点判断"是否真的装了语言包"：
 * - `findLanguageBundle()` 非 null ⇒ 已装语言包，使用 `DynamicBundle.getLocale()`
 *   选具体语言（装了中文包 → `_zh_CN`，装了日文包 → `_ja`，没有对应文件则自动回退英文基包）；
 * - 为 null ⇒ **强制英文**，与英文 IDE 界面保持一致。
 *
 * 这样：没装语言包时永远不会"冒中文"；装了语言包后自动跟随，无需改代码。
 * 自带 UTF-8 解码（IntelliJ 的 properties 是 UTF-8，`ResourceBundle` 默认按 ISO-8859-1 读）。
 *
 * ## `findLanguageBundle()` 的跨版本桥接（2026.x 搬迁）
 *
 * 该方法在 2026.x 从 `DynamicBundle`（静态）搬到了 `com.intellij.l10n.LocalizationUtil`（实例），
 * 且**旧入口被直接移除**（官方 Plugin Verifier 对 IC-2026.2.3 报 `NoSuchMethodError`）。
 * 直接用旧入口编译出的字节码在 2026.2.3 上一调就崩，故按"新平台优先"反射两条路径；
 * 两条都不可用、或确实没有语言包时返回 null。返回值只用于 `!= null` 判定，不接触其类型。
 */
object EnhanceMergeBundle {

    private const val BASE_NAME = "messages.EnhanceMergeBundle"

    /** 2026.x 起承载 `findLanguageBundle()` 的 Kotlin object 类名。 */
    private const val LOCALIZATION_UTIL_CLASS = "com.intellij.l10n.LocalizationUtil"

    /** Kotlin object 的单例字段名。 */
    private const val INSTANCE_FIELD = "INSTANCE"

    /** 两个平台版本上同名的探测方法名。 */
    private const val FIND_LANGUAGE_BUNDLE_METHOD = "findLanguageBundle"

    private val LOG = Logger.getInstance(EnhanceMergeBundle::class.java)

    /** 平台是否装载了语言包；为 false 时强制英文，避免与英文 IDE 界面不一致。 */
    private val languagePackInstalled: Boolean by lazy {
        val active = findLanguageBundle() != null
        val platformLocale = runCatching { DynamicBundle.getLocale() }.getOrNull()
        LOG.info(
            "EnhanceMerge i18n: languagePack=$active, platformLocale=$platformLocale, " +
                "defaultLocale=${Locale.getDefault()}, selected=${if (active) platformLocale else Locale.ROOT}"
        )
        active
    }

    /**
     * 探测平台语言包（详见类文档「跨版本桥接」）：
     * - 2026.x：`com.intellij.l10n.LocalizationUtil.INSTANCE.findLanguageBundle()`；
     * - 2022.1 ~ 2025.x：`DynamicBundle.findLanguageBundle()`（静态）。
     *
     * 返回 null = 两条路都不可用，或确实没有安装语言包（两种情形对本类的含义相同：强制英文）。
     */
    private fun findLanguageBundle(): Any? {
        val fromNewApi = try {
            val utilClass = Class.forName(LOCALIZATION_UTIL_CLASS)
            val util = utilClass.getDeclaredField(INSTANCE_FIELD).apply { isAccessible = true }.get(null)
            utilClass.getMethod(FIND_LANGUAGE_BUNDLE_METHOD).invoke(util)
        } catch (e: ReflectiveOperationException) {
            null
        } catch (e: LinkageError) {
            // 类在但二进制不兼容（如平台重构了字段/方法签名）
            LOG.warn("LocalizationUtil is present but not usable on this IDE build", e)
            null
        }
        if (fromNewApi != null) return fromNewApi

        return try {
            DynamicBundle::class.java.getMethod(FIND_LANGUAGE_BUNDLE_METHOD).invoke(null)
        } catch (e: ReflectiveOperationException) {
            null
        } catch (e: LinkageError) {
            LOG.warn("DynamicBundle.findLanguageBundle is not usable on this IDE build", e)
            null
        }
    }

    private val bundle: ResourceBundle by lazy { loadBundle() }

    /**
     * 供日期控件等第三方组件使用的有效 locale：与自绘界面文案保持一致
     * （未装语言包时强制英文；装了语言包跟随平台语言）。
     */
    val locale: Locale
        get() = bundle.locale.let { if (it == Locale.ROOT) Locale.ENGLISH else it }

    /**
     * 两步解析，规避 JDK 的一个反直觉行为（已用离线探针实测）：
     *
     * ```
     * getBundle(base, Locale.ENGLISH, loader, control)  默认 locale=zh_CN  -> 返回 _zh_CN（中文）
     * getBundle(base, Locale.ROOT,    loader, control)  默认 locale=zh_CN  -> 返回基包（英文）
     * ```
     *
     * 即：请求英文时 JDK 仍可能通过"默认 locale 回退"拿到中文资源。因此：
     * 1. 未装语言包 → 直接用 `Locale.ROOT`（基包 = 英文）；
     * 2. 装了语言包 → 用平台 locale 精确匹配，**若拿到的语言与目标语言不符**（说明发生了回退），
     *    也退回基包，避免"日文 IDE 显示中文"这类串语种。
     */
    private fun loadBundle(): ResourceBundle {
        val loader = EnhanceMergeBundle::class.java.classLoader
        val desired = if (languagePackInstalled) DynamicBundle.getLocale() else Locale.ROOT
        val base = { ResourceBundle.getBundle(BASE_NAME, Locale.ROOT, loader, Utf8Control) }

        return try {
            val found = ResourceBundle.getBundle(BASE_NAME, desired, loader, Utf8Control)
            if (desired == Locale.ROOT || found.locale.language == desired.language) found else base()
        } catch (t: MissingResourceException) {
            // 指定语言的资源不存在（装了语言包但本插件没有对应翻译）→ 退回英文基包
            LOG.warn("Failed to load EnhanceMerge bundle for $desired, falling back to base bundle", t)
            base()
        }
    }

    /** 触发初始化并返回本地化判定结果，便于启动时在日志中确认实际生效的语言。 */
    fun localizationDiagnostics(): String =
        "languagePack=$languagePackInstalled, bundleLocale=${bundle.locale}"

    fun message(key: String, vararg params: Any?): String {
        val pattern = bundle.getString(key)
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
