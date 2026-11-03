package net.osmand.plus.views.layers;

import static net.osmand.plus.profiles.LocationIcon.STATIC_DEFAULT;

import android.content.Context;
import android.graphics.*;
import android.graphics.Paint.Cap;
import android.graphics.drawable.LayerDrawable;
import android.os.SystemClock;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.content.ContextCompat;

import net.osmand.Location;
import net.osmand.PlatformUtil;
import net.osmand.core.android.MapRendererView;
import net.osmand.core.jni.MapMarkerBuilder;
import net.osmand.core.jni.MapMarkersCollection;
import net.osmand.core.jni.PointI;
import net.osmand.data.LatLon;
import net.osmand.data.PointDescription;
import net.osmand.data.QuadRect;
import net.osmand.data.RotatedTileBox;
import net.osmand.data.TransportStop;
import net.osmand.plus.ChartPointsHelper;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;
import net.osmand.plus.base.MapViewTrackingUtilities;
import net.osmand.plus.charts.TrackChartPoints;
import net.osmand.plus.chizu.ChizuTrafficPrefs;
import net.osmand.plus.routing.*;
import net.osmand.plus.routepreparationmenu.cards.ChizuRouteCandidateCard;
import net.osmand.plus.settings.backend.OsmandSettings;
import net.osmand.plus.track.helpers.GpxUtils;
import net.osmand.plus.utils.NativeUtilities;
import net.osmand.plus.views.layers.ContextMenuLayer.IContextMenuProvider;
import net.osmand.plus.views.layers.base.BaseRouteLayer;
import net.osmand.plus.views.layers.core.LocationPointsTileProvider;
import net.osmand.plus.views.layers.geometry.PublicTransportGeometryWay;
import net.osmand.plus.views.layers.geometry.PublicTransportGeometryWayContext;
import net.osmand.plus.views.layers.geometry.RouteGeometryWay;
import net.osmand.plus.views.layers.geometry.RouteGeometryWayContext;
import net.osmand.router.TransportRouteResult;
import net.osmand.shared.routing.ColoringType;
import net.osmand.util.Algorithms;
import net.osmand.util.MapUtils;

