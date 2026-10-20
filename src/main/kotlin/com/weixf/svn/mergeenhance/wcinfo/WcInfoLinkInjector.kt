package com.weixf.svn.mergeenhance.wcinfo

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.labels.LinkLabel
import com.intellij.ui.components.labels.LinkListener
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowId
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.weixf.svn.mergeenhance.EnhanceMergeAction
import com.weixf.svn.mergeenhance.EnhanceMergeBundle
import org.jetbrains.idea.svn.dialogs.WCInfo
import java.awt.Component
import java.awt.Container
import java.awt.event.ContainerEvent
import java.awt.event.ContainerListener

/**
 * 把「Enhance Merge…」注入到 IDEA 内置 SVN 插件的
 * 「Version Controls」工具窗 →「Subversion Working Copies Information」标签页中，
 * 位置紧跟「Merge From…」下方。这是本插件的**唯一入口**。
 *
 * ## 为什么用「组件插入」而不是「HTML 注入」
 *
 * 前两版都在改 `WorkingCopyInfoPanel` 的 `HTMLDocument`（v1 `insertAfterEnd(lastTr,…)` 链接根本不出现；
 * v2 `insertBeforeEnd(table,…)` 能插入但**每 2s 兜底定时器又插一次，最终出现 4 个链接**）。
 * 用 JDK `HTMLDocument` 做的对照探针显示：插入本身没问题，但 **href 属性只挂在
 * `HTML.Tag.A` 的嵌套 AttributeSet 上、且挂在 content 叶子元素而非 `<a>` 元素**，
 * 属性检测极易失配 —— 一旦失配就退化为无限重复插入。而 HTML 路径还依赖 svn4idea 的内部结构，跨版本脆弱。
 *
 * 因此改为在原版 `CopiesPanel` 的纵向容器（`JBPanel(VerticalLayout(8))`）里，
 * 直接在每个 `WorkingCopyInfoPanel` 之后插入一个真实的 `LinkLabel` 组件：
 * - **幂等性由组件身份保证**（看"下一个兄弟是否是我们自己的 LinkLabel"），不依赖任何属性解析；
 * - 不依赖 HTML 结构与 svn4idea 内部实现细节，跨版本稳定；
 * - 点击、悬停、手型光标、主题配色全部由平台 `LinkLabel` 提供。
 *
 * ## 自愈策略（原版每次刷新都会 `removeAll()` 重建面板）
 * 1. 容器上挂 `ContainerListener`：子组件增删即重新校正（主路径）；
 * 2. 工具窗弹出时立即扫描一次（`ToolWindowManagerListener`）；
 * 3. 2s 兜底定时器（仅在工具窗可见时遍历；因幂等，重复执行无副作用）。
 */
class WcInfoLinkInjector : StartupActivity.DumbAware {

    override fun runActivity(project: Project) {
        LinkInstaller(project)
    }
}

/**
 * 实际干活的安装器（由 [WcInfoLinkInjector] 在每个项目启动时创建一个实例）。
 *
 * 拆成两个类的原因：`StartupActivity` 的实例由平台创建、生命周期不受我们控制；
 * 而本类持有 Timer / messageBus 订阅，必须在项目关闭时释放 —— 放在独立类里便于
 * 明确其disposable 归属。
 */
private class LinkInstaller(private val project: Project) : Disposable {

    companion object {
        private val LOG = Logger.getInstance(LinkInstaller::class.java)

        /** svn4idea 内部面板类的运行时类名（该类非公开 API，仅按名字匹配） */
        private const val PANEL_CLASS_NAME = "org.jetbrains.idea.svn.dialogs.WorkingCopyInfoPanel"

        /** 面板链接文字（走本地化 bundle，不硬编码）。 */
        private val LINK_TEXT: String
            get() = EnhanceMergeBundle.message("link.text.enhance.merge")

        private const val KEY_OUR_LABEL = "svn.enhanceMerge.label"
        private const val KEY_CONTAINER_HOOKED = "svn.enhanceMerge.containerHooked"
        private const val KEY_LOGGED = "svn.enhanceMerge.logged"

        /** 面板上取当前工作副本信息的取值方法名（svn4idea 内部，非公开 API）。 */
        private const val WC_INFO_GETTER = "getInfo"

        /**
         * 兜底轮询间隔（毫秒）。
         *
         * 2s 是权衡值：更快会让空闲时反复遍历组件树，更慢会让"面板已打开但链接迟到"更明显。
         * 因注入幂等（见类文档），重复执行无副作用。
         */
        private const val POLL_INTERVAL_MS = 2000
    }

