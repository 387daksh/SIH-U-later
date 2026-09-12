package org.sih.offlinespeech

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.io.File

private data class TeamAlert(val key: String, val category: String, val text: String, val fix: TeamFix?)

class MapPageController(private val context: Activity, private val onSharedFix: (TeamFix?) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private val manager = context.getSystemService(LocationManager::class.java)
    private val preferences = context.getSharedPreferences("sutra", Activity.MODE_PRIVATE)
    private var sharing = preferences.getBoolean("share_team_location", false)
    private var resumed = false
    private var requesting = false
    private var local: TeamFix? = null
    private var peers = emptyList<TeamPeer>()
    private val alerts = ArrayList<TeamAlert>()
    private var target: String? = null
    private var anchor: String? = null
    private var online = false
    private var streetMap: MapView? = null
    private var attribution: View? = null
    private lateinit var field: TeamMapView
    private lateinit var mapFrame: FrameLayout
    private lateinit var status: TextView
    private lateinit var summary: TextView
    private lateinit var measurement: TextView
    private lateinit var list: LinearLayout
    private lateinit var shareButton: Button
    private lateinit var fieldButton: Button
    private lateinit var streetButton: Button
    private val tick = object : Runnable {
        override fun run() { if (resumed) { syncSharing(); refresh(); handler.postDelayed(this, 5_000) } }
    }
    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) { acceptLocation(location) }
        override fun onProviderDisabled(provider: String) { refresh() }
        override fun onProviderEnabled(provider: String) { refresh() }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }

    fun createPageView(): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(SutraPalette.ink)
        }
        mapFrame = FrameLayout(context)
        field = TeamMapView(context) { key -> target = key; anchor = null; refresh() }
        mapFrame.addView(field, FrameLayout.LayoutParams(-1, -1))
        val toolbar = LinearLayout(context).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        fieldButton = button("Field") {
            online = false; streetMap?.setUseDataConnection(false); streetMap?.visibility = View.GONE
            attribution?.visibility = View.GONE; field.visibility = View.VISIBLE; refresh()
        }
        streetButton = button("Streets ↗") { showStreetMap() }
        toolbar.addView(fieldButton, LinearLayout.LayoutParams(dp(78), dp(48)))
        toolbar.addView(streetButton, LinearLayout.LayoutParams(dp(100), dp(48)).apply { marginStart = dp(6) })
        toolbar.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        toolbar.addView(button("•••") { showMapOptions() }.apply { contentDescription = "Map sharing and accuracy settings" },
            LinearLayout.LayoutParams(dp(48), dp(48)))
        mapFrame.addView(toolbar, FrameLayout.LayoutParams(-1, dp(48)).apply {
            topMargin = dp(12); marginStart = dp(16); marginEnd = dp(16)
        })
        val zoom = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        zoom.addView(button("+") { if (online) streetMap?.controller?.zoomIn() else field.zoom(2.0) }
            .apply { contentDescription = "Zoom in" }, LinearLayout.LayoutParams(dp(48), dp(48)))
        zoom.addView(button("−") { if (online) streetMap?.controller?.zoomOut() else field.zoom(.5) }
            .apply { contentDescription = "Zoom out" }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { topMargin = dp(6) })
        zoom.addView(button("◎") { field.fit(); local?.let { streetMap?.controller?.setCenter(GeoPoint(it.lat, it.lon)) } }
            .apply { contentDescription = "Recenter map" }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { topMargin = dp(6) })
        mapFrame.addView(zoom, FrameLayout.LayoutParams(-2, -2, android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL).apply {
            marginEnd = dp(12)
        })
        status = label("Waiting for GPS", 11f, Color.rgb(221, 237, 229)).apply {
            setPadding(dp(12), dp(8), dp(12), dp(8)); background = background(Color.rgb(34, 61, 73))
            setOnClickListener { showMapOptions() }; contentDescription = "Location quality. Tap for settings"
            minHeight = dp(48); gravity = android.view.Gravity.CENTER_VERTICAL
        }
        mapFrame.addView(status, FrameLayout.LayoutParams(-2, -2, android.view.Gravity.BOTTOM or android.view.Gravity.START).apply {
            marginStart = dp(16); bottomMargin = dp(12)
        })
        root.addView(mapFrame, LinearLayout.LayoutParams(-1, 0, 1f))
        val sheet = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(10))
            background = GradientDrawable().apply {
                setColor(SutraPalette.paper)
                cornerRadii = floatArrayOf(dp(26).toFloat(), dp(26).toFloat(), dp(26).toFloat(), dp(26).toFloat(), 0f, 0f, 0f, 0f)
            }
        }
        val heading = LinearLayout(context).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        heading.addView(label("Your team", 20f, SutraPalette.ink, true), LinearLayout.LayoutParams(0, -2, 1f))
        heading.addView(button("Compare ↗") { chooseMeasurement() }.apply {
            setTextColor(SutraPalette.teal); background = background(SutraPalette.mint)
        }, LinearLayout.LayoutParams(dp(110), dp(48)))
        sheet.addView(heading)
        summary = label("No teammates nearby yet", 12f)
        sheet.addView(summary)
        measurement = label("", 15f, SutraPalette.ink, true)
        sheet.addView(measurement)
        list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val rows = ScrollView(context).apply { addView(list); isFillViewport = false }
        sheet.addView(rows, LinearLayout.LayoutParams(-1, 0, 1f))
        shareButton = button("Share my location") { showMapOptions() }.apply {
            setTextColor(Color.WHITE); background = background(SutraPalette.teal)
        }
        sheet.addView(shareButton, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })
        root.addView(sheet, LinearLayout.LayoutParams(-1, dp(240)))
        refresh()
        return root
    }

    private fun showMapOptions() {
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), dp(12))
        }
        val toggle = Switch(context).apply {
            text = "Share while app is open"; textSize = 14f; minHeight = dp(56)
            isChecked = sharing; setTextColor(SutraPalette.ink)
            setOnCheckedChangeListener { _, checked ->
                sharing = checked
                preferences.edit().putBoolean("share_team_location", checked).apply()
                syncSharing(); refresh()
            }
        }
        panel.addView(toggle)
        panel.addView(label("Share with nearby SUTRA teammates, including through relays. Sharing pauses when you leave the app.", 13f))
        panel.addView(label("The field view works offline. North is always up. Halos show GPS accuracy; dashed lines measure straight-line distance, not a route. Streets loads online map tiles.", 13f))
        panel.addView(label("Accuracy ranges combine the radii reported by both phones. They are not guaranteed bounds. Indoors, move near a window or outdoors for a better fix.", 12f))
        AlertDialog.Builder(context).setTitle("Map & location").setView(panel)
            .setNeutralButton("Phone location") { _, _ -> context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
            .setPositiveButton("Done") { _, _ ->
                if (sharing && !hasPermission()) context.requestPermissions(arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 51)
                else startLocationUpdates()
                syncSharing(); refresh()
            }.show()
    }

    private fun hasPermission() = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun startLocationUpdates() {
        if (!resumed || requesting) return
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        providers.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .sortedBy { it.elapsedRealtimeNanos }.forEach(::acceptLocation)
        providers.forEach { provider ->
            runCatching { manager.requestLocationUpdates(provider, 5_000L, 0f, listener, Looper.getMainLooper()) }
                .onSuccess { requesting = true }
        }
        refresh()
    }

    private fun acceptLocation(location: Location) {
        if (!location.hasAccuracy() || !TeamFix.valid(location.latitude, location.longitude, location.accuracy.toDouble())) return
        val now = SystemClock.elapsedRealtime()
        val sampled = location.elapsedRealtimeNanos / 1_000_000
        if (sampled > now || now - sampled > 120_000) return
        val next = TeamFix(location.latitude, location.longitude, location.accuracy.toDouble(), sampled)
        val previous = local
        if (previous != null && (next.sampledMs < previous.sampledMs ||
            previous.fresh(now) && next.accuracy > previous.accuracy * 2 && next.sampledMs - previous.sampledMs < 15_000)) return
        local = next
        syncSharing(); refresh()
    }

    fun syncSharing() = onSharedFix(local?.takeIf { sharing && resumed && hasPermission() && it.fresh(SystemClock.elapsedRealtime()) })
    fun onResume() {
        resumed = true; streetMap?.onResume(); streetMap?.setUseDataConnection(online); startLocationUpdates(); syncSharing()
        handler.removeCallbacks(tick); handler.post(tick)
    }
    fun onPause() {
        resumed = false; handler.removeCallbacks(tick)
        runCatching { manager.removeUpdates(listener) }; requesting = false
        onSharedFix(null); streetMap?.onPause(); streetMap?.setUseDataConnection(false)
    }
    fun onDestroy() { onPause(); streetMap?.onDetach(); streetMap = null }
    fun getCurrentCoordinates(): Pair<Double, Double>? = local?.takeIf {
        sharing && resumed && hasPermission() && it.fresh(SystemClock.elapsedRealtime())
    }?.let { it.lat to it.lon }

    fun updateTeam(value: List<TeamPeer>) { peers = value; refresh() }

    fun addAlertPin(category: String, text: String, lat: Double?, lon: Double?, sourceId: Long) {
        // Legacy alerts have no fix age/accuracy. Never invent a location for them.
        val peerFix = peers.firstOrNull { it.sourceId == sourceId }?.beacon?.fix
        val fix = if (lat == local?.lat && lon == local?.lon) local else peerFix
        val verified = fix?.takeIf { lat == it.lat && lon == it.lon && it.fresh(SystemClock.elapsedRealtime()) }
        alerts.add(0, TeamAlert("alert-${System.nanoTime()}", category, text.take(300), verified))
        while (alerts.size > 20) alerts.removeAt(alerts.lastIndex)
        refresh()
    }

    private fun points(now: Long): List<TeamMapPoint> = buildList {
        local?.let { add(TeamMapPoint("self", "You", it, SutraPalette.teal, !it.fresh(now))) }
        peers.forEachIndexed { index, peer -> peer.beacon.fix?.let {
            add(TeamMapPoint(peer.sourceId.toString(), "${index + 1} · ${peer.beacon.name}", it,
                Color.rgb(64, 129, 199), !it.fresh(now) || !peer.recent(now)))
        } }
        alerts.forEach { alert -> alert.fix?.let {
            add(TeamMapPoint(alert.key, "SOS · ${alert.category}", it, Color.rgb(190, 61, 47), !it.fresh(now)))
        } }
    }

    private fun refresh() {
        if (!::list.isInitialized) return
        val now = SystemClock.elapsedRealtime()
        peers = peers.filter { now - it.receivedMs <= 600_000 }
        val count = peers.count { it.recent(now) }
        summary.text = if (peers.isEmpty()) "Waiting for your first teammate" else
            "$count nearby · ${peers.count { it.beacon.fix?.fresh(now) == true }} fresh locations"
        shareButton.text = if (sharing) "●  Location sharing on  ·  Manage" else "Share my location"
        fieldButton.isSelected = !online; streetButton.isSelected = online
        fieldButton.setTextColor(if (!online) SutraPalette.ink else Color.WHITE)
        streetButton.setTextColor(if (online) SutraPalette.ink else Color.WHITE)
        fieldButton.background = background(if (!online) SutraPalette.mint else Color.rgb(34, 61, 73))
        streetButton.background = background(if (online) SutraPalette.mint else Color.rgb(34, 61, 73))
        val enabled = if (android.os.Build.VERSION.SDK_INT >= 28) manager.isLocationEnabled else
            runCatching { manager.isProviderEnabled(LocationManager.GPS_PROVIDER) || manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false)
        status.text = when {
            !hasPermission() -> "◎  Location permission needed"
            !enabled -> "◎  Location is off"
            local == null -> "◎  Finding your location…"
            else -> "◎  ±${MapDistanceCalculator.formatDistance(local!!.accuracy)}  ·  ${local!!.ageMs(now) / 1000}s ago"
        }
        val items = points(now)
        field.update(local, items, target, anchor)
        val from = items.firstOrNull { it.key == (anchor ?: "self") }
        val to = items.firstOrNull { it.key == target }
        val range = teamRange(from?.fix, to?.fix, now)
        measurement.visibility = if (range == null) View.GONE else View.VISIBLE
        measurement.text = if (range != null) "${range.label}  ·  ${from!!.label} → ${to!!.label}\n${range.detail}" else ""
        measurement.textSize = 13f
        list.removeAllViews()
        if (peers.isEmpty()) {
            list.addView(label("A shared sense of direction.", 16f, SutraPalette.ink, true))
            list.addView(label("Open SUTRA on another phone and share location to see each other here.", 13f))
        }
        peers.forEachIndexed { index, peer ->
            val fix = peer.beacon.fix
            val distance = teamRange(local, fix, now)
            val age = (now - peer.receivedMs).coerceAtLeast(0) / 1000
            val hops = peer.beacon.hops + 1
            val reach = if (hops == 1) "Direct receipt" else "Received over $hops hops"
            val row = LinearLayout(context).apply {
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = background(if (target == peer.sourceId.toString()) SutraPalette.mint else Color.WHITE)
            }
            row.addView(label("${index + 1}", 14f, SutraPalette.teal, true).apply {
                gravity = android.view.Gravity.CENTER; background = background(SutraPalette.mint)
            }, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(10) })
            val names = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            names.addView(label(peer.beacon.name, 14f, SutraPalette.ink, true))
            names.addView(label("$reach · ${age}s${if (!peer.recent(now)) " · stale" else ""}", 11f))
            row.addView(names, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(label(distance?.label ?: if (fix == null) "No location" else "No local fix", 14f, SutraPalette.teal, true))
            row.contentDescription = "${peer.beacon.name}, ${java.lang.Long.toUnsignedString(peer.sourceId, 16).takeLast(4)}. $reach, heard ${age}s ago. " +
                (distance?.let { "${it.label}, ${it.detail}" } ?: "Distance unavailable")
            row.setOnClickListener { target = peer.sourceId.toString(); anchor = null; refresh() }
            list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        alerts.forEach { alert -> list.addView(label("SOS · ${alert.category}\n${alert.text}\n" +
            if (alert.fix == null) "Location unverified — ask sender for a landmark" else "Sender location available at receipt", 13f, Color.rgb(179, 38, 30))) }
        if (online) renderStreet(items)
    }

    private fun chooseMeasurement() {
        val options = points(SystemClock.elapsedRealtime()).filter { !it.stale }
        if (options.size < 2) {
            AlertDialog.Builder(context).setMessage("Two fresh locations are needed. Enable sharing on both phones and wait for a GPS fix.").setPositiveButton("OK", null).show()
            return
        }
        AlertDialog.Builder(context).setTitle("Measure from").setItems(options.map { it.label }.toTypedArray()) { _, a ->
            val destinations = options.filter { it.key != options[a].key }
            AlertDialog.Builder(context).setTitle("Measure to").setItems(destinations.map { it.label }.toTypedArray()) { _, b ->
                anchor = options[a].key; target = destinations[b].key; refresh()
            }.show()
        }.show()
    }

    private fun showStreetMap() {
        if (streetMap == null) {
            Configuration.getInstance().userAgentValue = "org.sih.offlinespeech/0.12-team-map"
            Configuration.getInstance().osmdroidTileCache = File(context.cacheDir, "osmdroid_tiles")
            streetMap = MapView(context).apply {
                setTileSource(TileSourceFactory.MAPNIK); setMultiTouchControls(true)
                controller.setZoom(if (local == null) 4.0 else 17.0)
                controller.setCenter(local?.let { GeoPoint(it.lat, it.lon) } ?: GeoPoint(20.5937, 78.9629))
            }
            mapFrame.addView(streetMap, 0, FrameLayout.LayoutParams(-1, -1))
            attribution = label("© OpenStreetMap contributors · online tiles", 10f).apply {
                setBackgroundColor(Color.WHITE)
                setOnClickListener { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.openstreetmap.org/copyright"))) }
            }
            mapFrame.addView(attribution, FrameLayout.LayoutParams(-2, -2, android.view.Gravity.BOTTOM or android.view.Gravity.END))
        }
        online = true; field.visibility = View.GONE; streetMap?.visibility = View.VISIBLE; attribution?.visibility = View.VISIBLE
        streetMap?.setUseDataConnection(true); streetMap?.onResume(); refresh()
    }

    private fun renderStreet(items: List<TeamMapPoint>) {
        val map = streetMap ?: return
        map.overlays.clear()
        items.forEach { point -> map.overlays.add(Marker(map).apply {
            position = GeoPoint(point.fix.lat, point.fix.lon); title = point.label
            snippet = "±${MapDistanceCalculator.formatDistance(point.fix.accuracy)}${if (point.stale) " · stale fix" else ""}"
            setOnMarkerClickListener { marker, _ -> target = point.key; marker.showInfoWindow(); true }
        }) }
        map.invalidate()
    }

    private fun label(value: String, size: Float, color: Int = SutraPalette.muted, bold: Boolean = false) = TextView(context).apply {
        text = value; textSize = size; setTextColor(color); setPadding(0, dp(5), 0, dp(5))
        if (bold) setTypeface(null, Typeface.BOLD)
    }
    private fun button(value: String, action: () -> Unit) = Button(context).apply {
        text = value; textSize = 12f; isAllCaps = false; minHeight = dp(48)
        minWidth = 0; minimumWidth = 0; setPadding(dp(10), 0, dp(10), 0)
        setTextColor(Color.WHITE); setTypeface(null, Typeface.BOLD)
        background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(Color.argb(35, 255, 255, 255)),
            background(Color.rgb(34, 61, 73)), null)
        stateListAnimator = null
        setOnClickListener { action() }
    }
    private fun background(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(14).toFloat() }
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
}
