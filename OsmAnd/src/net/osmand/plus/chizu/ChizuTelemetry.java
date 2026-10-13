package net.osmand.plus.chizu;

/**
 * shiroikuma fork: the one switch for what upstream would send to osmand.net on its own — the
 * crash-report upload and its prompt, and the usage analytics upload and its opt-in prompt.
 * Crash logs and the local analytics database stay on the device.
 */
public final class ChizuTelemetry {

	public static final boolean DISABLED = true;

	private ChizuTelemetry() {
	}
}
