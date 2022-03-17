package com.kylecorry.trail_sense.shared.permissions

import android.content.Context
import android.os.Build
import com.kylecorry.andromeda.core.specifications.Specification
import com.kylecorry.andromeda.core.system.Android
import com.kylecorry.trail_sense.navigation.paths.infrastructure.BacktrackIsEnabled
import com.kylecorry.trail_sense.navigation.paths.infrastructure.BacktrackRequiresForeground
import com.kylecorry.trail_sense.weather.infrastructure.WeatherMonitorIsEnabled

class IsBatteryExemptionRequired : Specification<Context>() {

    override fun isSatisfiedBy(value: Context): Boolean {
        if (Android.sdk < Build.VERSION_CODES.S){
            return false
        }
        val backtrack = BacktrackIsEnabled().and(BacktrackRequiresForeground())
        val weather = WeatherMonitorIsEnabled()
        val foregroundRequired = backtrack.or(weather)
        return foregroundRequired.isSatisfiedBy(value)
    }
}