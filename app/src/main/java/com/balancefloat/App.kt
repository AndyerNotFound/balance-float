package com.balancefloat

import android.app.Application
import com.google.android.material.color.DynamicColors

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Android 12+ 跟随壁纸取色（Material You）
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
