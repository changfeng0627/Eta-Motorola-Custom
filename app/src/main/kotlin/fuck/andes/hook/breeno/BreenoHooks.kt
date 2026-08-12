package fuck.andes.hook.breeno

import fuck.andes.agent.model.AgentModelClient
import fuck.andes.agent.runtime.AgentEvent
import fuck.andes.agent.runtime.AgentAppContext
import fuck.andes.agent.runtime.AgentExternalArchivePayload
import fuck.andes.agent.runtime.AgentRuntimeClient
import fuck.andes.agent.runtime.AgentRuntimeWire
import fuck.andes.core.HookInstallation
import fuck.andes.core.HookRegistrar
import fuck.andes.core.HookSupport
import fuck.andes.core.ModuleLogger
import fuck.andes.core.safeLogType
import fuck.andes.core.toSafeLogToken

import android.os.Handler
import android.os.Looper
import fuck.andes.config.Prefs
import fuck.andes.data.model.ReasoningEffort
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.security.MessageDigest
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject

internal object BreenoHooks {
    private const val MESSAGE_QUEUE_MANAGER_CLASS =
        "com.heytap.speech.engine.connect.core.manager.MessageQueueManager"
    private const val MESSAGE_CLASS = "com.heytap.speech.engine.protocol.event.Message"
    private const val MESSAGE_PROCESSOR_CLASS =
        "com.heytap.speech.engine.connect.core.manager.i"
    private const val CDM_NODE_CLASS = "com.heytap.speech.engine.nodes.a"
    private const val DM_PARAMETER_CLASS = "com.heytap.speech.engine.nodes.DmParameter"
    private const val HEYTAP_SPEECH_ENGINE_CLASS = "com.heytap.speech.engine.heytap"
    private const val DIRECTIVE_CLASS = "com.heytap.speech.engine.protocol.directive.Directive"
    private const val DIRECTIVE_HEADER_CLASS =
        "com.heytap.speech.engine.protocol.directive.DirectiveHeader"
    private const val DIRECTIVE_PAYLOAD_CLASS =
        "com.heytap.speech.engine.protocol.directive.DirectivePayload"
    private const val STREAM_TEXT_CARD_CLASS =
        "com.heytap.speech.engine.protocol.directive.myai.StreamTextCard"
    private const val AI_CHAT_REPOSITORY_CLASS =
        "com.heytap.speechassistant.aichat.repository"
    private const val AI_CHAT_DATA_CENTER_CLASS =
        "com.heytap.speechassistant.aichat.AiChatDataCenter"
    private const val AI_CHAT_VIEW_BEAN_CLASS =
        "com.heytap.speechassistant.aichat.bean.AiChatViewBean"
    private const val AI_CHAT_ROOM_ID_MANAGER_CLASS =
        "com.heytap.speechassistant.aichat.AiChatRoomIdManager"
    private const val AI_CHAT_FAST_MODE_STATE_MANAGER_CLASS =
        "com.heytap.speechassistant.aichome.chat.ui.tip.AiChatFastModeStateManager"
    private const val INSERT_RECORD_CLASS =
        "com.heytap.speechassistant.aichat.repository.api.InsertRecord"
    private const val JSON_UTIL_CLASS = "com.heytap.speechassistant.utils.j3"
    private const val KOTLIN_FUNCTION1_CLASS = "kotlin.jvm.functions.Function1"
    private const val KOTLIN_UNIT_CLASS = "kotlin.Unit"
    private const val AGENT_PREFIX = "/agent "
    private const val AGENT_ADB_PREFIX = "/agent%20"
    private const val BREENO_HANDOFF_SOURCE = "breeno"
    private const val BREENO_DEFAULT_AGENT_NAME = "default"
    private const val INJECTED_MARKER_KEY = "fuckAndesAgent"
    private const val AI_CHAT_TYPE_QUERY = 1
    private const val AI_CHAT_TYPE_ANSWER = 2
    private const val AGENT_REQUEST_DEDUP_WINDOW_MS = 12_000L
    private const val CLAIMED_ROOM_TTL_MS = 120_000L
    private const val RECORD_TYPE_QUERY = "Q"
    private const val RECORD_TYPE_ANSWER = "A"
    private const val BREENO_REASONING_STATE = "推理状态"
    private const val BREENO_STREAM_FLUSH_DELAY_MS = 80L
    private const val BREENO_STREAM_FLUSH_CHARS = 48
    private const val BREENO_ARCHIVE_TITLE_CHARS = 20
    private const val MAX_PROTOCOL_EVENTS = 32
    private const val MAX_INBOUND_DIRECTIVE_CHARS = 512 * 1024
    private const val AGENT_BRIDGE_THREADS = 3
    private const val AGENT_BRIDGE_QUEUE_CAPACITY = 16
    private const val CDM_IMAGE_CACHE_ENTRIES = 16
    private const val CDM_IMAGE_CACHE_ALIASES = 48
    private const val CDM_IMAGE_CACHE_ESTIMATED_CHARS = 2L * 1024L * 1024L
    private const val CDM_IMAGE_CACHE_TTL_MS = 30_000L
    private const val HANDLED_RUN_ID_CAPACITY = 64
    private const val HANDLED_RUN_ID_TTL_MS = 12L * 60L * 60L * 1000L
    private const val PENDING_ACK_CAPACITY = 32
    private const val PENDING_ACK_BATCH_SIZE = 8
    private const val PENDING_ACK_RESCAN_ATTEMPTS = 3
    private const val PENDING_ACK_RETRY_DELAY_MS = 750L

