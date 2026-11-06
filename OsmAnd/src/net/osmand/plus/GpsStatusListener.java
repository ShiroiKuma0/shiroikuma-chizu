package net.osmand.plus;

import android.location.GnssStatus;

import androidx.annotation.NonNull;

import net.osmand.plus.OsmAndLocationProvider.GPSInfo;

public class GpsStatusListener extends GnssStatus.Callback {

	private final GPSInfo gpsInfo;

	public GpsStatusListener(@NonNull GPSInfo gpsInfo) {
		this.gpsInfo = gpsInfo;
	}

	@Override
	public void onStarted() {
		net.osmand.plus.chizu.ChizuGnssLog.onStarted(); // 白い熊 地図 raw satellite log
	}

	@Override
	public void onStopped() {
		net.osmand.plus.chizu.ChizuGnssLog.onStopped(); // 白い熊 地図 raw satellite log
		gpsInfo.reset();
	}

	@Override
	public void onFirstFix(int ttffMillis) {
		net.osmand.plus.chizu.ChizuGnssLog.onFirstFix(ttffMillis); // 白い熊 地図 raw satellite log
	}

	@Override
	public void onSatelliteStatusChanged(@NonNull GnssStatus status) {
		boolean fixed = false;
		int u = 0;
		int satCount = status.getSatelliteCount();
		for (int i = 0; i < satCount; i++) {
			if (status.usedInFix(i)) {
				u++;
				fixed = true;
			}
		}
		gpsInfo.fixed = fixed;
		gpsInfo.foundSatellites = satCount;
		gpsInfo.usedSatellites = u;
		net.osmand.plus.chizu.ChizuGnssLog.onStatus(status); // 白い熊 地図 raw satellite log
	}
}