    /**
     * 兜底轮询。
     *
     * 用平台的 [Alarm] 而**不是** `javax.swing.Timer`：官方明确要求禁用 Swing Timer——
     * 它不受 [Disposable] 管理（项目关闭后仍可能触发）、对模态状态无感知。
     * 这里以 `this`（[Disposable]）为父，dispose 时未触发的请求自动作废。
     */
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    init {
        // 启动时确认一次本地化判定（未装语言包时应为 bundleLocale=en）
        LOG.info("EnhanceMerge i18n state: ${EnhanceMergeBundle.localizationDiagnostics()}")

        // 本类即 Disposable：注册到 project，dispose 时 Alarm 自动解绑、链接自动摘除
        Disposer.register(project, this)

        project.messageBus.connect(this).subscribe(
            ToolWindowManagerListener.TOPIC,
            object : ToolWindowManagerListener {
                override fun toolWindowShown(toolWindow: ToolWindow) {
                    if (toolWindow.id == ToolWindowId.VCS) scanAndInject()
                }
            },
        )

        alarm.addRequest({ onTimerTick() }, POLL_INTERVAL_MS)
    }

    /** 项目关闭：官方要求插件清理自己挂到界面上的东西（详见 [removeInjectedLinks]）。 */
    override fun dispose() {
        removeInjectedLinks()
    }

    private fun onTimerTick() {
        if (project.isDisposed) return
        scanAndInject()
        // 排下一次轮询（Alarm 无「周期执行」API，故自续）
        alarm.addRequest({ onTimerTick() }, POLL_INTERVAL_MS)
    }

    /**
     * 项目关闭时把自己注入的链接摘掉。
     *
     * 官方要求插件清理自己挂到界面上的东西。虽然工具窗内容通常随窗口一起销毁，
     * 但 SVN 面板的纵向容器是长寿命的，会一直持有这些 `LinkLabel`
     * （连同 client property 与其背后的项目引用）。
     *
     * ⚠️ **不能在这里用 `project.isDisposed` 提前返回**（以前正是这么写的，等于永不清理）：
     * 2022.1 SDK 的 `ComponentManagerImpl.dispose()` 字节码（已用 javap 核对）先把容器状态
     * 置为 `DISPOSE_IN_PROGRESS`/`DISPOSED`，**之后**才 `Disposer.dispose()` 子节点——
     * 也就是说本回调被执行时 `project.isDisposed()` 恒为 true，加了守卫就一次都不会清理。
     *
     * 全程 try/catch：IDE 关闭过程中 Swing 组件可能已被拆除，或 `ToolWindowManager.getInstance()`
     * 对已释放的 project 抛 `AlreadyDisposedException`（`RuntimeException`），此时清理失败
     * 不应影响关闭流程（组件反正也要被销毁了）。
     */
    private fun removeInjectedLinks() {
        val cleanup = Runnable {
            try {
                val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(ToolWindowId.VCS)
                if (toolWindow != null) {
                    val hosts = mutableListOf<Container>()
                    for (content in toolWindow.contentManager.contents) {
                        collectLabelHosts(content.component, hosts)
                    }
                    for (host in hosts) {
                        val own = host.components.filter { isOurLabel(it) }
                        if (own.isEmpty()) continue
                        own.forEach { host.remove(it) }
                        host.revalidate()
                        host.repaint()
                    }
                }
            } catch (e: Exception) {
                LOG.warn("Failed to remove injected Enhance Merge links on dispose", e)
            }
        }
        // 已在 EDT 时同步执行：IDE 退出阶段排进事件队列的请求可能永远不会被调度，
        // 那就等于没清理。项目关闭走 EDT，这里是主路径；非 EDT 时仍走 invokeLater
        // （Swing 组件只能在 EDT 上动）。
        val app = ApplicationManager.getApplication()
        if (app.isDispatchThread) {
            cleanup.run()
        } else {
            // 官方要求显式带 ModalityState。这里用 `any()`（而不是默认状态）：
            // 清理动作要在**任何**模态状态下都能跑，否则 IDE 退出阶段残留注入的链接摘不掉。
            // 刻意**不**加 `project.disposed` 过期条件——项目已关闭时链接同样需要摘除。
            app.invokeLater(cleanup, ModalityState.any())
        }
    }