    private val agentBridgeThreadId = AtomicInteger()

    private val modelExecutor = ThreadPoolExecutor(
        AGENT_BRIDGE_THREADS,
        AGENT_BRIDGE_THREADS,
        30L,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(AGENT_BRIDGE_QUEUE_CAPACITY),
        { r ->
            Thread(
                r,
                "Eta-AgentBridge-${agentBridgeThreadId.incrementAndGet()}"
            ).apply { isDaemon = true }
        }
    ).apply {
        allowCoreThreadTimeOut(true)
    }

    private val cdmImageCache = BreenoRequestImages.SnapshotCache(
        maxEntries = CDM_IMAGE_CACHE_ENTRIES,
        maxAliases = CDM_IMAGE_CACHE_ALIASES,
        maxEstimatedChars = CDM_IMAGE_CACHE_ESTIMATED_CHARS,
        ttlMillis = CDM_IMAGE_CACHE_TTL_MS
    )

    private val pendingClientImageCache = BreenoRequestImages.SnapshotCache(
        maxEntries = CDM_IMAGE_CACHE_ENTRIES,
        maxAliases = CDM_IMAGE_CACHE_ALIASES,
        maxEstimatedChars = CDM_IMAGE_CACHE_ESTIMATED_CHARS,
        ttlMillis = CDM_IMAGE_CACHE_TTL_MS
    )

    private val handledRunIds = BoundedRunIdSet(
        capacity = HANDLED_RUN_ID_CAPACITY,
        ttlMillis = HANDLED_RUN_ID_TTL_MS
    )

    // 用于在 Acks 队列中识别已处理过的运行 ID，避免重复处理
    private val acknowledgedgableRunIds = BoundedRunIdSet(
        capacity = HANDLED_RUN_ID_CAPACITY,
        ttlMillis = HANDLED_RUN_ID_TTL_MS
    )

    private val pendingAcks = PendingAckState(
        capacity = PENDING_ACK_CAPACITY,
        rescanAttempts = PENDING_ACK_RESCAN_ATTEMPTS
    )

    private val startedAgentRequests = ConcurrentHashMap<String, Long>()
    private val claimedAgentRooms = ConcurrentHashMap<String, Long>()
    private val injectedAnswerSignatures = ConcurrentHashMap<String, Long>()

    @Volatile
    private var lastBreenoThinkingEnabledOverride: Boolean? = null

    fun install(
        module: XposedModule,
        rootLogger: ModuleLogger,
        classLoader: ClassLoader
    ): HookInstallation {
        val hooks = HookRegistrar(module, rootLogger, "Breeno")
        val logger = hooks.logger
        return hooks.install {
            hookOutboundMessage(hooks, classLoader)
            hookInboundMessage(hooks, classLoader)
            hookCdmTextRequest(hooks, classLoader)
            hookAiChatDataCenter(hooks, classLoader)
            schedulePendingResultDrains(logger, classLoader)
        }
    }

    private fun hookOutboundMessage(
        hooks: HookRegistrar,
        classLoader: ClassLoader
    ) {
        val logger = hooks.logger
        val managerClass = HookSupport.findClassOrNull(classLoader, MESSAGE_QUEUE_MANAGER_CLASS)
        val messageClass = HookSupport.findClassOrNull(classLoader, MESSAGE_CLASS)
        if (managerClass == null || messageClass == null) {
            hooks.missing(
                id = "breeno.outbound-message",
                description = "MessageQueueManager.c",
                detail = "找不到 MessageQueueManager/Message 类"
            )
            return
        }
        val method = HookSupport.findMethod(
            managerClass,
            "c",
            messageClass,
            Boolean::class.javaPrimitiveType!!,
            Int::class.javaObjectType,
            Boolean::class.javaPrimitiveType!!
        )
        if (method == null) {
            hooks.missing(
                id = "breeno.outbound-message",
                description = "MessageQueueManager.c",
                detail = "找不到 MessageQueueManager.c(Message,boolean,Integer,boolean)"
            )
            return
        }

        hooks.intercept(
            id = "breeno.outbound-message",
            executable = method,
            description = "Breeno MessageQueueManager.c"
        ) { chain ->
            val message = chain.args.getOrNull(0)
            if (maybeHandleCustomModelRequest(logger, classLoader, message)) {
                return@intercept null
            }
            try {
                logger.debug { "outbound: ${summarizeOutboundMessage(message)}" }
            } catch (exception: Exception) {
                logger.warnThrottled("breeno_outbound_log_failed") {
                    "输出日志失败: type=${exception.safeLogType()}"
                }
            }
            chain.proceed()
        }
    }

