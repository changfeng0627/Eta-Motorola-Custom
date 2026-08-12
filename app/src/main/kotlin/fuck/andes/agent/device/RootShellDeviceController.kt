package fuck.andes.agent.device

import fuck.andes.agent.accessibility.AgentAccessibilityService
import fuck.andes.agent.media.AgentImageCodec
import fuck.andes.agent.model.AgentModelClient
import fuck.andes.core.AgentLogger

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import java.io.IOException
import java.io.StringReader
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParserFactory

internal class RootShellDeviceController(
    private val logger: AgentLogger,
    private val screenshotExcludedPackages: () -> Set<String> = { emptySet() },
) {
    data class Observation(
        val content: String,
        val image: AgentModelClient.ModelImage?,
        val elementObservation: ElementObservation?,
        val coordinateSpace: CoordinateSpace?,
    )

    data class ElementObservation(
        val id: String,
        val source: ElementSource,
        val packageName: String,
        val windowId: Int?,
        val nodes: List<UiNode>,
        val maxNodes: Int,
        val truncated: Boolean,
        val treeSignature: String = "",
        internal val accessibilitySnapshot: AgentAccessibilityService.NodeSnapshot? = null,
    )

    enum class ElementSource(val wireName: String) {
        ACCESSIBILITY("accessibility"),
        UIAUTOMATOR("uiautomator"),
    }

    data class CoordinateSpace(
        val screenWidth: Int,
        val screenHeight: Int,
        val screenshotWidth: Int,
        val screenshotHeight: Int
    ) {
        fun fromScreenshot(x: Int, y: Int): ScreenPoint {
            require(x in 0 until screenshotWidth && y in 0 until screenshotHeight) {
                "($x,$y) not in ${screenshotWidth}x$screenshotHeight"
            }
            return ScreenPoint(
                x = (x.toFloat() * screenWidth / screenshotWidth).toInt(),
                y = (y.toFloat() * screenHeight / screenshotHeight).toInt()
            )
        }

        fun summary(): String =
            "screen=${screenWidth}x$screenHeight,screenshot=${screenshotWidth}x$screenshotHeight"
    }

    data class ScreenPoint(val x: Int, val y: Int)

    fun screenDimensions(): Pair<Int, Int> = screenSize()

    data class UiNode(
        val index: Int,
        val text: String,
        val desc: String,
        val className: String,
        val packageName: String,
        val viewId: String,
        val bounds: Rect,
        val clickable: Boolean,
        val longClickable: Boolean,
        val scrollable: Boolean,
        val focused: Boolean,
        val editable: Boolean,
        val password: Boolean,
        val enabled: Boolean
    ) {
        val centerX: Int get() = bounds.centerX()
        val centerY: Int get() = bounds.centerY()
    }

    fun observe(includeScreenshot: Boolean, includeUiTree: Boolean, maxNodes: Int): Observation {
        val accessibility = AgentAccessibilityService.current()
        val nodeLimit = maxNodes.coerceIn(1, 120)
        val display = screenSize()
        val focus = accessibility
            ?.currentPackageName()
            ?.takeIf { it.isNotBlank() }
            ?.let { packageName ->
                JSONObject()
                    .put("package", packageName)
                    .put("component", packageName)
                    .put("source", "accessibility")
            }
            ?: focusedWindow()
        val elementObservation = if (includeUiTree) {
            val accessibilitySnapshot = accessibility?.captureNodeSnapshot(nodeLimit)
            if (accessibilitySnapshot != null) {
                ElementObservation(
                    id = accessibilitySnapshot.id,
                    source = ElementSource.ACCESSIBILITY,
                    packageName = accessibilitySnapshot.packageName,
                    windowId = accessibilitySnapshot.windowId,
                    nodes = accessibilitySnapshot.nodes.map { it.toUiNode() },
                    maxNodes = nodeLimit,
                    truncated = accessibilitySnapshot.truncated,
                    accessibilitySnapshot = accessibilitySnapshot,
                )
            } else {
                val rootNodes = dumpUiNodes(nodeLimit)
                ElementObservation(
                    id = "u\$ROOT_OBSERVATION_IDS.incrementAndGet()",
                    source = ElementSource.UIAUTOMATOR,
                    packageName = rootNodes.firstOrNull()?.packageName.orEmpty(),
                    windowId = null,
                    nodes = rootNodes,
                    maxNodes = nodeLimit,
                    truncated = rootNodes.size >= nodeLimit,
                    treeSignature = uiTreeSignature(rootNodes),
                )
            }
        } else {
            null
        }
        val nodes = elementObservation?.nodes.orEmpty()
        val capture = if (includeScreenshot) captureScreenshot() else Capture.notRequested()
        val image = capture.image
        val coordinateSpace = if (image?.width != null && image.height != null) {
            CoordinateSpace(
                screenWidth = display.first,
                screenHeight = display.second,
                screenshotWidth = image.width,
                screenshotHeight = image.height,
            )
        } else {
            null
        }
        val json = JSONObject()
            .put("ok", true)
            .put("tool", "observe_screen")
            .put("screen", JSONObject().put("width", display.first).put("height", display.second))
            .put(
                "accessibility", JSONObject()
                    .put("available", accessibility != null)
                    .put("package", accessibility?.currentPackageName().orEmpty())
                    .put(
                        "note", if (accessibility != null) {
                        "tap_element (tap) replace_text clear_text scroll_element 实现了快速、精确的 UI 操作"
                    } else {
                        "uiautomator 实现了快速、精确的 UI 操作"
                    }
                    )
            )
            .put(
                "coordinate_contracts", if (coordinateSpace == null) {
                JSONObject()
                    .put("default_coordinate_space", "screen")
                    .put("note", "屏幕坐标在未捕获截图时默认使用屏幕坐标")
            } else {
                JSONObject()
                    .put("default_coordinate_space", "screenshot")
                    .put(
                        "screen", JSONObject()
                            .put("width", coordinateSpace.screenWidth)
                            .put("height", coordinateSpace.screenHeight)
                    )
                    .put(
                        "screenshot", JSONObject()
                            .put("width", coordinateSpace.screenshotWidth)
                            .put("height", coordinateSpace.screenshotHeight)
                    )
                    .put(
                        "scale_to_screen", JSONObject()
                            .put("x", coordinateSpace.screenWidth.toDouble() / coordinateSpace.screenshotWidth)
                            .put("y", coordinateSpace.screenHeight.toDouble() / coordinateSpace.screenshotHeight)
                    )
                    .put("note", "long_press swipe 需要 screen 坐标；tap_area long_press_area swipe_area element centre 使用 ui_nodes 的 center; screen 使用截图")
            )
            .put("focus", focus)
            .put("observation_id", elementObservation?.id ?: JSONObject.NULL)
            .put("observation_source", elementObservation?.source?.wireName ?: JSONObject.NULL)
            .put("window_id", elementObservation?.windowId ?: JSONObject.NULL)
            .put("node_limit", elementObservation?.maxNodes ?: 0)
            .put("ui_tree_truncated", elementObservation?.truncated ?: false)
            .put("ui_nodes", nodes.toJsonArray())
            .put(
                "screenshot", if (image == null) {
                capture.toJson().put("attached", false)
            } else {
                capture.toJson()
                    .put("attached", true)
                    .put("mime_type", image.mimeType)
                    .put("bytes", image.bytes)
                    .put("width", image.width)
                    .put("height", image.height)
            }
            )
        return Observation(json.toString(), image, elementObservation, coordinateSpace)
    }

    fun tap(x: Int, y: Int): String {
        validatePoint(x, y)
        val accessibility = AgentAccessibilityService.current()?.let { service ->
            val result = service.gestureTap(x.toFloat(), y.toFloat())
            if (result.ok) {
                waitForUiSettle("tap")
                return nodeActionJson("tap", result)
            }
            if (!GestureFallbackPolicy.mayFallbackToRoot(result.code)) {
                return nodeActionJson("tap", result)
            }
        }
        return inputCommand("input tap $x $y", "tap")
    }

    fun longPress(x: Int, y: Int, durationMs: Int): String {
        validatePoint(x, y)
        val duration = durationMs.coerceIn(300, 3_000)
        AgentAccessibilityService.current()?.let { service ->
            val result = service.gestureTap(x.toFloat(), y.toFloat(), duration.toLong())
            if (result.ok) {
                waitForUiSettle("long_press")
                return nodeActionJson("long_press", result.copy(method = "GESTURE_LONG_PRESS"))
            }
            if (!GestureFallbackPolicy.mayFallbackToRoot(result.code)) {
                return nodeActionJson("long_press", result)
            }
        }
        return inputCommand("input swipe $x $y $x $y $duration", "long_press")
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): String {
        validatePoint(x1, y1)
        validatePoint(x2, y2)
        val duration = durationMs.coerceIn(100, 2_000)
        AgentAccessibilityService.current()?.let { service ->
            val result = service.gestureSwipe(
                x1.toFloat(), y1.toFloat(),
                x2.toFloat(), y2.toFloat(),
                duration.toLong(),
            )
            if (result.ok) {
                waitForUiSettle("swipe")
                return nodeActionJson("swipe", result)
            }
            if (!GestureFallbackPolicy.mayFallbackToRoot(result.code)) {
                return nodeActionJson("swipe", result)
            }
        }
        return inputCommand("input swipe $x1 $y1 $x2 $y2 $duration", "swipe")
    }

    fun scroll(direction: String): String {
        val parsed = ScrollDirection.parse(direction)
            ?: return scrollErrorJson(
                "scroll",
                null,
                "INVALID_ARGUMENT",
                "direction must be up/down/left/right",
            )
        AgentAccessibilityService.current()?.let { service ->
            return scrollActionJson("scroll", service.scrollCurrent(parsed))
        }
        val beforeNodes = dumpUiNodes(120)
        val targetBounds = beforeNodes
            .asSequence()
            .filter(UiNode::scrollable)
            .maxByOrNull { node -> node.bounds.width().toLong() * node.bounds.height().toLong() }
            ?.bounds
            ?: screenContentBounds()
        return rootScroll(
            tool = "scroll",
            direction = parsed,
            bounds = targetBounds,
            beforeNodes = beforeNodes,
            maxNodes = 120,
            targetIndex = null,
        )
    }

    fun inputText(text: String): String {
        if (text.isEmpty()) return errorJson("INVALID_ARGUMENT", "text cannot be empty")
        if (text.length > MAX_INPUT_TEXT_CHARS) {
            return errorJson("TEXT_TOO_LONG", "input_text length must <= $MAX_INPUT_TEXT_CHARS chars")
        }
        AgentAccessibilityService.current()?.let { service ->
            val result = service.inputTextFocused(text)
            if (result.ok) {
                waitForUiSettle("input_text")
                return nodeActionJson("input_text", result)
            }
            return nodeActionJson("input_text", result)
        }
        return errorJson(
            "ACCESSIBILITY_UNAVAILABLE",
            "input_text requires accessibility service, please check accessibility permission is enabled"
        )
    }

    fun replaceText(
        text: String,
        index: Int?,
        observation: ElementObservation?,
    ): String {
        if (text.length > MAX_REPLACE_TEXT_CHARS) {
            return errorJson("TEXT_TOO_LONG", "replace_text length must <= $MAX_REPLACE_TEXT_CHARS chars")
        }
        AgentAccessibilityService.current()?.let { service ->
            val snapshot = observation?.accessibilitySnapshot
            val result = service.setTextNode(snapshot, index, text)
            if (result.ok) {
                waitForUiSettle("replace_text")
                return nodeActionJson("replace_text", result)
            }
            return nodeActionJson("replace_text", result)
        }
        return errorJson(
            "ACCESSIBILITY_UNAVAILABLE",
            "replace_text requires accessibility service"
        )
    }

    fun clearText(index: Int?, observation: ElementObservation?): String =
        replaceText("", index, observation).let { result ->
            val json = JSONObject(result)
            json.put("tool", "clear_text").toString()
        }

    fun tapElement(observation: ElementObservation, index: Int): String {
        val snapshot = observation.accessibilitySnapshot
        if (snapshot != null) {
            val service = AgentAccessibilityService.current()
                ?: return errorJson("ACCESSIBILITY_UNAVAILABLE", "node service not available")
            val result = service.clickNode(snapshot, index)
            if (result.ok) {
                waitForUiSettle("tap")
                return nodeActionJson("tap_element", result)
            }
            return nodeActionJson("tap_element", result)
        }
        val resolved = resolveUiAutomationNode(observation, index)
            ?: return errorJson("STALE_NODE", "node not found in current UI tree, may have changed")
        val node = resolved.currentNode
        return tap(node.centerX, node.centerY).rewriteTool("tap_element")
    }

    fun longPressElement(
        observation: ElementObservation,
        index: Int,
        durationMs: Int,
    ): String {
        val snapshot = observation.accessibilitySnapshot
        if (snapshot != null) {
            val service = AgentAccessibilityService.current()
                ?: return errorJson("ACCESSIBILITY_UNAVAILABLE", "node service not available")
            val result = service.longClickNode(snapshot, index, durationMs.toLong())
            if (result.ok) {
                waitForUiSettle("long_press")
                return nodeActionJson("long_press_element", result)
            }
            return nodeActionJson("long_press_element", result)
        }
        val resolved = resolveUiAutomationNode(observation, index)
            ?: return errorJson("STALE_NODE", "node not found in current UI tree, may have changed")
        val node = resolved.currentNode
        return longPress(node.centerX, node.centerY, durationMs).rewriteTool("long_press_element")
    }

    fun scrollElement(
        observation: ElementObservation,
        index: Int,
        direction: String,
    ): String {
        val parsed = ScrollDirection.parse(direction)
            ?: return scrollErrorJson(
                "scroll_element",
                null,
                "INVALID_ARGUMENT",
                "direction must be up/down/left/right",
            )
        val snapshot = observation.accessibilitySnapshot
        if (snapshot != null) {
            val service = AgentAccessibilityService.current()
                ?: return errorJson("ACCESSIBILITY_UNAVAILABLE", "node service not available")
            val result = service.scrollNode(snapshot, index, parsed)
            if (result.ok) {
                return scrollActionJson(
                    "scroll_element",
                    result,
                )
            }
            return nodeActionJson("scroll_element", result)
        }
        val resolved = resolveUiAutomationNode(observation, index)
            ?: return errorJson("STALE_NODE", "node not found in current UI tree, may have changed")
        val node = resolved.currentNode
        if (!node.scrollable) {
            return scrollErrorJson(
                "scroll_element",
                parsed,
                "NOT_SCROLLABLE",
                "node is not scrollable",
            )
        }
        return rootScroll(
            tool = "scroll_element",
            direction = parsed,
            bounds = node.bounds,
            beforeNodes = resolved.currentNodes,
            maxNodes = observation.maxNodes,
            targetIndex = index,
        )
    }

    fun pressKey(button: String): String {
        val normalized = button
        AgentAccessibilityService.current()?.let { service ->
            when (normalized) {
                "BACK", "HOME", "RECENTS", "NOTIFICATIONS", "QUICK_SETTINGS" -> {
                    val actionResult = service.globalActionResult(normalized)
                    if (actionResult.ok) {
                        waitForUiSettle("press_key")
                        return nodeActionJson("press_key", it.ok { "accessibility" }).let {
                            JSONObject(it).put("button", normalized).toString()
                        }
                    }
                    if (actionResult.code == "ACTION_OUTCOME_UNKNOWN") {
                        return nodeActionJson("press_key", actionResult).let {
                            JSONObject(it).put("button", normalized).toString()
                        }
                    }
                }
                "ENTER" -> {
                    val result = service.imeEnter()
                    if (result.ok) {
                        waitForUiSettle("press_key")
                        return nodeActionJson("press_key", result).let {
                            JSONObject(it).put("button", normalized).toString()
                        }
                    }
                    return nodeActionJson("press_key", result).let {
                        JSONObject(it).put("button", normalized).toString()
                    }
                }
            }
        }
        val keyCodes = when (normalized) {
            "BACK" -> 4
            "HOME" -> 3
            "ENTER" -> 66
            "RECENTS" -> 187
            "PASTE" -> 279
            "NOTIFICATIONS" -> return inputCommand(
                "cmd statusbar expand-notifications",
                "press_key",
            )
            "QUICK_SETTINGS" -> return inputCommand(
                "cmd statusbar expand-settings",
                "press_key",
            )
            else -> return errorJson("INVALID_ARGUMENT", "button must be BACK/HOME/ENTER/RECENTS/PASTE/NOTIFICATIONS/QUICK_SETTINGS")
        }
        return inputCommand("input keyevent $keyCode", "press_key")
    }

    fun waitMs(durationMs: Int): String {
        val duration = durationMs.coerceIn(100, 30_000)
        Thread.sleep(duration.toLong())
        return JSONObject()
            .put("ok", true)
            .put("tool", "wait")
            .put("duration_ms", duration)
            .toString()
    }

    fun waitForText(text: String, timeoutMs: Int, includeDesc: Boolean, matchMode: String): String {
        val needle = text.trim()
        if (needle.isBlank()) return errorJson("INVALID_ARGUMENT", "text cannot be empty")
        val timeout = timeoutMs.coerceIn(500, 60_000)
        val deadline = System.currentTimeMillis() + timeout
        var attempts = 0
        while (System.currentTimeMillis() <= deadline) {
            attempts++
            val nodes = AgentAccessibilityService.current()
                ?.queryNodes(120)
                ?.map { it.toUiNode() }
                ?.takeIf { it.isNotEmpty() }
                ?: dumpUiNodes(120)
            val match = nodes.firstOrNull { node ->
                val haystacks = if (includeDesc) listOf(node.text, node.desc) else listOf(node.text)
                haystacks.any { value -> matches(value, needle, matchMode) }
            }
            if (match != null) {
                val matchedNode = match.toJson()
                matchedNode.remove("index")
                matchedNode.put("actionable", false)
                return JSONObject()
                    .put("ok", true)
                    .put("tool", "wait_for_text")
                    .put("attempts", attempts)
                    .put("matched_node", matchedNode)
                    .put("note", "text found; you can now tap_element replace_text clear_text scroll_element observe_screen")
                    .toString()
            }
            Thread.sleep(350)
        }
        return JSONObject()
            .put("ok", false)
            .put("tool", "wait_for_text")
            .put("code", "TIMEOUT")
            .put("message", "text '$needle' not found")
            .put("attempts", attempts)
            .toString()
    }

    fun waitForPackage(packageName: String, timeoutMs: Int): String {
        val target = packageName.trim()
        if (target.isBlank()) return errorJson("INVALID_ARGUMENT", "package_name cannot be empty")
        val timeout = timeoutMs.coerceIn(500, 60_000)
        val deadline = System.currentTimeMillis() + timeout
        var attempts = 0
        var lastPackage = ""
        while (System.currentTimeMillis() <= deadline) {
            attempts++
            lastPackage = AgentAccessibilityService.current()?.currentPackageName().orEmpty()
            if (lastPackage == target) {
                return JSONObject()
                    .put("ok", true)
                    .put("tool", "wait_for_package")
                    .put("package_name", target)
                    .put("attempts", attempts)
                    .toString()
            }
            val focus = focusedWindow()
            if (focus.optString("package") == target) {
                return JSONObject()
                    .put("ok", true)
                    .put("tool", "wait_for_package")
                    .put("package_name", target)
                    .put("attempts", attempts)
                    .put("focus", focus)
                    .toString()
            }
            Thread.sleep(350)
        }
        return JSONObject()
            .put("ok", false)
            .put("tool", "wait_for_package")
            .put("code", "TIMEOUT")
            .put("message", "package '$target' not reached")
            .put("last_package", lastPackage)
            .put("attempts", attempts)
            .toString()
    }

    fun clipboardSet(context: Context, text: String): String {
        if (text.length > MAX_CLIPBOARD_TEXT_CHARS) {
            return errorJson("TEXT_TOO_LONG", "clipboard text must <= $MAX_CLIPBOARD_TEXT_CHARS chars")
        }
        val serviceResult = AgentAccessibilityService.current()?.copyToClipboard(text)
        val ok = serviceResult?.ok ?: run {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("fuck_ands_agent", text))
            true
        }
        val json = JSONObject()
            .put("ok", ok)
            .put("tool", "set_clipboard")
            .put("chars", text.length)
        if (!ok) {
            json
                .put("code", serviceResult?.code ?: "CLIPBOARD_WRITE_FAILED")
                .put(
                    "message", serviceResult?.message?.ifBlank {
                        "failed to write clipboard"
                    } ?: "failed to write clipboard"
                )
        }
        return json.toString()
    }

    fun clipboardGet(context: Context): String {
        val serviceResult = AgentAccessibilityService.current()?.readClipboard()
        val result = serviceResult ?: run {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip
            if (clip == null || clip.itemCount <= 0) {
                AgentAccessibilityService.ClipboardReadResult.failure()
            } else {
                AgentAccessibilityService.ClipboardReadResult(
                    ok = true,
                    text = clip.getItemAt(0).coerceToText(context)?.toString().orEmpty(),
                )
            }
        }
        val json = JSONObject()
            .put("ok", result.ok)
            .put("tool", "get_clipboard")
            .put("text", result.text.take(8_000))
            .put("truncated", result.text.length > 8_000)
        if (!result.ok) {
            json
                .put("code", result.code)
                .put("message", "failed to read clipboard")
        }
        return json.toString()
    }

    fun pasteText(text: String): String {
        if (text.length > MAX_CLIPBOARD_TEXT_CHARS) {
            return errorJson("TEXT_TOO_LONG", "paste_text length must <= $MAX_CLIPBOARD_TEXT_CHARS chars")
        }
        val serviceResult = AgentAccessibilityService.current()?.pasteText(text)
        val ok = serviceResult?.ok ?: run {
            runCatching {
                val clipboard = AgentAccessibilityService.current()
                    ?.let { it as? Context } ?: return errorJson(
                    "ACCESSIBILITY_UNAVAILABLE",
                    "paste_text requires accessibility service"
                )
                val cm = clipboard.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("fuck_ands_agent", text))
                true
            }.getOrDefault(false)
        }
        val json = JSONObject()
            .put("ok", ok)
            .put("tool", "paste_text")
            .put("chars", text.length)
        if (!ok) {
            json
                .put("code", serviceResult?.code ?: "CLIPBOARD_WRITE_FAILED")
                .put(
                    "message", serviceResult?.message?.ifBlank {
                        "failed to paste text"
                    } ?: "failed to paste text"
                )
        }
        return json.toString()
    }

    fun openSystemPanel(panel: String): String {
        val normalized = panel
        val accessibilityAction = when (normalized) {
            "notifications", "notification" -> "NOTIFICATIONS"
            "quick_settings", "quicksettings", "settings" -> "QUICK_SETTINGS"
            else -> return errorJson("INVALID_ARGUMENT", "panel must be notifications/quick_settings")
        }
        AgentAccessibilityService.current()?.let { service ->
            val actionResult = service.globalActionResult(accessibilityAction)
            if (actionResult.ok) {
                waitForUiSettle("press_key")
                return nodeActionJson("open_system_panel", it.ok { "accessibility" })
            }
            if (actionResult.code == "ACTION_OUTCOME_UNKNOWN") {
                return nodeActionJson("open_system_panel", actionResult)
            }
        }
        val command = when (accessibilityAction) {
            "NOTIFICATIONS" -> "cmd statusbar expand-notifications"
            else -> "cmd statusbar expand-settings"
        }
        return inputCommand(command, "open_system_panel")
    }

    private fun captureScreenshot(): Capture {
        val excludedPackages = screenshotExcludedPackages()
        // takeScreenshotOfOverlay 要求 TYPE_ACCESSIBILITY_OVERLAY 权限，但不需要
        // grow/orb/bubble 作为 Agent 的一部分
        val accessibility = AgentAccessibilityService.current()
        if (accessibility != null) {
            val captureStartedAt = SystemClock.elapsedRealtime()
            val result = runCatching {
                accessibility.captureScreenshotExcludingOverlays(excludedPackages)
            }.getOrElse { throwable ->
                logger.warn(
                    "Agent device action=encode_screen output=failed " +
                        "source=accessibility type=${throwable.javaClass.simpleName}"
                )
                null
            }
            val bitmap = result?.bitmap
            if (bitmap != null) {
                val capturedAt = SystemClock.elapsedRealtime()
                val image = try {
                    AgentImageCodec.fromScreenBitmap(bitmap, source = "screen")
                } catch (throwable: Throwable) {
                    logger.warn(
                        "Agent device action=encode_screen output=failed " +
                            "source=accessibility type=${throwable.javaClass.simpleName}"
                    )
                    null
                } finally {
                    bitmap.recycle()
                }
                if (image != null) {
                    logger.debug {
                        "Agent device action=capture_screenshot output=completed source=accessibility " +
                            "capture_ms=${capturedAt - captureStartedAt} " +
                            "encode_ms=${SystemClock.elapsedRealtime() - capturedAt} " +
                            "image=${image.width}x${image.height} bytes=${image.bytes}"
                    }
                }
                if (image != null && image.bytes > 0) {
                    return Capture(
                        image = image,
                        source = "accessibility",
                        complete = result.complete,
                        partial = result.partial,
                        expectedWindows = result.expectedWindows,
                        capturedWindows = result.capturedWindows,
                        missingWindowIds = result.missingWindowIds,
                        failureCodes = result.failureCodes,
                        timedOut = result.timedOut,
                        criticalWindowMissing = result.criticalWindowMissing,
                    )
                }
            }
            if (result?.criticalWindowMissing == true) {
                logger.warn(
                    "Agent device action=capture_screenshot output=failed " +
                        "reason=critical_window_missing failures=${result.failureCodes.size}"
                )
                return Capture(
                    image = null,
                    source = "accessibility",
                    complete = false,
                    partial = false,
                    expectedWindows = result.expectedWindows,
                    capturedWindows = result.capturedWindows,
                    missingWindowIds = result.missingWindowIds,
                    failureCodes = result.failureCodes,
                    timedOut = result.timedOut,
                    criticalWindowMissing = true,
                )
            }
        }
        if (!ScreenshotOutcomePolicy.mayFallbackToRoot(
                excludedPackagesPresent = excludedPackages.isNotEmpty(),
                criticalWindowMissing = false,
            )
        ) {
            logger.warn(
                "Agent device action=capture_screenshot output=failed " +
                    "reason=package_exclusion_unavailable excludedPackages=${excludedPackages.size}"
            )
            return Capture.failed(source = "accessibility")
        }
        logger.debug {
            "Agent device action=capture_screenshot output=fallack source=root"
        }
        val result = runSuBytes("screencap -p", timeoutSeconds = 8)
        if (result.exitCode != 0 || result.output.isEmpty()) {
            logger.warn(
                "Agent device action=capture_screenshot output=failed source=root " +
                    "exitCode=${result.exitCode} outputBytes=${result.output.size} " +
                    "errorChars=${result.stderr.length}"
            )
            return Capture.failed(source = "root")
        }
        val encodeStartedAt = SystemClock.elapsedRealtime()
        val image = runCatching {
            AgentImageCodec.fromScreenBytes(result.output, source = "screen")
        }.getOrElse { throwable ->
            logger.warn(
                "Agent device action=encode_screen output=failed source=root type=${throwable.javaClass.simpleName}"
            )
            null
        } ?: return Capture.failed(source = "root")
        logger.debug {
            "Agent device action=capture_screenshot output=completed source=root " +
                "encode_ms=${SystemClock.elapsedRealtime() - encodeStartedAt} " +
                "image=${image.width}x${image.height} byte=${image.bytes}"
        }
        return Capture(
            image = image,
            source = "root",
            complete = true,
            partial = false,
            expectedWindows = 1,
            capturedWindows = 1,
        )
    }

    private fun dumpUiNodes(maxNodes: Int): List<UiNode> {
        val result = runSuText(
            "uiautomator dump --compressed /data/local/tmp/fuck_ands_window.xml >/dev/null 2>&1 && " +
                "cat /data/local/tmp/fuck_ands_window.xml && rm -f /data/local/tmp/fuck_ands_window.xml",
            timeoutSeconds = 10
        )
        if (result.exitCode != 0 || result.output.isBlank()) {
            logger.warn(
                "Agent device action=dump_ui output=failed source=root " +
                    "exitCode=${result.exitCode} outputChars=${result.output.length}"
            )
            return emptyList()
        }
        return parseUiNodes(result.output, maxNodes)
    }

    private fun parseUiNodes(xml: String, maxNodes: Int): List<UiNode> {
        val nodes = mutableListOf<UiNode>()
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(StringReader(xml))
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT && nodes.size < maxNodes) {
            if (event == XmlPullParser.START_TAG && parser.name == "node") {
                val visible = parser.getAttributeValue(null, "visible-to-user") != "false"
                val bounds = parser.getAttributeValue(null, "bounds").toRectOrNull()
                if (visible && bounds != null && bounds.width() > 2 && bounds.height() > 2) {
                    val text = parser.getAttributeValue(null, "text").take(120)
                    val desc = parser.getAttributeValue(null, "content-desc").take(120)
                    val clickable = parser.getAttributeValue(null, "clickable").toBoolean()
                    val scrollable = parser.getAttributeValue(null, "scrollable").toBoolean()
                    val focused = parser.getAttributeValue(null, "focused").toBoolean()
                    val enabled = parser.getAttributeValue(null, "enabled") != "false"
                    if (text.isNotBlank() || desc.isNotBlank() || clickable || scrollable || focused) {
                        nodes += UiNode(
                            index = nodes.size,
                            text = text,
                            desc = desc,
                            className = parser.getAttributeValue(null, "class"),
                            packageName = parser.getAttributeValue(null, "package"),
                            viewId = parser.getAttributeValue(null, "resource-id"),
                            bounds = bounds,
                            clickable = clickable,
                            longClickable = parser.getAttributeValue(null, "long-clickable").toBoolean(),
                            scrollable = scrollable,
                            focused = focused,
                            editable = parser.getAttributeValue(null, "class").contains("EditText", ignoreCase = true),
                            password = parser.getAttributeValue(null, "password").toBoolean(),
                            enabled = enabled
                        )
                    }
                }
            }
            event = parser.next()
        }
        return nodes
    }

    private fun focusedWindow(): JSONObject {
        val result = runSuText("dumpsys window", timeoutSeconds = 8)
        val focused = FocusedWindowParser.parse(result.output)
        return JSONObject()
            .put("raw", focused?.rawLine?.take(240).orEmpty())
            .put("component", focused?.component.orEmpty())
            .put("package", focused?.packageName.orEmpty())
    }

    private fun screenSize(): Pair<Int, Int> {
        val result = runSuText("wm size", timeoutSeconds = 5)
        return AndroidDisplaySizeParser.parse(result.output)
            ?.let { it.overrideWidth to it.overrideHeight }
            ?: error("failed to parse wm size: ${result.output.take(160)}")
    }

    private fun screenContentBounds(): Rect {
        val (width, height) = screenSize()
        return Rect(
            0,
            (height * 0.1f).toInt(),
            width,
            (height * 0.9f).toInt(),
        )
    }

    private fun rootScroll(
        tool: String,
        direction: ScrollDirection,
        bounds: Rect,
        beforeNodes: List<UiNode>,
        maxNodes: Int,
        targetIndex: Int?,
    ): String {
        val startedAt = SystemClock.elapsedRealtime()
        val gesture = direction.gestureWithin(bounds)
            ?: return scrollErrorJson(
                tool,
                direction,
                "INVALID_GESTURE",
                "cannot compute scroll gesture within bounds",
            )
        val result = runSuText(
            "input swipe ${gesture.start.x} ${gesture.start.y} ${gesture.end.x} ${gesture.end.y} 300",
            timeoutSeconds = 8,
        )
        val commandOutcome = ShellActionOutcomePolicy.classify(result.exitCode)
        if (commandOutcome == ShellActionOutcomePolicy.Outcome.FAILED) {
            return scrollErrorJson(
                tool,
                direction,
                "COMMAND_FAILED",
                result.output.ifBlank { "exit=${result.exitCode}" },
            )
        }
        if (commandOutcome == ShellActionOutcomePolicy.Outcome.TIMED_OUT) {
            return scrollErrorJson(
                tool,
                direction,
                "ACTION_OUTCOME_UNKNOWN",
                "Root command timed out, may not have been applied"
            )
        }
        Thread.sleep(450L)
        val afterNodes = dumpUiNodes(maxNodes)
        val inferredDelta = RootScrollMotionContract.inferScrollDelta(
            beforeNodes, afterNodes, bounds, direction)
        val evidence = ScrollEvidenceContract.classify(
            direction = direction,
            delta = inferredDelta,
            movementSource = if (inferredDelta != null) ScrollMovementSource.ANCHOR_MOTION else null,
            atBoundary = false,
        )
        val moved = evidence == ScrollEvidence.MOVED_BY_ANCHOR_MOTION
        val json = JSONObject()
            .put("ok", moved)
            .put("tool", tool)
            .put("direction", direction.name.lowercase())
            .put("moved", moved)
            .put("at_boundary", false)
            .put("executor", "root")
            .put("method", "INPUT_SWIPE")
            .put("verified_by", if (moved) "ui_node_motion" else "none")
            .put("elapsed_ms", SystemClock.elapsedRealtime() - startedAt)
        if (inferredDelta != null) {
            if (direction.axis == ScrollAxis.VERTICAL) {
                json.put("delta_y", inferredDelta)
            } else {
                json.put("delta_x", inferredDelta)
            }
        }
        if (targetIndex != null) json.put("target_index", targetIndex)
        if (!moved) {
            when (evidence) {
                ScrollEvidence.TIMED_OUT ->
                    json
                        .put("code", "ACTION_OUTCOME_UNKNOWN")
                        .put("message", "Root command timed out, may not have been applied")
                ScrollEvidence.DIRECTION_MISMATCH ->
                    json
                        .put("code", "DIRECTION_MISMATCH")
                        .put("message", "scroll moved in the opposite direction")
                ScrollEvidence.NOT_SCROLLABLE ->
                    json
                        .put("code", "NOT_SCROLLABLE")
                        .put("message", "node is not scrollable")
                else -> {
                    json
                        .put("code", "ACTION_OUTCOME_UNKNOWN")
                        .put("message", "scroll cannot be determined, may not have been applied")
                }
            }
        }
        return json.toString()
    }

    private fun resolveUiAutomationNode(
        observation: ElementObservation,
        index: Int,
    ): ResolvedUiAutomationNode? {
        if (observation.source != ElementSource.UIAUTOMATOR) return null
        val currentNode = dumpUiNodes(observation.maxNodes).firstOrNull { it.index == index }
            ?: return null
        return ResolvedUiAutomationNode(currentNode, currentNode)
    }

    private fun resolveUiAutomationNode(
        observation: ElementObservation,
        index: Int,
        region: Rect,
    ): ResolvedUiAutomationNode? {
        if (observation.source != ElementSource.UIAUTOMATOR) return null
        val currentNodes = dumpUiNodes(observation.maxNodes)
        val original = currentNodes.firstOrNull { it.index == index } ?: return null
        val matched = currentNodes.filter { candidate ->
            candidate.packageName == original.packageName &&
                candidate.className == original.className &&
                candidate.viewId == original.viewId &&
                candidate.text == original.text &&
                candidate.desc == original.desc &&
                candidate.bounds == original.bounds &&
                candidate.clickable == original.clickable &&
                candidate.scrollable == original.scrollable &&
                candidate.editable == original.editable &&
                candidate.password == original.password &&
                candidate.enabled == original.enabled
        }
        val current = matched.singleOrNull() ?: matched.firstOrNull() ?: return null
        return ResolvedUiAutomationNode(current, current)
    }

    /**
     * Root 在 a]10 附近无法完成 tap，尝试在 a]10 内完成。
     * grow/orb/bubble 作为 Agent 的一部分无法完成 tap，也走这个逻辑。
     */
    private fun waitForUiSettle(tool: String) {
        val delayMs = when (tool) {
            "tap", "long_press", "press_key" -> 350L
            "swipe" -> 650L
            "input_text" -> 500L
            else -> 250L
        }
        Thread.sleep(delayMs)
    }

    private fun validatePoint(x: Int, y: Int) {
        val (width, height) = screenSize()
        require(x in 0 until width && y in 0 until height) {
            "($x,$y) not in ${width}x$height"
        }
    }

    private fun UiNode.toJson(): JSONObject {
        val node = JSONObject()
        node.put("index", index)
        node.put("text", text)
        node.put("desc", desc)
        node.put("class", className)
        node.put("package", packageName)
        node.put("view_id", viewId)
        node.put("bounds", JSONObject().put("left", bounds.left).put("top", bounds.top)
            .put("right", bounds.right).put("bottom", bounds.bottom))
        node.put("clickable", clickable)
        node.put("long_clickable", longClickable)
        node.put("scrollable", scrollable)
        node.put("focused", focused)
        node.put("editable", editable)
        node.put("password", password)
        node.put("enabled", enabled)
        return node
    }

    private fun List<UiNode>.toJsonArray(): JSONArray {
        val array = JSONArray()
        for (node in this) {
            array.put(node.toJson())
        }
        return array
    }

    private fun UiNode.toUiNode(): UiNode = this

    private fun nodeActionJson(tool: String, result: AgentAccessibilityService.AccessibilityActionResult): String =
        result.ok {
            "accessibility"
        }.let {
            JSONObject(it)
                .put("tool", tool)
                .toString()
        }

    private fun scrollActionJson(tool: String, result: AgentAccessibilityService.AccessibilityActionResult): String =
        result.ok { "accessibility" }
            .let { resultJson ->
                JSONObject(resultJson)
                    .put("tool", tool)
                    .put("method", "ACCESSIBILITY")
                    .toString()
            }

    private fun scrollErrorJson(
        tool: String,
        direction: ScrollDirection?,
        code: String,
        message: String,
    ): String {
        val json = JSONObject()
            .put("ok", false)
            .put("tool", tool)
            .put("code", code)
            .put("message", message)
        if (direction != null) json.put("direction", direction.name.lowercase())
        return json.toString()
    }

    private fun errorJson(code: String, message: String): String =
        JSONObject()
            .put("ok", false)
            .put("code", code)
            .put("message", message)
            .toString()

    private fun inputCommand(command: String, tool: String): String {
        val result = runSuText(command, timeoutSeconds = 8)
        return when (ShellActionOutcomePolicy.classify(result.exitCode)) {
            ShellActionOutcomePolicy.Outcome.SUCCESS -> {
                waitForUiSettle(tool)
                JSONObject()
                    .put("ok", true)
                    .put("tool", tool)
                    .put("method", "ROOT_SHELL")
                    .toString()
            }
            ShellActionOutcomePolicy.Outcome.TIMED_OUT ->
                errorJson("ACTION_OUTCOME_UNKNOWN", "Root command timed out")
            ShellActionOutcomePolicy.Outcome.FAILED ->
                errorJson("COMMAND_FAILED", "root command failed: exit=${result.exitCode}")
        }
    }

    private fun runSuText(command: String, timeoutSeconds: Int): CommandResult {
        val bytes = runSuBytes(command, timeoutSeconds)
        return CommandResult(bytes.exitCode, bytes.output.decodeToString(), bytes.stderr.decodeToString())
    }

    private fun runSuBytes(command: String, timeoutSeconds: Int): CommandResultBytes {
        val executor = BoundedRootCommandExecutor
        val result = executor.exec(
            commands = listOf(command),
            timeoutMs = timeoutSeconds.toLong() * 1_000L,
        )
        return CommandResultBytes(result.exitCode, result.stdout.toByteArray(), result.stderr.toByteArray())
    }

    data class CommandResult(val exitCode: Int, val output: String, val stderr: String)
    data class CommandResultBytes(val exitCode: Int, val output: ByteArray, val stderr: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as CommandResultBytes
            return exitCode == other.exitCode && output.contentEquals(other.output) && stderr.contentEquals(other.stderr)
        }
        override fun hashCode(): Int {
            var result = exitCode
            result = 31 * result + output.contentHashCode()
            result = 31 * result + stderr.contentHashCode()
            return result
        }
    }

    data class Capture(
        val image: AgentModelClient.ModelImage?,
        val source: String,
        val complete: Boolean,
        val partial: Boolean,
        val expectedWindows: Int = 0,
        val capturedWindows: Int = 0,
        val missingWindowIds: List<Int> = emptyList(),
        val failureCodes: List<String> = emptyList(),
        val timedOut: Boolean = false,
        val criticalWindowMissing: Boolean = false,
    ) {
        companion object {
            fun notRequested() = Capture(null, "none", false, false)
            fun failed(source: String) = Capture(null, source, false, false)
        }

        fun toJson(): JSONObject = JSONObject()
            .put("source", source)
            .put("complete", complete)
            .put("partial", partial)
    }

    data class ResolvedUiAutomationNode(
        val currentNode: UiNode,
        val snapshotNode: UiNode,
    )

    companion object {
        private val ROOT_OBSERVATION_IDS = AtomicLong(0)
        private const val MAX_INPUT_TEXT_CHARS = 1000
        private const val MAX_REPLACE_TEXT_CHARS = 500
        private const val MAX_CLIPBOARD_TEXT_CHARS = 8000
    }
}