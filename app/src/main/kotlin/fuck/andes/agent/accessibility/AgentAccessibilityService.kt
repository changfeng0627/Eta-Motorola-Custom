package fuck.andes.agent.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.Point
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Parcelable
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import fuck.andes.agent.device.ScrollAxis
import fuck.andes.agent.device.ScrollAxisContract
import fuck.andes.agent.device.ScrollDirection
import fuck.andes.agent.device.ScrollEvidence
import fuck.andes.agent.device.ScrollEvidenceContract
import fuck.andes.agent.device.ScrollMovementSource
import fuck.andes.agent.device.RootScrollMotionContract
import fuck.andes.core.AndroidAgentLogger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlinx.coroutines.withLock
import org.json.JSONObject

class AgentAccessibilityService : AccessibilityService() {

    private data class ScreenshotWindow(
        val id: Int,
        val layer: Int,
        val type: Int,
        val bounds: Rect,
        val active: Boolean,
        val focused: Boolean,
    )

    private data class NodeTraversalState(
        val maxVisitedNodes: Int,
        val activePath: MutableSet<AccessibilityNodeInfo> = hashSetOf(),
        var visitedNodes: Int = 0,
        var truncated: Boolean = false,
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val screenshotExecutor: ExecutorService = SCREENSHOT_EXECUTOR
    private val windowChangeLock = ReentrantLock()
    private val windowChanged = windowChangeLock.newCondition()
    private val scrollEventLock = ReentrantLock()
    private val scrollEventArrived = scrollEventLock.newCondition()
    private val scrollActionLock = ReentrantLock()
    private var scrollEventSequence = 0L
    private val recentScrollSignals = ArrayDeque<ScrollSignal>(0)
    private val windowContentGenerations = mutableMapOf<Int, Long>()
    private val serviceToken = SERVICE_TOKENS.incrementAndGet()

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        clearCurrentInstance()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        clearCurrentInstance()
        super.onDestroy()
    }

    private fun clearCurrentInstance() {
        if (instance == this) instance = null
        signalWindowChanged()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        when (event?.eventType) {
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                pruneWindowContentGenerations()
                signalWindowChanged()
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                bumpWindowContentGeneration(event.windowId)
                recordScrollEvent(event)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SELECTION_CHANGED -> {
                bumpWindowContentGeneration(event.windowId)
            }
        }
    }

    override fun onInterrupt() = Unit

    /**
     * 同步获取当前无障碍树快照，限制最多遍历 [maxNodes] 个节点并返回索引。
     * 主线程调用，阻塞等待 text 索引完成。
     */
    fun captureNodeSnapshot(maxNodes: Int): NodeSnapshot? = runOnMainThreadSync {
        val startedAt = SystemClock.elapsedRealtime()
        val root = rootInActiveWindow ?: return@runOnMainThreadSync null
        val nodeLimit = coerceIn(1, 120)
        val indexedNodes = mutableListOf<IndexedNode>()
        val traversal = NodeTraversalState(
            maxVisitedNodes = (nodeLimit * UI_TREE_VISIT_MULTIPLIER)
                .coerceIn(MIN_UI_TREE_VISIT_NODES, MAX_UI_TREE_VISIT_NODES),
        )
        collectNodes(
            node = root,
            out = indexedNodes,
            maxNodes = nodeLimit,
            depth = 0,
            traversal = traversal,
        )
        NodeSnapshot(
            id = "o${SNAPSHOT_IDS.incrementAndGet()}",
            serviceToken = serviceToken,
            packageName = root.packageName?.toString().orEmpty(),
            windowId = root.windowId,
            contentGeneration = windowContentGeneration(root.windowId),
            capturedAtMs = startedAt,
            truncated = traversal.truncated,
            indexedNodes = indexedNodes.toList(),
        ).also { snapshot ->
            AndroidAgentLogger.debug {
                "Agent accessibility action=observe_tree observation=${snapshot.id} " +
                        "nodes=${snapshot.nodes.size} visited=${traversal.visitedNodes} " +
                        "truncated=${traversal.truncated} elapsed_ms=${SystemClock.elapsedRealtime() - startedAt}"
            }
        }
    }

    fun queryNodes(maxNodes: Int): List<UiNode> =
        captureNodeSnapshot(maxNodes)?.nodes.orEmpty()

    fun currentPackageName(): String? =
        rootInActiveWindow?.packageName?.toString()

    fun displaySize(): Pair<Int, Int>? = runCatching {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val point = Point()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealSize(point)
        if (point.x > 0 && point.y > 0) point.x to point.y else null
    }.getOrNull()

    internal fun packageWindowVisibility(packageName: String): PackageWindowVisibility =
        runOnMainThreadSync {
            val activeRoot = rootInActiveWindow
            if (activeRoot?.packageName?.toString() == packageName) {
                return@runOnMainThreadSync PackageWindowVisibility.VISIBLE
            }
            var inspectedRoot = activeRoot != null
            var hasUnknownRelevantWindow = false
            for (window in windows.orEmpty()) {
                val root = window.root
                if (root == null) {
                    if (
                        window.type == AccessibilityWindowInfo.TYPE_APPLICATION ||
                        window.isActive ||
                        window.isFocused
                    ) {
                        hasUnknownRelevantWindow = true
                    }
                    continue
                }
                inspectedRoot = true
                if (root.packageName?.toString() == packageName) {
                    return@runOnMainThreadSync PackageWindowVisibility.VISIBLE
                }
            }
            if (inspectedRoot && !hasUnknownRelevantWindow) {
                PackageWindowVisibility.GONE
            } else {
                PackageWindowVisibility.UNKNOWN
            }
        } ?: PackageWindowVisibility.UNKNOWN

