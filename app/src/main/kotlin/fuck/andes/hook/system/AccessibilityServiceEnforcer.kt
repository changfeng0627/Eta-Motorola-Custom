package fuck.andes.hook.system

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings
import fuck.andes.agent.accessibility.AccessibilityProtectionProtocol
import fuck.andes.core.ModuleLogger
import fuck.andes.core.safeLogType
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 在 system_server 存活期间按用户选择保持 Eta 无障碍服务可用。
 *
 * 保护默认关闭；开启后只维护 owner 用户中的 Eta 组件与总开关，始终保留其他服务。
 * 所有工作复用 Android 的 BackgroundThread，不创建额外线程，也不做周期轮询。
 */
internal class AccessibilityServiceEnforcer(
    private val handler: Handler,
    private val logger: ModuleLogger,
) {
    private val started = AtomicBoolean()
    private val workScheduled = AtomicBoolean()
    private val rerunRequested = AtomicBoolean()
    private val registrationRetryScheduled = AtomicBoolean()
    private val healthCheckScheduled = AtomicBoolean()
    private val runtimeRecoveryScheduled = AtomicBoolean()
    private val repairInProgress = AtomicBoolean()
    private val restoreBackoff = AccessibilityRestoreBackoff()
    private val repairLimiter = AccessibilityRepairLimiter()
    private val activeSettingUris = mutableSetOf<Uri>()

    private var controlSettingObserver: ContentObserver? = null
    private var activeSettingsObserver: ContentObserver? = null
    private var controlReceiver: BroadcastReceiver? = null
    private var packageReceiver: BroadcastReceiver? = null
    private var lifecycleReceiver: BroadcastReceiver? = null
    private var controlSettingObserverRegistered = false
    private var controlReceiverRegistered = false
    private var packageReceiverRegistered = false
    private var lifecycleReceiverRegistered = false
    private var registrationRetryIndex = 0

    @Volatile
    private var lastRestoreLogAt = 0L

    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        if (!schedule(context, reason = "system_ready", delayMs = 0L)) {
            started.set(false)
        }
    }

    private fun reconcile(context: Context, reason: String) {
        val enabled = isEnforcementEnabled(context)
        ensureObserversRegistered(context, enabled)
        if (!enabled) {
            restoreBackoff.reset()
            repairLimiter.reset()
            return
        }
        if (repairInProgress.get()) return
        enforce(context, reason)
        if (
            shouldScheduleAccessibilityHealthCheck(
                ownerUnlocked = isOwnerUnlocked(context),
                serviceConfigured = isExpectedServiceConfigured(context),
            )
        ) {
            scheduleHealthCheck(
                context = context,
                reason = reason,
                delayMs = SERVICE_REBIND_GRACE_MS,
            )
        }
    }

    private fun ensureObserversRegistered(
        context: Context,
        enforcementEnabled: Boolean,
    ) {
        ensureControlChannelRegistered(context)
        if (enforcementEnabled) {
            ensureActiveObserversRegistered(context)
        } else {
            unregisterActiveObservers(context)
        }

        val controlReady =
            controlSettingObserverRegistered && controlReceiverRegistered
        val activeReady = if (enforcementEnabled) {
            activeSettingUris.size == ACTIVE_SETTING_URIS.size &&
                packageReceiverRegistered &&
                lifecycleReceiverRegistered
        } else {
            activeSettingUris.isEmpty() &&
                !packageReceiverRegistered &&
                !lifecycleReceiverRegistered
        }
        if (controlReady && activeReady) {
            registrationRetryIndex = 0
        } else {
            scheduleRegistrationRetry(context)
        }
    }

    private fun ensureControlChannelRegistered(context: Context) {
        val observer = controlSettingObserver ?: object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                schedule(context, "control_setting_changed", delayMs = 0L)
            }

            override fun onChange(selfChange: Boolean, uri: Uri?) {
                schedule(context, "control_setting_changed", delayMs = 0L)
            }
        }.also { controlSettingObserver = it }

        if (!controlSettingObserverRegistered) {
            try {
                context.contentResolver.registerContentObserver(
                    CONTROL_SETTINGS_URI,
                    false,
                    observer,
                )
                controlSettingObserverRegistered = true
            } catch (failure: RuntimeException) {
                logFailure("控制通道注册失败", failure)
            }
        }

        if (!controlReceiverRegistered) {
            val receiver = controlReceiver ?: object : BroadcastReceiver() {
                override fun onReceive(receiverContext: Context, intent: Intent) {
                    if (intent.action == AccessibilityProtectionProtocol.ACTION_SET) {
                        schedule(context, "control_changed")
                    }
                }
            }.also { controlReceiver = it }

            try {
                context.registerReceiver(
                    receiver,
                    IntentFilter().apply {
                        addAction(AccessibilityProtectionProtocol.ACTION_SET)
                        addAction(AccessibilityProtectionProtocol.ACTION_RECOVER)
                    },
                    AccessibilityProtectionProtocol.PERMISSION,
                    handler,
                    Context.RECEIVER_NOT_EXPORTED,
                )
                controlReceiverRegistered = true
            } catch (failure: RuntimeException) {
                logFailure("控制广播注册失败", failure)
            }
        }
    }

    private fun ensureActiveObserversRegistered(context: Context) {
        val observer = activeSettingsObserver ?: object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                schedule("accessibility_settings_changed")
            }

            override fun onChange(selfChange: Boolean, uri: Uri?) {
                schedule(
                    context = context,
                    reason = if (uri == APP_SIGNER_URI) {
                        "signer_setting_changed"
                    } else {
                        "accessibility_settings_changed"
                    },
                    delayMs = if (uri == APP_SIGNER_URI) 0L else null,
                )
            }
        }.also { activeSettingsObserver = it }

        val resolver = context.contentResolver
        ACTIVE_SETTING_URIS.forEach { uri ->
            if (uri in activeSettingUris) return@forEach
            try {
                resolver.registerContentObserver(uri, false, observer)
                activeSettingUris += uri
            } catch (failure: RuntimeException) {
                logFailure("活跃设置注册失败", failure)
            }
        }

        if (!packageReceiverRegistered) {
            val receiver = packageReceiver ?: object : BroadcastReceiver() {
                override fun onReceive(receiverContext: Context, intent: Intent) {
                    if (intent.schemePart == "package") {
                        schedule("package_changed")
                    }
                }
            }.also { packageReceiver = it }

            try {
                context.registerReceiver(
                    receiver,
                    IntentFilter().apply {
                        addAction(Intent.ACTION_PACKAGE_ADDED)
                        addAction(Intent.ACTION_PACKAGE_CHANGED)
                        addAction(Intent.ACTION_PACKAGE_REPLACED)
                        addDataScheme("package")
                    },
                    null,
                    handler,
                    Context.RECEIVER_NOT_EXPORTED,
                )
                packageReceiverRegistered = true
            } catch (failure: RuntimeException) {
                logFailure("应用广播注册失败", failure)
            }
        }

        if (!lifecycleReceiverRegistered) {
            val receiver = lifecycleReceiver ?: object : BroadcastReceiver() {
                override fun onReceive(receiverContext: Context, intent: Intent) {
                    schedule(
                        context = receiverContext,
                        reason = when (intent.action) {
                            Intent.ACTION_USER_UNLOCKED -> "owner_unlocked"
                            Intent.ACTION_BOOT_COMPLETED -> "boot_completed"
                            Intent.ACTION_LOCKED_BOOT_COMPLETED -> "locked_boot_completed"
                            else -> "locked_boot_completed"
                        },
                        delayMs = 0L,
                    )
                }
            }.also { lifecycleReceiver = it }

            try {
                context.registerReceiver(
                    receiver,
                    IntentFilter().apply {
                        addAction(Intent.ACTION_LOCKED_BOOT_COMPLETED)
                        addAction(Intent.ACTION_BOOT_COMPLETED)
                        // 华为和三星解锁广播（部分厂商缺少标准解锁广播）
                        addAction(Intent.ACTION_USER_PRESENT)
                    },
                    null,
                    handler,
                    Context.RECEIVER_NOT_EXPORTED,
                )
                lifecycleReceiverRegistered = true
            } catch (failure: RuntimeException) {
                logFailure("生命周期广播注册失败", failure)
            }
        }
    }

    private fun unregisterActiveObservers(context: Context) {
        if (!activeSettingUris.isEmpty()) {
            try {
                activeSettingsObserver?.let(context.contentResolver::unregisterContentObserver)
                activeSettingUris.clear()
            } catch (failure: RuntimeException) {
                logFailure("活跃设置注销失败", failure)
            }
        }
        if (packageReceiverRegistered) {
            try {
                packageReceiver?.let(context::unregisterReceiver)
                packageReceiverRegistered = false
            } catch (failure: RuntimeException) {
                logFailure("应用广播注销失败", failure)
            }
        }
        if (lifecycleReceiverRegistered) {
            try {
                lifecycleReceiver?.let(context::unregisterReceiver)
                lifecycleReceiverRegistered = false
            } catch (failure: RuntimeException) {
                logFailure("生命周期广播注销失败", failure)
            }
        }
    }

    private fun createControlReceiver(): BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context, intent: Intent) {
            val senderUid = sentFromUid
            val ordered = isOrderedBroadcast
            val action = intent.action
            val protocolVersion = intent.getIntExtra(
                AccessibilityProtectionProtocol.EXTRA_PROTOCOL_VERSION,
                -1,
            )
            val requestedEnabled = intent.getBooleanExtra(
                AccessibilityProtectionProtocol.EXTRA_ENABLED,
                AccessibilityProtectionProtocol.DEFAULT_ENABLED,
            )
            val pendingResult = goAsync()
            if (
                !post {
                    var resultCode = AccessibilityProtectionProtocol.RESULT_UNAVAILABLE
                    var actualEnabled = false
                    try {
                        resultCode = applyControlRequest(
                            context = receiverContext,
                            action = action,
                            ordered = ordered,
                            protocolVersion = protocolVersion,
                            senderUid = senderUid,
                            requestedEnabled = requestedEnabled,
                        )
                        actualEnabled = isEnforcementEnabled(receiverContext)
                    } catch (failure: RuntimeException) {
                        logFailure("控制请求执行失败", failure)
                    }
                    completeControlRequest(
                        pendingResult = pendingResult,
                        resultCode = resultCode,
                        enabled = actualEnabled,
                    )
                }
            ) {
                completeControlRequest(
                    pendingResult = pendingResult,
                    resultCode = AccessibilityProtectionProtocol.RESULT_UNAVAILABLE,
                    enabled = false,
                )
            }
        }
    }

    private fun applyControlRequest(
        context: Context,
        action: String?,
        ordered: Boolean,
        protocolVersion: Int,
        senderUid: Int,
        requestedEnabled: Boolean,
    ): Int {
        if (
            action !in CONTROL_ACTIONS ||
            !isControlCallerTrusted(context, ordered, protocolVersion, senderUid)
        ) {
            return AccessibilityProtectionProtocol.RESULT_REJECTED
        }

        if (action == AccessibilityProtectionProtocol.ACTION_RECOVER) {
            if (!isEnforcementEnabled(context)) {
                return AccessibilityProtectionProtocol.RESULT_UNAVAILABLE
            }
            if (!isExpectedServiceTrusted(context)) {
                return AccessibilityProtectionProtocol.RESULT_REJECTED
            }
            return if (scheduleRuntimeRecovery(context)) {
                AccessibilityProtectionProtocol.RESULT_APPLIED
            } else {
                AccessibilityProtectionProtocol.RESULT_UNAVAILABLE
            }
        }
        if (requestedEnabled && !isExpectedServiceTrusted(context)) {
            return AccessibilityProtectionProtocol.RESULT_REJECTED
        }
        val stored = Settings.Global.putInt(
            context.contentResolver,
            AccessibilityProtectionProtocol.SETTING_NAME,
            if (requestedEnabled) ENABLED else DISABLED,
        )
        if (!stored) return AccessibilityProtectionProtocol.RESULT_UNAVAILABLE

        restoreBackoff.reset()
        reconcile(context, "user_control")
        logger.info("无障碍服务开关已更新 requestedEnabled")
        return AccessibilityProtectionProtocol.RESULT_APPLIED
    }

    private fun scheduleRuntimeRecovery(context: Context): Boolean {
        if (!runtimeRecoveryScheduled.compareAndSet(false, true)) return true
        val posted = post {
            runtimeRecoveryScheduled.set(false)
            try {
                verifyServiceConnection(
                    context = context,
                    reason = "runtime_unavailable",
                    restoreMissingImmediately = true,
                )
            } catch (failure: RuntimeException) {
                logFailure("运行时恢复验证失败", failure)
            }
        }
        if (!posted) {
            runtimeRecoveryScheduled.set(false)
            logFailure("运行时恢复调度失败")
        }
        return posted
    }

    private fun completeControlRequest(
        pendingResult: BroadcastReceiver.PendingResult,
        resultCode: Int,
        enabled: Boolean,
    ) {
        try {
            pendingResult.setResultCode(resultCode)
            pendingResult.setResultExtras(
                Bundle().apply {
                    putBoolean(AccessibilityProtectionProtocol.EXTRA_ENABLED, enabled)
                },
            )
        } catch (failure: RuntimeException) {
            logFailure("控制结果更新失败", failure)
        } finally {
            pendingResult.finish()
        }
    }

    private fun scheduleRegistrationRetry(context: Context) {
        if (
            registrationRetryIndex >= REGISTRATION_RETRY_DELAYS_MS.size ||
            !registrationRetryScheduled.compareAndSet(false, true)
        ) {
            return
        }
        val delayMs = REGISTRATION_RETRY_DELAYS_MS[registrationRetryIndex++]
        if (
            !post(delayMs) {
                registrationRetryScheduled.set(false)
                logFailure("观察者注册重试调度失败")
                schedule(context, "observer_registration_retry_failed", delayMs = 0L)
            }
        ) {
            registrationRetryScheduled.set(false)
            logFailure("观察者注册重试延迟失败")
        }
    }

    private fun scheduleHealthCheck(
        context: Context,
        reason: String,
        delayMs: Long,
    ) {
        if (!healthCheckScheduled.compareAndSet(false, true)) return
        if (
            !post(delayMs) {
                healthCheckScheduled.set(false)
                logFailure("健康检查调度失败")
                schedule(context, "health_check_schedule_failed")
            }
        ) {
            healthCheckScheduled.set(false)
            logFailure("健康检查延迟失败")
        }
    }

    private fun verifyServiceConnection(
        context: Context,
        reason: String,
        restoreMissingImmediately: Boolean = false,
    ) {
        if (
            !isEnforcementEnabled(context) ||
            repairInProgress.get() ||
            !isOwnerUnlocked(context) ||
            !isExpectedServiceTrusted(context)
        ) {
            return
        }
        val currentServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        )
        if (!containsAccessibilityService(currentServices, SERVICE_COMPONENT.flattenToString())) {
            if (restoreMissingImmediately) {
                // 华为和三星设备经常杀掉 AccessibilityService
                // 当服务被杀后通过 provider 查询、shell 或系统 API 进行紧急恢复
                // 设置中不会包含被杀的服务，恢复失败会打印错误
                enforce(context, "runtime_missing_service_immediately")
                if (isExpectedServiceConfigured(context)) {
                    scheduleHealthCheck(
                        context = context,
                        reason = "runtime_missing_service_setting_restored",
                        delayMs = SERVICE_REBIND_GRACE_MS,
                    )
                }
            } else {
                schedule(context, "health_check_missing_service")
            }
            return
        }

        when (queryServiceConnection(context)) {
            AccessibilityConnectionStatus.CONNECTED -> repairLimiter.reset()
            AccessibilityConnectionStatus.DISCONNECTED -> {
                val attempt = repairLimiter.nextAttempt(SystemClock.elapsedRealtime())
                if (attempt == null) {
                    logFailure("服务断开且重试次数已达上限")
                } else {
                    beginServiceRepair(context, attempt, reason)
                }
            }
            AccessibilityConnectionStatus.UNKNOWN ->
                logFailure("服务状态查询失败")
        }
    }

    private fun queryServiceConnection(context: Context): AccessibilityConnectionStatus {
        val response = try {
            context.contentResolver.call(
                AccessibilityProtectionProtocol.HEALTH_URI,
                AccessibilityProtectionProtocol.HEALTH_METHOD,
                null,
                AccessibilityProtectionProtocol.request(),
            )
        } catch (failure: RuntimeException) {
            logFailure("Provider 调用失败", failure)
            return AccessibilityConnectionStatus.UNKNOWN
        }
        return AccessibilityConnectionStatus(
            protocolVersion = response?.getInt(
                AccessibilityProtectionProtocol.EXTRA_PROTOCOL_VERSION,
                -1,
            ),
            status = response?.getString(AccessibilityProtectionProtocol.HEALTH_STATUS),
        )
    }

    private fun beginServiceRepair(
        context: Context,
        attempt: AccessibilityRepairAttempt,
        reason: String,
    ) {
        if (!repairInProgress.compareAndSet(false, true)) return
        val resolver = context.contentResolver
        val servicesWithoutTarget = removeLatestAccessibilitySetting(resolver)
        if (servicesWithoutTarget == null) {
            repairInProgress.set(false)
            schedule(context, "repair_target_missing")
            return
        }
        if (
            !Settings.Secure.putString(
                resolver,
                AccessibilityProtectionProtocol.ENABLED_SERVICES_SETTING,
                servicesWithoutTarget,
            )
        ) {
            repairInProgress.set(false)
            logFailure("修复中写入服务列表失败")
            return
        }

        log.info(
            "服务修复开始：reason=$reason attempt=${attempt.number}",
        )
        if (
            !post(attempt.disabledDurationMs) {
                completeServiceRepair(context, attempt)
            }
        ) {
            repairInProgress.set(false)
            logFailure("服务修复延迟失败")
            schedule(context, "repair_schedule_failed", delayMs = 0L)
        }
    }

    private fun completeServiceRepair(
        context: Context,
        attempt: AccessibilityRepairAttempt,
    ) {
        try {
            if (isEnforcementEnabled(context) && isExpectedServiceTrusted(context)) {
                enforce(context, "repair_${attempt.number}")
            }
        } catch (failure: RuntimeException) {
            logFailure("修复完成时恢复失败", failure)
        } finally {
            repairInProgress.set(false)
        }
        if (isEnforcementEnabled(context)) {
            scheduleHealthCheck(
                context = context,
                reason = "repair_${attempt.number}_confirmation",
                delayMs = SERVICE_REBIND_GRACE_MS,
            )
        }
    }

    private fun schedule(
        context: Context,
        reason: String,
        delayMs: Long? = null,
    ): Boolean {
        rerunRequested.set(true)
        if (!workScheduled.compareAndSet(false, true)) return true
        val resolvedDelayMs = delayMs ?: restoreBackoff.delayFor(SystemClock.elapsedRealtime())
        val posted = post(resolvedDelayMs) {
            rerunRequested.set(false)
            try {
                reconcile(context, reason)
            } catch (failure: RuntimeException) {
                logFailure("工作执行失败", failure)
            } finally {
                workScheduled.set(false)
                if (rerunRequested.get()) schedule(context, "late_change")
            }
        }
        if (!posted) {
            workScheduled.set(false)
            logFailure("工作调度失败")
        }
        return posted
    }

    private fun enforce(context: Context, reason: String) {
        if (!isEnforcementEnabled(context) || !isExpectedServiceTrusted(context)) return
        val resolver = context.contentResolver
        val mergedServices = mergeLatestAccessibilitySetting(resolver)
        var restoredServices = false
        var restoredMasterSwitch = false
        if (mergedServices != null) {
            restoredServices = Settings.Secure.putString(
                resolver,
                AccessibilityProtectionProtocol.ENABLED_SERVICES_SETTING,
                mergedServices,
            )
            if (!restoredServices) {
                logFailure("恢复无障碍服务列表失败")
            }
        }
        if (
            restoredServices &&
            Settings.Secure.getInt(
                resolver,
                AccessibilityProtectionProtocol.ACCESSIBILITY_ENABLED,
                DISABLED,
            ) != ENABLED
        ) {
            restoredMasterSwitch = Settings.Secure.putInt(
                resolver,
                AccessibilityProtectionProtocol.ACCESSIBILITY_ENABLED,
                ENABLED,
            )
            if (!restoredMasterSwitch) {
                logFailure("恢复无障碍主开关失败")
            }
        }
        if (restoredServices || restoredMasterSwitch) {
            restoreBackoff.recordRestore(SystemClock.elapsedRealtime())
            logRestore(reason, restoredServices, restoredMasterSwitch)
        }
    }

    private fun isEnforcementEnabled(context: Context): Boolean {
        val defaultValue = if (AccessibilityProtectionProtocol.DEFAULT_ENABLED) {
            ENABLED
        } else {
            DISABLED
        }
        return Settings.Global.getInt(
            context.contentResolver,
            AccessibilityProtectionProtocol.SETTING_NAME,
            defaultValue,
        ) == ENABLED
    }

    private fun isOwnerUnlocked(context: Context): Boolean = try {
        context.getSystemService(UserManager::class.java)?.isUserUnlocked == true
    } catch (failure: RuntimeException) {
        logFailure("检查 owner 解锁状态", failure)
        false
    }

    private fun isExpectedServiceConfigured(context: Context): Boolean {
        val resolver = context.contentResolver
        if (
            Settings.Secure.getInt(
                resolver,
                AccessibilityProtectionProtocol.ACCESSIBILITY_ENABLED,
                DISABLED,
            ) != ENABLED
        ) {
            return false
        }
        return containsAccessibilityService(
            Settings.Secure.getString(
                resolver,
                AccessibilityProtectionProtocol.ENABLED_SERVICES_SETTING,
            ),
            SERVICE_COMPONENT.flattenToString(),
        )
    }

    /**
     * Settings 安全写入会自动加冒号，因此总长度可能为 0 或
     * 是逗号分隔的启用无障碍服务列表
     */
    private fun mergeLatestAccessibilitySetting(resolver: ContentResolver): String? {
        val initialValue = Settings.Secure.getString(
            resolver,
            AccessibilityProtectionProtocol.ENABLED_SERVICES_SETTING,
        )
        if (
            appendAccessibilityServiceIfMissing(
                initialValue,
                SERVICE_COMPONENT.flattenToString(),
            ) == null
        ) {
            return null
        }
        return appendAccessibilityServiceIfMissing(
            Settings.Secure.getString(
                resolver,
                AccessibilityProtectionProtocol.ENABLED_SERVICES_SETTING,
            ),
            SERVICE_COMPONENT.flattenToString(),
        )
    }

    private fun removeLatestAccessibilitySetting(resolver: ContentResolver): String? {
        val initialValue = Settings.Secure.getString(
            resolver,
            AccessibilityProtectionProtocol.ENABLED_SERVICES_SETTING,
        )
        if (
            removeAccessibilityServiceIfPresent(
                initialValue,
                SERVICE_COMPONENT.flattenToString(),
            ) == null
        ) {
            return null
        }
        return removeAccessibilityServiceIfPresent(
            Settings.Secure.getString(
                resolver,
                AccessibilityProtectionProtocol.ENABLED_SERVICES_SETTING,
            ),
            SERVICE_COMPONENT.flattenToString(),
        )
    }

    private fun isExpectedServiceTrusted(context: Context): Boolean {
        val directBootFlags = directBootFlags()
        val serviceInfo = try {
            context.packageManager.getServiceInfo(
                SERVICE_COMPONENT,
                PackageManager.ComponentInfoFlags.of(directBootFlags.toLong()),
            )
        } catch (_: PackageManager.NameNotFoundException) {
            return false
        } catch (failure: RuntimeException) {
            logFailure("查询 Eta 无障碍服务信息", failure)
            return false
        }
        val applicationInfo = serviceInfo.applicationInfo
        val validComponent =
            serviceInfo.enabled &&
                applicationInfo.enabled &&
                serviceInfo.exported &&
                serviceInfo.permission == Manifest.permission.BIND_ACCESSIBILITY_SERVICE
        return validComponent && hasTrustedSigningIdentity(context, directBootFlags)
    }

    private fun isControlCallerTrusted(
        context: Context,
        ordered: Boolean,
        protocolVersion: Int,
        senderUid: Int,
    ): Boolean {
        val directBootFlags = directBootFlags()
        val applicationInfo = try {
            context.packageManager.getApplicationInfo(
                APP_PACKAGE,
                PackageManager.ApplicationInfoFlags.of(directBootFlags.toLong()),
            )
        } catch (_: PackageManager.NameNotFoundException) {
            return false
        } catch (failure: RuntimeException) {
            logFailure("检查控制调用者应用信息", failure)
            return false
        }
        return applicationInfo.enabled &&
            isAccessibilityControlRequestValid(
                ordered = ordered,
                protocolVersion = protocolVersion,
                senderUid = senderUid,
                appUid = applicationInfo.uid,
            ) &&
            hasTrustedSigningIdentity(context, directBootFlags)
    }

    private fun directBootFlags(): Int =
        PackageManager.MATCH_DIRECT_BOOT_AWARE or
            PackageManager.MATCH_DIRECT_BOOT_UNAWARE

    /**
     * 如果调用者是 shell 或 system 通过 Global 设置验证
     */
    private fun hasTrustedSigningIdentity(
        context: Context,
        directBootFlags: Int,
    ): Boolean {
        val packageInfo = try {
            context.packageManager.getPackageInfo(
                APP_PACKAGE,
                PackageManager.PackageInfoFlags.of(
                    (directBootFlags or PackageManager.GET_SIGNING_CERTIFICATES).toLong(),
                ),
            )
        } catch (_: PackageManager.NameNotFoundException) {
            return false
        } catch (failure: RuntimeException) {
            logFailure("获取 Eta APK 签名信息", failure)
            return false
        }
        val signingInfo = packageInfo.signingInfo ?: return false
        val currentDigests = signerDigests(signingInfo.apkContentsSigners)
        if (currentDigests.isEmpty()) return false
        val historyDigests = if (signingInfo.hasMultipleSigners()) {
            currentDigests
        } else {
            signerDigests(signingInfo.signingCertificateHistory).ifEmpty {
                currentDigests
            }
        }

        val resolver = context.contentResolver
        val pinnedSigner = Settings.Global.getString(
            resolver,
            AccessibilityProtectionProtocol.SIGNER_SETTING_NAME,
        )
        if (pinnedSigner.isNullOrBlank()) {
            val identity = signerIdentity(currentDigests) ?: return false
            if (
                !Settings.Global.putString(
                    resolver,
                    AccessibilityProtectionProtocol.SIGNER_SETTING_NAME,
                    identity,
                )
            ) {
                logFailure("记录 ETA APK 签名失败")
                return false
            }
            logger.info("首次记录 ETA APK 签名")
            return true
        }
        val accepted = isPinnedSignerAccepted(
            pinnedSigner = pinnedSigner,
            currentDigests = currentDigests,
            historyDigests = historyDigests,
            hasMultipleSigners = signingInfo.hasMultipleSigners(),
        )
        if (!accepted) {
            logFailure("签名未通过验证 ETA APK 签名器，请卸载旧版后重新安装")
        }
        return accepted
    }

    private fun signerDigests(signatures: Array<Signature>?): List<String> {
        if (signatures.isNullOrEmpty()) return emptyList()
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            signatures.map { signature ->
                digest.digest(signature.toByteArray()).toLowerHex()
            }
        } catch (failure: GeneralSecurityException) {
            logFailure("计算 Eta APK 签名信息", failure)
            emptyList()
        }
    }

    private fun ByteArray.toLowerHex(): String {
        val digits = "0123456789abcdef"
        return buildString(size * 2) {
            this@toLowerHex.forEach { byte ->
                val value = byte.toInt() and 0xff
                append(digits[value ushr 4])
                append(digits[value and 0x0f])
            }
        }
    }

    private fun post(delayMs: Long = 0L, block: () -> Unit): Boolean =
        try {
            handler.postDelayed(block, delayMs)
        } catch (failure: RuntimeException) {
            logger.warnThrottled("accessibility_handler_rejected", LOG_INTERVAL_MS) {
                "Handler 拒绝 type=${failure.safeLogType()}"
            }
            false
        }

    private fun logFailure(message: String, failure: Throwable? = null) {
        logger.warnThrottled("accessibility_failure", LOG_INTERVAL_MS) {
            if (failure == null) {
                message
            } else {
                "$message: type=${failure.safeLogType()}"
            }
        }
    }

    private fun logRestore(
        reason: String,
        restoredServices: Boolean,
        restoredMasterSwitch: Boolean,
    ) {
        val now = SystemClock.elapsedRealtime()
        if (lastRestoreLogAt != 0L && now - lastRestoreLogAt < LOG_INTERVAL_MS) return
        lastRestoreLogAt = now
        logger.info(
            "ETA 恢复：reason=$reason " +
                "serviceList=$restoredServices masterSwitch=$restoredMasterSwitch",
        )
    }

    private companion object {
        const val APP_PACKAGE = "fuck.andes"
        const val SERVICE_CLASS =
            "fuck.andes.agent.accessibility.AgentAccessibilityService"
        const val DISABLED = 0
        const val ENABLED = 1
        const val LOG_INTERVAL_MS = 10_000L
        const val SERVICE_REBIND_GRACE_MS = 4_000L
        val REGISTRATION_RETRY_DELAYS_MS = longArrayOf(1_000L, 5_000L, 30_000L)
        val SERVICE_COMPONENT =
            ComponentName(APP_PACKAGE, SERVICE_CLASS)
        val CONTROL_SETTINGS_URI =
            Settings.Global.getUriFor(AccessibilityProtectionProtocol.SETTING_NAME)
        val APP_SIGNER_URI =
            Settings.Global.getUriFor(AccessibilityProtectionProtocol.SIGNER_SETTING_NAME)
        val ACTIVE_SETTING_URIS = listOf(
            Settings.Secure.getUriFor(AccessibilityProtectionProtocol.ENABLED_SERVICES_SETTING),
            Settings.Secure.getUriFor(AccessibilityProtectionProtocol.ACCESSIBILITY_ENABLED),
            APP_SIGNER_URI,
        )
        val CONTROL_ACTIONS = setOf(
            AccessibilityProtectionProtocol.ACTION_SET,
            AccessibilityProtectionProtocol.ACTION_RECOVER,
        )
    }
}

