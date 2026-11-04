package com.cloudmonitor.monitor

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reports ANR ("isn't responding") observations.
 *
 * `ApplicationExitInfo` only ever covers the caller's own UID, so the only way to obtain
 * ANR evidence for a THIRD party app on a stock device is to observe the system's
 * "isn't responding" dialog. This service does exactly that, and nothing else.
 */
@Singleton
class AnrReporter @Inject constructor() {

    private companion object {
        const val TAG = "AnrReporter"
    }

    private val _anrEvents = MutableSharedFlow<Long>(extraBufferCapacity = 4)

    /** Emits the timestamp of every observed ANR dialog. */
    val anrEvents: SharedFlow<Long> = _anrEvents.asSharedFlow()

    fun report() {
        Log.w(TAG, "ANR dialog observed")
        _anrEvents.tryEmit(System.currentTimeMillis())
    }
}

/**
 * Optional AccessibilityService that watches for the system ANR dialog.
 *
 * It is optional on purpose: the monitor works without it, it merely upgrades an
 * unclassified death from `E-101` (low confidence) to `E-204` (ANR) when it is enabled.
 */
class AnrWatcherAccessibilityService : AccessibilityService() {

    private lateinit var reporter: AnrReporter

    override fun onServiceConnected() {
        super.onServiceConnected()
        reporter = EntryPointAccessors.fromApplication(
            applicationContext,
            MonitorEntryPoint::class.java,
        ).anrReporter()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val type = event?.eventType ?: return
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            return
        }
        if (!looksLikeAnrDialog(event)) return
        reporter.report()
    }

    override fun onInterrupt() = Unit

    private companion object {
        const val MAX_DEPTH = 8
    }

    private fun looksLikeAnrDialog(event: AccessibilityEvent): Boolean {
        val text = StringBuilder()
        event.text?.let { text.append(it).append(' ') }
        event.contentDescription?.let { text.append(it).append(' ') }

        // The window content is reached through the AccessibilityNodeInfo of the event.
        val root = event.source
        if (root != null) {
            collectText(root, text, depth = 0)
            root.recycle()
        }

        val haystack = text.toString()
        return haystack.contains("isn't responding", ignoreCase = true) ||
            haystack.contains("not responding", ignoreCase = true) ||
            haystack.contains("keeps stopping", ignoreCase = true)
    }

    /** Depth limited traversal of the window content. */
    private fun collectText(node: AccessibilityNodeInfo, out: StringBuilder, depth: Int) {
        if (depth > MAX_DEPTH) return
        node.text?.let { out.append(it).append(' ') }
        node.contentDescription?.let { out.append(it).append(' ') }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            collectText(child, out, depth + 1)
            child.recycle()
        }
    }
}
