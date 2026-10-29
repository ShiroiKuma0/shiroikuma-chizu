package net.osmand.plus.routing;

import net.osmand.Location;
import net.osmand.data.LatLon;
import net.osmand.gpx.GPXFile;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.settings.backend.ApplicationMode;
import net.osmand.router.RouteCalculationProgress;
import net.osmand.shared.gpx.GpxFile;

import java.util.List;
import java.util.Set;

public class RouteCalculationParams {

	public Location start;
	public LatLon end;
	public List<LatLon> intermediates;
	public Location currentLocation;

	public OsmandApplication ctx;
	public ApplicationMode mode;
	public GPXRouteParams gpxRoute;
	public RouteCalculationResult previousToRecalculate;

	public boolean onlyStartPointChanged;
	public boolean fast;
	public boolean leftSide;
	public boolean startTransportStop;
	public boolean targetTransportStop;
	public boolean inPublicTransportMode;
	public boolean extraIntermediates;
	public boolean initialCalculation;
	public GpxFile gpxFile;

	public RouteCalculationProgress calculationProgress;
	public RouteCalculationProgressListener calculationProgressListener;
	public RouteCalculationResultListener alternateResultListener;

	// 白い熊 地図: roads banned for this calculation only (traffic candidates), never saved
	public Set<Long> chizuExtraImpassable;
	// 白い熊 地図: force the shortest-distance route for this calculation only
	public boolean chizuShortestWay;
	// 白い熊 地図: a traffic candidate — its listener gets every outcome (failed or cancelled too)
	// and nothing is shown or stored by the routing helper
	public boolean chizuCandidate;

	public boolean recheckRouteNearestPoint() {
		return previousToRecalculate != null && onlyStartPointChanged && start != null && gpxRoute != null;
	}

	public interface RouteCalculationResultListener {
		void onRouteCalculated(RouteCalculationResult route);
	}
}
