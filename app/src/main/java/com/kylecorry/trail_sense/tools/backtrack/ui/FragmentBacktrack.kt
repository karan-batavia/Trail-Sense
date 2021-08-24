package com.kylecorry.trail_sense.tools.backtrack.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.lifecycle.LiveData
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.kylecorry.andromeda.alerts.Alerts
import com.kylecorry.andromeda.core.sensors.asLiveData
import com.kylecorry.andromeda.core.time.Timer
import com.kylecorry.andromeda.core.time.toZonedDateTime
import com.kylecorry.andromeda.core.tryOrNothing
import com.kylecorry.andromeda.fragments.BoundFragment
import com.kylecorry.andromeda.fragments.show
import com.kylecorry.andromeda.list.ListView
import com.kylecorry.andromeda.preferences.Preferences
import com.kylecorry.trail_sense.R
import com.kylecorry.trail_sense.databinding.FragmentBacktrackBinding
import com.kylecorry.trail_sense.databinding.ListItemWaypointBinding
import com.kylecorry.trail_sense.navigation.domain.BeaconEntity
import com.kylecorry.trail_sense.navigation.domain.MyNamedCoordinate
import com.kylecorry.trail_sense.navigation.infrastructure.persistence.BeaconRepo
import com.kylecorry.trail_sense.shared.*
import com.kylecorry.trail_sense.shared.sensors.SensorService
import com.kylecorry.trail_sense.tools.backtrack.domain.WaypointEntity
import com.kylecorry.trail_sense.tools.backtrack.infrastructure.BacktrackScheduler
import com.kylecorry.trail_sense.tools.backtrack.infrastructure.persistence.WaypointRepo
import com.kylecorry.trailsensecore.domain.geo.GeoService
import com.kylecorry.trailsensecore.domain.navigation.Beacon
import com.kylecorry.trailsensecore.domain.navigation.BeaconOwner
import com.kylecorry.trailsensecore.domain.navigation.NavigationService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant

class FragmentBacktrack : BoundFragment<FragmentBacktrackBinding>() {

    private val waypointRepo by lazy { WaypointRepo.getInstance(requireContext()) }
    private lateinit var waypointsLiveData: LiveData<List<WaypointEntity>>
    private val formatService by lazy { FormatServiceV2(requireContext()) }
    private val prefs by lazy { UserPreferences(requireContext()) }
    private val beaconRepo by lazy { BeaconRepo.getInstance(requireContext()) }
    private val navigationService = NavigationService()
    private val geoService = GeoService()
    private val sensorService by lazy { SensorService(requireContext()) }
    private val gps by lazy { sensorService.getGPS(false) }
    private val compass by lazy { sensorService.getCompass() }
    private val cache by lazy { Preferences(requireContext()) }

    private var pathIds: List<Long> = emptyList()

    private val stateChecker = Timer {
        context ?: return@Timer
        wasEnabled = BacktrackScheduler.isOn(requireContext())
        if (wasEnabled && !(prefs.isLowPowerModeOn && prefs.lowPowerModeDisablesBacktrack)) {
            binding.startBtn.setImageResource(R.drawable.ic_baseline_stop_24)
        } else {
            binding.startBtn.setImageResource(R.drawable.ic_baseline_play_arrow_24)
        }
    }

    private var wasEnabled = false

    private lateinit var listView: ListView<BacktrackListItem>
    private var pathSheet: PathBottomSheet? = null
    private var displayedPathId: Long? = null


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        listView = ListView(binding.waypointsList, R.layout.list_item_waypoint) { itemView, item ->
            drawListItem(ListItemWaypointBinding.bind(itemView), item)
        }

        listView.addLineSeparator()

        waypointsLiveData = waypointRepo.getWaypoints()
        waypointsLiveData.observe(viewLifecycleOwner) { waypoints ->
            onWaypointsChanged(waypoints)
        }

        wasEnabled = prefs.backtrackEnabled
        if (wasEnabled && !(prefs.isLowPowerModeOn && prefs.lowPowerModeDisablesBacktrack)) {
            binding.startBtn.setImageResource(R.drawable.ic_baseline_stop_24)
        } else {
            binding.startBtn.setImageResource(R.drawable.ic_baseline_play_arrow_24)
        }