    /** 收集"直接包含我们自己的 LinkLabel"的容器。 */
    private fun collectLabelHosts(component: Component?, acc: MutableList<Container>) {
        if (component !is Container) return
        if (component.components.any { isOurLabel(it) }) {
            acc.add(component)
            return
        }
        for (child in component.components) collectLabelHosts(child, acc)
    }

    /**
     * 扫描「Version Controls」工具窗，找到承载 WorkingCopyInfoPanel 的容器并校正链接。
     *
     * 必须在 EDT 执行：后续要 add/remove Swing 组件。
     * `assertIsDispatchThread()` 是**主动断言**而非防御——三个入口（ContainerListener、
     * ToolWindowManagerListener、Timer）理论都应在 EDT，若断言失败说明有入口被接到了错误线程，
     * 应立刻暴露而不是放任竞态。
     * 工具窗不可见时直接返回：不可见时面板尚未/已不再构建，扫描纯属浪费（兜底定时器的主要过滤）。
     */
    private fun scanAndInject() {
        ApplicationManager.getApplication().assertIsDispatchThread()
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(ToolWindowId.VCS) ?: return
        if (!toolWindow.isVisible) return

        // 一个工具窗可有多个 content（例如同时打开多个项目的 VCS 标签页），逐个处理
        for (content in toolWindow.contentManager.contents) {
            val root = content.component
            val containers = mutableListOf<Container>()
            collectHostContainers(root, containers)
            containers.forEach(::ensureLinks)
        }
    }

    /**
     * 收集"直接子组件里含 WorkingCopyInfoPanel"的容器。
     *
     * 只找**直接父容器**（找到即 `break`）：链接要插在面板旁边，插到更上层或更下层都不对。
     * 同层出现多个面板时只记一次容器（容器会在 `ensureLinks` 内统一处理所有面板），
     * 避免同一容器被重复加入待处理列表。
     */
    private fun collectHostContainers(component: Component?, acc: MutableList<Container>) {
        if (component !is Container) return
        for (i in 0 until component.componentCount) {
            if (isWcInfoPanel(component.getComponent(i))) {
                acc.add(component)
                break
            }
        }
        for (child in component.components) collectHostContainers(child, acc)
    }

    private fun isWcInfoPanel(component: Component?): Boolean =
        component != null && component.javaClass.name == PANEL_CLASS_NAME

    private fun isOurLabel(component: Component?): Boolean =
        component is LinkLabel<*> && component.getClientProperty(KEY_OUR_LABEL) != null

    /**
     * 保证"每个 WorkingCopyInfoPanel 之后紧跟我们的 LinkLabel"。
     * 幂等判定只看向下一个兄弟是否是自己的组件，不做任何文本/属性解析。
     */
    private fun ensureLinks(container: Container) {
        hookContainer(container)

        var changed = false
        // 自后向前遍历：插入位置是 i+1，倒序可避免已处理项的索引漂移
        for (i in container.componentCount - 1 downTo 0) {
            val panel = container.getComponent(i)
            if (!isWcInfoPanel(panel)) continue

            val next = if (i + 1 < container.componentCount) container.getComponent(i + 1) else null
            if (isOurLabel(next)) continue

            container.add(createLink(panel), i + 1)
            changed = true

            val panelComponent = panel as? javax.swing.JComponent
            if (panelComponent != null && panelComponent.getClientProperty(KEY_LOGGED) == null) {
                panelComponent.putClientProperty(KEY_LOGGED, true)
                LOG.info("Injected Enhance Merge link into Subversion WC info panel: ${resolveWcPath(panel)}")
            }
        }

        if (changed) {
            container.revalidate()
            container.repaint()
            // 校验用日志：正常情况下每个工作副本面板恰好对应 1 个链接
            LOG.info("Enhance Merge link ensured: panels=${countPanels(container)}, labels=${countOurLabels(container)}")
        }
    }