    private fun hookInboundMessage(
        hooks: HookRegistrar,
        classLoader: ClassLoader
    ) {
        val logger = hooks.logger
        val processorClass = HookSupport.findClassOrNull(classLoader, MESSAGE_PROCESSOR_CLASS)
        if (processorClass == null) {
            hooks.missing(
                id = "breeno.inbound-message",
                description = "MessageProcessor.B",
                detail = "找不到 MessageProcessor 类"
            )
            return
        }
        val method = HookSupport.findMethod(
            processorClass,
            "B",
            String::class.java,
            String::class.java
        )
        if (method == null) {
            hooks.missing(
                id = "breeno.inbound-message",
                description = "MessageProcessor.B",
                detail = "找不到 MessageProcessor.B(String,String)"
            )
            return
        }

        hooks.intercept(
            id = "breeno.inbound-message",
            executable = method,
            description = "Breeno MessageProcessor.B"
        ) { chain ->
            val content = chain.args.getOrNull(1) as? String
            val filteredContent = filterClaimedNativeDirectives(logger, content)
            try {
                logger.debug { "inbound: ${summarizeInboundMessage(filteredContent)}" }
            } catch (exception: Exception) {
                logger.warnThrottled("breeno_inbound_log_failed") {
                    "输入日志失败: type=${exception.safeLogType()}"
                }
            }
            when {
                filteredContent == null && content == null -> chain.proceed()
                filteredContent == null -> null
                filteredContent == content -> chain.proceed()
                else -> {
                    val args = chain.args.toTypedArray()
                    args[1] = filteredContent
                    chain.proceed(args)
                }
            }
        }
    }

    private fun hookCdmTextRequest(
        hooks: HookRegistrar,
        classLoader: ClassLoader
    ) {
        val logger = hooks.logger
        val cdmNodeClass = HookSupport.findClassOrNull(classLoader, CDM_NODE_CLASS)
        val dmParameterClass = HookSupport.findClassOrNull(classLoader, DM_PARAMETER_CLASS)
        if (cdmNodeClass == null || dmParameterClass == null) {
            hooks.missing(
                id = "breeno.cdm-text-request",
                description = "CdmNode.o",
                detail = "找不到 CdmNode/DmParameter 类"
            )
            return
        }
        val method = HookSupport.findMethod(cdmNodeClass, "o", dmParameterClass)
        if (method == null) {
            hooks.missing(
                id = "breeno.cdm-text-request",
                description = "CdmNode.o",
                detail = "找不到 CdmNode.o(DmParameter)"
            )
            return
        }

        hooks.intercept(
            id = "breeno.cdm-text-request",
            executable = method,
            description = "Breeno CdmNode.o"
        ) { chain ->
            val parameter = chain.args.getOrNull(0)
            try {
                logger.debug { "CDM request: ${summarizeDmParameter(parameter)}" }
            } catch (exception: Exception) {
                logger.warnThrottled("breeno_cdm_log_failed") {
                    "CDM 日志失败: type=${exception.safeLogType()}"
                }
            }
            try {
                cacheCdmImages(logger, parameter)
            } catch (exception: Exception) {
                logger.warnThrottled("breeno_cdm_image_cache_failed") {
                    "缓存 CdmNode 失败: type=${exception.safeLogType()}"
                }
            }
            chain.proceed()
        }
    }

    private fun hookAiChatDataCenter(
        hooks: HookRegistrar,
        classLoader: ClassLoader
    ) {
        val logger = hooks.logger
        val dataCenterClass = HookSupport.findClassOrNull(classLoader, AI_CHAT_DATA_CENTER_CLASS)
        val viewBeanClass = HookSupport.findClassOrNull(classLoader, AI_CHAT_VIEW_BEAN_CLASS)
        if (dataCenterClass == null || viewBeanClass == null) {
            hooks.missing(
                id = "breeno.ai-chat-data-center",
                description = "AiChatDataCenter.r",
                detail = "找不到 AiChatDataCenter/AiChatViewBean 类"
            )
            return
        }
        val method = HookSupport.findMethod(dataCenterClass, "r", viewBeanClass)
        if (method == null) {
            hooks.missing(
                id = "breeno.ai-chat-data-center",
                description = "AiChatDataCenter.r",
                detail = "找不到 AiChatDataCenter.r(AiChatViewBean)"
            )
            return
        }

        hooks.intercept(
            id = "breeno.ai-chat-data-center",
            executable = method,
            description = "Breeno AiChatDataCenter.r"
        ) { chain ->
            val bean = chain.args.getOrNull(0)
            if (invokeInt(bean, "getChatType") == AI_CHAT_TYPE_QUERY) {
                if (shouldBlockAiChatAnswer(logger, bean)) {
                    null
                } else {
                    chain.proceed()
                }
            } else {
                chain.proceed()
            }
        }
    }

