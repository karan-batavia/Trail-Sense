package com.kylecorry.trail_sense.tools.signal_finder.ui

import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.core.text.method.LinkMovementMethodCompat
import com.kylecorry.andromeda.core.system.GeoUri
import com.kylecorry.andromeda.fragments.useBackgroundEffect
import com.kylecorry.andromeda.markdown.MarkdownService
import com.kylecorry.andromeda.signal.CellNetwork
import com.kylecorry.andromeda.signal.CellSignal
import com.kylecorry.andromeda.views.list.AndromedaListView
import com.kylecorry.andromeda.views.toolbar.Toolbar
import com.kylecorry.sol.science.geology.Geofence
import com.kylecorry.sol.units.Coordinate
import com.kylecorry.sol.units.Distance
import com.kylecorry.trail_sense.R
import com.kylecorry.trail_sense.shared.extensions.TrailSenseReactiveFragment
import com.kylecorry.trail_sense.shared.extensions.useCellSignalSensor
import com.kylecorry.trail_sense.shared.extensions.useCoroutineQueue
import com.kylecorry.trail_sense.shared.extensions.useLocation
import com.kylecorry.trail_sense.shared.extensions.useService
import com.kylecorry.trail_sense.shared.extensions.useTopic
import com.kylecorry.trail_sense.shared.openTool
import com.kylecorry.trail_sense.tools.navigation.infrastructure.Navigator
import com.kylecorry.trail_sense.tools.signal_finder.infrastructure.CellTowerModel
import com.kylecorry.trail_sense.tools.tools.infrastructure.Tools
import java.time.Duration

class ToolSignalFinderFragment : TrailSenseReactiveFragment(R.layout.fragment_signal_finder) {

    override fun update() {
        val context = useAndroidContext()

        // Views
        val list = useView2<AndromedaListView>(R.id.list)
        val disclaimer = useView2<TextView>(R.id.disclaimer)
        val emptyText = useView2<TextView>(R.id.empty_text)
        val title = useView2<Toolbar>(R.id.title)
        val navController = useNavController()

        // Services
        val markdown = useService<MarkdownService>()
        val navigator = useService<Navigator>()

        // Other objects
        val queue = useCoroutineQueue()

        // Sensor readings
        val signals = useCellSignals()
        val location = useLocation(Duration.ofSeconds(5))

        // State
        val (nearby, setNearby) = useState<List<Pair<Coordinate, List<CellNetwork>>>>(emptyList())
        val (loading, setLoading) = useState(false)

        list.emptyView = emptyText

        // Set up the disclaimer
        useEffect(disclaimer) {
            disclaimer.text = markdown.toMarkdown(getString(R.string.cell_tower_disclaimer))
            disclaimer.movementMethod = LinkMovementMethodCompat.getInstance()
        }

        // List items
        useEffect(
            list,
            navController,
            signals,
            nearby,
            distance(location, Distance.meters(100f))
        ) {
            val signalMapper = CellSignalListItemMapper(context)
            val signalItems = signals.map { signalMapper.map(it) }

            val towerMapper =
                CellTowerListItemMapper(context, location) { (towerLocation, _), action ->
                    when (action) {
                        CellTowerListItemAction.Navigate -> {
                            navigator.navigateTo(towerLocation, getString(R.string.cell_tower))
                            navController.openTool(Tools.NAVIGATION)
                        }

                        CellTowerListItemAction.CreateBeacon -> {
                            val bundle = bundleOf(
                                "initial_location" to GeoUri(towerLocation)
                            )
                            navController.navigate(R.id.placeBeaconFragment, bundle)
                        }
                    }
                }
            val nearbyItems = nearby.flatMap { (towerLocation, networks) ->
                networks.map { towerMapper.map(towerLocation to it) }
            }

            list.setItems(signalItems + nearbyItems)
        }

        // Cell tower updating
        useBackgroundEffect(distance(location, Distance.meters(100f))) {
            setLoading(true)
            queue.replace {
                setNearby(CellTowerModel.getTowers(
                    requireContext(),
                    Geofence(location, Distance.kilometers(20f)),
                    5
                ).sortedBy { location.distanceTo(it.first) })
                setLoading(false)
            }
        }

        // Loading
        useEffect(title, loading) {
            title.subtitle.text = if (loading) {
                getString(R.string.loading)
            } else {
                null
            }
        }
    }

    private fun useCellSignals(): List<CellSignal> {
        val cellSignal = useCellSignalSensor(false)
        return useTopic(cellSignal, emptyList()) { it.signals }
    }
}