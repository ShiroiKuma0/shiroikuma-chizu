package net.osmand.plus.chizu;

import static net.osmand.plus.views.OsmandMapTileView.DEFAULT_ELEVATION_ANGLE;

import androidx.annotation.NonNull;

import net.osmand.plus.OsmandApplication;
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.routepreparationmenu.ChooseRouteFragment;
import net.osmand.plus.routepreparationmenu.MapRouteInfoMenu;
import net.osmand.plus.routing.RoutingHelper;
import net.osmand.plus.views.OsmandMapTileView;

/**
 * 白い熊 地図 +076: the phone's route planning shows the routes as the car's route preview does (白い熊,
 * 2026-11-16) — flat and north-up, so the routes fill the map above the planning card instead of lying in a
 * thin band of a tilted one, and without the POI overlay (a fuel filter left on buried the routes under gas
 * stations).
 * <p>
 * The preview begins with the first route fit of the planning card and ends when the card is gone or
 * navigation starts. The tilt it replaces is never saved over: the map goes back to the saved one.
 */
public final class ChizuRoutePreview {

	private static final long CHECK_DELAY_MS = 500;

	private static volatile boolean active;

	private ChizuRoutePreview() {
	}

	/** True while the phone map is held flat for the route planning card: tilt is not saved, POIs are hidden. */
	public static boolean isActive() {
		return active;
	}

	/** Before each route fit of the planning card: flat and north-up, whatever tilted the map since. */
	public static void enter(@NonNull MapActivity activity) {
		OsmandMapTileView mapView = activity.getMapView();
		if (mapView.isCarView() || !isPlanning(activity.getApp())) {
			return; // the car fits its own preview
		}
		boolean changed = !active;
		active = true;
		if (mapView.getElevationAngle() < DEFAULT_ELEVATION_ANGLE) {
			if (mapView.getAnimatedDraggingThread() != null) {
				mapView.getAnimatedDraggingThread().stopAnimatingSync();
			}
			mapView.setElevationAngle(DEFAULT_ELEVATION_ANGLE);
			changed = true;
		}
		if (mapView.getRotate() != 0) {
			mapView.setRotate(0, true);
			changed = true;
		}
		if (changed) {
			ChizuDriveLog.log("phone route preview flat");
			mapView.refreshMap();
		}
	}

	/** After the planning card was dismissed, or the activity came back: ends the preview when nothing of it is left. */
	public static void checkLater(@NonNull MapActivity activity) {
		if (active) {
			activity.getApp().runInUIThread(() -> {
				if (active && !isShown(activity)) {
					exit(activity);
				}
			}, CHECK_DELAY_MS);
		}
	}

	private static void exit(@NonNull MapActivity activity) {
		active = false;
		OsmandApplication app = activity.getApp();
		OsmandMapTileView mapView = activity.getMapView();
		// navigation that has started tilts the map itself, and the car map is the car's
		if (!mapView.isCarView() && !app.getRoutingHelper().isFollowingMode()) {
			mapView.setElevationAngle(app.getSettings().getLastKnownMapElevation());
		}
		ChizuDriveLog.log("phone route preview ends");
		mapView.refreshMap();
	}

	private static boolean isPlanning(@NonNull OsmandApplication app) {
		RoutingHelper rh = app.getRoutingHelper();
		return rh.isRoutePlanningMode() && !rh.isFollowingMode();
	}

	/** The planning card, or a screen it opened and comes back from, is up. */
	private static boolean isShown(@NonNull MapActivity activity) {
		if (activity.isDestroyed() || activity.isFinishing() || !isPlanning(activity.getApp())) {
			return false;
		}
		MapRouteInfoMenu menu = activity.getMapRouteInfoMenu();
		return menu.isVisible() || menu.isSelectFromMap()
				|| activity.getSupportFragmentManager().findFragmentByTag(ChooseRouteFragment.TAG) != null;
	}
}