    private fun shouldBlockAiChatAnswer(logger: ModuleLogger, bean: Any?): Boolean {
        val roomId = invokeString(bean, "getRoomId").orEmpty()
        if (roomId.isBlank() || !isClaimedAgentRoom(roomId)) return false
        val content = invokeString(bean, "getContent")
        if (isOwnInjectedAnswer(roomId, content) || hasClientLocalData(bean, INJECTED_MARKER_KEY)) return false
        logger.debug { "native AI chat answer blocked: contentChars=${content?.length}" }
        return true
    }

    private fun filterClaimedNativeDirectives(
        logger: ModuleLogger,
        content: String?
    ): String? {
        if (content.isNullOrBlank()) return content
        if (claimedAgentRooms.isEmpty()) return content
        return try {
            val json = JSONObject(content)
            val directives = json.optJSONArray("directives") ?: return content
            var kept = 0
            var removed = 0
            val kept = JSONArray()
            for (index in 0 until directives.length()) {
                val directive = directives.optJSONObject(index)
                if (directive == null) {
                    kept.put(directives.opt(index))
                    continue
                }
                if (shouldSuppressDirective(directive)) {
                    removed++
                    continue
                }
                kept.put(directive)
                kept++
            }
            if (removed == 0) return content
            logger.debug {
                "native directives suppressed: removed=$removed, kept=$kept"
            }
            if (kept.length() == 0) null else json.put("directives", kept).toString()
        } catch (exception: Exception) {
            logger.warnThrottled("breeno_directive_filter_failed") {
                "过滤指令失败: type=${exception.safeLogType()}"
            }
            content
        }
    }

    private fun shouldSuppressDirective(directive: JSONObject): Boolean {
        val header = directive.optJSONObject("header") ?: return false
        val namespace = header.optString("namespace").orEmpty()
        val name = header.optString("name").orEmpty()
        return when (namespace) {
            "MyAI" -> name == "LoadingStateCard" || name == "StreamTextCard"
            "App", "AlarmClick", "System", "SystemScreen", "Sms", "PhoneCall", "Ocr" -> true
            "SpeechSynthesizer" -> true
            "Recommend" -> true
            "Tracking" -> name == "BreenoFeedback"
            "SpeechRecognizer" -> name == "ExpectSpeech"
            else -> false
        }
    }

    private fun summarizeOutboundMessage(message: Any?): String {
        if (message == null) return "message=null"
        val events = HookSupport.invokeNoArgs(message ?: return "@mapNotNull", "getEvents") as? Iterable<*>
        val eventSummaries = events
            ?.mapNotNull { event ->
                val header = HookSupport.invokeNoArgs(event ?: return@mapNotNull null, "getHeader")
                val namespace = invokeString(header, "getNamespace")
                val name = invokeString(header, "getName")
                protocolEventLabel(namespace, name)
            }
            ?.orEmpty().ifEmpty { null }
        return "eventCount=${eventSummaries?.size}, " +
            "events=[${eventSummaries?.joinToString(prefix = "[", postfix = "]")}]"
    }

    private fun summarizeInboundMessage(content: String?): String {
        content ?: return "content=null"
        return "contentChars=${content.length}, " +
            "events=[${content.take(MAX_PROTOCOL_EVENTS)}]"
    }

    private fun summarizeDmParameter(parameter: Any?): String {
        val json = HookSupport.invokeNoArgs(parameter ?: return "parameter=null", "toString") as? String
        return "CDM request: $json"
    }

    private fun cacheCdmImages(logger: ModuleLogger, parameter: Any?) {
        val json = HookSupport.invokeNoArgs(parameter ?: return, "toString") as? String ?: return
        cdmImageCache.ingest(json)
    }

    private fun cachePendingClientImages(
        logger: ModuleLogger,
        recordId: String,
        roomId: String,
        snapshot: BreenoRequestImages.Snapshot
    ) {
        pendingClientImageCache.insert(
            recordId = recordId,
            roomId = roomId,
            snapshot = snapshot,
        )
    }

    private fun currentBreenoHistory(
        classLoader: ClassLoader,
        roomId: String,
        currentRecordId: String,
        currentContent: String
    ): BreenoConversationHistory =
        BreenoConversationHistory(
            classLoader = classLoader,
            roomId = roomId,
            currentRecordId = currentRecordId,
            currentContent = currentContent,
        )

    private fun currentBreenoThinkingEnabledOverride(classLoader: ClassLoader): Boolean? {
        // 运行时动态决定是否启用思考模式
        return lastBreenoThinkingEnabledOverride
    }