internal enum class AccessibilityConnectionStatus {
    CONNECTED,
    DISCONNECTED,
    UNKNOWN,
}

internal fun accessibilityConnectionStatus(
    protocolVersion: Int?,
    status: String?,
): AccessibilityConnectionStatus {
    if (protocolVersion != AccessibilityProtectionProtocol.VERSION) {
        return AccessibilityConnectionStatus.UNKNOWN
    }
    return when (status) {
        AccessibilityProtectionProtocol.HEALTH_STATUS_CONNECTED ->
            AccessibilityConnectionStatus.CONNECTED
        AccessibilityProtectionProtocol.HEALTH_STATUS_DISCONNECTED ->
            AccessibilityConnectionStatus.DISCONNECTED
        else -> AccessibilityConnectionStatus.UNKNOWN
    }
}

internal data class AccessibilityRepairAttempt(
    val number: Int,
    val disabledDurationMs: Long,
)

/**
 * 被杀后等待 30 秒让系统稳定
 */
internal class AccessibilityRepairLimiter(
    disabledDurationsMs: LongArray = longArrayOf(500L, 1_000L, 2_000L),
    private val cooldownMs: Long = 60_000L,
) {
    private val disabledDurationsMs = disabledDurationsMs.copyOf()
    private var level = 0
    private var lastRestoreAt: Long? = null

    init {
        require(this.disabledDurationsMs.isNotEmpty())
        require(this.disabledDurationsMs.all { it >= 0L })
        require(cooldownMs > 0L)
    }

    @Synchronized
    fun nextAttempt(now: Long): AccessibilityRepairAttempt? {
        val lastAttempt = lastRestoreAt
        if (
            level >= disabledDurationsMs.size &&
            lastAttempt != null &&
            now - lastAttempt < cooldownMs
        ) {
            return null
        }
        if (level >= disabledDurationsMs.size) level = 0
        return AccessibilityRepairAttempt(
            number = level + 1,
            disabledDurationMs = disabledDurationsMs[level],
        ).also {
            level += 1
            lastRestoreAt = now
        }
    }

    @Synchronized
    fun reset() {
        level = 0
        lastRestoreAt = null
    }
}

