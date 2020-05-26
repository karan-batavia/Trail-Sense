package com.kylecorry.trail_sense.astronomy.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.kylecorry.trail_sense.R
import com.kylecorry.trail_sense.astronomy.domain.AstronomyService
import com.kylecorry.trail_sense.astronomy.domain.moon.MoonTruePhase
import com.kylecorry.trail_sense.astronomy.domain.sun.SunTimes
import com.kylecorry.trail_sense.astronomy.domain.sun.SunTimesMode
import com.kylecorry.trail_sense.shared.*
import com.kylecorry.trail_sense.shared.align
import com.kylecorry.trail_sense.shared.math.getPercentOfDuration
import com.kylecorry.trail_sense.shared.sensors.GPS
import com.kylecorry.trail_sense.shared.sensors.IGPS
import org.threeten.bp.Duration
import org.threeten.bp.LocalDate
import org.threeten.bp.LocalDateTime
import org.threeten.bp.format.DateTimeFormatter
import java.util.*
import kotlin.concurrent.fixedRateTimer
import kotlin.math.roundToInt

class AstronomyFragment : Fragment() {

    private lateinit var gps: IGPS

    private lateinit var sunTxt: TextView
    private lateinit var remDaylightTxt: TextView
    private lateinit var moonTxt: TextView
    private lateinit var sunStartTimeTxt: TextView
    private lateinit var sunEndTimeTxt: TextView
    private lateinit var moonRiseTimeTxt: TextView
    private lateinit var moonSetTimeTxt: TextView
    private lateinit var timer: Timer
    private lateinit var handler: Handler
    private lateinit var moonPosition: ImageView
    private lateinit var sunPosition: ImageView

    private lateinit var prevDateBtn: ImageButton
    private lateinit var nextDateBtn: ImageButton
    private lateinit var dateTxt: TextView
    private lateinit var astroChart: AstroChart

    private lateinit var displayDate: LocalDate

    private lateinit var sunTimesMode: SunTimesMode