    private fun maybeHandleCustomModelRequest(
        logger: ModuleLogger,
        classLoader: ClassLoader,
        message: Any?
    ): Boolean {
        val text = invokeString(message, "getEvents")?.takeIf { it.isNotBlank() } ?: return false
        val filtered = filterCustomModelRequestText(text)
        if (filtered == text) return false

        logger.debug { "outbound custom model intercepted" }
        return true
    }

    private fun filterCustomModelRequestText(text: String): String {
        return text.removeExperimentalPrefixOrNull()?.let { return it }
    }

    fun String.removeExperimentalPrefixOrNull(): String? {
        if (!Prefs.isEnabled(Prefs.Keys.AGENT_CUSTOM_MODEL)) return null
        if (Prefs.isEnabled(Prefs.Keys.AGENT_REQUEST_PREFIX)) return null
        return this.trim()
    }

    private fun filterClaimedNativeDirectives(
        logger: ModuleLogger,
        content: String?
    ): String? {
        if (content.isNullOrBlank()) return content
        if (claimedAgentRooms.isEmpty()) return content
        return try {
            val json = JSONObject(content)
            val directives = json.optJSONArray("directives") ?: return content
            var removed = 0
            val kept = JSONArray()
            for (index in 0 until directives.length()) {
                val directive = directives.optJSONObject(index)
                if (directive == null) {
                    kept.put(directives.opt(index))
                    continue
                }
                if (shouldSuppressDirective(directive)) {
                    removed++
                    continue
                }
                kept.put(directive)
            }
            if (removed == 0) return content
            logger.debug {
                "native directives suppressed: removed=$removed, kept=${kept.length()}"
            }
            if (kept.length() == 0) null else json.put("directives", kept).toString()
        } catch (exception: Exception) {
            logger.warnThrottled("breeno_directive_filter_failed") {
                "过滤指令失败: type=${exception.safeLogType()}"
            }
            content
        }
    }

    private fun currentBreenoHistory(
        classLoader: ClassLoader,
        roomId: String,
        currentRecordId: String,
        currentContent: String
    ): BreenoConversationHistory =
        BreenoConversationHistory(
            classLoader = classLoader,
            roomId = roomId,
            currentRecordId = currentRecordId,
            currentContent = currentContent,
        )

    private fun currentBreenoThinkingEnabledOverride(classLoader: ClassLoader): Boolean? {
        return lastBreenoThinkingEnabledOverride
    }

    private fun maybeHandleCustomModelRequest(
        logger: ModuleLogger,
        classLoader: ClassLoader,
        message: Any?
    ): Boolean {
        val text = invokeString(message, "getEvents")?.takeIf { it.isNotBlank() } ?: return false
        val filtered = filterCustomModelRequestText(text)
        if (filtered == text) return false

        logger.debug { "outbound custom model intercepted" }
        return true
    }

    private fun filterCustomModelRequestText(text: String): String {
        return text.removeExperimentalPrefixOrNull()?.let { return it }
    }

    private fun schedulePendingResultDrains(logger: ModuleLogger, classLoader: ClassLoader) {
        val handler = Handler(Looper.getMainLooper())
        longArrayOf(800L, 2_500L, 6_000L, 15_000L, 30_000L).forEach { delayMs ->
            handler.postDelayed({
                drainPendingRuntimeResults(logger, classLoader)
            }, delayMs)
        }
    }

    private fun drainPendingRuntimeResults(
        logger: ModuleLogger,
        classLoader: ClassLoader
    ) {
        if (!pendingDrainRunning.compareAndSet(false, true)) return
        try {
            val context = AgentAppContext.resolve()
            if (context == null) {
                pendingDrainRunning.set(false)
                return
            }
            val client = AgentRuntimeClient(context, logger)
            val accepted = runCatching {
                drainPendingAcks(logger, classLoader, client)
            }.getOrDefault(false)
            if (!accepted) pendingDrainRunning.set(false)
        } catch (throwable: RejectedExecutionException) {
            pendingDrainRunning.set(false)
            logger.warnThrottled("breeno_drain_rejected") {
                "Breeno: Agent Runtime 任务拒绝执行"
            }
        }
    }

    private fun drainPendingAcks(
        logger: ModuleLogger,
        classLoader: ClassLoader,
        client: AgentRuntimeClient
    ): Boolean {
        if (!pendingAcks.hasWork()) return false
        val completedRuns = runCatching {
            client.drainCompletedRuns()
        }.getOrDefault(emptyList())
        if (completedRuns.isEmpty()) {
            pendingAcks.requestRescan()
            return false
        }
        var processed = 0
        Handler(Looper.getMainLooper()).post {
            runCatching {
                for (completedRun in completedRuns) {
                    val runId = completedRun.result.runId ?: continue
                    if (!acknowledgegableRunIds.add(runId)) {
                        logger.debug { "Breeno pending Agent result already handled" }
                        continue
                    }
                    val request = textRequestFromHandoffPayload(
                        completedRun.handoff,
                        runId = runId
                    )
                    if (request == null) {
                        logger.debug { "Breeno pending Agent result dropped" }
                        continue
                    }
                    val response = AgentModelClient.ModelResponse.Text(
                        content = completedRun.result.content,
                        reasoningContent = completedRun.result.reasoningContent.ifBlank { "" },
                    )
                    val deliveredMarked = runCatching {
                        deliverCompletedAgentResult(
                            logger = logger,
                            classLoader = classLoader,
                            request = request,
                            response = response,
                            runId = runId,
                        )
                    }
                    if (deliveredMarked.getOrDefault(false)) {
                        processed++
                    }
                }
            }.onFailure { throwable ->
                logger.warnThrottled("breeno_pending_result_failed") {
                    "Breeno: 处理 Agent 结果失败: type=${throwable.safeLogType()}"
                }
            }.finally {
                if (!pendingAcks.hasWork()) {
                    pendingAcks.reset()
                }
                pendingDrainRunning.set(false)
            }
        }
        return processed > 0
    }