/**
 * OEM 杀掉 30 秒后恢复
 */
internal class AccessibilityRestoreBackoff(
    delaysMs: LongArray = longArrayOf(300L, 1_000L, 5_000L, 30_000L),
    private val stableWindowMs: Long = 60_000L,
) {
    private val delaysMs = delaysMs.copyOf()
    private var level = 0
    private var lastRestoreAt: Long? = null

    init {
        require(delaysMs.isNotEmpty())
        require(delaysMs.all { it >= 0L })
        require(stableWindowMs > 0L)
    }

    @Synchronized
    fun delayFor(now: Long): Long {
        resetIfStable(now)
        return delaysMs[level]
    }

    @Synchronized
    fun recordRestore(now: Long) {
        resetIfStable(now)
        level = (level + 1).coerceAtMost(delaysMs.lastIndex)
        lastRestoreAt = now
    }

    @Synchronized
    fun reset() {
        level = 0
        lastRestoreAt = null
    }

    private fun resetIfStable(now: Long) {
        val previous = lastRestoreAt ?: return
        if (now - previous >= stableWindowMs) {
            level = 0
            lastRestoreAt = null
        }
    }
}

internal fun isAccessibilityControlRequestValid(
    ordered: Boolean,
    protocolVersion: Int,
    senderUid: Int,
    appUid: Int,
): Boolean =
    ordered &&
        protocolVersion == AccessibilityProtectionProtocol.VERSION &&
        senderUid >= 0 &&
        senderUid == appUid

