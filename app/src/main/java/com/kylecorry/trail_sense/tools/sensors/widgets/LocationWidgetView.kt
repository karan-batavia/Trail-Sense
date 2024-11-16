package com.kylecorry.trail_sense.tools.sensors.widgets

import android.content.Context
import android.widget.RemoteViews
import com.kylecorry.andromeda.permissions.Permissions
import com.kylecorry.luna.coroutines.onMain
import com.kylecorry.trail_sense.shared.FormatService
import com.kylecorry.trail_sense.shared.UserPreferences
import com.kylecorry.trail_sense.shared.navigation.NavigationUtils
import com.kylecorry.trail_sense.shared.sensors.LocationSubsystem
import com.kylecorry.trail_sense.shared.sensors.SensorSubsystem
import com.kylecorry.trail_sense.shared.sensors.SensorSubsystem.SensorRefreshPolicy
import com.kylecorry.trail_sense.tools.tools.infrastructure.Tools
import com.kylecorry.trail_sense.tools.tools.ui.widgets.SimpleToolWidgetView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class LocationWidgetView : SimpleToolWidgetView() {

    override fun onUpdate(context: Context, views: RemoteViews, commit: () -> Unit) {
        CoroutineScope(Dispatchers.Default).launch {
            populateLocationDetails(context, views)
            onMain {
                commit()
            }
        }
    }

    private suspend fun populateLocationDetails(context: Context, views: RemoteViews) {
        val formatter = FormatService.getInstance(context)
        val userPrefs = UserPreferences(context)
        val locationSubsystem = LocationSubsystem.getInstance(context)

        // Check if location is stale and attempt to get a new location
        val isStale = locationSubsystem.locationAge.toMinutes() > 30
        val location =
            if (isStale && Permissions.isBackgroundLocationEnabled(context) && userPrefs.useAutoLocation) {
                val sensors = SensorSubsystem.getInstance(context)
                sensors.getLocation(SensorRefreshPolicy.Refresh)
            } else {
                locationSubsystem.location
            }

        views.setTextViewText(TITLE_TEXTVIEW, formatter.formatLocation(location))
        views.setOnClickPendingIntent(
            ROOT,
            // While this widget belongs to the sensor tool, it makes more sense to open the navigation tool
            NavigationUtils.toolPendingIntent(context, Tools.NAVIGATION)
        )
    }
}
