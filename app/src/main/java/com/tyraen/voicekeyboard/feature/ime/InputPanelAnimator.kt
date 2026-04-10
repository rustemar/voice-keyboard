package com.tyraen.voicekeyboard.feature.ime

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator

class InputPanelAnimator(
    private val wave1: View,
    private val wave2: View
) {

    private var animator1: ObjectAnimator? = null
    private var animator2: ObjectAnimator? = null

    fun beginPulse() {
        wave1.alpha = 0.3f
        wave2.alpha = 0.2f

        animator1 = ObjectAnimator.ofPropertyValuesHolder(
            wave1,
            PropertyValuesHolder.ofFloat("scaleX", 1f, 1.15f, 1f),
            PropertyValuesHolder.ofFloat("scaleY", 1f, 1.15f, 1f)
        ).apply {
            duration = 1500
            repeatCount = ObjectAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }

        animator2 = ObjectAnimator.ofPropertyValuesHolder(
            wave2,
            PropertyValuesHolder.ofFloat("scaleX", 0.9f, 1.05f, 0.9f),
            PropertyValuesHolder.ofFloat("scaleY", 0.9f, 1.05f, 0.9f)
        ).apply {
            duration = 1800
            repeatCount = ObjectAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            startDelay = 200
            start()
        }
    }

    fun haltPulse() {
        animator1?.cancel()
        animator2?.cancel()
        wave1.alpha = 0f
        wave1.scaleX = 1f
        wave1.scaleY = 1f
        wave2.alpha = 0f
        wave2.scaleX = 1f
        wave2.scaleY = 1f
    }

    fun adjustForAmplitude(level: Int) {
        val normalized = (level.coerceIn(0, 20000) / 20000f)
        val scale = 1f + normalized * 0.3f
        wave1.scaleX = scale
        wave1.scaleY = scale
        wave1.alpha = 0.3f + normalized * 0.5f
        wave2.scaleX = scale * 0.9f
        wave2.scaleY = scale * 0.9f
        wave2.alpha = 0.2f + normalized * 0.3f
    }
}