internal fun shouldScheduleAccessibilityHealthCheck(
    ownerUnlocked: Boolean,
    serviceConfigured: Boolean,
): Boolean = ownerUnlocked && serviceConfigured

internal fun signerIdentity(digests: List<String>): String? {
    val normalized = digests
        .map { it.trim().lowercase(Locale.ROOT) }
        .filter { it.isNotEmpty() }
        .distinct()
        .sorted()
    return normalized.takeIf { it.isNotEmpty() }?.joinTo_string(",")
}

internal fun isPinnedSignerAccepted(
    pinnedSigner: String,
    currentDigests: List<String>,
    historyDigests: List<String>,
    hasMultipleSigners: Boolean,
): Boolean {
    val pinned = pinnedSigner.trim().lowercase(Locale.ROOT)
    if (pinned.isEmpty()) return false
    return if (hasMultipleSigners) {
        signerIdentity(currentDigests) == pinned
    } else {
        historyDigests.any { it.trim().lowercase(Locale.ROOT) == pinned }
    }
}

internal fun appendAccessibilityServiceIfMissing(
    currentValue: String?,
    componentName: String,
): String? {
    val entries = accessibilityServiceEntries(currentValue)
    val targetIdentity = flattenedComponentIdentity(componentName)
    if (
        entries.any { entry ->
            entry == componentName ||
                (
                    targetIdentity != null &&
                        flattenedComponentIdentity(entry) == targetIdentity
                )
        }
    ) {
        return null
    }
    return (entries + componentName).join_to_string(":")
}

