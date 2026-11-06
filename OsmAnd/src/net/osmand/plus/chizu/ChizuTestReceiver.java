package net.osmand.plus.chizu;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import net.osmand.plus.OsmandApplication;
import net.osmand.plus.auto.NavigationSession;
import net.osmand.plus.auto.SurfaceRenderer;
import net.osmand.plus.plugins.PluginsHelper;
import net.osmand.plus.plugins.rastermaps.OsmandRasterMapsPlugin;
import net.osmand.plus.settings.backend.OsmandSettings;
import net.osmand.plus.simulation.OsmAndLocationSimulation;
import net.osmand.util.Algorithms;

/**
 * 白い熊 地図 +043: test switches for measuring the car screen's cost on the desktop head unit, without
 * anyone sitting in the car. Sent from the PC only — the receiver needs DUMP, which the adb shell holds
 * and no ordinary app does:
 * <pre>
 * adb shell am broadcast -n shiroikuma.chizu/net.osmand.plus.chizu.ChizuTestReceiver --es cmd overlay --es arg off
 * </pre>
 * Commands: {@code mark <text>}, {@code overlay on|off}, {@code car flat|3d}, {@code fps <n>},
 * {@code sim on|off} (simulate driving the current route), {@code dump} (every profile's layer settings). Each one is written to the drive log.
 */
public class ChizuTestReceiver extends BroadcastReceiver {

	private static String savedOverlay;

	@Override
	public void onReceive(Context context, Intent intent) {
		OsmandApplication app = (OsmandApplication) context.getApplicationContext();
		String cmd = intent.getStringExtra("cmd");
		String arg = intent.getStringExtra("arg");
		if (cmd == null) {
			return;
		}
		app.runInUIThread(() -> run(app, cmd, arg == null ? "" : arg));
	}

	private static void run(OsmandApplication app, String cmd, String arg) {
		String result;
		switch (cmd) {
			case "mark":
				ChizuDiag.mark("test " + arg);
				result = "ok";
				break;
			case "overlay":
				result = overlay(app, "on".equals(arg));
				break;
			case "car":
				result = car(app, "flat".equals(arg));
				break;
			case "fps":
				result = fps(app, arg);
				break;
			case "dump":
				result = dump(app);
				break;
			case "sim":
				result = sim(app, "on".equals(arg));
				break;
			default:
				result = "unknown command";
		}
		ChizuDriveLog.logAlways("test " + cmd + " " + arg + ": " + result);
	}

	private static String overlay(OsmandApplication app, boolean on) {
		OsmandSettings st = app.getSettings();
		if (on) {
			String name = !Algorithms.isEmpty(st.MAP_OVERLAY.get()) ? st.MAP_OVERLAY.get()
					: savedOverlay != null ? savedOverlay : st.MAP_OVERLAY_PREVIOUS.get();
			if (Algorithms.isEmpty(name)) {
				return "no overlay to restore";
			}
			st.MAP_OVERLAY.set(name);
		} else {
			String now = st.MAP_OVERLAY.get();
			if (!Algorithms.isEmpty(now)) {
				savedOverlay = now;
			}
			st.MAP_OVERLAY.set(null);
		}
		OsmandRasterMapsPlugin plugin = PluginsHelper.getActivePlugin(OsmandRasterMapsPlugin.class);
		if (plugin != null) {
			plugin.updateMapLayers(app, app.getOsmandMap().getMapView().getMapActivity(), null);
		}
		app.getOsmandMap().refreshMap(true);
		return "overlay=" + st.MAP_OVERLAY.get();
	}

	/** Every profile's map layer settings, and which profile is in use. */
	private static String dump(OsmandApplication app) {
		OsmandSettings st = app.getSettings();
		StringBuilder sb = new StringBuilder("in use=").append(st.getApplicationMode().getStringKey());
		for (net.osmand.plus.settings.backend.ApplicationMode m : net.osmand.plus.settings.backend.ApplicationMode.values(app)) {
			sb.append(" | ").append(m.getStringKey())
					.append(": underlay=").append(st.MAP_UNDERLAY.getModeValue(m))
					.append(" vector=").append(st.MAP_TRANSPARENCY.getModeValue(m))
					.append(" overlay=").append(st.MAP_OVERLAY.getModeValue(m))
					.append(" overlayAlpha=").append(st.MAP_OVERLAY_TRANSPARENCY.getModeValue(m))
					.append(" source=").append(st.MAP_TILE_SOURCES.getModeValue(m))
					.append(" onlineMap=").append(st.MAP_ONLINE_DATA.getModeValue(m));
		}
		return sb.toString();
	}

	private static SurfaceRenderer surface(OsmandApplication app) {
		NavigationSession session = app.getCarNavigationSession();
		return session == null ? null : session.getNavigationCarSurface();
	}

	private static String car(OsmandApplication app, boolean flat) {
		SurfaceRenderer s = surface(app);
		if (s == null) {
			return "no car screen";
		}
		return s.chizuSetFlat(flat) ? (flat ? "flat" : "3D") : "car map not ready";
	}

	private static String fps(OsmandApplication app, String arg) {
		int fps;
		try {
			fps = Integer.parseInt(arg);
		} catch (NumberFormatException e) {
			return "not a number";
		}
		if (fps < 1 || fps > 60) {
			return "out of range";
		}
		SurfaceRenderer s = surface(app);
		if (s == null) {
			SurfaceRenderer.chizuSetDefaultFrameRate(fps);
			return fps + " fps from the next car session";
		}
		s.chizuSetFrameRate(fps);
		return fps + " fps";
	}

	private static String sim(OsmandApplication app, boolean on) {
		OsmAndLocationSimulation sim = app.getLocationProvider().getLocationSimulation();
		if (on == sim.isRouteAnimating()) {
			return on ? "already simulating" : "not simulating";
		}
		if (on && !app.getRoutingHelper().isRouteCalculated()) {
			return "no route";
		}
		sim.startStopRouteAnimation(null, false, null);
		return on ? "simulating the route" : "stopped";
	}
}
