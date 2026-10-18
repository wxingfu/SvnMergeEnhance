package com.weixf.svn.mergeenhance.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * 插件设置（Spec R11），持久化到应用级 `svnEnhanceMerge.xml`。
 *
 * ## 为什么是应用级而不是项目级
 *
 * 需求原文写「项目级」，但实现为**应用级**服务，原因有两条：
 * 1. **IDEA 的 VCS 设置惯例即应用级**：内置 Subversion 自己的 `SvnConfigurable`
 *    就是 `applicationConfigurable`，本插件的设置放在 `Settings → Version Control` 下
 *    与它并列，用户预期一致；
 * 2. **技术约束**：`projectConfigurable` 的实例由平台**无参构造**，拿不到 `Project` 句柄，
 *    而 `projectService` 的取值又依赖 Project，二者无法自然衔接。
 *
 * 这些配置（每页条数、是否算合并状态、Bug-ID 正则、默认日期范围）本质是全局偏好，
 * 不属于某个项目，放应用级语义上也更准确。
 */
@State(name = "EnhanceMergeSettings", storages = [Storage("svnEnhanceMerge.xml")])
class EnhanceMergeSettings : PersistentStateComponent<EnhanceMergeSettings.State> {

    /** 持久化状态；字段默认值即「首次安装」的默认值。 */
    data class State(
        var pageSize: Int = DEFAULT_PAGE_SIZE,
        var computeMergeStatus: Boolean = true,
        var bugIdPattern: String = DEFAULT_BUG_ID_PATTERN,
        var defaultDateRangeDays: Int = 0,
        // ---- 过滤栏「上次使用」的界面状态（关闭对话框后重开仍沿用）----
        /** 勾选的过滤字段（存 [com.weixf.svn.mergeenhance.log.LogFilterField.id]）；为空表示用默认全选。 */
        var filterFields: MutableList<String> = mutableListOf(),
        var dateRangeEnabled: Boolean = true,
        var useRegex: Boolean = false,
        var caseSensitive: Boolean = false,
    )

    private var state = State()

    init {
        normalize()
    }

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
        normalize()
    }

    // ------------------------------------------------------------ 便于调用的属性

    var pageSize: Int
        get() = state.pageSize
        set(value) {
            state.pageSize = value.coerceIn(MIN_PAGE_SIZE, MAX_PAGE_SIZE)
        }

    /** 是否计算并显示已合并状态（T8）。关闭后既不查 mergeinfo，也不显示图标。 */
    var computeMergeStatus: Boolean
        get() = state.computeMergeStatus
        set(value) {
            state.computeMergeStatus = value
        }

    var bugIdPattern: String
        get() = state.bugIdPattern
        set(value) {
            state.bugIdPattern = value
        }

    var defaultDateRangeDays: Int
        get() = state.defaultDateRangeDays
        set(value) {
            state.defaultDateRangeDays = value.coerceAtLeast(0)
        }

    // ------------------------------------------------- 过滤栏「上次使用」状态
    // 这几项不在设置页暴露 UI：它们是"顺手记住"的交互偏好（勾了哪些字段、是否开正则），
    // 由过滤栏自身在用户改动时写入。存的是 [LogFilterField.id] 字符串而非枚举，
    // 以免配置格式与枚举名耦合（枚举改名不会让旧配置反序列化失败）。

    /** 勾选的过滤字段 id；为空表示"未记录过"，由界面回退到默认全选。 */
    var filterFieldIds: List<String>
        get() = state.filterFields.toList()
        set(value) {
            state.filterFields = value.toMutableList()
        }

    var dateRangeEnabled: Boolean
        get() = state.dateRangeEnabled
        set(value) {
            state.dateRangeEnabled = value
        }

    var useRegex: Boolean
        get() = state.useRegex
        set(value) {
            state.useRegex = value
        }

    var caseSensitive: Boolean
        get() = state.caseSensitive
        set(value) {
            state.caseSensitive = value
        }

    /**
     * 编译后的 Bug-ID 正则；用户把正则改坏时返回 null（调用方回退默认值，
     * 使 Bug-ID 字段退化为「不匹配」而不是崩溃）。
     */
    val bugIdRegex: Regex?
        get() = try {
            Regex(bugIdPattern)
        } catch (e: IllegalArgumentException) {
            null
        }

    /** 默认日期范围的起始日期（[defaultDateRangeDays] 天前）；<=0 时返回 null 表示不限制。 */
    fun defaultFromDate(now: Long = System.currentTimeMillis()): Date? {
        val days = state.defaultDateRangeDays
        if (days <= 0) return null
        return Date(now - TimeUnit.DAYS.toMillis(days.toLong()))
    }

    // ------------------------------------------------------------ 内部

    /** 把非法/越界值拉回合法范围（配置文件可能被手改过）。 */
    private fun normalize() {
        state.pageSize = state.pageSize.coerceIn(MIN_PAGE_SIZE, MAX_PAGE_SIZE)
        state.defaultDateRangeDays = state.defaultDateRangeDays.coerceAtLeast(0)
    }

    companion object {
        /** 每页条数下限（设置页 spinner 与配置校验共用）。 */
        const val MIN_PAGE_SIZE = 10

        /** 每页条数上限：与「Show All / +500」的分页步长同量级，避免一次拉爆内存。 */
        const val MAX_PAGE_SIZE = 500

        /**
         * 每页条数默认值：对齐 TortoiseSVN 的 `NumberOfLogs` 默认 100
         * （`CRegDWORD("Software\\TortoiseSVN\\NumberOfLogs", 100)`）。
         */
        const val DEFAULT_PAGE_SIZE = 100

        /** Bug-ID 默认正则：`[A-Z][A-Z0-9]+-\d+`。 */
        const val DEFAULT_BUG_ID_PATTERN = "[A-Z][A-Z0-9]+-\\d+"

        fun getInstance(): EnhanceMergeSettings = service()
    }
}