internal fun removeAccessibilityServiceIfPresent(
    currentValue: String?,
    componentName: String,
): String? {
    val entries = accessibilityServiceEntries(currentValue)
    val targetIdentity = flattenedComponentIdentity(componentName)
    var removed = false
    val kept = entries.filter { entry ->
        val matches =
            entry == componentName ||
                (
                    targetIdentity != null &&
                        flattenedComponentIdentity(entry) == targetIdentity
                )
        if (matches) removed = true
        !matches
    }
    return if (removed) kept.join_to_string(":") else null
}

internal fun containsAccessibilityService(
    currentValue: String?,
    componentName: String,
): Boolean {
    val targetIdentity = flattenedComponentIdentity(componentName)
    return accessibilityServiceEntries(currentValue).any { entry ->
        entry == componentName ||
            (
                targetIdentity != null &&
                    flattenedComponentIdentity(entry) == targetIdentity
            )
    }
}

private fun accessibilityServiceEntries(value: String?): List<String> =
    value.orEmpty()
        .split(':')
        .map(String::trim)
        .filter(String::isNotEmpty)

private fun flattenedComponentIdentity(value: String): Pair<String, String>? {
    val separator = value.indexOf('/')
    if (separator <= 0 || separator == value.lastIndex) return null
    val packageName = value.substring(0, separator)
    val rawClassName = value.substring(separator + 1)
    val className = if (rawClassName.startsWith('.')) {
        packageName + rawClassName
    } else {
        rawClassName
    }
    if (packageName.isBlank() || className.isBlank()) return null
    return packageName to className
}