    private fun countPanels(container: Container): Int =
        (0 until container.componentCount).count { isWcInfoPanel(container.getComponent(it)) }

    private fun countOurLabels(container: Container): Int =
        (0 until container.componentCount).count { isOurLabel(container.getComponent(it)) }

    private fun createLink(panel: Component): LinkLabel<*> {
        // LinkListener 的第一个参数即被点击的 LinkLabel 自身，直接拿它当分支弹窗的锚点
        val label = LinkLabel<Unit>(LINK_TEXT, null, LinkListener { source, _ -> invokeEnhanceMerge(panel, source) })
        label.putClientProperty(KEY_OUR_LABEL, true)
        // 与原版面板内容左对齐（面板自身有 2,4 内边距），并留出与下一块的间距
        label.border = JBUI.Borders.empty(0, 6, 0, 0)
        return label
    }

    /** 容器子组件增删时自动校正（原版刷新会 removeAll 后重建）。 */
    private fun hookContainer(container: Container) {
        val jc = container as? javax.swing.JComponent ?: return
        if (jc.getClientProperty(KEY_CONTAINER_HOOKED) != null) return
        jc.putClientProperty(KEY_CONTAINER_HOOKED, true)
        container.addContainerListener(object : ContainerListener {
            override fun componentAdded(e: ContainerEvent) {
                // 自己插入的 LinkLabel 会再触发一次本回调：跳过，避免递归
                if (isOurLabel(e.child)) return
                scheduleEnsure(container)
            }

            override fun componentRemoved(e: ContainerEvent) = scheduleEnsure(container)
        })
    }

    private fun scheduleEnsure(container: Container) {
        ApplicationManager.getApplication().invokeLater(
            {
                if (!project.isDisposed && container.isShowing) ensureLinks(container)
            },
            // 官方要求：显式 ModalityState + 过期条件（项目已关闭就不必再注入链接）
            ModalityState.any(),
            project.disposed,
        )
    }

    /**
     * 点击处理：反射取出该面板对应的 WCInfo（纯内存读取，不触发任何 svn 调用），
     * 交给 `EnhanceMergeAction.performFor` 启动流程，并把被点击的链接作为分支弹窗锚点
     * （与原版用 infoPanel 作锚点一致）。
     */
    private fun invokeEnhanceMerge(panel: Component, anchor: Component?) {
        val wcInfo = resolveWcInfo(panel)
        if (wcInfo == null) {
            LOG.warn("Enhance Merge link clicked but WCInfo could not be resolved")
            return
        }
        EnhanceMergeAction().performFor(project, wcInfo, anchor)
    }

    /**
     * 反射读取面板当前展示的 WCInfo。
     *
     * 为什么可以反射：`WorkingCopyInfoPanel` 是 svn4idea 的 Kotlin `internal` 类，
     * 编译期不可引用，但字节码里 `getInfo()` 就是普通 public 方法（Kotlin internal 只做编译期检查）。
     *
     * 为什么安全：这里只做**纯内存读取**（面板已经持有的字段），不触发任何 `svn info`
     * 子进程——这正是不用 `SvnVcs.allWcInfos` 的原因（见 `EnhanceMergeAction.performFor`：
     * 那条路径可能触发工作副本重新探测，进而在 EDT 上同步执行 svn 命令，被平台断言 blame）。
     * 反射失败（原版改了方法名）只丢一次链接点击并记 warn，不会崩。
     */
    private fun resolveWcInfo(panel: Component): WCInfo? {
        return try {
            val method = panel.javaClass.getMethod(WC_INFO_GETTER)
            method.isAccessible = true
            method.invoke(panel) as? WCInfo
        } catch (e: ReflectiveOperationException) {
            // 方法被改名/移除：只丢一次点击，不崩
            LOG.warn("Failed to resolve WCInfo from Subversion WC info panel", e)
            null
        } catch (e: LinkageError) {
            // 类在但签名不兼容（平台重构了 WCInfo）
            LOG.warn("WCInfo accessor is not linkable on this IDE build", e)
            null
        }
    }

    private fun resolveWcPath(panel: Component): String? = resolveWcInfo(panel)?.path
}
