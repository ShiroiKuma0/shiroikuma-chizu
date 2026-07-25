package net.osmand.plus.chizu;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;

/**
 * shiroikuma fork: the automation gate for the 保存復元 state-export contract.
 *
 * A sister app (白い熊 自由作業盤) fires a token-gated broadcast at this app to have it
 * export itself headlessly. Nothing is reachable until 白い熊 turns the switch on in
 * 白い熊 地図 UI → Export / Import, and every request carries the token.
 *
 * Switch and token live in the device-local {@link ChizuBackup#PREFS_NAME} prefs file,
 * which is never part of any export — the token must never travel in a backup.
 */
public class ChizuAutomation {

	private static final String KEY_ENABLED = "automation_enabled";
	private static final String KEY_TOKEN = "automation_token";

	private static final int TOKEN_BYTES = 24;

	private ChizuAutomation() {
	}

	public static boolean isEnabled(@NonNull Context context) {
		return ChizuBackup.prefs(context).getBoolean(KEY_ENABLED, false);
	}

	public static void setEnabled(@NonNull Context context, boolean enabled) {
		ChizuBackup.prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply();
	}

	/** The token, generated on first read so the settings row always shows a value. */
	@NonNull
	public static synchronized String getToken(@NonNull Context context) {
		SharedPreferences prefs = ChizuBackup.prefs(context);
		String token = prefs.getString(KEY_TOKEN, null);
		if (token == null || token.isEmpty()) {
			token = generate();
			prefs.edit().putString(KEY_TOKEN, token).apply();
		}
		return token;
	}

	@NonNull
	public static synchronized String regenerate(@NonNull Context context) {
		String token = generate();
		ChizuBackup.prefs(context).edit().putString(KEY_TOKEN, token).apply();
		return token;
	}

	/** Constant-time comparison — never leak the token through timing. */
	public static boolean matches(@NonNull Context context, @Nullable String candidate) {
		if (candidate == null || candidate.isEmpty()) {
			return false;
		}
		String stored = getToken(context);
		return MessageDigest.isEqual(candidate.getBytes(), stored.getBytes());
	}

	/** {@code 80922d8c…4c49a87c} — what the settings row shows. */
	@NonNull
	public static String abbreviate(@NonNull String token) {
		if (token.length() <= 20) {
			return token;
		}
		return token.substring(0, 8) + "…" + token.substring(token.length() - 8);
	}

	@NonNull
	private static String generate() {
		byte[] bytes = new byte[TOKEN_BYTES];
		new SecureRandom().nextBytes(bytes);
		StringBuilder builder = new StringBuilder(bytes.length * 2);
		for (byte value : bytes) {
			builder.append(String.format(Locale.US, "%02x", value));
		}
		return builder.toString();
	}
}