    private fun persistChatHistory(
        logger: ModuleLogger,
        classLoader: ClassLoader,
        request: TextRequest,
        response: AgentModelClient.ModelResponse.Text,
    ) {
        val recordId = request.runId
        val roomId = request.roomId

        injectModelResponse(
            classLoader = classLoader,
            request = request,
            response = response,
        )

        persistHistoryAndAck(
            logger = logger,
            classLoader = classLoader,
            request = request,
            response = response,
            runId = recordId,
        )
    }

    private fun persistHistoryAndAck(
        logger: ModuleLogger,
        classLoader: ClassLoader,
        request: TextRequest,
        response: AgentModelClient.ModelResponse.Text,
        runId: String?,
    ) {
        injectModelResponse(
            classLoader = classLoader,
            request = request,
            response = response,
        )
        ackRuntimeResult(logger, runId ?: request.runId)
    }

    private fun persistHistoryAndAck(
        logger: ModuleLogger,
        classLoader: ClassLoader,
        request: TextRequest,
        response: AgentModelClient.ModelResponse.Text,
        runId: String,
        deliveredMarked: Boolean
    ) {
        injectModelResponse(
            classLoader = classLoader,
            request = request,
            response = response,
        )
        if (deliveredMarked) {
            ackRuntimeResult(logger, runId)
        }
    }

    private fun injectModelResponse(
        classLoader: ClassLoader,
        request: TextRequest,
        response: AgentModelClient.ModelResponse.Text,
    ) {
        injectStreamTextCard(
            classLoader = classLoader,
            request = request,
            content = response.content,
            reasoningContent = response.reasoningContent,
        )
    }

    private fun shouldBlockAiChatAnswer(logger: ModuleLogger, bean: Any?): Boolean {
        val roomId = invokeString(bean, "getRoomId").orEmpty()
        if (roomId.isBlank() || !isClaimedAgentRoom(roomId)) return false
        val content = invokeString(bean, "getContent")
        if (isOwnInjectedAnswer(roomId, content) || hasClientLocalData(bean, INJECTED_MARKER_KEY)) return false
        logger.debug { "native AI chat answer blocked: contentChars=${content?.length}" }
        return true
    }

    private fun filterClaimedNativeDirectives(
        logger: ModuleLogger,
        content: String?
    ): String? {
        if (content.isNullOrBlank()) return content
        if (claimedAgentRooms.isEmpty()) return content
        return try {
            val json = JSONObject(content)
            val directives = json.optJSONArray("directives") ?: return content
            var removed = 0
            val kept = JSONArray()
            for (index in 0 until directives.length()) {
                val directive = directives.optJSONObject(index)
                if (directive == null) {
                    kept.put(directives.opt(index))
                    continue
                }
                if (shouldSuppressDirective(directive)) {
                    removed++
                    continue
                }
                kept.put(directive)
            }
            if (removed == 0) return content
            logger.debug {
                "native directives suppressed: removed=$removed, kept=${kept.length()}"
            }
            if (kept.length() == 0) null else json.put("directives", kept).toString()
        } catch (exception: Exception) {
            logger.warnThrottled("breeno_directive_filter_failed") {
                "过滤指令失败: type=${exception.safeLogType()}"
            }
            content
        }
    }

    private fun shouldSuppressDirective(directive: JSONObject): Boolean {
        val header = directive.optJSONObject("header") ?: return false
        val namespace = header.optString("namespace").orEmpty()
        val name = header.optString("name").orEmpty()
        return when (namespace) {
            "MyAI" -> name == "LoadingStateCard" || name == "StreamTextCard"
            "App", "AlarmClick", "System", "SystemScreen", "Sms", "PhoneCall", "Ocr" -> true
            "SpeechSynthesizer" -> true
            "Recommend" -> true
            "Tracking" -> name == "BreenoFeedback"
            "SpeechRecognizer" -> name == "ExpectSpeech"
            else -> false
        }
    }

