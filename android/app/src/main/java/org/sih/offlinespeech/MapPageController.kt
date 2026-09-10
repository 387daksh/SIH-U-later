package org.sih.offlinespeech

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class PeerMapNode(
    val id: String,
    val name: String,
    val transport: String,
    var lat: Double,
    var lon: Double,
    var isConnected: Boolean = true,
    var lastSeenMs: Long = System.currentTimeMillis(),
)

data class AlertMapNode(
    val id: String,
    val category: String,
    val text: String,
    val lat: Double,
    val lon: Double,
    val timestampMs: Long = System.currentTimeMillis(),
    val sourceId: Long = 0L,
)

class MapPageController(
    private val context: Context,
    private val onPinSelected: ((String) -> Unit)? = null,
) {
    private val ink = Color.rgb(15, 29, 51)
    private val mutedInk = Color.rgb(91, 103, 121)
    private val blue = Color.rgb(40, 81, 127)
    private val accentRed = Color.rgb(179, 38, 30)
    private val surface = Color.WHITE
    private val canvas = Color.rgb(241, 242, 244)
    private val divider = Color.rgb(226, 230, 235)

    private val peers = LinkedHashMap<String, PeerMapNode>()
    private val alerts = ArrayList<AlertMapNode>()

    private var mapView: MapView? = null
    private var myLocation: GeoPoint = GeoPoint(20.5937, 78.9629) // Default India center
    private var hasGpsFix = false
    private var locationManager: LocationManager? = null

    private lateinit var peerCountBadge: TextView
    private lateinit var alertCountBadge: TextView
    private lateinit var markerListContainer: LinearLayout
    private lateinit var myLocationText: TextView

    private val bluePinDrawable: Drawable by lazy { createPinDrawable(Color.rgb(33, 150, 243), "📱") }
    private val redPinDrawable: Drawable by lazy { createPinDrawable(accentRed, "⚠️") }
    private val myPinDrawable: Drawable by lazy { createPinDrawable(Color.rgb(11, 97, 86), "📍") }

    private var myMarker: Marker? = null

    // ── Measurement state ──────────────────────────────────────────────────────
    private var measureMode = false
    private var measureAnchorPoint: GeoPoint? = null
    private var measureAnchorLabel: String = ""
    private var measureLine: Polyline? = null
    private lateinit var measureBanner: TextView
    private lateinit var measureBtn: Button

    fun createPageView(): View {
        Configuration.getInstance().userAgentValue = "SUTRA-Offline-Speech/1.0"
        Configuration.getInstance().osmdroidTileCache = File(context.cacheDir, "osmdroid_tiles")

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(canvas)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        // Subheader with coordinates and badges
        val subheader = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setBackgroundColor(surface)

            val statusCol = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

                addView(TextView(context).apply {
                    text = "OFFLINE MESH TOPOLOGY"
                    textSize = 10f
                    letterSpacing = 0.15f
                    setTextColor(mutedInk)
                    setTypeface(null, Typeface.BOLD)
                })

                myLocationText = TextView(context).apply {
                    text = "GPS: Waiting for fix..."
                    textSize = 12f
                    setTextColor(ink)
                }
                addView(myLocationText)
            }
            addView(statusCol)

            peerCountBadge = TextView(context).apply {
                text = "0 Peers"
                textSize = 11f
                setTypeface(null, Typeface.BOLD)
                setTextColor(blue)
                background = roundedBackground(Color.rgb(220, 235, 252), 12)
                setPadding(dp(10), dp(5), dp(10), dp(5))
            }
            addView(peerCountBadge)

            alertCountBadge = TextView(context).apply {
                text = "0 Alerts"
                textSize = 11f
                setTypeface(null, Typeface.BOLD)
                setTextColor(accentRed)
                background = roundedBackground(Color.rgb(255, 235, 235), 12)
                setPadding(dp(10), dp(5), dp(10), dp(5))
            }
            addView(alertCountBadge, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(8)
            })
        }
        root.addView(subheader)

        // Map container with floating action controls
        val mapContainer = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(280))
        }

        mapView = MapView(context).apply {
            setTileSource(TileSourceFactory.DEFAULT_TILE_SOURCE)
            setMultiTouchControls(true)
            isTilesScaledToDpi = true
            setUseDataConnection(false) // Pure offline operation - no data leaks or battery drain
            controller.setZoom(7.0)
            controller.setCenter(myLocation)
        }
        mapContainer.addView(mapView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Map overlay controls (Zoom In, Zoom Out, Center GPS, Measure)
        val controls = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END

            val btnGps = createFloatingButton("🎯") { centerOnLocation() }
            val btnZoomIn = createFloatingButton("+") { mapView?.controller?.zoomIn() }
            val btnZoomOut = createFloatingButton("-") { mapView?.controller?.zoomOut() }
            measureBtn = createFloatingButton("📏") { toggleMeasureMode() }

            addView(btnGps)
            addView(btnZoomIn, LinearLayout.LayoutParams(dp(38), dp(38)).apply { topMargin = dp(6) })
            addView(btnZoomOut, LinearLayout.LayoutParams(dp(38), dp(38)).apply { topMargin = dp(6) })
            addView(measureBtn, LinearLayout.LayoutParams(dp(38), dp(38)).apply { topMargin = dp(6) })
        }
        mapContainer.addView(controls, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = dp(12)
            marginEnd = dp(12)
        })

        root.addView(mapContainer)

        // Measurement banner — hidden until a measurement is active
        measureBanner = TextView(context).apply {
            visibility = View.GONE
            setTextColor(Color.WHITE)
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = roundedBackground(Color.rgb(15, 76, 52), 0)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        root.addView(measureBanner)

        // Lower section: scrollable list of connected phones (blue) and alert markers (red)
        val listScroll = ScrollView(context).apply {
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            setBackgroundColor(canvas)
        }

        markerListContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(16))
        }
        listScroll.addView(markerListContainer)
        root.addView(listScroll)

        startLocationUpdates()
        refreshPins()

        return root
    }

    private fun createFloatingButton(label: String, onClick: () -> Unit) = Button(context).apply {
        text = label
        textSize = 16f
        setTextColor(ink)
        background = roundedBackground(surface, 19).apply { setStroke(dp(1), divider) }
        elevation = dp(4).toFloat()
        layoutParams = LinearLayout.LayoutParams(dp(38), dp(38))
        setOnClickListener { onClick() }
    }

    private var locationListener: LocationListener? = null

    fun startLocationUpdates() {
        runCatching {
            locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            val mgr = locationManager ?: return

            val lastGps = runCatching { mgr.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull()
            val lastNet = runCatching { mgr.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }.getOrNull()
            val lastPass = runCatching { mgr.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER) }.getOrNull()
            val best = listOfNotNull(lastGps, lastNet, lastPass).maxByOrNull { it.time }
            if (best != null) {
                updateCurrentLocation(best.latitude, best.longitude)
                mapView?.controller?.setCenter(myLocation)
                mapView?.controller?.setZoom(16.0)
            }

            if (locationListener == null) {
                locationListener = object : LocationListener {
                    override fun onLocationChanged(loc: Location) {
                        val firstFix = !hasGpsFix
                        updateCurrentLocation(loc.latitude, loc.longitude)
                        if (firstFix) {
                            mapView?.controller?.setZoom(16.0)
                            mapView?.controller?.animateTo(GeoPoint(loc.latitude, loc.longitude))
                        }
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
                    override fun onProviderEnabled(provider: String) = Unit
                    override fun onProviderDisabled(provider: String) = Unit
                }
            }

            locationListener?.let { listener ->
                if (mgr.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    runCatching { mgr.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2_000L, 2f, listener) }
                }
                if (mgr.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    runCatching { mgr.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2_000L, 2f, listener) }
                }
            }
        }
    }

    fun onResume() {
        mapView?.onResume()
        startLocationUpdates()
    }

    fun onPause() {
        mapView?.onPause()
    }

    fun onDestroy() {
        runCatching {
            locationListener?.let { locationManager?.removeUpdates(it) }
            locationListener = null
        }
        mapView?.onDetach()
        mapView = null
    }

    fun updateCurrentLocation(lat: Double, lon: Double) {
        myLocation = GeoPoint(lat, lon)
        hasGpsFix = true
        myLocationText.text = "GPS: ${"%.4f".format(lat)}, ${"%.4f".format(lon)}"

        val map = mapView ?: return
        if (myMarker == null) {
            myMarker = Marker(map).apply {
                icon = myPinDrawable
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                title = "My Device"
                snippet = "Local SUTRA Terminal"
            }
            map.overlays.add(myMarker)
        }
        myMarker?.position = myLocation
        map.invalidate()
    }

    fun getCurrentCoordinates(): Pair<Double, Double>? =
        if (hasGpsFix) Pair(myLocation.latitude, myLocation.longitude) else null

    fun updatePeer(id: String, name: String, transport: String, isConnected: Boolean, lat: Double? = null, lon: Double? = null) {
        val existing = peers[id]
        if (existing != null) {
            existing.isConnected = isConnected
            existing.lastSeenMs = System.currentTimeMillis()
            if (lat != null && lon != null) {
                existing.lat = lat
                existing.lon = lon
            }
        } else {
            val peerLat = lat ?: run {
                val offsetIndex = peers.size + 1
                myLocation.latitude + (0.0003 * (if (offsetIndex % 2 == 0) 1 else -1) * offsetIndex)
            }
            val peerLon = lon ?: run {
                val offsetIndex = peers.size + 1
                myLocation.longitude + (0.0003 * (if (offsetIndex % 3 == 0) 1 else -1) * offsetIndex)
            }
            peers[id] = PeerMapNode(id, name, transport, peerLat, peerLon, isConnected)
        }
        refreshPins()
    }

    fun clearConnectedPeers() {
        peers.values.forEach { it.isConnected = false }
        refreshPins()
    }

    fun addAlertPin(category: String, text: String, lat: Double?, lon: Double?, sourceId: Long) {
        val alertLat = lat ?: (myLocation.latitude + (0.0003 * (if (alerts.size % 2 == 0) -1 else 1)))
        val alertLon = lon ?: (myLocation.longitude + (0.0003 * (if (alerts.size % 2 == 0) 1 else -1)))

        val node = AlertMapNode(
            id = "alert-${System.currentTimeMillis()}",
            category = category,
            text = text,
            lat = alertLat,
            lon = alertLon,
            timestampMs = System.currentTimeMillis(),
            sourceId = sourceId,
        )
        alerts.add(0, node) // Most recent first
        refreshPins()

        // Auto-center map on new alert
        mapView?.controller?.animateTo(GeoPoint(alertLat, alertLon))
    }

    private fun refreshPins() {
        val map = mapView ?: return

        // Clear existing markers and measurement line except the "my location" marker
        val myCurrentMarker = myMarker
        map.overlays.clear()
        if (myCurrentMarker != null) {
            map.overlays.add(myCurrentMarker)
        }

        // Re-draw active measurement line so it stays visible after a refresh
        measureLine?.let { map.overlays.add(it) }

        // Add blue pins for peers (with click-to-measure support)
        peers.values.filter { it.isConnected }.forEach { peer ->
            val marker = Marker(map).apply {
                position = GeoPoint(peer.lat, peer.lon)
                icon = bluePinDrawable
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                title = "📱 ${peer.name}"
                val dist = MapDistanceCalculator.measure(
                    myLocation.latitude, myLocation.longitude, "My Device",
                    peer.lat, peer.lon, peer.name
                )
                snippet = "${dist.formattedDistance} · ${dist.compassDirection} · via ${peer.transport}"
                setOnMarkerClickListener { m, _ ->
                    handlePinTap(GeoPoint(peer.lat, peer.lon), peer.name)
                    m.showInfoWindow()
                    true
                }
            }
            map.overlays.add(marker)
        }

        // Add red pins for alerts (with click-to-measure support)
        alerts.forEach { alert ->
            val marker = Marker(map).apply {
                position = GeoPoint(alert.lat, alert.lon)
                icon = redPinDrawable
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                title = "🚨 EMERGENCY: ${alert.category}"
                val dist = MapDistanceCalculator.measure(
                    myLocation.latitude, myLocation.longitude, "My Device",
                    alert.lat, alert.lon, alert.category
                )
                snippet = "${dist.formattedDistance} away · ${dist.compassDirection}  |  ${alert.text.take(60)}"
                setOnMarkerClickListener { m, _ ->
                    handlePinTap(GeoPoint(alert.lat, alert.lon), alert.category)
                    m.showInfoWindow()
                    true
                }
            }
            map.overlays.add(marker)
        }

        map.invalidate()

        // Update counts
        val activePeers = peers.values.count { it.isConnected }
        peerCountBadge.text = "$activePeers Peer${if (activePeers == 1) "" else "s"}"
        alertCountBadge.text = "${alerts.size} Alert${if (alerts.size == 1) "" else "s"}"

        // Rebuild lower list
        rebuildMarkerList()
    }

    private fun rebuildMarkerList() {
        markerListContainer.removeAllViews()

        if (peers.isEmpty() && alerts.isEmpty()) {
            markerListContainer.addView(TextView(context).apply {
                text = "No connected peers or alert signals yet.\nPeers will appear in blue when connected. Alerts appear in red."
                textSize = 13f
                setTextColor(mutedInk)
                gravity = Gravity.CENTER
                setPadding(0, dp(24), 0, dp(24))
            })
            return
        }

        // 1. Alerts Section (Red Cards)
        if (alerts.isNotEmpty()) {
            markerListContainer.addView(TextView(context).apply {
                text = "EMERGENCY ALERTS"
                textSize = 11f
                letterSpacing = 0.15f
                setTextColor(accentRed)
                setTypeface(null, Typeface.BOLD)
                setPadding(0, dp(4), 0, dp(6))
            })

            val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            alerts.take(10).forEach { alert ->
                val dist = MapDistanceCalculator.measure(
                    myLocation.latitude, myLocation.longitude, "My Device",
                    alert.lat, alert.lon, alert.category
                )
                val card = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = roundedBackground(Color.rgb(255, 245, 245), 14).apply {
                        setStroke(dp(1), Color.rgb(255, 205, 205))
                    }
                    setPadding(dp(14), dp(10), dp(14), dp(10))

                    val col = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

                        val titleRow = LinearLayout(context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            addView(TextView(context).apply {
                                text = "⚠️ ${alert.category.uppercase()}"
                                textSize = 13f
                                setTextColor(accentRed)
                                setTypeface(null, Typeface.BOLD)
                            })
                            addView(TextView(context).apply {
                                text = " · ${timeFormat.format(Date(alert.timestampMs))}"
                                textSize = 11f
                                setTextColor(mutedInk)
                            })
                        }
                        addView(titleRow)

                        addView(TextView(context).apply {
                            text = alert.text.ifBlank { "Emergency distress beacon" }
                            textSize = 13f
                            setTextColor(ink)
                            setPadding(0, dp(2), 0, 0)
                        })

                        // Distance badge
                        addView(TextView(context).apply {
                            text = "📏 ${dist.formattedDistance} · ${dist.compassDirection} of you"
                            textSize = 11f
                            setTextColor(mutedInk)
                            setPadding(0, dp(3), 0, 0)
                        })
                    }
                    addView(col)

                    val btnGroup = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER
                        val btnFocus = Button(context).apply {
                            text = "Show"
                            textSize = 11f
                            setTextColor(Color.WHITE)
                            background = roundedBackground(accentRed, 12)
                            setPadding(dp(12), dp(4), dp(12), dp(4))
                            setOnClickListener {
                                mapView?.controller?.animateTo(GeoPoint(alert.lat, alert.lon))
                                mapView?.controller?.setZoom(15.0)
                            }
                        }
                        val btnMeasure = Button(context).apply {
                            text = "📏 Measure"
                            textSize = 10f
                            setTextColor(accentRed)
                            background = roundedBackground(Color.rgb(255, 235, 235), 12)
                            setPadding(dp(8), dp(2), dp(8), dp(2))
                            setOnClickListener {
                                drawMeasureLine(
                                    myLocation, "My Device",
                                    GeoPoint(alert.lat, alert.lon), alert.category
                                )
                            }
                        }
                        addView(btnFocus)
                        addView(btnMeasure, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
                    }
                    addView(btnGroup)
                }

                markerListContainer.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(8)
                })
            }
        }

        // 2. Connected Peers Section (Blue Cards)
        val connectedPeers = peers.values.filter { it.isConnected }
        if (connectedPeers.isNotEmpty()) {
            markerListContainer.addView(TextView(context).apply {
                text = "CONNECTED PEERS"
                textSize = 11f
                letterSpacing = 0.15f
                setTextColor(blue)
                setTypeface(null, Typeface.BOLD)
                setPadding(0, dp(12), 0, dp(6))
            })

            connectedPeers.forEach { peer ->
                val dist = MapDistanceCalculator.measure(
                    myLocation.latitude, myLocation.longitude, "My Device",
                    peer.lat, peer.lon, peer.name
                )
                val card = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = roundedBackground(surface, 14).apply {
                        setStroke(dp(1), divider)
                    }
                    setPadding(dp(14), dp(10), dp(14), dp(10))

                    val col = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

                        addView(TextView(context).apply {
                            text = "📱 ${peer.name}"
                            textSize = 13f
                            setTextColor(ink)
                            setTypeface(null, Typeface.BOLD)
                        })
                        addView(TextView(context).apply {
                            text = "Connected via ${peer.transport}"
                            textSize = 11.5f
                            setTextColor(mutedInk)
                        })
                        // Distance badge
                        addView(TextView(context).apply {
                            text = "📏 ${dist.formattedDistance} · ${dist.compassDirection} of you"
                            textSize = 11f
                            setTextColor(mutedInk)
                            setPadding(0, dp(3), 0, 0)
                        })
                    }
                    addView(col)

                    val btnGroup = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER
                        val btnFocus = Button(context).apply {
                            text = "Show"
                            textSize = 11f
                            setTextColor(blue)
                            background = roundedBackground(Color.rgb(230, 240, 252), 12)
                            setPadding(dp(12), dp(4), dp(12), dp(4))
                            setOnClickListener {
                                mapView?.controller?.animateTo(GeoPoint(peer.lat, peer.lon))
                                mapView?.controller?.setZoom(15.0)
                            }
                        }
                        val btnMeasure = Button(context).apply {
                            text = "📏 Measure"
                            textSize = 10f
                            setTextColor(blue)
                            background = roundedBackground(Color.rgb(220, 235, 252), 12)
                            setPadding(dp(8), dp(2), dp(8), dp(2))
                            setOnClickListener {
                                drawMeasureLine(
                                    myLocation, "My Device",
                                    GeoPoint(peer.lat, peer.lon), peer.name
                                )
                            }
                        }
                        addView(btnFocus)
                        addView(btnMeasure, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
                    }
                    addView(btnGroup)
                }

                markerListContainer.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(8)
                })
            }
        }
    }

    private fun centerOnLocation() {
        mapView?.controller?.animateTo(myLocation)
        if (hasGpsFix) mapView?.controller?.setZoom(15.0)
    }

    // ── Measurement helpers ────────────────────────────────────────────────────

    /** Toggles point-to-point measure mode. When active the next pin tap sets the anchor. */
    private fun toggleMeasureMode() {
        if (measureMode || measureLine != null) {
            // Clear measurement
            measureMode = false
            measureAnchorPoint = null
            measureAnchorLabel = ""
            measureLine?.let { mapView?.overlays?.remove(it) }
            measureLine = null
            measureBanner.visibility = View.GONE
            measureBtn.background = roundedBackground(surface, 19).apply { setStroke(dp(1), divider) }
            measureBtn.setTextColor(ink)
            mapView?.invalidate()
        } else {
            measureMode = true
            measureAnchorPoint = myLocation
            measureAnchorLabel = "My Device"
            measureBtn.background = roundedBackground(Color.rgb(15, 76, 52), 19)
            measureBtn.setTextColor(Color.WHITE)
            measureBanner.text = "📏 Tap any pin to measure distance from My Device"
            measureBanner.visibility = View.VISIBLE
        }
    }

    /** Called when any pin is tapped in measure mode. */
    private fun handlePinTap(point: GeoPoint, label: String) {
        if (!measureMode) return
        val anchor = measureAnchorPoint ?: myLocation
        val anchorLabel = measureAnchorLabel.ifBlank { "My Device" }
        drawMeasureLine(anchor, anchorLabel, point, label)
        measureMode = false
        measureAnchorPoint = null
        measureBtn.background = roundedBackground(surface, 19).apply { setStroke(dp(1), divider) }
        measureBtn.setTextColor(ink)
    }

    /**
     * Draws a dashed measurement Polyline between [from] and [to] on the map and updates
     * the measurement banner with the computed distance and compass heading.
     */
    fun drawMeasureLine(from: GeoPoint, fromLabel: String, to: GeoPoint, toLabel: String) {
        val map = mapView ?: return
        // Remove previous line
        measureLine?.let { map.overlays.remove(it) }

        val info = MapDistanceCalculator.measure(
            from.latitude, from.longitude, fromLabel,
            to.latitude, to.longitude, toLabel
        )

        // Draw the Polyline
        val line = Polyline(map).apply {
            setPoints(listOf(from, to))
            outlinePaint.color = Color.rgb(15, 76, 52)
            outlinePaint.strokeWidth = dp(3).toFloat()
            outlinePaint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(dp(12).toFloat(), dp(6).toFloat()), 0f)
        }
        measureLine = line
        map.overlays.add(line)
        map.invalidate()

        // Zoom map to fit both endpoints with padding
        val bbox = BoundingBox.fromGeoPoints(listOf(from, to)).increaseByScale(1.6f)
        map.post { map.zoomToBoundingBox(bbox, true, dp(32)) }

        // Update banner
        measureBanner.text = "📏 ${info.formattedDistance}  ·  ${info.formattedBearing}  ·  $fromLabel → $toLabel"
        measureBanner.visibility = View.VISIBLE
    }



    private fun createPinDrawable(pinColor: Int, symbol: String): Drawable {
        val sizePx = dp(38)
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Drop shadow circle
        paint.color = Color.argb(60, 0, 0, 0)
        canvas.drawCircle(sizePx / 2f, sizePx / 2f + dp(2), sizePx / 2.3f, paint)

        // Main colored circle
        paint.color = pinColor
        canvas.drawCircle(sizePx / 2f, sizePx / 2f - dp(1), sizePx / 2.4f, paint)

        // White border
        paint.color = Color.WHITE
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2).toFloat()
        canvas.drawCircle(sizePx / 2f, sizePx / 2f - dp(1), sizePx / 2.4f, paint)

        // Draw symbol
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = dp(14).toFloat()
            textAlign = Paint.Align.CENTER
        }
        val yOffset = ((textPaint.descent() + textPaint.ascent()) / 2f)
        canvas.drawText(symbol, sizePx / 2f, (sizePx / 2f - dp(1)) - yOffset, textPaint)

        return BitmapDrawable(context.resources, bitmap)
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    private fun roundedBackground(color: Int, radiusDp: Int = 18) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }
}
