package com.example.version1

import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager

/** Pleos IME policy: input windows resize; non-editing popups do not anchor to another IME. */
internal object DialogImePolicy {
    fun containsEditor(view: View): Boolean = view.onCheckIsTextEditor() ||
        (view is ViewGroup && (0 until view.childCount).any { containsEditor(view.getChildAt(it)) })
    fun apply(window: Window, content: View) {
        val editor = containsEditor(content)
        if (editor) window.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        else window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        window.setSoftInputMode(if (editor) WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            else WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        if (Build.VERSION.SDK_INT >= 30) window.attributes = window.attributes.apply {
            setFitInsetsTypes(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars() or
                if (editor) WindowInsets.Type.ime() else 0)
        }
    }
}