    private val prefs by lazy { UserPreferences(requireContext()) }
    private val astronomyService = AstronomyService()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.activity_astronomy, container, false)

        sunTxt = view.findViewById(R.id.remaining_time)
        moonTxt = view.findViewById(R.id.moon_phase)
        remDaylightTxt = view.findViewById(R.id.remaining_time_lbl)
        sunStartTimeTxt = view.findViewById(R.id.sun_start_time)
        sunEndTimeTxt = view.findViewById(R.id.sun_end_time)

        moonRiseTimeTxt = view.findViewById(R.id.moon_rise_time)
        moonSetTimeTxt = view.findViewById(R.id.moon_set_time)

        sunPosition = view.findViewById(R.id.sun_position)
        moonPosition = view.findViewById(R.id.moon_position)

        dateTxt = view.findViewById(R.id.date)
        nextDateBtn = view.findViewById(R.id.next_date)
        prevDateBtn = view.findViewById(R.id.prev_date)

        astroChart = AstroChart(view.findViewById(R.id.moonChart))

        prevDateBtn.setOnClickListener {
            displayDate = displayDate.minusDays(1)
            updateUI()
        }

        nextDateBtn.setOnClickListener {
            displayDate = displayDate.plusDays(1)
            updateUI()
        }

        gps = GPS(requireContext())

        sunTimesMode = prefs.astronomy.sunTimesMode

        return view
    }

    override fun onResume() {
        super.onResume()
        displayDate = LocalDate.now()
        gps.start(this::onLocationUpdate)
        handler = Handler(Looper.getMainLooper())
        timer = fixedRateTimer(period = 1000 * 60) {
            handler.post { updateUI() }
        }
        updateUI()
    }

    override fun onPause() {
        super.onPause()
        gps.stop(this::onLocationUpdate)
        timer.cancel()
    }

    private fun onLocationUpdate(): Boolean {
        updateUI()
        return false
    }

    private fun updateUI() {
        dateTxt.text = getDateString(displayDate)
        updateSunUI()
        updateMoonUI()
    }

    private fun getDateString(date: LocalDate): String {
        val now = LocalDate.now()
        return when {
            date == now -> {
                getString(R.string.today)
            }
            date == now.plusDays(1) -> {
                getString(R.string.tomorrow)
            }
            date == now.minusDays(1) -> {
                getString(R.string.yesterday)
            }
            date.year == now.year -> {
                date.format(DateTimeFormatter.ofPattern(getString(R.string.this_year_format)))
            }
            else -> {
                date.format(DateTimeFormatter.ofPattern(getString(R.string.other_year_format)))
            }
        }
    }

    private fun updateMoonUI() {
        if (context == null) {
            return
        }

        val moonPhase = astronomyService.getCurrentMoonPhase()
        val today = astronomyService.getMoonTimes(gps.location, displayDate)

        moonRiseTimeTxt.text = today.up?.toDisplayFormat(requireContext()) ?: "-"
        moonSetTimeTxt.text = today.down?.toDisplayFormat(requireContext()) ?: "-"
        moonPosition.setImageResource(getMoonImage(moonPhase.phase))
        moonTxt.text = "${moonPhase.phase.longName} (${moonPhase.illumination.roundToInt()}%)"

        updateMoonPosition()

        val altitudes = astronomyService.getTodayMoonAltitudes(gps.location)
        val sunAltitudes = astronomyService.getTodaySunAltitudes(gps.location)

        val current = altitudes.minBy { Duration.between(LocalDateTime.now(), it.time).abs() }
        val currentIdx = altitudes.indexOf(current)

        astroChart.plot(listOf(
            AstroChart.AstroChartDataset(altitudes, resources.getColor(R.color.white, null)),
            AstroChart.AstroChartDataset(sunAltitudes, resources.getColor(R.color.colorPrimary, null))
        ))

        val point = astroChart.getPoint(1, currentIdx)
        moonPosition.x = point.first - moonPosition.width / 2f
        moonPosition.y = point.second - moonPosition.height / 2f

        val point2 = astroChart.getPoint(2, currentIdx)
        sunPosition.x = point2.first - sunPosition.width / 2f
        sunPosition.y = point2.second - sunPosition.height / 2f
    }

    private fun updateSunUI() {
        if (context == null) {
            return
        }
        val currentTime = LocalDateTime.now()

        val displayDateTimes = astronomyService.getSunTimes(gps.location, sunTimesMode, displayDate)
        displaySunTimes(displayDateTimes, sunStartTimeTxt, sunEndTimeTxt)

        displayTimeUntilNextSunEvent()
        updateSunPosition(currentTime)
    }

    private fun updateSunPosition(currentTime: LocalDateTime) {
        val today = astronomyService.getTodaySunTimes(gps.location, sunTimesMode)
        val tomorrow = astronomyService.getTomorrowSunTimes(gps.location, sunTimesMode)
        val yesterday = astronomyService.getYesterdaySunTimes(gps.location, sunTimesMode)

        val percent = when {
            today.down != null && currentTime.isAfter(today.down) && tomorrow.up != null -> {
                getPercentOfDuration(today.down, tomorrow.up, currentTime)
            }
            today.up != null && currentTime.isAfter(today.up) && today.down != null -> {
                getPercentOfDuration(today.up, today.down, currentTime)
            }
            yesterday.down != null && today.up != null -> {
                getPercentOfDuration(yesterday.down, today.up, currentTime)
            }
            else -> 0f
        }

        val angle = if (today.up != null && today.down != null && currentTime.isAfter(today.up) && currentTime.isBefore(today.down)) {
            // Day time
            180 * percent
        } else {
            // Night time
            180 + 180 * percent
        }

//        sunIconClock.display(angle, 0.98f)
    }

    private fun updateMoonPosition() {
        val isUp = astronomyService.isMoonUp(gps.location)

        val percent = if (isUp) {
            val lastUp = astronomyService.getLastMoonRise(gps.location)
            val nextDown = astronomyService.getNextMoonSet(gps.location)
            if (lastUp != null && nextDown != null) {
                getPercentOfDuration(lastUp, nextDown, LocalDateTime.now())
            } else {
                0f
            }
        } else {
            val lastDown = astronomyService.getLastMoonSet(gps.location)
            val nextUp = astronomyService.getNextMoonRise(gps.location)
            if (lastDown != null && nextUp != null) {
                getPercentOfDuration(lastDown, nextUp, LocalDateTime.now())
            } else {
                0f
            }
        }

        val angle = if (isUp) {
            // Day time
            180 * percent
        } else {
            // Night time
            180 + 180 * percent
        }

//        moonIconClock.display(angle, 0.5f)
    }

    private fun getMoonImage(phase: MoonTruePhase): Int {
        return when (phase) {
            MoonTruePhase.FirstQuarter -> R.drawable.moon_first_quarter
            MoonTruePhase.Full -> R.drawable.moon_full
            MoonTruePhase.ThirdQuarter -> R.drawable.moon_last_quarter
            MoonTruePhase.New -> R.drawable.moon_new
            MoonTruePhase.WaningCrescent -> R.drawable.moon_waning_crescent
            MoonTruePhase.WaningGibbous -> R.drawable.moon_waning_gibbous
            MoonTruePhase.WaxingCrescent -> R.drawable.moon_waxing_crescent
            MoonTruePhase.WaxingGibbous -> R.drawable.moon_waxing_gibbous
        }
    }

    private fun displayTimeUntilNextSunEvent() {
        val currentTime = LocalDateTime.now()
        val today = astronomyService.getTodaySunTimes(gps.location, sunTimesMode)
        val tomorrow = astronomyService.getTomorrowSunTimes(gps.location, sunTimesMode)
        when {
            today.down != null && currentTime > today.down && tomorrow.up != null -> {
                // Time until tomorrow's sunrise
                sunTxt.text = Duration.between(currentTime, tomorrow.up).formatHM()
                remDaylightTxt.text = getString(R.string.until_sunrise_label)
            }
            today.up != null && currentTime < today.up -> {
                // Time until today's sunrise
                sunTxt.text = Duration.between(currentTime, today.up).formatHM()
                remDaylightTxt.text = getString(R.string.until_sunrise_label)
            }
            today.down != null -> {
                sunTxt.text = Duration.between(currentTime, today.down).formatHM()
                remDaylightTxt.text = getString(R.string.until_sunset_label)
            }
            today.isAlwaysUp -> {
                sunTxt.text = getString(R.string.sun_up_no_set)
                remDaylightTxt.text = getString(R.string.sun_does_not_set)
            }
            today.isAlwaysDown -> {
                sunTxt.text = getString(R.string.sun_down_no_set)
                remDaylightTxt.text = getString(R.string.sun_does_not_rise)
            }
        }
    }


    private fun displaySunTimes(sunTimes: SunTimes, upTxt: TextView, downTxt: TextView) {
        upTxt.text = sunTimes.up?.toDisplayFormat(requireContext()) ?: "-"
        downTxt.text = sunTimes.down?.toDisplayFormat(requireContext()) ?: "-"
    }

}