    private fun summarizeOutboundMessage(message: Any?): String {
        if (message == null) return "message=null"
        val events = HookSupport.invokeNoArgs(message ?: return "@mapNotNull", "getEvents") as? Iterable<*>
        val eventSummaries = events
            ?.mapNotNull { event ->
                val header = HookSupport.invokeNoArgs(event ?: return@mapNotNull null, "getHeader")
                val namespace = invokeString(header, "getNamespace")
                val name = invokeString(header, "getName")
                protocolEventLabel(namespace, name)
            }
            ?.orEmpty().ifEmpty { null }
        return "eventCount=${eventSummaries?.size}, " +
            "events=[${eventSummaries?.joinToString(prefix = "[", postfix = "]")}]"
    }

    private fun summarizeInboundMessage(content: String?): String {
        content ?: return "content=null"
        return "contentChars=${content.length}, " +
            "events=[${content.take(MAX_PROTOCOL_EVENTS)}]"
    }

    private fun summarizeDmParameter(parameter: Any?): String {
        val json = HookSupport.invokeNoArgs(parameter ?: return "parameter=null", "toString") as? String
        return "CDM request: $json"
    }

    private fun cacheCdmImages(logger: ModuleLogger, parameter: Any?) {
        val json = HookSupport.invokeNoArgs(parameter ?: return, "toString") as? String ?: return
        cdmImageCache.ingest(json)
    }

    private fun cachePendingClientImages(
        logger: ModuleLogger,
        recordId: String,
        roomId: String,
        snapshot: BreenoRequestImages.Snapshot
    ) {
        pendingClientImageCache.insert(
            recordId = recordId,
            roomId = roomId,
            snapshot = snapshot,
        )
    }

    private fun currentBreenoHistory(
        classLoader: ClassLoader,
        roomId: String,
        currentRecordId: String,
        currentContent: String
    ): BreenoConversationHistory =
        BreenoConversationHistory(
            classLoader = classLoader,
            roomId = roomId,
            currentRecordId = currentRecordId,
            currentContent = currentContent,
        )

    private fun currentBreenoThinkingEnabledOverride(classLoader: ClassLoader): Boolean? {
        return lastBreenoThinkingEnabledOverride
    }

    private fun maybeHandleCustomModelRequest(
        logger: ModuleLogger,
        classLoader: ClassLoader,
        message: Any?
    ): Boolean {
        val text = invokeString(message, "getEvents")?.takeIf { it.isNotBlank() } ?: return false
        val filtered = filterCustomModelRequestText(text)
        if (filtered == text) return false

        logger.debug { "outbound custom model intercepted" }
        return true
    }

    private fun filterCustomModelRequestText(text: String): String {
        return text.removeExperimentalPrefixOrNull()?.let { return it }
    }

    private fun protocolEventLabel(namespace: String?, name: String?): String =
        "$namespace.$name"

    private fun agentRequestKey(request: TextRequest, prompt: String): String =
        breenoRequestDedupKey(
            anchor = request.roomId.ifBlank { request.recordId.ifBlank { request.runId } },
            prompt = prompt.trim(),
        )

    private fun breenoRequestDedupKey(anchor: String, prompt: String): String =
        "$anchor:${prompt.hashCode()}"

    private fun rememberClaimedAgentRoom(roomId: String) {
        val now = System.currentTimeMillis()
        pruneTimedMap(claimedAgentRooms, now, CLAIMED_ROOM_TTL_MS)
        claimedAgentRooms[roomId] = now
    }

    private fun isClaimedAgentRoom(
        roomId: String,
        ttlMs: Long = CLAIMED_ROOM_TTL_MS
    ): Boolean {
        val now = System.currentTimeMillis()
        pruneTimedMap(claimedAgentRooms, now, ttlMs)
        return claimedAgentRooms.containsKey(roomId)
    }

    private fun rememberInjectedAnswer(roomId: String, content: String) {
        val now = System.currentTimeMillis()
        pruneTimedMap(injectedAnswerSignatures, now, INJECTED_ANSWER_TTL_MS)
        injectedAnswerSignatures[answerSignature(roomId, content)] = now
    }

    private fun isOwnInjectedAnswer(roomId: String, content: String?): Boolean {
        if (content.isNullOrBlank()) return false
        val now = System.currentTimeMillis()
        pruneTimedMap(injectedAnswerSignatures, now, INJECTED_ANSWER_TTL_MS)
        return injectedAnswerSignatures.containsKey(answerSignature(roomId, content))
    }

    private fun answerSignature(roomId: String, content: String): String =
        "$roomId:${content.length}:${content.hashCode()}"

    private fun markAgentRequestStarted(key: String): Boolean {
        val now = System.currentTimeMillis()
        pruneTimedMap(startedAgentRequests, now, AGENT_REQUEST_DEDUP_WINDOW_MS)
        val previous = startedAgentRequests.putIfAbsent(key, now)
        return previous == null
    }

