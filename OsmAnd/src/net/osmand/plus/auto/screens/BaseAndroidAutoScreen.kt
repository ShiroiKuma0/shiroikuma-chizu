package net.osmand.plus.auto.screens

import androidx.car.app.CarContext
import androidx.car.app.HostException
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import net.osmand.data.LatLon
import net.osmand.data.QuadRect
import net.osmand.plus.OsmandApplication
import net.osmand.plus.R
import net.osmand.plus.chizu.ChizuCar
import net.osmand.plus.views.Zoom
import net.osmand.search.core.SearchResult
import net.osmand.util.Algorithms
import kotlin.math.max
import kotlin.math.min
import net.osmand.data.RotatedTileBox

abstract class BaseAndroidAutoScreen(carContext: CarContext) : Screen(carContext),
	DefaultLifecycleObserver {

	private val ANIMATION_RETURN_FROM_PREVIEW_TIME = 1500

	private var prevElevationAngle = 90f
	private var prevRotationAngle = 0f
	private var prevZoom: Zoom? = null
	private var prevMapLinkedToLocation = false
	private var prevFollowingMode = false
	private var prevStateSaved = false
	protected var firstTimeGetTemplate = true

	protected val app: OsmandApplication
		get() {
			return carContext.applicationContext as OsmandApplication
		}
	protected var contentLimit: Int = 0
		private set
	var session = app.carNavigationSession

	private fun initContentLimit() {
		val manager = carContext.getCarService(
			ConstraintManager::class.java
		)
		try {
			contentLimit = DEFAULT_CONTENT_LIMIT.coerceAtMost(
				manager.getContentLimit(getConstraintLimitType())
			)
		} catch (e: HostException) {
			contentLimit = DEFAULT_CONTENT_LIMIT
		}
		contentLimit = max(2, contentLimit)
	}

	final override fun onGetTemplate(): Template {
		if(firstTimeGetTemplate) {
			onFirstGetTemplate()
			firstTimeGetTemplate = false
		}
		return getTemplate()
	}

	abstract fun getTemplate(): Template

	protected open fun onFirstGetTemplate() {
		initContentLimit()
	}

	protected open fun shouldRestoreMapState(): Boolean = false

	protected open fun getConstraintLimitType(): Int {
		return ConstraintManager.CONTENT_LIMIT_TYPE_LIST
	}

	protected fun openRoutePreview(
		settingsAction: Action,
		result: SearchResult
	) {
		screenManager.pushForResult(
			RoutePreviewScreen(carContext, settingsAction, result, true)
		) { obj: Any? ->
			obj?.let {
				screenManager.popToRoot()
				startNavigation()
				finish()
			}
		}
	}

	protected open fun onSearchResultSelected(result: SearchResult) {
	}

	private fun startNavigation() {
		app.osmandMap.mapActions.startNavigation()
		val session = app.carNavigationSession
		session?.startNavigationScreen()
	}

	// shiroikuma fork: yellow glyph, like every other car action
	protected fun createSearchAction() =
		ChizuCar.action(carContext, R.drawable.ic_action_search_dark)
			.setOnClickListener { openSearch() }
			.build()

	private fun openSearch() {
		app.carNavigationSession?.let { navigationSession ->
			screenManager.pushForResult(
				SearchScreen(
					carContext,
					navigationSession.settingsAction
				)
			) { _: Any? -> }
		}
	}

	private var chizuFitToken: Any? = null
	private val CHIZU_FILL = 0.95f
	private val CHIZU_PAD = 44
	private val CHIZU_EDGE = 12
	private val CHIZU_FLAG = 36
	private val CHIZU_CARD_DP = 340f

	protected open fun adjustMapToRect(location: LatLon, mapRect: QuadRect) {
		app.mapViewTrackingUtilities.isMapLinkedToLocation = false
		Algorithms.extendRectToContainPoint(mapRect, location.longitude, location.latitude)
		app.carNavigationSession?.navigationCarSurface?.let { surfaceRenderer ->
			if (!mapRect.hasInitialState()) {
				val mapView = app.osmandMap.mapView
				val tb = RotatedTileBox(mapView.rotatedTileBox)
				val surfaceAdditionalWidth = surfaceRenderer.surfaceAdditionalWidth
				val adjustedPixWidth = tb.pixWidth - surfaceAdditionalWidth
				tb.setPixelDimensions(adjustedPixWidth, tb.pixHeight, surfaceRenderer.cachedRatioX, surfaceRenderer.cachedRatioY)
				tb.rotate = 0f;
				tb.setZoomAndAnimation(tb.zoom, 0.0, 0.0);
//				tb.mapDensity = surfaceRenderer.density.toDouble() * app.settings.MAP_DENSITY.get();
				tb.mapDensity = surfaceRenderer.density.toDouble() * app.osmandMap.mapDensity;
				// 白い熊 地図 +052: fit into the part of the screen the car leaves free beside the panel, as the
				// car reports it — not half the screen, which on a wide car screen left the route small
				val rect = QuadRect(mapRect.left, mapRect.top, mapRect.right, mapRect.bottom)
				val points = chizuFitPoints
				val token = Any()
				chizuFitToken = token
				val flat = mapView.elevationAngle >= 89f
				if (!flat) {
					mapView.setElevationAngle(90f)
					mapView.setRotate(0f, true)
					mapView.refreshMap()
				}
				// 白い熊 地図 +063: a preview already framed is left alone when it still fits
				app.runInUIThread({ chizuFit(token, rect, points, 1, flat) }, if (flat) 0L else 400L)
			}
		}
	}

	/** 白い熊 地図 +063: route points for the preview fit, set by the route preview before it asks for a fit. */
	protected var chizuFitPoints: List<LatLon>? = null

	/**
	 * 白い熊 地図 +063: the route preview fit, measured on the car screen (白い熊, 2026-11-06).
	 * 1. Frame the routes in the whole screen, centred.
	 * 2. If a route would run under the panel card, shift them right by just enough, at the same zoom, when they
	 *    still fit; only otherwise frame them in the part right of the panel, full height.
	 * 3. A later route leaves a view alone that already fits that way and is well filled.
	 * Map-engine pixels are converted to screen pixels: the car map is drawn from a wider picture shifted left.
	 */
	private fun chizuFit(token: Any, rect: QuadRect, points: List<LatLon>?, pass: Int, keepIfFits: Boolean) {
		if (token !== chizuFitToken || app.routingHelper.isFollowingMode) {
			return // a newer fit took over, or navigation started
		}
		val mapView = app.osmandMap.mapView
		val renderer = mapView.mapRenderer ?: return
		val surface = app.carNavigationSession?.navigationCarSurface ?: return
		if (mapView.elevationAngle < 89f) {
			return
		}
		val tb = mapView.rotatedTileBox
		val off = surface.chizuLeftOffset()
		// the route points on the screen (corners of the rect when the screen gave none)
		val geo = if (points.isNullOrEmpty()) listOf(LatLon(rect.top, rect.left), LatLon(rect.top, rect.right),
			LatLon(rect.bottom, rect.left), LatLon(rect.bottom, rect.right)) else points
		val xs = FloatArray(geo.size)
		val ys = FloatArray(geo.size)
		for (i in geo.indices) {
			val p = net.osmand.plus.utils.NativeUtilities.getPixelFromLatLon(renderer, tb, geo[i])
			xs[i] = p.x + off
			ys[i] = p.y
		}
		val l = xs.minOrNull() ?: return
		val r = xs.maxOrNull() ?: return
		val t = ys.minOrNull() ?: return
		val b = ys.maxOrNull() ?: return
		val w = r - l
		val h = b - t
		if (w < 1 || h < 1) {
			return
		}
		val target = net.osmand.plus.utils.NativeUtilities.getPixelFromLatLon(renderer, tb, mapView.latitude, mapView.longitude)
		val tx = target.x + off
		val ty = target.y
		// the whole screen, inside a margin for the drawn lines and markers
		val sw = tb.pixWidth - surface.surfaceAdditionalWidth
		val al = CHIZU_EDGE + CHIZU_PAD
		// 白い熊 地図 +065: top and bottom margins half of what they were (92 → 46, 56 → 28 px)
		val at = (CHIZU_EDGE + CHIZU_PAD + CHIZU_FLAG) / 2 // the destination flag stands above its point
		val ar = sw - CHIZU_EDGE - CHIZU_PAD
		val ab = tb.pixHeight - (CHIZU_EDGE + CHIZU_PAD) / 2
		// the panel card, when the car shows it: its width from the area the car leaves free, its height ~340 dp
		val panel = surface.visibleArea?.takeIf { it.left >= sw / 5 } ?: surface.chizuPanelArea
		val zr = if (panel != null) (panel.left + CHIZU_PAD).toFloat() else Float.NEGATIVE_INFINITY
		val zb = if (panel != null) minOf(tb.pixHeight.toFloat(), CHIZU_CARD_DP * surface.density + CHIZU_PAD) else 0f
		fun underPanel(px: FloatArray, py: FloatArray): Float { // how far right they must go to clear the card
			var need = 0f
			for (i in px.indices) {
				if (py[i] < zb && px[i] < zr) need = maxOf(need, zr - px[i])
			}
			return need
		}
		// 1. the whole screen, centred
		var k = minOf((ar - al) * CHIZU_FILL / w, (ab - at) * CHIZU_FILL / h)
		var fx = (al + ar) / 2f
		var fy = (at + ab) / 2f
		val cx = (l + r) / 2f
		val cy = (t + b) / 2f
		val px = FloatArray(xs.size) { fx + (xs[it] - cx) * k }
		val py = FloatArray(ys.size) { fy + (ys[it] - cy) * k }
		var how = "centred"
		val need = underPanel(px, py)
		if (need > 0) {
			if ((px.maxOrNull() ?: 0f) + need <= ar) {
				// 2a. shift right by just enough, same zoom
				fx += need
				how = "shifted off the panel"
			} else {
				// 2b. the part right of the panel, full height
				val bl = zr
				k = minOf((ar - bl) * CHIZU_FILL / w, (ab - at) * CHIZU_FILL / h)
				fx = (bl + ar) / 2f
				fy = (at + ab) / 2f
				how = "right of the panel"
			}
		}
		val dz = Math.log(k.toDouble()) / Math.log(2.0)
		// 3. a view that already fits that way and is well filled stays; a route a few pixels inside the
		// card's margin counts as clear (+066), the same 10 px a move would ignore anyway
		if (keepIfFits && Math.abs(dz) < 0.3 && l >= al && r <= ar && t >= at && b <= ab && underPanel(xs, ys) < 10f) {
			net.osmand.plus.chizu.ChizuDriveLog.log(String.format(java.util.Locale.US,
				"car route preview kept: routes %.0fx%.0f fit, zoom %+.2f would be all", w, h, dz))
			return
		}
		val dzc = dz.coerceIn(-5.0, 5.0)
		val kk = Math.pow(2.0, dzc).toFloat()
		// zoom keeps the target in place: the routes' middle C lands on F when the new target is P = C - (F - T) / k
		val ptx = cx - (fx - tx) / kk
		val pty = cy - (fy - ty) / kk
		net.osmand.plus.chizu.ChizuDriveLog.log(String.format(java.util.Locale.US,
			"car route preview %s (pass %d): routes %.0fx%.0f at %.0f,%.0f, %d points, screen %dx%d, panel to %.0f,%.0f, shift %.0f, zoom %+.2f",
			how, pass, w, h, cx, cy, geo.size, sw, tb.pixHeight, zr, zb, need, dzc))
		if (Math.abs(dzc) < 0.05 && Math.abs(ptx - tx) < 10 && Math.abs(pty - ty) < 10) {
			return
		}
		val to = net.osmand.plus.utils.NativeUtilities.getLatLonFromPixel(renderer, tb, ptx - off, pty) ?: return
		val total = mapView.zoom + mapView.zoomFloatPart + dzc
		val base = Math.round(total).toInt()
		mapView.animatedDraggingThread.startMoving(to.latitude, to.longitude, base, (total - base).toFloat())
		if (pass < 3) {
			app.runInUIThread({ chizuFit(token, rect, points, pass + 1, false) }, 900)
		}
	}

	protected fun recenterMap() {
		session?.navigationCarSurface?.handleRecenter()
	}

	override fun onCreate(owner: LifecycleOwner) {
		if (shouldSaveMapState()) {
			saveMapState()
		}
	}

	override fun onDestroy(owner: LifecycleOwner) {
		if (prevStateSaved) {
			val mapLinkedToLocation = prevMapLinkedToLocation || app.routingHelper.isFollowingMode
			if (mapLinkedToLocation != app.mapViewTrackingUtilities.isMapLinkedToLocation) {
				app.mapViewTrackingUtilities.isMapLinkedToLocation = mapLinkedToLocation
			}
			restoreMapState()
		}
	}

	private fun shouldSaveMapState(): Boolean {
		return shouldRestoreMapState() && screenManager.screenStack
			.filterIsInstance<BaseAndroidAutoScreen>()
			.none { it != this && it.shouldRestoreMapState() }
	}

	private fun saveMapState() {
		val mapView = app.osmandMap.mapView
		prevMapLinkedToLocation = app.mapViewTrackingUtilities.isMapLinkedToLocation
		prevFollowingMode = app.routingHelper.isFollowingMode
		prevZoom = mapView.currentZoom
		prevRotationAngle = mapView.rotate
		prevElevationAngle = mapView.normalizeElevationAngle(mapView.elevationAngle)
		prevStateSaved = true
	}

	private fun restoreMapState() {
		val mapView = app.osmandMap.mapView
		val locationProvider = app.locationProvider
		val lastKnownLocation = locationProvider.lastKnownLocation
		// navigation started on this screen goes to its own view, not to the one before the screen
		val navigationStarted = app.routingHelper.isFollowingMode && !prevFollowingMode
		val zoom = prevZoom ?: mapView.currentZoom
		mapView.animateToState(
			lastKnownLocation?.latitude ?: mapView.latitude,
			lastKnownLocation?.longitude ?: mapView.longitude,
			if (navigationStarted && zoom.baseZoom < NAVIGATION_ZOOM) {
				Zoom(NAVIGATION_ZOOM, 0f, mapView.minZoom, mapView.maxZoom)
			} else {
				zoom
			},
			prevRotationAngle,
			if (navigationStarted) app.settings.lastKnownMapElevation else prevElevationAngle,
			ANIMATION_RETURN_FROM_PREVIEW_TIME.toLong(),
			false)
	}

	companion object {
		private const val DEFAULT_CONTENT_LIMIT = 12
		private const val NAVIGATION_ZOOM = 17
	}
}