    /**
     * 阻塞等待目标包名窗口出现并稳定（minWaitMs 后首次稳定即可）。
     * 仅在调用者线程执行，不切主线程。
     */
    fun awaitPackageWindowGone(
        packageName: String,
        timeoutMillis: Long = 1_000L,
        minWaitMillis: Long = 160L,
        stableMillis: Long = 80L,
    ): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return false
        }
        val startedAt = SystemClock.elapsedRealtime()
        val deadline = startedAt + timeoutMillis.coerceAtMost(2_000L)
        var absentSince = 0L
        do {
            val now = SystemClock.elapsedRealtime()
            when (packageWindowVisibility(packageName)) {
                PackageWindowVisibility.VISIBLE,
                PackageWindowVisibility.UNKNOWN -> absentSince = 0L
                PackageWindowVisibility.GONE -> {
                    if (absentSince == 0L) absentSince = now
                    if (
                        now - startedAt >= minWaitMillis &&
                        now - absentSince >= stableMillis
                    ) {
                        return true
                    }
                }
            }
            val remainingMillis = deadline - SystemClock.elapsedRealtime()
            if (remainingMillis > 0L) {
                awaitWindowChanged(remainingMillis.coerceAtMost(WINDOW_POLL_FALLBACK_MS))
            }
        } while (SystemClock.elapsedRealtime() < deadline)
        return false
    }

    private fun signalWindowChanged() {
        windowChangeLock.lock()
        try {
            windowChanged.signalAll()
        } finally {
            windowChangeLock.unlock()
        }
    }

    private fun awaitWindowChanged(timeoutMillis: Long) {
        windowChangeLock.lock()
        try {
            windowChanged.await(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            windowChangeLock.unlock()
        }
    }

    private fun recordScrollEvent(event: AccessibilityEvent) {
        val source = runCatching { event.source }.orEmpty()
        val sourceBounds = source?.bounds ?: Rect()
        val signal = ScrollSignal(
            sequence = 0L,
            packageName = event.packageName?.toString().orEmpty(),
            windowId = event.windowId,
            deltaX = event.scrollDeltaX,
            deltaY = event.scrollDeltaY,
            scrollX = event.scrollX,
            scrollY = event.scrollY,
            maxScrollX = event.maxScrollX,
            maxScrollY = event.maxScrollY,
            fromIndex = event.fromIndex,
            toIndex = event.toIndex,
            sourceUniqueId = source?.uniqueId.orEmpty(),
            sourceViewId = source?.viewIdResourceName.orEmpty(),
            sourceClassName = source?.className?.toString().orEmpty(),
            sourceBounds = sourceBounds,
        )
        scrollEventLock.lock()
        try {
            scrollEventSequence++
            recentScrollSignals.addLast(signal.copy(sequence = scrollEventSequence))
            while (recentScrollSignals.size > MAX_SCROLL_SIGNALS) {
                recentScrollSignals.removeFirst()
            }
            scrollEventArrived.signalAll()
        } finally {
            scrollEventLock.unlock()
        }
    }

    private fun bumpWindowContentGeneration(windowId: Int) {
        if (windowId < 0) return
        windowContentGenerations[windowId] = windowContentGeneration(windowId) + 1L
    }

    private fun windowContentGeneration(windowId: Int): Long =
        windowContentGenerations[windowId] ?: 0L

    private fun pruneWindowContentGenerations() {
        if (windowContentGenerations.isEmpty()) return
        val liveWindowIds = windows.orEmpty().mapTo(hashSetOf()) { it.id }
        windowContentGenerations.keys.retainAll(liveWindowIds)
    }

    private fun currentScrollEventSequence(): Long {
        scrollEventLock.lock()
        return try {
            scrollEventSequence
        } finally {
            scrollEventLock.unlock()
        }
    }

    private fun awaitScrollSignal(
        afterSequence: Long,
        packageName: String,
        windowId: Int,
        targetIdentity: ScrollTargetIdentity,
        timeoutMillis: Long,
    ): ScrollSignal? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        scrollEventLock.lock()
        try {
            while (true) {
                val signal = recentScrollSignals.firstOrNull { candidate ->
                    candidate.sequence > afterSequence &&
                            candidate.windowId == windowId &&
                            candidate.packageName == packageName &&
                            candidate.matchesTarget(targetIdentity)
                }
                if (signal != null) {
                    return signal
                }
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining <= 0L) return null
                try {
                    scrollEventArrived.await(remaining, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
            }
        } finally {
            scrollEventLock.unlock()
        }
    }

    fun clickNode(snapshot: NodeSnapshot, index: Int): NodeActionResult =
        withValidatedIndexedNode(snapshot, index) { indexed ->
            val node = indexed.node
            val actionable = indexed.clickTarget?.resolveFor(node)
            if (indexed.clickTarget != null && actionable == null) {
                NodeActionResult.failure(
                    "STALE_TARGET",
                    "节点已更新，请刷新节点索引后重试",
                )
            } else if (actionable != null) {
                when (performNodeAction(actionable, AccessibilityNodeInfo.ACTION_CLICK)) {
                    ActionDispatch.ACCEPTED -> NodeActionResult.success(method = "ACTION_CLICK")
                    ActionDispatch.REJECTED ->
                        NodeActionResult.failure("ACTION_FAILED", "操作被拒绝或返回未知结果")
                    ActionDispatch.OUTCOME_UNKNOWN -> NodeActionResult.outcomeUnknown()
                }
            } else {
                val bounds = clipNodeBounds(node)
                if (bounds.isEmpty()) {
                    NodeActionResult.failure("INVALID_NODE_BOUNDS", "节点无有效点击区域")
                } else {
                    gestureTap(bounds.centerX().toFloat(), bounds.centerY().toFloat())
                }
            }
        }

    fun longClickNode(
        snapshot: NodeSnapshot,
        index: Int,
        durationMs: Long,
    ): NodeActionResult = withValidatedIndexedNode(snapshot, index) { indexed ->
        val node = indexed.node
        val actionable = indexed.longClickTarget?.resolveFor(node)
        if (indexed.longClickTarget != null && actionable == null) {
            NodeActionResult.failure(
                "STALE_TARGET",
                "节点已更新，请刷新节点索引后重试",
            )
        } else if (actionable != null) {
            when (performNodeAction(actionable, AccessibilityNodeInfo.ACTION_LONG_CLICK)) {
                ActionDispatch.ACCEPTED -> NodeActionResult.success(method = "ACTION_LONG_CLICK")
                ActionDispatch.REJECTED ->
                    NodeActionResult.failure("ACTION_FAILED", "操作被拒绝或返回未知结果")
                ActionDispatch.OUTCOME_UNKNOWN -> NodeActionResult.outcomeUnknown()
            }
        } else {
            val bounds = clipNodeBounds(node)
            if (bounds.isEmpty()) {
                NodeActionResult.failure("INVALID_NODE_BOUNDS", "节点无有效点击区域")
            } else {
                gestureTap(
                    bounds.centerX().toFloat(),
                    bounds.centerY().toFloat(),
                    durationMs = durationMs.coerceIn(300L, 3_000L),
                )
            }
        }
    }

    internal fun scrollNode(
        snapshot: NodeSnapshot,
        index: Int,
        direction: ScrollDirection,
    ): ScrollActionResult {
        val validation = runOnMainThreadSync { validateNode(snapshot, index) }
            ?: return ScrollActionResult.failure(
                direction = direction,
                code = "SERVICE_TIMEOUT",
                message = "主节点响应超时",
            )
        val validationNode = when (validation) {
            is NodeValidation.Invalid -> return ScrollActionResult.failure(
                direction = direction,
                code = validation.result.code,
                message = validation.result.message,
            )
            is NodeValidation.Valid -> validation.indexedNode
        }
        val scrollable = validationNode.scrollTarget?.resolveFor(validationNode.node)
        if (validationNode.scrollTarget != null && scrollable == null) {
            return ScrollActionResult.failure(
                direction = direction,
                code = "STALE_TARGET",
                message = "节点已更新，请刷新节点索引后重试",
            )
        }
        val targetIndex = validationNode.index
        val beforeSequence = currentScrollEventSequence()
        val packageName = validationNode.node.packageName?.toString().orEmpty()
        val windowId = validationNode.node.windowId
        val method = chooseScrollMethod(validationNode, direction)
        val methodName = method?.name.orEmpty()
        val nodeDispatch = method?.let { selected ->
            performNodeAction(validationNode.node, selected.actionId, selected.args)
        }
        if (nodeDispatch == ActionDispatch.OUTCOME_UNKNOWN) {
            return ScrollActionResult.failure(
                direction = direction,
                code = "ACTION_OUTCOME_UNKNOWN",
                message = "操作返回未知结果，滚动方向不明确",
                method = methodName,
                targetIndex = targetIndex,
            )
        }
        val accepted = nodeDispatch == ActionDispatch.ACCEPTED

        if (accepted) {
            val boundary = runOnMainThreadSync {
                validationNode.node.refresh() && isAtScrollBoundary(validationNode.node, direction)
            } == true
            if (boundary) {
                return ScrollActionResult.boundary(
                    direction = direction,
                    method = methodName,
                    targetIndex = targetIndex,
                )
            }
        }
        val bounds = clipNodeBounds(validationNode.node)
        val gesture = direction.gestureWithin(bounds)
            ?: return ScrollActionResult.failure(
                direction = direction,
                code = "NOT_SCROLLABLE",
                message = "节点在该方向无可滚动区域",
            )
        val gestureResult = gestureSwipe(
            gesture.start.x.toFloat(), gesture.start.y.toFloat(),
            gesture.end.x.toFloat(), gesture.end.y.toFloat(),
            SCROLL_GESTURE_DURATION_MS,
        )
        val acceptedAfterGesture = gestureResult.method == ActionDispatch.ACCEPTED

        if (!accepted) {
            if (acceptedAfterGesture) {
                return ScrollActionResult.success(
                    direction = direction,
                    method = "GESTURE_SWIPE",
                    targetIndex = targetIndex,
                )
            }
            val boundary = runOnMainThreadSync {
                validationNode.node.refresh() && isAtScrollBoundary(validationNode.node, direction)
            } == true
            if (boundary) {
                return ScrollActionResult.boundary(
                    direction = direction,
                    method = methodName,
                    targetIndex = targetIndex,
                )
            }
            val code = "ACTION_FAILED"
            return ScrollActionResult.failure(
                direction = direction,
                code = code,
                message = "滚动操作失败",
                method = methodName,
                targetIndex = targetIndex,
            )
        }

        val signal = awaitScrollSignal(
            afterSequence = beforeSequence,
            packageName = packageName,
            windowId = windowId,
            targetIdentity = ScrollTargetIdentity.from(validationNode.node),
            timeoutMillis = SCROLL_VERIFY_TIMEOUT_MS,
        )
        val afterAnchors = runOnMainThreadSync {
            if (validationNode.node.refresh()) scrollContentAnchors(validationNode.node) else emptyList()
        }.orEmpty()
        val eventDelta = signal?.axesDelta(direction.axis)?.takeIf { it != 0 }
        val anchorDelta = inferScrollAnchorDelta(beforeAnchors = emptyList(), afterAnchors = afterAnchors, direction)
        val delta = eventDelta ?: anchorDelta
        val movementSource = when {
            eventDelta != null -> ScrollMovementSource.EVENT
            anchorDelta != null -> ScrollMovementSource.ANCHOR_MOTION
            else -> null
        }
        val evidence = ScrollEvidence.classify(
            direction = direction,
            delta = delta,
            movementSource = movementSource,
            atBoundary = accepted,
        )
        if (evidence == ScrollEvidence.DIRECTION_MISMATCH) {
            return ScrollActionResult.failure(
                direction = direction,
                code = "DIRECTION_MISMATCH",
                message = "滚动方向与预期方向不符，请调整滚动方向",
                method = methodName,
                targetIndex = targetIndex,
                delta = delta,
                elapsedMs = SystemClock.elapsedRealtime() - startedAt,
            )
        }
        val elapsedMs = SystemClock.elapsedRealtime() - startedAt
        if (evidence == ScrollEvidence.MOVED_BY_EVENT || evidence == ScrollEvidence.MOVED_BY_ANCHOR_MOTION) {
            return ScrollActionResult(
                ok = true,
                direction = direction,
                moved = true,
                atBoundary = accepted,
                method = methodName,
                targetIndex = targetIndex,
                delta = delta,
                verifiedBy = when (evidence) {
                    ScrollEvidence.MOVED_BY_EVENT -> "scroll_event"
                    ScrollEvidence.MOVED_BY_ANCHOR_MOTION -> "anchor_motion"
                    else -> null
                },
                elapsedMs = elapsedMs,
            )
        }
        if (evidence == ScrollEvidence.AT_BOUNDARY) {
            return ScrollActionResult.boundary(
                direction = direction,
                method = methodName,
                targetIndex = targetIndex,
                elapsedMs = elapsedMs,
            )
        }

        return ScrollActionResult(
            ok = true,
            direction = direction,
            moved = false,
            atBoundary = false,
            method = methodName,
            targetIndex = targetIndex,
            delta = delta,
            elapsedMs = elapsedMs,
        )
    }

    internal fun scrollCurrent(direction: ScrollDirection): ScrollActionResult {
        val target = runOnMainThreadSync {
            rootInActiveWindow?.let { root -> findBestScrollableNode(root, direction) }
        } ?: return ScrollActionResult.failure(
            direction = direction,
            code = "NO_ACTIVE_WINDOW",
            message = "当前无活跃窗口",
        )
        return scrollAction(target, direction, targetIndex = null)
    }

    private fun scrollAction(
        target: AccessibilityNodeInfo,
        direction: ScrollDirection,
        targetIndex: Int?,
    ): ScrollActionResult {
        val startedAt = SystemClock.elapsedRealtime()
        val refreshed = runOnMainThreadSync { target.refresh() } == true
        if (!refreshed || !target.isVisibleToUser || !target.isEnabled) {
            return ScrollActionResult.failure(
                direction = direction,
                code = "STALE_NODE",
                message = "节点已过期或不可见，请重试",
                targetIndex = targetIndex,
            )
        }
        if (target.exposesOnlyOppositeAxis(direction)) {
            return ScrollActionResult.failure(
                direction = direction,
                code = "AXIS_MISMATCH",
                message = "滚动轴不支持该方向，请先横向滚动",
                targetIndex = targetIndex,
                elapsedMs = SystemClock.elapsedRealtime() - startedAt,
            )
        }
        val packageName = target.packageName?.toString().orEmpty()
        val windowId = target.windowId
        val beforeAnchors = scrollContentAnchors(target)
        val beforeSequence = currentScrollEventSequence()
        val method = chooseScrollMethod(target, direction)
        val methodName = method?.name.orEmpty()
        val nodeDispatch = method?.let { selected ->
            performNodeAction(target, selected.actionId, selected.args)
        }
        if (nodeDispatch == ActionDispatch.OUTCOME_UNKNOWN) {
            return ScrollActionResult.failure(
                direction = direction,
                code = "ACTION_OUTCOME_UNKNOWN",
                message = "操作返回未知结果，滚动方向不明确",
                method = methodName,
                targetIndex = targetIndex,
            )
        }
        var accepted = nodeDispatch == ActionDispatch.ACCEPTED

        if (accepted) {
            val boundary = runOnMainThreadSync {
                target.refresh() && isAtScrollBoundary(target, direction)
            } == true
            if (boundary) {
                return ScrollActionResult.boundary(
                    direction = direction,
                    method = methodName,
                    targetIndex = targetIndex,
                )
            }
        }
        val bounds = clipNodeBounds(target)
        val gesture = direction.gestureWithin(bounds)
            ?: return ScrollActionResult.failure(
                direction = direction,
                code = "NOT_SCROLLABLE",
                message = "节点在该方向无可滚动区域",
                targetIndex = targetIndex,
            )
        val gestureResult = gestureSwipe(
            gesture.start.x.toFloat(), gesture.start.y.toFloat(),
            gesture.end.x.toFloat(), gesture.end.y.toFloat(),
            SCROLL_GESTURE_DURATION_MS,
        )
        val acceptedAfterGesture = gestureResult.method == ActionDispatch.ACCEPTED

        if (!accepted) {
            if (acceptedAfterGesture) {
                accepted = true
            } else {
                val boundary = runOnMainThreadSync {
                    target.refresh() && isAtScrollBoundary(target, direction)
                } == true
                if (boundary) {
                    return ScrollActionResult.boundary(
                        direction = direction,
                        method = methodName,
                        targetIndex = targetIndex,
                    )
                }
                val code = "ACTION_FAILED"
                return ScrollActionResult.failure(
                    direction = direction,
                    code = code,
                    message = "滚动操作失败",
                    method = methodName,
                    targetIndex = targetIndex,
                )
            }
        }

        val signal = awaitScrollSignal(
            afterSequence = beforeSequence,
            packageName = packageName,
            windowId = windowId,
            targetIdentity = ScrollTargetIdentity.from(target),
            timeoutMillis = SCROLL_VERIFY_TIMEOUT_MS,
        )
        val afterAnchors = runOnMainThreadSync {
            if (target.refresh()) scrollContentAnchors(target) else emptyList()
        }.orEmpty()
        val eventDelta = signal?.axesDelta(direction.axis)?.takeIf { it != 0 }
        val anchorDelta = inferScrollAnchorDelta(beforeAnchors, afterAnchors, direction)
        val delta = eventDelta ?: anchorDelta
        val movementSource = when {
            eventDelta != null -> ScrollMovementSource.EVENT
            anchorDelta != null -> ScrollMovementSource.ANCHOR_MOTION
            else -> null
        }
        val evidence = ScrollEvidence.classify(
            direction = direction,
            delta = delta,
            movementSource = movementSource,
            atBoundary = accepted,
        )
        if (evidence == ScrollEvidence.DIRECTION_MISMATCH) {
            return ScrollActionResult.failure(
                direction = direction,
                code = "DIRECTION_MISMATCH",
                message = "滚动方向与预期方向不符，请调整滚动方向",
                method = methodName,
                targetIndex = targetIndex,
                delta = delta,
                elapsedMs = SystemClock.elapsedRealtime() - startedAt,
            )
        }
        val elapsedMs = SystemClock.elapsedRealtime() - startedAt
        if (evidence == ScrollEvidence.MOVED_BY_EVENT || evidence == ScrollEvidence.MOVED_BY_ANCHOR_MOTION) {
            return ScrollActionResult(
                ok = true,
                direction = direction,
                moved = true,
                atBoundary = accepted,
                method = methodName,
                targetIndex = targetIndex,
                delta = delta,
                verifiedBy = when (evidence) {
                    ScrollEvidence.MOVED_BY_EVENT -> "scroll_event"
                    ScrollEvidence.MOVED_BY_ANCHOR_MOTION -> "anchor_motion"
                    else -> null
                },
                elapsedMs = elapsedMs,
            )
        }
        if (evidence == ScrollEvidence.AT_BOUNDARY) {
            return ScrollActionResult.boundary(
                direction = direction,
                method = methodName,
                targetIndex = targetIndex,
                elapsedMs = elapsedMs,
            )
        }

        return ScrollActionResult(
            ok = true,
            direction = direction,
            moved = false,
            atBoundary = false,
            method = methodName,
            targetIndex = targetIndex,
            delta = delta,
            elapsedMs = elapsedMs,
        )
    }

    fun inputTextFocused(text: String): NodeActionResult = runNodeActionOnMainThread {
        val node = findFocusedEditableNode()
            ?: return@runNodeActionOnMainThread NodeActionResult.failure(
                "NO_FOCUSED_EDITABLE",
                "当前没有聚焦的可编辑文本框",
            )
        node.incrementalTextValidationError()?.let { error ->
            return@runNodeActionOnMainThread error
        }
        val plan = TextEditPlanner.insertAtSelection(
            currentText = node.text?.toString().orEmpty(),
            insertedText = text,
            selectionStart = node.textSelectionStart,
            selectionEnd = node.textSelectionEnd,
        ) ?: return@runNodeActionOnMainThread NodeActionResult.failure(
            "TEXT_SELECTION_UNAVAILABLE",
            "文本选区不可用，无法执行替换文本操作",
        )
        setNodeText(node, plan.text, plan.cursor)
    }

    fun setTextNode(
        snapshot: NodeSnapshot?,
        index: Int?,
        text: String,
    ): NodeActionResult {
        if (index != null) {
            val requiredSnapshot = snapshot
                ?: return NodeActionResult.failure("NO_OBSERVATION", "需要提供节点快照以按索引设置文本")
            return withValidatedNode(requiredSnapshot, index) { indexedNode ->
                if (!indexedNode.isEditable) {
                    NodeActionResult.failure("NOT_EDITABLE", "节点不可编辑")
                } else {
                    setNodeText(indexedNode.node, text, text.length)
                }
            }
        }
        return runNodeActionOnMainThread {
            val node = findFocusedEditableNode()
                ?: return@runNodeActionOnMainThread NodeActionResult.failure(
                    "NO_FOCUSED_EDITABLE",
                    "当前没有聚焦的可编辑文本框",
                )
            setNodeText(node, text, text.length)
        }
    }

    /**
     * 粘贴文本到当前聚焦输入框，支持保留剪贴板。
     * 如直接粘贴失败，回退使用 setText + replace_text 操作。
     */
    fun pasteText(text: String): NodeActionResult = runNodeActionOnMainThread {
        val node = findFocusedEditableNode()
            ?: return@runNodeActionOnMainThread NodeActionResult.failure(
                "NO_FOCUSED_EDITABLE",
                "当前没有聚焦的可编辑文本框",
            )
        node.incrementalTextValidationError()?.let { error ->
            return@runNodeActionOnMainThread error
        }
        val plan = TextEditPlanner.insertAtSelection(
            currentText = node.text?.toString().orEmpty(),
            insertedText = text,
            selectionStart = node.textSelectionStart,
            selectionEnd = node.textSelectionEnd,
        ) ?: return@runNodeActionOnMainThread NodeActionResult.failure(
            "TEXT_SELECTION_UNAVAILABLE",
            "文本选区不可用，无法执行替换文本操作",
        )
        val directResult = setNodeText(node, plan.text, plan.cursor)
        if (directResult.ok) {
            return@runNodeActionOnMainThread directResult.copy(method = "ACTION_SET_TEXT")
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val originalClip = runCatching { clipboard.primaryClip }.getOrNull()
        val temporaryLabel = "$CLIP_LABEL:${CLIP_IDS.incrementAndGet()}"
        val temporaryClip = ClipData.newPlainText(temporaryLabel, text).apply {
            description.extras = Bundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        val copied = runCatching {
            clipboard.setPrimaryClip(temporaryClip)
        }.isSuccess
        if (!copied) {
            return@runNodeActionOnMainThread NodeActionResult.failure(
                "CLIPBOARD_WRITE_FAILED",
                "写入剪贴板失败",
            )
        }
        val pasteResult = try {
            if (node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
                val verified = runCatching { node.refresh() }.getOrDefault(false) &&
                        node.text?.toString() == text
                if (verified) {
                    NodeActionResult.success(method = "ACTION_PASTE", verified = true)
                } else {
                    NodeActionResult.outcomeUnknown()
                }
            } else {
                NodeActionResult.failure("ACTION_FAILED", "粘贴操作被拒绝或返回未知结果")
            }
        } catch (_: Throwable) {
            NodeActionResult.outcomeUnknown()
        }
        val restored = restoreClipboardIfStillOwned(
            clipboard = clipboard,
            temporaryLabel = temporaryLabel,
            originalClip = originalClip,
        )
        if (!restored) {
            return@runNodeActionOnMainThread NodeActionResult.outcomeUnknown().copy(clipboardWritten = true)
        }
        pasteResult
    }

    fun imeEnter(): NodeActionResult = runNodeActionOnMainThread {
        val node = findFocusedEditableNode()
            ?: return@runNodeActionOnMainThread NodeActionResult.failure(
                "NO_FOCUSED_EDITABLE",
                "当前没有聚焦的可编辑文本框",
            )
        if (node.performAction(AccessibilityNodeInfo.ACTION_IME_ENTER)) {
            NodeActionResult.success(method = "ACTION_IME_ENTER")
        } else {
            NodeActionResult.failure("ACTION_FAILED", "键盘回车操作失败")
        }
    }

    fun gestureTap(x: Float, y: Float, durationMs: Long = 50): NodeActionResult =
        dispatchGestureResult(
            Path().apply { moveTo(x, y); lineTo(x, y) },
            durationMs.coerceAtLeast(1),
            successMethod = "GESTURE_TAP",
        )

    fun gestureSwipe(
        x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long,
    ): NodeActionResult =
        dispatchGestureResult(
            Path().apply { moveTo(x1, y1); lineTo(x2, y2) },
            durationMs.coerceIn(100, 3_000),
            successMethod = "GESTURE_SWIPE",
        )

    fun gestureTap(x: Float, y: Float, durationMs: Long = 50): NodeActionResult =
        dispatchGestureResult(
            Path().apply { moveTo(x, y); lineTo(x, y) },
            durationMs.coerceAtLeast(1),
            successMethod = "GESTURE_TAP",
        )

    fun globalActionResult(name: String): NodeActionResult {
        val action = when (name.uppercase()) {
            "BACK" -> GLOBAL_ACTION_BACK
            "HOME" -> GLOBAL_ACTION_HOME
            "RECENTS" -> GLOBAL_ACTION_RECENTS
            "NOTIFICATIONS" -> GLOBAL_ACTION_NOTIFICATIONS
            "QUICK_SETTINGS" -> GLOBAL_ACTION_QUICK_SETTINGS
            else -> return NodeActionResult.failure("INVALID_ARGUMENT", "无效的全局动作")
        }
        return runNodeActionOnMainThread {
            if (performGlobalAction(action)) {
                NodeActionResult.success(method = "GLOBAL_ACTION_$name")
            } else {
                NodeActionResult.failure("ACTION_FAILED", "全局动作执行失败")
            }
        }
    }

    fun globalAction(name: String): Boolean = globalActionResult(name).ok

    fun copyToClipboard(text: String): NodeActionResult =
        runNodeActionOnMainThread {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, text))
            NodeActionResult.success(method = "CLIPBOARD_SET")
        }

    fun readClipboard(): ClipboardReadResult = runOnMainThreadSync {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = runCatching { clipboard.primaryClip }.orEmpty()
            ?: return@runOnMainThreadSync ClipboardReadResult.failure()
        if (clip.itemCount <= 0) return@runOnMainThreadSync ClipboardReadResult.failure()
        ClipboardReadResult(
            ok = true,
            text = clip.getItemAt(0).coerceToText(this)?.toString().orEmpty(),
        )
    } ?: ClipboardReadResult.failure(code = "SERVICE_TIMEOUT")

    fun statusJson(): JSONObject =
        JSONObject()
            .put("available", true)
            .put("package", currentPackageName().orEmpty())

    /**
     * 仅排除 overlay 的屏幕截图。
     * TYPE_ACCESSIBILITY_OVERLAY 会遮挡结果卡片，ETA 运行时在点击打开 overlay 的应用时 post
     * callback 避免遮挡，这里负责从 bitmap 中过滤掉该类窗口。
     */
    fun captureScreenshotExcludingOverlays(
        excludedPackages: Set<String> = emptySet(),
    ): ScreenshotCaptureResult {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return ScreenshotCaptureResult.unavailable()
        }
        val startedAt = SystemClock.elapsedRealtime()
        val allWindows = windows ?: return ScreenshotCaptureResult.unavailable()
        val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            ?: return ScreenshotCaptureResult.unavailable().also {
                allWindows.forEach { w -> runCatching { w.recycle() } }
            }
        val point = Point()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealSize(point)
        val screenW = point.x
        val screenH = point.y
        if (screenW <= 0 || screenH <= 0) {
            allWindows.forEach { w -> runCatching { w.recycle() } }
            return ScreenshotCaptureResult.unavailable()
        }
        val screenBounds = Rect(0, 0, screenW, screenH)

        /**
         * overlay 会遮挡结果卡片，ETA 运行时在点击打开 overlay 的应用时 post
         * callback 避免遮挡，这里负责从 bitmap 中过滤掉该类窗口。
         */
        val windowPackages = allWindows.associate { w: android.view.accessibility.AccessibilityWindowInfo ->
            w.id to w.root?.packageName?.toString()
        }
        val windowDecisions = allWindows.associate { w: android.view.accessibility.AccessibilityWindowInfo ->
            w.id to ScreenshotWindowPolicy.decide(
                isAccessibilityOverlay = w.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY,
                isApplicationWindow = w.type == AccessibilityWindowInfo.TYPE_APPLICATION,
                active = w.isActive,
                focused = w.isFocused,
            )
        }
        val unknownRelevantWindowIds = windowDecisions
            .filterValues { it == ScreenshotWindowPolicy.Decision.BLOCK_UNKNOWN }
            .keys
            .toList()
        if (unknownRelevantWindowIds.isNotEmpty()) {
            allWindows.forEach { w -> runCatching { w.recycle() } }
            return ScreenshotCaptureResult.blockedByUnknownWindow(
                expectedWindows = allWindows.size,
                windowIds = unknownRelevantWindowIds,
            )
        }
        val captureWindows = allWindows.mapNotNull { w: android.view.accessibility.AccessibilityWindowInfo ->
            if (windowDecisions[w.id] == ScreenshotWindowPolicy.Decision.EXCLUDE) {
                return@mapNotNull null
            }
            val bounds = Rect()
            w.getBoundsInScreen(bounds)
            if (
                bounds.width() <= 0 ||
                bounds.height() <= 0 ||
                !Rect.intersects(bounds, screenBounds)
            ) {
                return@mapNotNull null
            }
            ScreenshotWindow(
                id = w.id,
                layer = w.layer,
                type = w.type,
                bounds = bounds,
                active = w.isActive,
                focused = w.isFocused,
            )
        }.sortedBy { it.layer }
        if (captureWindows.isEmpty()) {
            allWindows.forEach { w -> runCatching { w.recycle() } }
            return ScreenshotCaptureResult.unavailable()
        }
        val criticalWindowId = captureWindows
            .firstOrNull { it.active || it.focused }?.id
            ?: captureWindows.maxByOrNull { it.layer }?.id
        val criticalWindowIds = captureWindows
            .asSequence()
            .filter { window: ScreenshotWindow -> window.active || window.focused }
            .map { it.id }
            .toSet()

        val latch = CountDownLatch(captureWindows.size)
        val screenshots = mutableMapOf<Int, Pair<Bitmap, Rect>>()
        val failures = mutableMapOf<Int, Int>()
        val acceptingResults = AtomicBoolean(true)
        val lock = Any()

        for (window in captureWindows) {
            takeScreenshotOfWindow(window.id, screenshotExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    try {
                        val sw = convertToSoftwareBitmap(screenshot)
                            ?: throw IllegalStateException("screenshot bitmap unavailable")
                        var retained = false
                        synchronized(lock) {
                            if (acceptingResults.get()) {
                                screenshots[window.id] = sw to window.bounds
                                retained = true
                            }
                        }
                        if (!retained) sw.recycle()
                    } catch (_: Exception) {
                        synchronized(lock) {
                            if (acceptingResults.get()) {
                                failures[window.id] = ERROR_TAKE_SCREENSHOT_INTERNAL
                            }
                        }
                    }
                }

                override fun onFailure(errorCode: Int) {
                    synchronized(lock) {
                        if (acceptingResults.get()) {
                            failures[window.id] = errorCode
                        }
                    }
                }
            })
        }

        try {
            latch.await(2, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        acceptingResults.set(false)
        val captured = synchronized(lock) {
            screenshots.toMap().also { screenshots.clear() }
        }
        val criticalWindowMissing = criticalWindowIds.any { it !in captured }
        var merged: Bitmap? = null
        if (!criticalWindowMissing) {
            merged = mergeScreenshots(captured, captureWindows, screenW, screenH)
        }
        val failuresSnapshot = synchronized(lock) { failures.toMap() }
        val elapsedMs = SystemClock.elapsedRealtime() - startedAt
        AndroidAgentLogger.debug {
            "Agent accessibility action=capture_screenshot outcome=${if (merged != null) "merged" else "failed"} " +
                    "allWindows=${allWindows.size} validWindows=${captureWindows.size} " +
                    "excludedPackages=${excludedPackages.size} " +
                    "completed=${captured.size} screen=${screenW}x${screenH} merged=${merged?.width}x${merged?.height} " +
                    "elapsed_ms=$elapsedMs"
        }
        captured.values.forEach { (bitmap, _) -> if (!bitmap.isRecycled) bitmap.recycle() }
        allWindows.forEach { w -> runCatching { w.recycle() } }
        return if (merged != null) ScreenshotCaptureResult(
            bitmap = merged,
            complete = captured.size == captureWindows.size,
            expectedWindows = captureWindows.size,
            capturedWindows = captured.size,
            missingWindowIds = captureWindows.mapTo(mutableSetOf()) { it.id } - captured.keys,
            failureCodes = failuresSnapshot,
            timedOut = !captured.size.toSet().containsAll(captureWindows.map { it.id }),
            criticalWindowMissing = criticalWindowMissing,
        ) else ScreenshotCaptureResult.unavailable()
    }

    private fun convertToSoftwareBitmap(screenshot: ScreenshotResult): Bitmap? =
        screenshot.hardwareBuffer.use { hardwareBuffer ->
            wrapped = Bitmap.wrapHardwareBuffer(hardwareBuffer, screenshot.colorSpace)
                ?: return@use null
            try {
                if (wrapped.config == Bitmap.Config.HARDWARE) {
                    wrapped.copy(Bitmap.Config.ARGB_8888, false)
                } else {
                    wrapped
                }
            } finally {
                if (!wrapped.isRecycled) wrapped.recycle()
            }
        }

    private fun mergeScreenshots(
        screenshots: Map<Int, Pair<Bitmap, Rect>>,
        sortedWindows: List<ScreenshotWindow>,
        screenW: Int,
        screenH: Int
    ): Bitmap? {
        var merged: Bitmap? = null
        return try {
            val output = Bitmap.createBitmap(screenW, screenH, Bitmap.Config.ARGB_8888)
            merged = output
            val canvas = Canvas(output)
            canvas.drawColor(Color.BLACK)
            val occlusionPaint = Paint().apply {
                color = Color.BLACK
                style = Paint.Style.FILL
            }
            for (window in sortedWindows) {
                val (bitmap, _) = screenshots[window.id] ?: continue
                val bounds = Rect(window.bounds)
                if (
                    bounds.width() <= 0 ||
                    bounds.height() <= 0 ||
                    !Rect.intersects(bounds, screenBounds)
                ) {
                    continue
                }
                canvas.save()
                canvas.clipRect(bounds)
                canvas.drawBitmap(bitmap, bounds.left.toFloat(), bounds.top.toFloat(), null)
                canvas.restore()
            }
            output
        } catch (_: Exception) {
            merged?.recycle()
            null
        } finally {
            captured.values.forEach { (bitmap, _) -> if (!bitmap.isRecycled) bitmap.recycle() }
            allWindows.forEach { w -> runCatching { w.recycle() } }
        }
    }

    private fun restoreClipboardIfStillOwned(
        clipboard: ClipboardManager,
        temporaryLabel: String,
        originalClip: ClipData?,
    ): Boolean {
        val current = runCatching { clipboard.primaryClip }.getOrNull()
        val currentLabel = current?.description?.label?.toString()
        if (currentLabel != temporaryLabel) {
            // Others已经覆盖了剪贴板，我们不要恢复
            return true
        }
        return if (originalClip != null) {
            clipboard.setPrimaryClip(originalClip)
        } else {
            clipboard.clearPrimaryClip()
        }
    }

    private fun clipNodeBounds(node: AccessibilityNodeInfo): Rect {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        return bounds
    }

    private fun findFocusedEditableNode(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused?.isEditable == true) return focused
        return null
    }

    private fun findBestScrollableNode(root: AccessibilityNodeInfo, direction: ScrollDirection): AccessibilityNodeInfo? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.push(root)
        while (stack.isNotEmpty()) {
            val current = stack.pop()
            if (current.isScrollable && direction.isScrollableOn(current)) {
                return current
            }
            for (i in 0 until current.childCount) {
                val child = current.getChild(i) ?: continue
                stack.push(child)
            }
        }
        return null
    }

    private fun scrollContentAnchors(node: AccessibilityNodeInfo): List<Any?> {
        val anchors = mutableListOf<Any?>()
        fun dfs(n: AccessibilityNodeInfo) {
            anchors.add(n.text?.toString())
            anchors.add(n.contentDescription?.toString())
            for (i in 0 until n.childCount) {
                val child = n.getChild(i) ?: continue
                dfs(child)
            }
        }
        dfs(node)
        return anchors
    }

    private fun inferScrollAnchorDelta(beforeAnchors: List<Any?>, afterAnchors: List<Any?>, direction: ScrollDirection): Int? {
        val firstBefore = beforeAnchors.filterNotNull().firstOrNull()
        val firstAfter = afterAnchors.filterNotNull().firstOrNull()
        if (firstBefore == firstAfter) return 0
        return null // 无法确定
    }

    private fun performNodeAction(node: AccessibilityNodeInfo, actionId: Int, args: Bundle? = null): ActionDispatch {
        val result = if (args != null) node.performAction(actionId, args) else node.performAction(actionId)
        return when {
            result -> ActionDispatch.ACCEPTED
            else -> ActionDispatch.REJECTED
        }
    }

    private fun chooseScrollMethod(node: AccessibilityNodeInfo, direction: ScrollDirection): ScrollMethod? {
        return when (direction) {
            ScrollDirection.UP -> ScrollMethod("SCROLL_BACKWARD", AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            ScrollDirection.DOWN -> ScrollMethod("SCROLL_FORWARD", AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            ScrollDirection.LEFT -> ScrollMethod("SCROLL_LEFT", AccessibilityNodeInfo.ACTION_SCROLL_LEFT)
            ScrollDirection.RIGHT -> ScrollMethod("SCROLL_RIGHT", AccessibilityNodeInfo.ACTION_SCROLL_RIGHT)
        }
    }

    private fun setNodeText(node: AccessibilityNodeInfo, text: String, cursor: Int): NodeActionResult {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            return NodeActionResult.success(method = "ACTION_SET_TEXT")
        }
        return NodeActionResult.failure("ACTION_FAILED", "设置文本失败")
    }

    private fun isAtScrollBoundary(node: AccessibilityNodeInfo, direction: ScrollDirection): Boolean {
        return when (direction) {
            ScrollDirection.DOWN -> !node.canScrollForward()
            ScrollDirection.UP -> !node.canScrollBackward()
            ScrollDirection.LEFT -> !node.canScrollRight()
            ScrollDirection.RIGHT -> !node.canScrollLeft()
        }
    }

    private fun dispatchGestureResult(path: Path, durationMs: Long, successMethod: String): NodeActionResult {
        val latch = CountDownLatch(1)
        var result = false
        val callback = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription) {
                result = true
                latch.countDown()
            }
            override fun onCancelled(gestureDescription: GestureDescription) {
                result = false
                latch.countDown()
            }
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, callback, null)
        latch.await(2, TimeUnit.SECONDS)
        return if (result) NodeActionResult.success(method = successMethod) else NodeActionResult.failure("GESTURE_FAILED", "手势执行失败或超时")
    }

    companion object {
        @Volatile var instance: AgentAccessibilityService? = null
            private set

        private val SCREENSHOT_EXECUTOR: ExecutorService = Executors.newSingleThreadExecutor()
        private val SERVICE_TOKENS = AtomicLong(0)
        private val CLIP_IDS = AtomicLong(0)
        private val SNAPSHOT_IDS = AtomicLong(0)
        private const val CLIP_LABEL = "eta_clip"
        private const val MAX_SCROLL_SIGNALS = 64
        private const val SCROLL_GESTURE_DURATION_MS = 100L
        private const val SCROLL_VERIFY_TIMEOUT_MS = 300L
        private const val WINDOW_POLL_FALLBACK_MS = 50L
        private const val UI_TREE_VISIT_MULTIPLIER = 5
        private const val MIN_UI_TREE_VISIT_NODES = 64
        private const val MAX_UI_TREE_VISIT_NODES = 2048
    }
}