import org.apache.commons.logging.Log;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class RouteLayer extends BaseRouteLayer implements IContextMenuProvider {

	private static final Log LOG = PlatformUtil.getLog(RouteLayer.class);

	private final RoutingHelper helper;
	private final TransportRoutingHelper transportHelper;
	private Location lastRouteProjection;

	private final ChartPointsHelper chartPointsHelper;
	private TrackChartPoints trackChartPoints;

	private RenderingLineAttributes attrsPT;
	private RenderingLineAttributes attrsWPT;
	private RenderingLineAttributes attrsW;

	private RouteGeometryWayContext routeWayContext;
	private PublicTransportGeometryWayContext publicTransportWayContext;
	private RouteGeometryWay routeGeometry;
	// 白い熊 地図 渋滞回避: the traffic chooser's other routes, drawn under the selected one
	private float chizuDensity = 1f;
	private ChizuWayPool chizuAlternatives;
	// 白い熊 地図 渋滞回避: a soft dark band under every previewed route, so nearby streets recede
	private ChizuWayPool chizuHalos;
	// the selected route's gold glow: a bright inner ring and a soft wide outer one
	private ChizuWayPool chizuGlowInner;
	private ChizuWayPool chizuGlowOuter;
	private static final int CHIZU_GLOW_INNER = 0xFFFFD700;
	private static final int CHIZU_GLOW_OUTER = 0x70FFC21A;
	// …and its shimmer, drawn over the map while planning: a light band running along it and twinkling sparkles
	private static final int CHIZU_SHIMMER_MAX_POINTS = 400;
	private static final int CHIZU_SPARKLES_MAX = 48;
	private volatile float chizuShimmerWidth;
	private final List<float[]> chizuSparkles = new ArrayList<>();
	private final java.util.Random chizuRandom = new java.util.Random();
	private final Paint chizuBandPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint chizuClearPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint chizuSparkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Path chizuBandPath = new Path();
	private float chizuBandBlur;
	private static final int CHIZU_HALO_COLOR = 0x8C000000;
	private PublicTransportGeometryWay publicTransportRouteGeometry;

	private final ColoringTypeAvailabilityCache coloringAvailabilityCache;

	private LayerDrawable projectionIcon;

	private boolean isCreated;

	//OpenGL
	private final RouteRenderState renderState = new RouteRenderState();
	private LocationPointsTileProvider trackChartPointsProvider;
	private MapMarkersCollection highlightedPointCollection;
	private net.osmand.core.jni.MapMarker highlightedPointMarker;
	private LatLon highlightedPointLocationCached;
	private List<LatLon> xAxisPointsCached = new ArrayList<>();
	private MapMarkersCollection projectionPointCollection;
	private net.osmand.core.jni.MapMarker projectedPointMarker;

	private interface ConditionMatcher {
		boolean match();
	}

	public RouteLayer(@NonNull Context context) {
		super(context);
		OsmandApplication app = (OsmandApplication) context.getApplicationContext();
		this.helper = app.getRoutingHelper();
		this.transportHelper = helper.getTransportRoutingHelper();
		this.chartPointsHelper = new ChartPointsHelper(app);
		this.coloringAvailabilityCache = new ColoringTypeAvailabilityCache(app);
	}

	public RoutingHelper getHelper() {
		return helper;
	}

	public void setTrackChartPoints(TrackChartPoints trackChartPoints) {
		this.trackChartPoints = trackChartPoints;
	}

	@Override
	protected void initAttrs(float density) {
		super.initAttrs(density);

		attrsPT = new RenderingLineAttributes("publicTransportLine");
		attrsPT.defaultWidth = (int) (12 * density);
		attrsPT.defaultWidth3 = (int) (7 * density);
		attrsPT.defaultColor = ContextCompat.getColor(getContext(), R.color.nav_track);
		attrsPT.paint3.setStrokeCap(Cap.BUTT);
		attrsPT.paint3.setColor(Color.WHITE);
		attrsPT.paint2.setStrokeCap(Cap.BUTT);
		attrsPT.paint2.setColor(Color.BLACK);

		attrsWPT = new RenderingLineAttributes("walkingRouteLine");
		attrsWPT.defaultWidth = (int) (12 * density);
		attrsWPT.defaultWidth3 = (int) (7 * density);
		attrsWPT.defaultColor = ContextCompat.getColor(getContext(), R.color.nav_track_walk_fill);
		attrsWPT.paint3.setStrokeCap(Cap.BUTT);
		attrsWPT.paint3.setColor(Color.WHITE);
		attrsWPT.paint2.setStrokeCap(Cap.BUTT);
		attrsWPT.paint2.setColor(Color.BLACK);

		attrsW = new RenderingLineAttributes("straightWalkingRouteLine");
		attrsW.defaultWidth = (int) (12 * density);
		attrsW.defaultWidth3 = (int) (7 * density);
		attrsW.defaultColor = ContextCompat.getColor(getContext(), R.color.nav_track_walk_fill);
		attrsW.paint3.setStrokeCap(Cap.BUTT);
		attrsW.paint3.setColor(Color.WHITE);
		attrsW.paint2.setStrokeCap(Cap.BUTT);
		attrsW.paint2.setColor(Color.BLACK);
	}

	@Override
	protected void initGeometries(float density) {
		chizuDensity = density;
		chizuAlternatives = new ChizuWayPool(0xC0000000, 2 * density);
		chizuHalos = new ChizuWayPool(0, 0);
		chizuGlowInner = new ChizuWayPool(0, 0);
		chizuGlowOuter = new ChizuWayPool(0, 0);
		routeWayContext = new RouteGeometryWayContext(getContext(), density);
		routeWayContext.updatePaints(nightMode, attrs, attrsW);
		routeGeometry = new RouteGeometryWay(routeWayContext);
		// a black casing keeps the route legible over any street colour
		routeGeometry.getDrawer().setChizuCasing(0xFF000000, 3 * density);
		routeGeometry.baseOrder = getBaseOrder();

		publicTransportWayContext = new PublicTransportGeometryWayContext(getContext(), density);
		publicTransportWayContext.updatePaints(nightMode, attrs, attrsPT, attrsW);
		publicTransportRouteGeometry = new PublicTransportGeometryWay(publicTransportWayContext);
		publicTransportRouteGeometry.baseOrder = getBaseOrder();
	}

	@Override
	public boolean areMapRendererViewEventsAllowed() {
		return true;
	}

	@Override
	public void onUpdateFrame(MapRendererView mapRenderer) {
		super.onUpdateFrame(mapRenderer);
		ConditionMatcher drawLocationsMatcher = () -> hasMapRenderer() && !helper.isPublicTransportMode()
				&& helper.getFinalLocation() != null && helper.getRoute().isCalculated();
		if (drawLocationsMatcher.match()) {
			getApplication().runInUIThread(() -> {
				if (drawLocationsMatcher.match()) {
					drawLocations(null, view.getRotatedTileBox());
				}
			});
		}
	}

	@Override
	public void onPrepareBufferImage(Canvas canvas, RotatedTileBox tileBox, DrawSettings settings) {
		super.onPrepareBufferImage(canvas, tileBox, settings);
		MapRendererView mapRenderer = getMapRenderer();
		if ((helper.isPublicTransportMode() && transportHelper.getRoutes() != null) ||
				(helper.getFinalLocation() != null && helper.getRoute().isCalculated())) {
			isCreated = true;
			updateRouteColoringType();
			updateAttrs(settings, tileBox);
			updateRouteColors(nightMode);

			drawLocations(canvas, tileBox);

			if (mapRenderer == null) {
				drawXAxisPoints(trackChartPoints, canvas, tileBox);
			} else {
				if (highlightedPointCollection == null || mapActivityInvalidated || mapRendererChanged) {
					recreateHighlightedPointCollection();
				}
				drawXAxisPointsOpenGl(trackChartPoints, mapRenderer, tileBox);
			}
		} else if (isCreated) {
			isCreated = false;
			resetLayer();
		}
		mapActivityInvalidated = false;
		if (mapRenderer != null) {
			mapRendererChanged = false;
		}
	}

	private void drawLocations(@Nullable Canvas canvas, @NonNull RotatedTileBox tileBox) {
		int w = tileBox.getPixWidth();
		int h = tileBox.getPixHeight();
		Location lastProjection = helper.getLastProjection();
		RotatedTileBox cp;
		if (lastProjection != null &&
				tileBox.containsLatLon(lastProjection.getLatitude(), lastProjection.getLongitude())) {
			cp = tileBox.copy();
			cp.increasePixelDimensions(w / 2, h);
		} else {
			cp = tileBox;
		}
		QuadRect latlonRect = cp.getLatLonBounds();
		QuadRect correctedQuadRect = getCorrectedQuadRect(latlonRect);
		drawLocations(tileBox, canvas, correctedQuadRect.top, correctedQuadRect.left,
				correctedQuadRect.bottom, correctedQuadRect.right);
	}

	@Nullable
	public Location getLastRouteProjection() {
		return lastRouteProjection;
	}

	private boolean useMapCenter() {
		OsmandSettings settings = getApplication().getSettings();
		MapViewTrackingUtilities mapViewTrackingUtilities = getApplication().getMapViewTrackingUtilities();
		return settings.ANIMATE_MY_LOCATION.get()
				&& !mapViewTrackingUtilities.isMovingToMyLocation()
				&& mapViewTrackingUtilities.isMapLinkedToLocation();
	}

	private void drawXAxisPoints(@Nullable TrackChartPoints chartPoints, @NonNull Canvas canvas,
	                             @NonNull RotatedTileBox tileBox) {
		if (chartPoints != null) {
			canvas.rotate(-tileBox.getRotate(), tileBox.getCenterPixelX(), tileBox.getCenterPixelY());
			List<LatLon> xAxisPoints = chartPoints.getXAxisPoints();
			if (!Algorithms.isEmpty(xAxisPoints)) {
				chartPointsHelper.drawXAxisPoints(xAxisPoints, attrs.defaultColor, canvas, tileBox);
			}
			LatLon highlightedPoint = chartPoints.getHighlightedPoint();
			if (highlightedPoint != null) {
				chartPointsHelper.drawHighlightedPoint(highlightedPoint, canvas, tileBox);
			}
			canvas.rotate(tileBox.getRotate(), tileBox.getCenterPixelX(), tileBox.getCenterPixelY());
		}
	}

	private void drawXAxisPointsOpenGl(@Nullable TrackChartPoints chartPoints, @NonNull MapRendererView mapRenderer,
	                                   @NonNull RotatedTileBox tileBox) {
		if (chartPoints != null) {
			if (trackChartPoints != null && trackChartPoints.getHighlightedPoint() != null) {
				LatLon highlightedPoint = trackChartPoints.getHighlightedPoint();
				if (!Algorithms.objectEquals(highlightedPointLocationCached, highlightedPoint)) {
					highlightedPointLocationCached = highlightedPoint;
					setHighlightedPointMarkerLocation(highlightedPoint);
					setHighlightedPointMarkerVisibility(true);
				}
			} else {
				setHighlightedPointMarkerVisibility(false);
			}

			List<LatLon> xAxisPoints = chartPoints.getXAxisPoints();
			if (Algorithms.objectEquals(xAxisPointsCached, xAxisPoints) && trackChartPointsProvider != null
					&& !mapActivityInvalidated && !mapRendererChanged) {
				return;
			}
			xAxisPointsCached = xAxisPoints;
			clearXAxisPoints();
			if (!Algorithms.isEmpty(xAxisPoints)) {
				Bitmap pointBitmap = chartPointsHelper.createXAxisPointBitmap(attrs.defaultColor, tileBox.getDensity());
				trackChartPointsProvider = new LocationPointsTileProvider(getPointsOrder() - 2000, xAxisPoints, pointBitmap);
				trackChartPointsProvider.drawPoints(mapRenderer);
			}
		} else {
			xAxisPointsCached = new ArrayList<>();
			highlightedPointLocationCached = null;
			setHighlightedPointMarkerVisibility(false);
			clearXAxisPoints();
		}
	}

	private void setHighlightedPointMarkerLocation(@NonNull LatLon latLon) {
		MapRendererView mapRenderer = getMapRenderer();
		if (mapRenderer != null && highlightedPointMarker != null) {
			highlightedPointMarker.setPosition(new PointI(MapUtils.get31TileNumberX(latLon.getLongitude()),
					MapUtils.get31TileNumberY(latLon.getLatitude())));
		}
	}

	private void setHighlightedPointMarkerVisibility(boolean visible) {
		if (highlightedPointMarker != null) {
			highlightedPointMarker.setIsHidden(!visible);
		}
	}

	private void clearXAxisPoints() {
		MapRendererView mapRenderer = getMapRenderer();
		if (mapRenderer != null && trackChartPointsProvider != null) {
			trackChartPointsProvider.clearPoints(mapRenderer);
			trackChartPointsProvider = null;
		}
	}

	private void recreateHighlightedPointCollection() {
		MapRendererView mapRenderer = getMapRenderer();
		if (mapRenderer != null) {
			if (highlightedPointCollection != null) {
				mapRenderer.removeSymbolsProvider(highlightedPointCollection);
			}
			highlightedPointCollection = new MapMarkersCollection();
			MapMarkerBuilder builder = new MapMarkerBuilder();
			builder.setBaseOrder(getPointsOrder() - 2100);
			builder.setIsAccuracyCircleSupported(false);
			builder.setIsHidden(true);
			builder.setPinIcon(NativeUtilities.createSkImageFromBitmap(
					chartPointsHelper.createHighlightedPointBitmap()));
			highlightedPointMarker = builder.buildAndAddToCollection(highlightedPointCollection);
			mapRenderer.addSymbolsProvider(highlightedPointCollection);
		}
	}

	private void setProjectedPointMarkerLocation(@NonNull LatLon latLon) {
		MapRendererView mapRenderer = getMapRenderer();
		if (mapRenderer != null && projectedPointMarker != null) {
			projectedPointMarker.setPosition(new PointI(MapUtils.get31TileNumberX(latLon.getLongitude()),
					MapUtils.get31TileNumberY(latLon.getLatitude())));
		}
	}

	private void setProjectedPointMarkerVisibility(boolean visible) {
		if (projectedPointMarker != null) {
			projectedPointMarker.setIsHidden(!visible);
		}
	}

	private void recreateProjectedPointCollection() {
		MapRendererView mapRenderer = getMapRenderer();
		if (mapRenderer != null) {
			if (projectionPointCollection != null) {
				mapRenderer.removeSymbolsProvider(projectionPointCollection);
			}
			projectionPointCollection = new MapMarkersCollection();
			MapMarkerBuilder builder = new MapMarkerBuilder();
			builder.setBaseOrder(getPointsOrder() - 2110);
			builder.setIsAccuracyCircleSupported(false);
			builder.setIsHidden(true);
			builder.setPinIcon(NativeUtilities.createSkImageFromBitmap(
					chartPointsHelper.createHighlightedPointBitmap()));
			projectedPointMarker = builder.buildAndAddToCollection(projectionPointCollection);
			mapRenderer.addSymbolsProvider(projectionPointCollection);
		}
	}

	private void removeProjectedPointCollection() {
		MapRendererView mapRenderer = getMapRenderer();
		if (mapRenderer != null && projectionPointCollection != null) {
			mapRenderer.removeSymbolsProvider(projectionPointCollection);
			projectedPointMarker = null;
		}
	}

	@Override
	protected void updateAttrs(DrawSettings settings, RotatedTileBox tileBox) {
		boolean updatePaints = attrs.updatePaints(view.getApplication(), settings, tileBox);
		attrs.isPaint3 = false;
		attrs.isPaint2 = false;
		attrsPT.updatePaints(view.getApplication(), settings, tileBox);
		attrsPT.isPaint3 = false;
		attrsPT.isPaint2 = false;
		attrsWPT.updatePaints(view.getApplication(), settings, tileBox);
		attrsPT.isPaint3 = false;
		attrsPT.isPaint2 = false;
		attrsW.updatePaints(view.getApplication(), settings, tileBox);
		float routeLineWidth = getRouteLineWidth(tileBox);
		updatePaints |= attrsW.paint.getStrokeWidth() != routeLineWidth;
		attrsW.paint.setStrokeWidth(routeLineWidth);

		nightMode = settings != null && settings.isNightMode();

		if (updatePaints) {
			routeWayContext.updatePaints(nightMode, attrs, attrsW);
			publicTransportWayContext.updatePaints(nightMode, attrs, attrsPT, attrsWPT);
		}
	}

	@Override
	protected void updateTurnArrowColor() {
		if (routeColoringType.isGradient() && isColoringAvailable(routeColoringType, null)) {
			customTurnArrowColor = Color.WHITE;
		} else {
			customTurnArrowColor = attrs.paint3.getColor();
		}
		paintIconAction.setColorFilter(new PorterDuffColorFilter(customTurnArrowColor, PorterDuff.Mode.MULTIPLY));
	}

	private void drawActionArrows(@NonNull RotatedTileBox tb, @NonNull Canvas canvas,
			@NonNull List<RouteActionPoint> actionPoints) {
		if (actionPoints.size() > 0) {
			canvas.rotate(-tb.getRotate(), tb.getCenterPixelX(), tb.getCenterPixelY());
			try {
				float routeWidth = routeGeometry.getDefaultWayStyle().getWidth(0);
				Path path = new Path();
				Matrix matrix = new Matrix();
				float x = 0, px = 0, py = 0, y = 0;
				List<List<RouteActionPoint>> actionArrows = routeGeometry.getActionArrows(actionPoints);

				for (List<RouteActionPoint> arrow : actionArrows) {
					int arrowColor = routeGeometry.getContrastArrowColor(arrow, customTurnArrowColor);

					for (int i = 0; i < arrow.size(); i++) {
						RouteActionPoint actionPoint = arrow.get(i);
						double lat = actionPoint.location.getLatitude();
						double lon = actionPoint.location.getLongitude();

						px = x;
						py = y;
						x = tb.getPixXFromLatLon(lat, lon);
						y = tb.getPixYFromLatLon(lat, lon);

						if (i == 0) {
							path.reset();
							path.moveTo(x, y);
						} else {
							path.lineTo(x, y);
						}
					}

					int styleTurnArrowColor = attrs.paint3.getColor();
					setTurnArrowPaintsColor(arrowColor);
					if (routeWidth != 0) {
						attrs.paint3.setStrokeWidth(routeWidth / 2);
					}
					canvas.drawPath(path, attrs.paint3);
					drawTurnArrow(canvas, matrix, x, y, px, py);
					setTurnArrowPaintsColor(styleTurnArrowColor);
				}
			} finally {
				canvas.rotate(tb.getRotate(), tb.getCenterPixelX(), tb.getCenterPixelY());
			}
		}
	}

	private void drawProjectionPoint(@NonNull Canvas canvas, @NonNull RotatedTileBox tileBox, @NonNull LatLon latLon) {
		if (projectionIcon == null) {
			helper.getSettings().getApplicationMode().getLocationIcon();
			projectionIcon = (LayerDrawable) AppCompatResources.getDrawable(getContext(), STATIC_DEFAULT.getIconId());
		}
		if (projectionIcon != null) {
			canvas.rotate(-tileBox.getRotate(), tileBox.getCenterPixelX(), tileBox.getCenterPixelY());
			int x = (int) tileBox.getPixXFromLatLon(latLon.getLatitude(), latLon.getLongitude());
			int y = (int) tileBox.getPixYFromLatLon(latLon.getLatitude(), latLon.getLongitude());

			QuadRect rect = calculateRect(x, y, projectionIcon.getIntrinsicWidth(), projectionIcon.getIntrinsicHeight());
			projectionIcon.setBounds((int) rect.left, (int) rect.top, (int) rect.right, (int) rect.bottom);
			projectionIcon.draw(canvas);
			canvas.rotate(tileBox.getRotate(), tileBox.getCenterPixelX(), tileBox.getCenterPixelY());
		}
	}

	public void drawLocations(@NonNull RotatedTileBox tileBox, @Nullable Canvas canvas,
			double topLatitude, double leftLongitude, double bottomLatitude,
			double rightLongitude) {
		if (helper.isPublicTransportMode()) {
			publicTransportRouteGeometry.baseOrder = getBaseOrder();
			int currentRoute = transportHelper.getCurrentRoute();
			if (publicTransportRouteGeometry.hasMapRenderer()) {
				renderState.updateTransportRouteState(currentRoute);
				if (renderState.shouldRebuildTransportRoute) {
					publicTransportRouteGeometry.resetSymbolProviders();
				}
			}
			List<TransportRouteResult> routes = transportHelper.getRoutes();
			TransportRouteResult route = routes != null && routes.size() > currentRoute ? routes.get(currentRoute) : null;
			routeGeometry.clearRoute();
			boolean routeUpdated = publicTransportRouteGeometry.updateRoute(tileBox, route);
			boolean draw = routeUpdated || renderState.shouldRebuildTransportRoute
					|| !publicTransportRouteGeometry.hasMapRenderer() || mapActivityInvalidated || mapRendererChanged;
			if (route != null && draw) {
				LatLon start = transportHelper.getStartLocation();
				Location startLocation = new Location("transport");
				startLocation.setLatitude(start.getLatitude());
				startLocation.setLongitude(start.getLongitude());
				publicTransportRouteGeometry.drawSegments(tileBox, canvas, topLatitude, leftLongitude, bottomLatitude, rightLongitude,
						startLocation, 0);
			}
		} else {
			routeGeometry.baseOrder = getBaseOrder();
			RouteCalculationResult route = helper.getRoute();
			boolean directTo = route.getRouteService() == RouteService.DIRECT_TO;
			boolean straight = route.getRouteService() == RouteService.STRAIGHT;
			publicTransportRouteGeometry.clearRoute();

			ColoringType actualColoringType = isColoringAvailable(routeColoringType, routeInfoAttribute) ?
					routeColoringType : ColoringType.DEFAULT;
			int routeLineColor = getRouteLineColor();
			float routeLineWidth = getRouteLineWidth(tileBox);
			ChizuRouteCandidates chooser = helper.getChizuCandidates();
			boolean chizuChoice = helper.isRoutePlanningMode() && chooser.hasChoice() && chooser.getSelectedIndex() >= 0;
			if (chizuChoice) {
				// the selected route wears its card's colour, so card and line match, and is the widest
				routeLineColor = chooser.colorOf(chooser.getSelectedIndex());
				actualColoringType = ColoringType.DEFAULT;
				routeLineWidth *= 1.2f;
			}
			drawChizuHalos(tileBox, canvas, topLatitude, leftLongitude, bottomLatitude, rightLongitude,
					helper.isRoutePlanningMode() && route.isCalculated(), chizuChoice ? chooser : null, route,
					routeLineWidth);
			drawChizuAlternatives(tileBox, canvas, topLatitude, leftLongitude, bottomLatitude, rightLongitude,
					chizuChoice ? chooser : null, routeLineWidth);
			drawChizuGlow(tileBox, canvas, topLatitude, leftLongitude, bottomLatitude, rightLongitude,
					chizuChoice ? route : null, routeLineWidth);
			chizuShimmerWidth = chizuChoice ? routeLineWidth : 0;
			boolean shouldShowDirectionArrows = shouldShowDirectionArrows();
			routeGeometry.setRouteStyleParams(routeLineColor, routeLineWidth, shouldShowDirectionArrows,
					getDirectionArrowsColor(), actualColoringType, routeInfoAttribute, routeGradientPalette);
			boolean routeUpdated = routeGeometry.updateRoute(tileBox, route);
			boolean shouldShowTurnArrows = shouldShowTurnArrows();

			Location lastProjection;
			int startLocationIndex;
			if (directTo) {
				lastProjection = null;
				startLocationIndex = 0;
			} else if (route.getCurrentStraightAngleRoute() > 0) {
				Location lastFixedLocation = helper.getLastFixedLocation();
				Location currentLocation = new Location(lastFixedLocation);
				MapRendererView mapRenderer = getMapRenderer();
				OsmandApplication app = getApplication();
				boolean useMapCenter = useMapCenter();
				if (mapRenderer != null) {
					if (useMapCenter) {
						PointI target31 = mapRenderer.getTarget();
						currentLocation.setLatitude(MapUtils.get31LatitudeY(target31.getY()));
						currentLocation.setLongitude(MapUtils.get31LongitudeX(target31.getX()));
					} else {
						LatLon lastMarkerLocation = app.getOsmandMap().getMapLayers().getLocationLayer().getLastMarkerLocation();
						if (lastMarkerLocation != null) {
							currentLocation.setLatitude(lastMarkerLocation.getLatitude());
							currentLocation.setLongitude(lastMarkerLocation.getLongitude());
						}
					}
				} else if (useMapCenter) {
					currentLocation.setLatitude(tileBox.getLatitude());
					currentLocation.setLongitude(tileBox.getLongitude());
				}
				List<Location> locations = route.getImmutableAllLocations();
				int currentRoute = route.getCurrentRouteForLocation(currentLocation);
				if (currentRoute > 0) {
					Location previousRouteLocation = locations.get(currentRoute - 1);
					Location currentRouteLocation = locations.get(currentRoute);
					lastProjection = RoutingHelperUtils.getProject(currentLocation, previousRouteLocation, currentRouteLocation);
					float calcbearing = !MapUtils.areLatLonEqual(previousRouteLocation, currentRouteLocation) ? previousRouteLocation.bearingTo(currentRouteLocation) :
							previousRouteLocation.bearingTo(currentLocation);
					lastProjection.setBearing(MapUtils.normalizeDegrees360(calcbearing));
					if (currentLocation.distanceTo(lastProjection) > helper.getMaxAllowedProjectDist(currentLocation)) {
						lastProjection = null;
					} else if (app.getSettings().SNAP_TO_ROAD.get() && currentRoute + 1 < locations.size()) {
						// Not needed here as this code for preview turns
//						Location nextRouteLocation = locations.get(currentRoute + 1);
//						RoutingHelperUtils.approximateBearingIfNeeded(helper, lastProjection, currentLocation,
//								previousRouteLocation, currentRouteLocation, nextRouteLocation, true);
					}
				} else {
					lastProjection = null;
				}
				startLocationIndex = currentRoute;
			} else {
				lastProjection = straight || routeUpdated ? helper.getLastFixedLocation() : helper.getLastProjection();
				startLocationIndex = route.getCurrentStraightAngleRoute();
			}
			lastRouteProjection = lastProjection;
			boolean draw = true;
			if (routeGeometry.hasMapRenderer()) {
				renderState.updateRouteState(lastProjection, startLocationIndex, actualColoringType, routeLineColor,
						routeLineWidth, route.getCurrentRoute(), tileBox.getZoom(), shouldShowTurnArrows, shouldShowDirectionArrows);
				draw = routeUpdated || renderState.shouldRebuildRoute || mapActivityInvalidated || mapRendererChanged;
				if (draw) {
					routeGeometry.resetSymbolProviders();
				} else {
					draw = renderState.shouldUpdateRoute;
				}
			}

			List<RouteActionPoint> actionPoints = null;
			boolean drawTurnArrows = !directTo && tileBox.getZoom() >= 14 && shouldShowTurnArrows;
			if (drawTurnArrows) {
				if (routeGeometry.hasMapRenderer()) {
					if (routeUpdated || renderState.shouldUpdateActionPoints || mapActivityInvalidated || mapRendererChanged) {
						actionPoints = calculateActionPoints(helper.getLastProjection(),
								route.getRouteLocations(), route.getCurrentRoute(), tileBox.getZoom());
					}
				} else if (canvas != null) {
					actionPoints = calculateActionPoints(topLatitude, leftLongitude,
							bottomLatitude, rightLongitude, helper.getLastProjection(),
							route.getRouteLocations(), route.getCurrentRoute(), tileBox.getZoom());
				}
			}

			if (draw) {
				routeGeometry.setForceIncludedPointIndexesFromActionPoints(actionPoints);
				routeGeometry.drawSegments(tileBox, canvas, topLatitude, leftLongitude, bottomLatitude, rightLongitude,
						lastProjection, startLocationIndex);
			}

			if (actionPoints != null) {
				if (routeGeometry.hasMapRenderer()) {
					routeGeometry.buildActionArrows(actionPoints, customTurnArrowColor);
				} else if (canvas != null) {
					drawActionArrows(tileBox, canvas, actionPoints);
				}
			} else if (!drawTurnArrows) {
				if (routeGeometry.hasMapRenderer()) {
					routeGeometry.resetActionLines();
				}
			}
			if (directTo) {
				LatLon latLon = GpxUtils.calculateProjectionOnRoute(helper, tileBox);
				MapRendererView mapRenderer = getMapRenderer();
				if (mapRenderer != null) {
					if (projectionPointCollection == null || mapActivityInvalidated || mapRendererChanged) {
						recreateProjectedPointCollection();
					}
					if (latLon != null) {
						setProjectedPointMarkerLocation(latLon);
					}
					setProjectedPointMarkerVisibility(latLon != null);
				} else if (latLon != null && canvas != null) {
					drawProjectionPoint(canvas, tileBox, latLon);
				}
			} else {
				removeProjectedPointCollection();
			}
		}
	}

	private List<RouteActionPoint> calculateActionPoints(Location lastProjection,
			List<Location> routeNodes, int cd, int zoom) {
		return calculateActionPoints(0, 0, 0, 0, lastProjection, routeNodes, cd, zoom);
	}

	private List<RouteActionPoint> calculateActionPoints(double topLatitude, double leftLongitude,
			double bottomLatitude,
			double rightLongitude, Location lastProjection, List<Location> routeNodes, int cd,
			int zoom) {
		Iterator<RouteDirectionInfo> it = helper.getRouteDirections().iterator();
		RouteDirectionInfo nf = null;

		int currentRoute = helper.getRoute().getCurrentRoute();

		double DISTANCE_ACTION = 35;
		if (zoom >= 17) {
			DISTANCE_ACTION = 15;
		} else if (zoom == 15) {
			DISTANCE_ACTION = 70;
		} else if (zoom < 15) {
			DISTANCE_ACTION = 110;
		}
		double actionDist = 0;
		Location previousAction = null;
		List<RouteActionPoint> actionPoints = new ArrayList<>();
		int prevFinishPoint = -1;
		for (int routePoint = 0; routePoint < routeNodes.size(); routePoint++) {
			Location loc = routeNodes.get(routePoint);
			if (RouteCalculationResult.FIRST_LAST_LOCATION_PROVIDER.equals(loc.getProvider())) {
				continue;
			}
			if (nf != null) {
				int pnt = nf.routeEndPointOffset == 0 ? nf.routePointOffset : nf.routeEndPointOffset;
				if (pnt < routePoint + cd) {
					nf = null;
				}
			}
			while (nf == null && it.hasNext()) {
				nf = it.next();
				int pnt = nf.routeEndPointOffset == 0 ? nf.routePointOffset : nf.routeEndPointOffset;
				if (pnt < routePoint + cd) {
					nf = null;
				}
			}
			boolean action = nf != null && (nf.routePointOffset == routePoint + cd ||
					(nf.routePointOffset <= routePoint + cd && routePoint + cd <= nf.routeEndPointOffset));
			if (!action && previousAction == null) {
				// no need to check
				continue;
			}
			boolean visible = (leftLongitude == rightLongitude) ||
					(leftLongitude <= loc.getLongitude()
							&& loc.getLongitude() <= rightLongitude
							&& bottomLatitude <= loc.getLatitude()
							&& loc.getLatitude() <= topLatitude);
			if (action && !visible && previousAction == null) {
				continue;
			}
			if (!action) {
				// previousAction != null
				float dist = loc.distanceTo(previousAction);
				actionDist += dist;
				if (actionDist >= DISTANCE_ACTION) {
					double normalizedOffset = 1 - (actionDist - DISTANCE_ACTION) / dist;
					Location projection = calculateProjection(normalizedOffset, previousAction, loc);
					actionPoints.add(new RouteActionPoint(projection, routePoint - 1 + currentRoute, normalizedOffset));
					actionPoints.add(null);
					prevFinishPoint = routePoint;
					previousAction = null;
					actionDist = 0;
				} else {
					actionPoints.add(new RouteActionPoint(loc, routePoint + currentRoute, 0.0f));
					previousAction = loc;
				}
			} else {
				// action point
				if (previousAction == null && lastProjection != null) {
					addPreviousToActionPoints(actionPoints, lastProjection, routeNodes, DISTANCE_ACTION,
							prevFinishPoint, routePoint, loc);
				}
				actionPoints.add(new RouteActionPoint(loc, routePoint + currentRoute, 0.0f));
				previousAction = loc;
				prevFinishPoint = -1;
				actionDist = 0;
			}
		}
		if (previousAction != null) {
			actionPoints.add(null);
		}
		return actionPoints;
	}


	private void addPreviousToActionPoints(List<RouteActionPoint> actionPoints,
			Location lastProjection,
			List<Location> routeNodes, double distanceAction,
			int prevFinishPoint, int routePoint, Location loc) {
		int currentRoute = helper.getRoute().getCurrentRoute();

		// put some points in front
		int ind = actionPoints.size();
		Location lprevious = loc;
		double dist = 0;
		for (int k = routePoint - 1; k >= -1; k--) {
			Location location = k == -1 ? lastProjection : routeNodes.get(k);
			int actionPointIndex = k == -1 ? -1 : k + currentRoute;
			float locDist = lprevious.distanceTo(location);
			dist += locDist;
			if (dist >= distanceAction) {
				if (locDist > 1) {
					double normalizedOffset = (dist - distanceAction) / locDist;
					Location projection = calculateProjection(1 - normalizedOffset, lprevious, location);
					actionPoints.add(ind, new RouteActionPoint(projection, actionPointIndex, normalizedOffset));
				}
				break;
			} else {
				actionPoints.add(ind, new RouteActionPoint(location, actionPointIndex, 0.0));
				lprevious = location;
			}
			if (prevFinishPoint == k) {
				if (ind >= 2) {
					actionPoints.remove(ind - 2);
					actionPoints.remove(ind - 2);
				}
				break;
			}
		}
	}

	private Location calculateProjection(double part, Location lp, Location l) {
		Location p = new Location(l);
		p.setLatitude(lp.getLatitude() + part * (l.getLatitude() - lp.getLatitude()));
		p.setLongitude(lp.getLongitude() + part * (l.getLongitude() - lp.getLongitude()));
		return p;
	}

	@Override
	public void onDraw(Canvas canvas, RotatedTileBox tileBox, DrawSettings settings) {
		drawChizuShimmer(canvas, tileBox);
	}

	/** The selected route's shimmer: a warm light band running along its glow, and sparkles twinkling on it. */
	private void drawChizuShimmer(@NonNull Canvas canvas, @NonNull RotatedTileBox tileBox) {
		ChizuRouteCandidates chooser = helper.getChizuCandidates();
		RouteCalculationResult route = helper.getRoute();
		float width = chizuShimmerWidth;
		ChizuTrafficPrefs.Shimmer prefs = ChizuTrafficPrefs.getShimmer(getContext());
		if (!prefs.on || view == null || view.isCarView() || !helper.isRoutePlanningMode() || !chooser.hasChoice()
				|| chooser.getSelectedIndex() < 0 || route == null || !route.isCalculated() || width <= 0) {
			chizuSparkles.clear();
			return;
		}
		List<Location> locations = route.getImmutableAllLocations();
		if (locations.size() < 2) {
			return;
		}
		MapRendererView mapRenderer = getMapRenderer();
		int step = Math.max(1, (locations.size() + CHIZU_SHIMMER_MAX_POINTS - 1) / CHIZU_SHIMMER_MAX_POINTS);
		int n = (locations.size() - 1) / step + 2;
		float[] xs = new float[n];
		float[] ys = new float[n];
		float[] cum = new float[n];
		int count = 0;
		for (int i = 0; ; i = Math.min(i + step, locations.size() - 1)) {
			Location l = locations.get(i);
			PointF p = NativeUtilities.getPixelFromLatLon(mapRenderer, tileBox, l.getLatitude(), l.getLongitude());
			xs[count] = p.x;
			ys[count] = p.y;
			cum[count] = count == 0 ? 0 : cum[count - 1] + (float) Math.hypot(p.x - xs[count - 1], p.y - ys[count - 1]);
			count++;
			if (i == locations.size() - 1) {
				break;
			}
		}
		float total = cum[count - 1];
		if (total < 1) {
			return;
		}
		float density = chizuDensity;
		float core = width + 2 * 3 * density;  // the route line with its black casing stays untouched
		float outer = width * 3.2f;
		long now = SystemClock.uptimeMillis();

		canvas.save();
		canvas.rotate(-tileBox.getRotate(), tileBox.getCenterPixelX(), tileBox.getCenterPixelY());

		// the band: a soft warm light, about an eighth of the route long, running from start to destination
		float bandLen = Math.max(total * prefs.lengthPercent / 100f, 120 * density);
		long loopMs = prefs.loopSeconds * 1000L;
		float phase = (now % loopMs) / (float) loopMs;
		float bright = prefs.bandPercent / 100f;
		float centre = phase * (total + bandLen) - bandLen / 2;
		if (chizuBandBlur != width * 0.5f) {
			chizuBandBlur = width * 0.5f;
			chizuBandPaint.setMaskFilter(chizuBandBlur > 0 ? new BlurMaskFilter(chizuBandBlur, BlurMaskFilter.Blur.NORMAL) : null);
		}
		chizuBandPaint.setStyle(Paint.Style.STROKE);
		chizuBandPaint.setStrokeCap(Paint.Cap.ROUND);
		chizuBandPaint.setStrokeJoin(Paint.Join.ROUND);
		chizuClearPaint.setStyle(Paint.Style.STROKE);
		chizuClearPaint.setStrokeCap(Paint.Cap.ROUND);
		chizuClearPaint.setStrokeJoin(Paint.Join.ROUND);
		chizuClearPaint.setStrokeWidth(core);
		chizuClearPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
		int layer = canvas.saveLayer(null, null);
		chizuBandPaint.setStrokeWidth(outer);
		chizuBandPaint.setColor(chizuAlpha(0x78, bright) | 0xFFE38A);
		drawChizuSubPath(canvas, xs, ys, cum, count, centre - bandLen / 2, centre + bandLen / 2, chizuBandPaint);
		chizuBandPaint.setStrokeWidth(outer * 0.8f);
		chizuBandPaint.setColor(chizuAlpha(0xB4, bright) | 0xFFF4C8);
		drawChizuSubPath(canvas, xs, ys, cum, count, centre - bandLen / 4, centre + bandLen / 4, chizuBandPaint);
		drawChizuSubPath(canvas, xs, ys, cum, count, centre - bandLen, centre + bandLen, chizuClearPaint);
		canvas.restoreToCount(layer);

		// the sparkles: four-pointed stars on the glow ring, each fading in and out at its own place
		float amount = prefs.sparklesPercent / 100f;
		int wanted = Math.round(amount * Math.min(CHIZU_SPARKLES_MAX, Math.max(6, total / (70 * density))));
		for (int i = chizuSparkles.size() - 1; i >= 0; i--) {
			float[] s = chizuSparkles.get(i);
			if (now - (long) s[3] > s[4] && now >= (long) s[3]) {
				chizuSparkles.remove(i);
			}
		}
		while (chizuSparkles.size() > wanted) {
			chizuSparkles.remove(chizuSparkles.size() - 1);
		}
		while (chizuSparkles.size() < wanted) {
			// {position along, side, distance into the ring, birth, life, size}
			chizuSparkles.add(new float[] {chizuRandom.nextFloat(), chizuRandom.nextBoolean() ? 1 : -1,
					chizuRandom.nextFloat(), now + chizuRandom.nextInt(900), 500 + chizuRandom.nextInt(700),
					0.6f + chizuRandom.nextFloat() * 0.6f});
		}
		chizuSparkPaint.setStyle(Paint.Style.STROKE);
		chizuSparkPaint.setStrokeCap(Paint.Cap.ROUND);
		float[] at = new float[4];
		for (float[] s : chizuSparkles) {
			float t = (now - s[3]) / s[4];
			if (t <= 0 || t >= 1) {
				continue;
			}
			float env = (float) Math.sin(Math.PI * t);
			chizuPointAt(xs, ys, cum, count, s[0] * total, at);
			float off = s[1] * (core / 2 + (outer / 2 - core / 2) * (0.2f + 0.7f * s[2]));
			float x = at[0] - at[3] * off;
			float y = at[1] + at[2] * off;
			float len = (4 + 9 * env) * density * s[5] * prefs.sparkleSizePercent / 100f;
			int alpha = (int) (255 * env);
			chizuSparkPaint.setStrokeWidth(1.6f * density);
			chizuSparkPaint.setColor((alpha << 24) | 0xFFF6D0);
			canvas.drawLine(x - len, y, x + len, y, chizuSparkPaint);
			canvas.drawLine(x, y - len, x, y + len, chizuSparkPaint);
			float d = len * 0.4f;
			chizuSparkPaint.setStrokeWidth(1.0f * density);
			canvas.drawLine(x - d, y - d, x + d, y + d, chizuSparkPaint);
			canvas.drawLine(x - d, y + d, x + d, y - d, chizuSparkPaint);
			chizuSparkPaint.setStyle(Paint.Style.FILL);
			chizuSparkPaint.setColor((alpha << 24) | 0xFFFFFF);
			canvas.drawCircle(x, y, 1.8f * density * env, chizuSparkPaint);
			chizuSparkPaint.setStyle(Paint.Style.STROKE);
		}
		canvas.restore();

		// next frame (about 30 per second), only while the choice is on screen
		if (mapRenderer != null) {
			View v = view.getView();
			if (v != null) {
				v.postInvalidateDelayed(33);
			}
		}
	}

	private static int chizuAlpha(int alpha, float factor) {
		return Math.min(255, Math.round(alpha * factor)) << 24;
	}

	/** The route between two lengths along it (screen pixels), as one stroke. */
	private void drawChizuSubPath(@NonNull Canvas canvas, float[] xs, float[] ys, float[] cum, int count,
	                              float from, float to, @NonNull Paint paint) {
		from = Math.max(0, from);
		to = Math.min(cum[count - 1], to);
		if (to <= from) {
			return;
		}
		float[] at = new float[4];
		chizuPointAt(xs, ys, cum, count, from, at);
		chizuBandPath.reset();
		chizuBandPath.moveTo(at[0], at[1]);
		for (int i = 0; i < count; i++) {
			if (cum[i] > from && cum[i] < to) {
				chizuBandPath.lineTo(xs[i], ys[i]);
			}
		}
		chizuPointAt(xs, ys, cum, count, to, at);
		chizuBandPath.lineTo(at[0], at[1]);
		canvas.drawPath(chizuBandPath, paint);
	}

	/** The point at a length along the route: x, y and the unit direction there. */
	private static void chizuPointAt(float[] xs, float[] ys, float[] cum, int count, float s, float[] out) {
		int lo = 0;
		int hi = count - 1;
		while (hi - lo > 1) {
			int mid = (lo + hi) >>> 1;
			if (cum[mid] <= s) {
				lo = mid;
			} else {
				hi = mid;
			}
		}
		float seg = cum[hi] - cum[lo];
		float f = seg > 0 ? Math.max(0, Math.min(1, (s - cum[lo]) / seg)) : 0;
		float dx = xs[hi] - xs[lo];
		float dy = ys[hi] - ys[lo];
		float len = (float) Math.hypot(dx, dy);
		out[0] = xs[lo] + f * dx;
		out[1] = ys[lo] + f * dy;
		out[2] = len > 0 ? dx / len : 1;
		out[3] = len > 0 ? dy / len : 0;
	}

	@Override
	public boolean drawInScreenPixels() {
		return false;
	}

	@Override
	public boolean onLongPressEvent(@NonNull PointF point, @NonNull RotatedTileBox tileBox) {
		return false;
	}

	@Override
	public boolean onSingleTap(@NonNull PointF point, @NonNull RotatedTileBox tileBox) {
		return selectChizuRouteAt(point, tileBox);
	}

	private int getRadiusPoi(RotatedTileBox tb) {
		double zoom = tb.getZoom();
		int r;
		if (zoom <= 15) {
			r = 8;
		} else if (zoom <= 16) {
			r = 10;
		} else if (zoom <= 17) {
			r = 14;
		} else {
			r = 18;
		}
		return (int) (r * tb.getDensity());
	}

	@Nullable
	private List<TransportStop> getRouteTransportStops() {
		return helper.isPublicTransportMode() ? publicTransportRouteGeometry.getDrawer().getRouteTransportStops() : null;
	}

	private void collectTransportStopsFromPoint(@NonNull MapSelectionResult result,
			@NonNull List<TransportStop> transportStops) {
		PointF point = result.getPoint();
		RotatedTileBox tileBox = result.getTileBox();
		MapRendererView mapRenderer = getMapRenderer();

		float radius = getRadiusPoi(tileBox) * TOUCH_RADIUS_MULTIPLIER;
		List<PointI> touchPolygon31 = null;
		if (mapRenderer != null) {
			touchPolygon31 = NativeUtilities.getPolygon31FromPixelAndRadius(mapRenderer, point, radius);
			if (touchPolygon31 == null) {
				return;
			}
		}

		try {
			for (int i = 0; i < transportStops.size(); i++) {
				TransportStop transportStop = transportStops.get(i);
				LatLon latLon = transportStop.getLocation();
				if (latLon == null) {
					continue;
				}

				boolean add = mapRenderer != null
						? NativeUtilities.isPointInsidePolygon(latLon, touchPolygon31)
						: tileBox.isLatLonNearPixel(latLon, point.x, point.y, radius);
				if (add) {
					result.collect(transportStop, this);
				}
			}
		} catch (IndexOutOfBoundsException e) {
			// ignore
		}
	}

	private boolean isColoringAvailable(@NonNull ColoringType routeColoringType,
	                                    @Nullable String routeInfoAttribute) {
		return coloringAvailabilityCache
				.isColoringAvailable(helper.getRoute(), routeColoringType, routeInfoAttribute);
	}

	@Override
	public void collectObjectsFromPoint(@NonNull MapSelectionResult result, @NonNull MapSelectionRules rules) {
		if (rules.isOnlyTouchableObjects()) {
			return;
		}

		List<TransportStop> routeTransportStops = getRouteTransportStops();
		if (!Algorithms.isEmpty(routeTransportStops)) {
			collectTransportStopsFromPoint(result, routeTransportStops);
		}
	}

	@Override
	public LatLon getObjectLocation(Object o) {
		if (o instanceof TransportStop) {
			return ((TransportStop) o).getLocation();
		}
		return null;
	}

	@Override
	public PointDescription getObjectName(Object o) {
		if (o instanceof TransportStop) {
			return new PointDescription(PointDescription.POINT_TYPE_TRANSPORT_STOP, getContext().getString(R.string.transport_Stop),
					((TransportStop) o).getName());
		}
		return null;
	}

	@Override
	public boolean disableSingleTap() {
		return isPreviewRouteLineVisible();
	}

	@Override
	public boolean disableLongPressOnMap(PointF point, RotatedTileBox tileBox) {
		return isPreviewRouteLineVisible();
	}

	@Override
	protected void cleanupResources() {
		super.cleanupResources();
		resetLayer();
	}

	private void drawChizuAlternatives(@NonNull RotatedTileBox tileBox, @Nullable Canvas canvas,
	                                   double top, double left, double bottom, double right,
	                                   @Nullable ChizuRouteCandidates chooser, float mainWidth) {
		List<RouteCalculationResult> wanted = new ArrayList<>();
		List<Integer> colors = new ArrayList<>();
		if (chooser != null) {
			List<ChizuRouteCandidates.Candidate> list = chooser.getCandidates();
			for (int i = 0; i < list.size(); i++) {
				if (i != chooser.getSelectedIndex()) {
					wanted.add(list.get(i).route);
					// not selected: in its own colour at full strength; the selected one wears the gold glow
					colors.add(list.get(i).color | 0xFF000000);
				}
			}
		}
		// larger order = lower in OpenGL: well under the selected route's lines
		chizuAlternatives.draw(tileBox, canvas, top, left, bottom, right, wanted, colors, mainWidth * 0.75f,
				getBaseOrder() + 5000);
	}

	private void drawChizuGlow(@NonNull RotatedTileBox tileBox, @Nullable Canvas canvas,
	                           double top, double left, double bottom, double right,
	                           @Nullable RouteCalculationResult selected, float mainWidth) {
		List<RouteCalculationResult> wanted = new ArrayList<>();
		List<Integer> outer = new ArrayList<>();
		List<Integer> inner = new ArrayList<>();
		if (selected != null) {
			wanted.add(selected);
			outer.add(CHIZU_GLOW_OUTER);
			inner.add(CHIZU_GLOW_INNER);
		}
		// above the other routes (+5000 and lower), under the selected route's own line and casing
		chizuGlowOuter.draw(tileBox, canvas, top, left, bottom, right, wanted, outer, mainWidth * 3.2f,
				getBaseOrder() + 3000);
		chizuGlowInner.draw(tileBox, canvas, top, left, bottom, right, wanted, inner, mainWidth * 2.0f,
				getBaseOrder() + 2000);
	}

	private void drawChizuHalos(@NonNull RotatedTileBox tileBox, @Nullable Canvas canvas,
	                            double top, double left, double bottom, double right, boolean show,
	                            @Nullable ChizuRouteCandidates chooser, @NonNull RouteCalculationResult route,
	                            float mainWidth) {
		List<RouteCalculationResult> wanted = new ArrayList<>();
		List<Integer> colors = new ArrayList<>();
		if (show) {
			wanted.add(route);
			if (chooser != null) {
				for (ChizuRouteCandidates.Candidate c : chooser.getCandidates()) {
					if (c.route != route) {
						wanted.add(c.route);
					}
				}
			}
			for (int i = 0; i < wanted.size(); i++) {
				colors.add(CHIZU_HALO_COLOR);
			}
		}
		// under every route line and casing
		chizuHalos.draw(tileBox, canvas, top, left, bottom, right, wanted, colors, mainWidth * 3.5f,
				getBaseOrder() + 30000);
	}

	/** Extra route lines of the traffic chooser, each its own geometry way (own OpenGL line collection). */
	private class ChizuWayPool {
		private final List<RouteGeometryWay> ways = new ArrayList<>();
		private final List<RouteCalculationResult> routes = new ArrayList<>();
		private final List<Integer> colors = new ArrayList<>();
		private final List<Float> widths = new ArrayList<>();
		private final int casingColor;
		private final float casingWidth;

		ChizuWayPool(int casingColor, float casingWidth) {
			this.casingColor = casingColor;
			this.casingWidth = casingWidth;
		}

		void draw(@NonNull RotatedTileBox tileBox, @Nullable Canvas canvas,
		          double top, double left, double bottom, double right,
		          @NonNull List<RouteCalculationResult> wanted, @NonNull List<Integer> wantedColors,
		          float width, int baseOrder) {
			while (ways.size() < wanted.size()) {
				RouteGeometryWayContext context = new RouteGeometryWayContext(getContext(), chizuDensity);
				context.updatePaints(nightMode, attrs, attrsW);
				RouteGeometryWay way = new RouteGeometryWay(context);
				way.getDrawer().setChizuCasing(casingColor, casingWidth);
				ways.add(way);
				routes.add(null);
				colors.add(0);
				widths.add(0f);
			}
			for (int k = 0; k < ways.size(); k++) {
				RouteGeometryWay way = ways.get(k);
				RouteCalculationResult route = k < wanted.size() ? wanted.get(k) : null;
				int color = k < wanted.size() ? wantedColors.get(k) : 0;
				boolean changed = route != routes.get(k) || color != colors.get(k)
						|| Math.abs(width - widths.get(k)) > 0.5f;
				routes.set(k, route);
				colors.set(k, color);
				widths.set(k, width);
				if (route == null) {
					if (changed) {
						clear(way);
					}
					continue;
				}
				way.baseOrder = baseOrder + k * 1000;
				// the palette must stay non-null: the way compares it with equals() on the next frame
				way.setRouteStyleParams(color, width, false, null, ColoringType.DEFAULT, null, routeGradientPalette);
				boolean updated = way.updateRoute(tileBox, route);
				boolean draw = !way.hasMapRenderer() || changed || updated || mapActivityInvalidated || mapRendererChanged;
				if (draw) {
					if (way.hasMapRenderer()) {
						way.resetSymbolProviders();
					}
					way.drawSegments(tileBox, canvas, top, left, bottom, right, null, 0);
				}
			}
		}

		void reset() {
			for (int k = 0; k < ways.size(); k++) {
				clear(ways.get(k));
				routes.set(k, null);
			}
		}

		private void clear(@NonNull RouteGeometryWay way) {
			way.clearRoute();
			if (way.hasMapRenderer()) {
				way.resetSymbolProviders();
			}
		}
	}

	/** Tapping a route of the traffic chooser selects it; true when the tap was taken. */
	private boolean selectChizuRouteAt(@NonNull PointF point, @NonNull RotatedTileBox tileBox) {
		ChizuRouteCandidates chooser = helper.getChizuCandidates();
		if (!helper.isRoutePlanningMode() || !chooser.hasChoice()) {
			return false;
		}
		MapRendererView mapRenderer = getMapRenderer();
		QuadRect bounds = tileBox.getLatLonBounds();
		float best = 24 * tileBox.getDensity();
		int bestIndex = -1;
		List<ChizuRouteCandidates.Candidate> list = chooser.getCandidates();
		for (int i = 0; i < list.size(); i++) {
			PointF prev = null;
			for (Location l : list.get(i).route.getImmutableAllLocations()) {
				boolean inside = l.getLatitude() <= bounds.top + 0.01 && l.getLatitude() >= bounds.bottom - 0.01
						&& l.getLongitude() >= bounds.left - 0.01 && l.getLongitude() <= bounds.right + 0.01;
				if (!inside) {
					prev = null;
					continue;
				}
				PointF p = NativeUtilities.getPixelFromLatLon(mapRenderer, tileBox, l.getLatitude(), l.getLongitude());
				float d = prev == null ? (float) Math.hypot(p.x - point.x, p.y - point.y)
						: distanceToSegment(point, prev, p);
				// the selected route wins ties: it is drawn on top
				if (d < best || (d == best && i == chooser.getSelectedIndex())) {
					best = d;
					bestIndex = i;
				}
				prev = p;
			}
		}
		if (bestIndex < 0) {
			return false;
		}
		chooser.select(bestIndex);
		return true;
	}

	private static float distanceToSegment(@NonNull PointF p, @NonNull PointF a, @NonNull PointF b) {
		float dx = b.x - a.x;
		float dy = b.y - a.y;
		float len2 = dx * dx + dy * dy;
		float t = len2 == 0 ? 0 : Math.max(0, Math.min(1, ((p.x - a.x) * dx + (p.y - a.y) * dy) / len2));
		return (float) Math.hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy));
	}

	private void resetLayer() {
		clearXAxisPoints();
		if (chizuAlternatives != null) {
			chizuAlternatives.reset();
			chizuHalos.reset();
			chizuGlowInner.reset();
			chizuGlowOuter.reset();
		}
		if (routeGeometry != null && routeGeometry.hasMapRenderer()) {
			routeGeometry.resetSymbolProviders();
		}
		if (publicTransportRouteGeometry != null && publicTransportRouteGeometry.hasMapRenderer()) {
			publicTransportRouteGeometry.resetSymbolProviders();
		}
		lastRouteProjection = null;
	}

	public void resetColorAvailabilityCache() {
		coloringAvailabilityCache.resetCache();
	}
}