        binding.startBtn.setOnClickListener {
            if (prefs.isLowPowerModeOn && prefs.lowPowerModeDisablesBacktrack) {
                Alerts.toast(
                    requireContext(),
                    getString(R.string.backtrack_disabled_low_power_toast)
                )
            } else {
                prefs.backtrackEnabled = !wasEnabled
                if (!wasEnabled) {
                    binding.startBtn.setImageResource(R.drawable.ic_baseline_stop_24)
                    BacktrackScheduler.start(requireContext(), true)
                } else {
                    binding.startBtn.setImageResource(R.drawable.ic_baseline_play_arrow_24)
                    BacktrackScheduler.stop(requireContext())
                }
                wasEnabled = !wasEnabled
            }
        }

        // TODO: Only listen when the path sheet is open
        gps.asLiveData().observe(viewLifecycleOwner, { onLocationUpdate() })
        compass.asLiveData().observe(viewLifecycleOwner, { onCompassUpdate() })
    }

    private fun deleteWaypoint(waypointEntity: WaypointEntity) {
        Alerts.dialog(
            requireContext(),
            getString(R.string.delete_waypoint_prompt),
            getWaypointTitle(waypointEntity)
        ) { cancelled ->
            if (!cancelled) {
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        waypointRepo.deleteWaypoint(waypointEntity)
                    }
                }
            }
        }
    }

    private fun createBeacon(waypoint: WaypointEntity) {
        AppUtils.placeBeacon(
            requireContext(),
            MyNamedCoordinate(waypoint.coordinate, getWaypointTitle(waypoint))
        )
    }

    private fun getWaypointTitle(waypoint: WaypointEntity): String {
        val date = waypoint.createdInstant.toZonedDateTime()
        val time = date.toLocalTime()
        return getString(
            R.string.waypoint_beacon_title_template,
            formatService.formatDate(
                date,
                includeWeekDay = false
            ), formatService.formatTime(time, includeSeconds = false)
        )
    }

    private fun onLocationUpdate() {
        pathSheet?.location = gps.location
        compass.declination = getDeclination()
        onCompassUpdate()
    }

    private fun onCompassUpdate() {
        pathSheet?.azimuth = compass.rawBearing
    }

    private fun getDeclination(): Float {
        return if (!prefs.useAutoDeclination) {
            prefs.declinationOverride
        } else {
            geoService.getDeclination(gps.location, gps.altitude)
        }
    }

    override fun onResume() {
        super.onResume()
        stateChecker.interval(Duration.ofSeconds(1))
    }

    override fun onPause() {
        super.onPause()
        stateChecker.stop()
    }

    override fun generateBinding(
        layoutInflater: LayoutInflater,
        container: ViewGroup?
    ): FragmentBacktrackBinding {
        return FragmentBacktrackBinding.inflate(layoutInflater, container, false)
    }

    private fun onWaypointsChanged(waypoints: List<WaypointEntity>) {
        val filteredWaypoints = filterCurrentWaypoints(waypoints)
        val groupedWaypoints =
            groupWaypointsByPath(filteredWaypoints).toList().sortedByDescending { it.first }

        pathIds = groupedWaypoints.map { it.first }

        val listItems = mutableListOf<BacktrackListItem>()
        for (group in groupedWaypoints) {
            listItems.add(PathListItem(group.second))
            listItems.addAll(group.second.sortedByDescending { it.createdOn }
                .map { WaypointListItem(it) })

            if (group.first == displayedPathId) {
                pathSheet?.path = group.second
            }
        }

        listView.setData(listItems)

        if (filteredWaypoints.isEmpty()) {
            binding.waypointsEmptyText.visibility = View.VISIBLE
        } else {
            binding.waypointsEmptyText.visibility = View.INVISIBLE
        }
    }

    private fun drawListItem(itemBinding: ListItemWaypointBinding, item: BacktrackListItem) {
        if (item is WaypointListItem) {
            drawWaypointListItem(itemBinding, item)
        } else if (item is PathListItem) {
            drawPathListItem(itemBinding, item)
        }
    }

    private fun drawWaypointListItem(itemBinding: ListItemWaypointBinding, item: WaypointListItem) {
        val itemStrategy = WaypointListItemStrategy(
            requireContext(),
            formatService,
            prefs,
            { createBeacon(it) },
            { deleteWaypoint(it) },
            { navigateToWaypoint(it) }
        )

        itemStrategy.display(itemBinding, item)
    }

    private fun drawPathListItem(itemBinding: ListItemWaypointBinding, item: PathListItem) {
        val itemStrategy =
            PathListItemStrategy(
                requireContext(),
                formatService,
                prefs,
                navigationService,
                { deletePath(it) },
                { mergePreviousPath(it) },
                { showPath(it) }
            )
        itemStrategy.display(itemBinding, item)
    }

    private fun filterCurrentWaypoints(waypoints: List<WaypointEntity>): List<WaypointEntity> {
        return waypoints.filter {
            it.createdInstant > Instant.now().minus(prefs.navigation.backtrackHistory)
        }
    }

    private fun groupWaypointsByPath(waypoints: List<WaypointEntity>): Map<Long, List<WaypointEntity>> {
        return waypoints.groupBy { it.pathId }
    }

    private fun showPath(path: List<WaypointEntity>) {
        pathSheet?.dismiss()
        pathSheet = PathBottomSheet()
        pathSheet?.path = path
        pathSheet?.location = gps.location
        displayedPathId = path.firstOrNull()?.pathId
        pathSheet?.drawPathToGPS = isCurrentPath(path.firstOrNull()?.pathId ?: 0L)
        pathSheet?.show(this)
    }

    private fun deletePath(path: List<WaypointEntity>) {
        Alerts.dialog(
            requireContext(),
            getString(R.string.delete_path),
            resources.getQuantityString(R.plurals.waypoints_to_be_deleted, path.size, path.size)
        ) { cancelled ->
            if (!cancelled) {
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        waypointRepo.deletePath(path.first().pathId)
                    }
                }
            }
        }
    }

    private fun isCurrentPath(pathId: Long): Boolean {
        if (!prefs.backtrackEnabled || (prefs.isLowPowerModeOn && prefs.lowPowerModeDisablesBacktrack)) return false
        val current = cache.getLong(getString(R.string.pref_last_backtrack_path_id))
        return current == pathId
    }

    private fun mergePreviousPath(path: List<WaypointEntity>) {
        val current = path.first().pathId
        val previous = pathIds.filter { it < current }.maxOrNull()

        if (previous == null) {
            Alerts.toast(requireContext(), getString(R.string.no_previous_path))
            return
        }

        Alerts.dialog(
            requireContext(),
            getString(R.string.merge_previous_path_title)
        ) { cancelled ->
            if (!cancelled) {
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        waypointRepo.moveToPath(previous, current)
                    }
                }
            }
        }
    }

    private fun navigateToWaypoint(waypoint: WaypointEntity) {
        tryOrNothing {
            lifecycleScope.launch {
                val date = waypoint.createdInstant.toZonedDateTime()
                val time = date.toLocalTime()
                var newTempId: Long
                withContext(Dispatchers.IO) {
                    val tempBeaconId =
                        beaconRepo.getTemporaryBeacon(BeaconOwner.Backtrack)?.id ?: 0L
                    val beacon = Beacon(
                        tempBeaconId,
                        getString(
                            R.string.waypoint_beacon_title_template,
                            formatService.formatDate(
                                date,
                                includeWeekDay = false
                            ), formatService.formatTime(time, includeSeconds = false)
                        ),
                        waypoint.coordinate,
                        visible = false,
                        elevation = waypoint.altitude,
                        temporary = true,
                        color = prefs.navigation.backtrackPathColor.color,
                        owner = BeaconOwner.Backtrack
                    )
                    beaconRepo.addBeacon(BeaconEntity.from(beacon))

                    newTempId =
                        beaconRepo.getTemporaryBeacon(BeaconOwner.Backtrack)?.id ?: 0L
                }

                withContext(Dispatchers.Main) {
                    findNavController().navigate(
                        R.id.action_fragmentBacktrack_to_action_navigation,
                        bundleOf("destination" to newTempId)
                    )
                }
            }
        }
    }

    interface BacktrackListItem
    data class WaypointListItem(val waypoint: WaypointEntity) : BacktrackListItem
    data class PathListItem(val path: List<WaypointEntity>) : BacktrackListItem

}