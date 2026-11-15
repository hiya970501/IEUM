package com.example.version1

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout

/** A panel owns real views; only a genuine native dialog exposes a Window. */
internal interface UiPanel {
    val contentView: View
    val nativeWindow: Window?
    val isShowing: Boolean
    fun getButton(which: Int): Button?
    fun setOnDismissListener(listener: () -> Unit)
    fun show()
    fun dismiss()
}

internal class NativeUiPanel(private val dialog: AlertDialog) : UiPanel {
    override val contentView: View get() = checkNotNull(dialog.window).decorView
    override val nativeWindow: Window? get() = dialog.window
    override val isShowing: Boolean get() = dialog.isShowing
    override fun getButton(which: Int): Button? = dialog.getButton(which)
    override fun setOnDismissListener(listener: () -> Unit) { dialog.setOnDismissListener { listener() } }
    override fun show() = dialog.show()
    override fun dismiss() = dialog.dismiss()
}

/** Public, non-editing content shares the Activity's existing renderer/window.
 * Sensitive and editing content continues to use a real secure/IME native window. */
internal class InlineUiPanel(
    activity: Activity,
    title: String,
    titleView: View,
    body: View,
    positive: Button,
    negative: Button?,
    widthPixels: Int,
    cardBackground: android.graphics.drawable.Drawable
) : UiPanel {
    private val host = activity.findViewById<ViewGroup>(android.R.id.content)
    private val density = activity.resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())
    private var dismissed: (() -> Unit)? = null
    private var showing = false
    private var visibilityGeneration = 0L
    var onClosing: (() -> Unit)? = null
    private val buttons = mapOf(AlertDialog.BUTTON_POSITIVE to positive, AlertDialog.BUTTON_NEGATIVE to negative)
    private fun dp(n: Int) = (n * density).toInt()
    private val layer = FrameLayout(activity).apply {
        isClickable = true
        isFocusableInTouchMode = true
        accessibilityPaneTitle = title
        setBackgroundColor(0x33000000)
        setPadding(dp(12), dp(20), dp(12), dp(20))
    }
    init {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBackground
            isClickable = true // Empty card space must not dismiss or click through.
            addView(titleView, LinearLayout.LayoutParams(-1, -2))
            addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(LinearLayout(activity).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(28), dp(8), dp(28), dp(16))
                negative?.let { addView(it, LinearLayout.LayoutParams(0, dp(80), 1f).apply { marginEnd = dp(12) }) }
                addView(positive, LinearLayout.LayoutParams(0, dp(80), 1f))
            }, LinearLayout.LayoutParams(-1, -2))
        }
        layer.addView(card, FrameLayout.LayoutParams(widthPixels, -2, Gravity.CENTER))
        layer.setOnClickListener { dismiss() }
    }
    override val contentView: View get() = layer
    override val nativeWindow: Window? get() = null
    override val isShowing: Boolean get() = showing && layer.parent === host && layer.isShown
    override fun getButton(which: Int): Button? = buttons[which]
    override fun setOnDismissListener(listener: () -> Unit) { dismissed = listener }
    override fun show() {
        check(!showing && layer.parent == null)
        visibilityGeneration++
        // Detaching clears Android's laid-out state. Reusing unchanged bounds
        // still needs a real layout pass, including the cached descendants.
        fun invalidateLayout(view:View) {
            view.forceLayout()
            if(view is ViewGroup) for(i in 0 until view.childCount) invalidateLayout(view.getChildAt(i))
        }
        invalidateLayout(layer)
        host.addView(layer, ViewGroup.LayoutParams(-1, -1))
        layer.requestLayout()
        showing = true
        layer.requestFocus()
    }
    override fun dismiss() {
        if (!showing) return
        onClosing?.invoke()
        showing = false
        host.removeView(layer)
        // Match native Dialog's posted dismissal ordering during replacement.
        val closedGeneration = visibilityGeneration
        val listener = dismissed
        handler.post {
            // A detached public panel can be reused. An old posted dismissal
            // must never clear focus/modal ownership of its new presentation.
            if (!showing && visibilityGeneration == closedGeneration) listener?.invoke()
        }
    }
}

@android.annotation.TargetApi(33)
internal object UiPanelBackApi33 {
    fun register(activity: Activity, back: () -> Unit): () -> Unit {
        val callback = android.window.OnBackInvokedCallback { back() }
        val dispatcher = activity.onBackInvokedDispatcher
        dispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_OVERLAY, callback)
        return { dispatcher.unregisterOnBackInvokedCallback(callback) }
    }
}
