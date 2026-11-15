package com.example.version1

import android.graphics.Rect
import android.util.TypedValue
import android.widget.TextView
import kotlin.math.ceil

/** Reported display DPI is an engineering proxy; OEM panel calibration remains required.
 * Measure actual bundled-font ink, rather than treating nominal sp as glyph height. */
internal object PleosTypography {
    fun requiredInkPixels(millimeters:Float,reportedDpi:Float,density:Float):Int {
        require(millimeters.isFinite() && millimeters in 1f..20f)
        require(density.isFinite() && density>0)
        val dpi=reportedDpi.takeIf { it.isFinite() && it in 72f..1000f } ?: (160f*density)
        return ceil(millimeters*dpi/25.4f).toInt()
    }
    fun minimumInkHeight(view:TextView,millimeters:Float) {
        val value=view.text.toString().takeIf { it.any(Char::isLetterOrDigit) } ?: return
        val metrics=view.resources.displayMetrics
        val target=requiredInkPixels(millimeters,metrics.ydpi,metrics.density)
        val bounds=Rect()
        repeat(3) {
            view.paint.getTextBounds(value,0,value.length,bounds)
            if(bounds.height()<=0 || bounds.height()>=target) return
            val pixels=ceil(view.textSize*target/bounds.height())+1f
            view.setTextSize(TypedValue.COMPLEX_UNIT_PX,pixels)
        }
    }
    fun safetyNotice(view:TextView,full:String,compact:String) {
        val initial=if(view.paint.measureText(full)<=view.maxWidth) full else compact
        if(view.text.toString()!=initial) view.text=initial
        minimumInkHeight(view,5f)
        if(view.paint.measureText(view.text.toString())>view.maxWidth && view.text.toString()!=compact) {
            view.text=compact
            minimumInkHeight(view,5f)
        }
    }
}