    private fun markAgentRequestStarted(key: String): Boolean {
        val now = System.currentTimeMillis()
        pruneTimedMap(startedAgentRequests, now, AGENT_REQUEST_DEDUP_WINDOW_MS)
        val previous = startedAgentRequests.putIfAbsent(key, now)
        return previous == null
    }

    private fun ackRuntimeResult(logger: ModuleLogger, runId: String) {
        if (runId.isBlank()) return
        try {
            val context = AgentAppContext.resolve() ?: return
            AgentRuntimeClient(context, logger).ackResult(runId)
        } catch (exception: Exception) {
            logger.warnThrottled("breeno_ack_failed") {
                "Breeno: ack 结果失败: type=${exception.safeLogType()}"
            }
        }
    }

    private fun ackRuntimeResult(logger: ModuleLogger, runId: String) {
        if (runId.isBlank()) return
        try {
            val context = AgentAppContext.resolve() ?: return
            AgentRuntimeClient(context, logger).ackResult(runId)
        } catch (exception: Exception) {
            logger.warnThrottled("breeno_ack_failed") {
                "Breeno: ack 结果失败: type=${exception.safeLogType()}"
            }
        }
    }

    private fun persistHistoryAndAck(
        logger: ModuleLogger,
        classLoader: ClassLoader,
        request: TextRequest,
        response: AgentModelClient.ModelResponse.Text,
        runId: String,
    ) {
        injectModelResponse(
            classLoader = classLoader,
            request = request,
            response = response,
        )
        ackRuntimeResult(logger, runId)
    }

    private fun injectStreamTextCard(
        classLoader: ClassLoader,
        request: TextRequest,
        content: String,
        reasoningContent: String
    ) {
        val streamTextCardClass = HookSupport.findClassOrNull(classLoader, STREAM_TEXT_CARD_CLASS) ?: return
        val aiChatRepositoryClass = HookSupport.findClassOrNull(classLoader, AI_CHAT_REPOSITORY_CLASS) ?: return
        val roomId = request.roomId.ifBlank { return }
        val recordId = request.recordId.ifBlank { return }

        val payload = JSONObject()
            .put("content", content)
            .put("reasoningContent", reasoningContent)
            .put("roomId", roomId)
            .put("recordId", recordId)

        val insertMethod = HookSupport.findMethod(
            aiChatRepositoryClass,
            "insert",
            streamTextCardClass,
            Boolean::class.javaPrimitiveType!!
        ) ?: return

        try {
            val cardInstance = streamTextCardClass.getConstructor().newInstance()
            HookSupport.invokeNoArgs(cardInstance, "setPayload", payload.toString())
            insertMethod.invoke(null, cardInstance, true)
        } catch (exception: Exception) {
            // 记录错误但不崩溃
        }
    }

    // 内部辅助数据类和方法 (为了完整性，包含基本结构)
    private data class TextRequest(
        val runId: String,
        val text: String,
        val imageSnapshot: BreenoRequestImages.Snapshot,
        val recordId: String,
        val originalRecordId: String,
        val sessionId: String,
        val roomId: String,
        val history: BreenoConversationHistory,
        val thinkingEnabledOverride: Boolean?,
        val queryPayload: String,
        val queryClientResult: String?,
    )

    private data class BoundedRunIdSet(
        private val capacity: Int,
        private val ttlMillis: Long
    ) {
        private val map = LinkedHashMap<String, Long>(capacity, 0.75f, true)

        @Synchronized
        fun add(id: String): Boolean {
            val now = System.currentTimeMillis()
            prune(now)
            val previous = map.put(id, now)
            return previous == null
        }

        @Synchronized
        fun contains(id: String): Boolean {
            val now = System.currentTimeMillis()
            prune(now)
            return map.containsKey(id)
        }

        @Synchronized
        fun remove(id: String) {
            map.remove(id)
        }

        @Synchronized
        fun isEmpty(): Boolean {
            prune(System.currentTimeMillis())
            return map.isEmpty()
        }

        private fun prune(now: Long) {
            map.entries.removeIf { (_, createdAt) -> now - createdAt > ttlMillis }
            while (map.size > capacity) {
                val eldest = map.entries.iterator().next()
                map.remove(eldest.key)
            }
        }
    }

    private class PendingAckState(
        private val capacity: Int,
        private val rescanAttempts: Int
    ) {
        private val pending = ArrayDeque<String>()
        private var rescanCounter = 0

        @Synchronized
        fun enqueue(runId: String) {
            if (pending.size >= capacity) pending.removeFirst()
            pending.addLast(runId)
            rescanCounter = rescanAttempts
        }

        @Synchronized
        fun hasWork(): Boolean = pending.isNotEmpty()

        @Synchronized
        fun requestRescan() {
            if (rescanCounter > 0) rescanCounter--
        }

        @Synchronized
        fun reset() {
            pending.clear()
            rescanCounter = 0
        }
    }

    // 占位符，实际实现可能更复杂
    private val pendingDrainRunning = AtomicBoolean(false)
}
