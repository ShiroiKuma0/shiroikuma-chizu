package net.osmand.plus.routing;

import androidx.annotation.NonNull;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 白い熊 地図: the traffic bans of the route 白い熊 chose (jammed road ids). Every route calculation
 * that does not carry its own candidate bans applies these, so an off-route recalculation keeps
 * avoiding the same jams. Held in memory only: never written to settings, gone on restart, and
 * cleared when the route is finished, cancelled, or planned anew.
 */
public final class ChizuTrafficBans {

	private static volatile Set<Long> active = Collections.emptySet();

	private ChizuTrafficBans() {
	}

	@NonNull
	public static Set<Long> getActive() {
		return active;
	}

	public static void setActive(@NonNull Set<Long> ids) {
		active = Collections.unmodifiableSet(new HashSet<>(ids));
	}

	public static void clear() {
		active = Collections.emptySet();
	}
}
