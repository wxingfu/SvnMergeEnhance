package com.weixf.svn.mergeenhance.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

/**
 * 插件设置，持久化到应用级 `svnEnhanceMerge.xml`。
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
 * ## 字段沿革（第五轮收敛）
 *
 * 保留 `pageSize` / `computeMergeStatus` 两项；`defaultDateRangeDays` / `dateRangeEnabled` /
 * `filterFields` / `useRegex` / `caseSensitive` / `bugIdPattern` 随「过滤栏（By/日期/正则/大小写）」
 * 整体删除（见 `LogFilter` 沿革说明）。**持久化兼容**：平台对已删除字段直接忽略，
 * 旧 `svnEnhanceMerge.xml` 无需迁移。
 */
@State(name = "EnhanceMergeSettings", storages = [Storage("svnEnhanceMerge.xml")])
class EnhanceMergeSettings : PersistentStateComponent<EnhanceMergeSettings.State> {

    /** 持久化状态；字段默认值即「首次安装」的默认值。 */
    data class State(
        var pageSize: Int = DEFAULT_PAGE_SIZE,
        var computeMergeStatus: Boolean = true,
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

    // ------------------------------------------------------------ 内部

    /** 把非法/越界值拉回合法范围（配置文件可能被手改过）。 */
    private fun normalize() {
        state.pageSize = state.pageSize.coerceIn(MIN_PAGE_SIZE, MAX_PAGE_SIZE)
    }

    companion object {
        /** 每页条数下限（设置页 spinner 与配置校验共用）。 */
        const val MIN_PAGE_SIZE = 10

        /** 每页条数上限：与「+500」的分页步长同量级，避免一次拉爆内存。 */
        const val MAX_PAGE_SIZE = 500

        /**
         * 每页条数默认值：对齐 TortoiseSVN 的 `NumberOfLogs` 默认 100
         * （`CRegDWORD("Software\\TortoiseSVN\\NumberOfLogs", 100)`）。
         */
        const val DEFAULT_PAGE_SIZE = 100

        fun getInstance(): EnhanceMergeSettings = service()
    }
}
