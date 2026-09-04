package net.osmand.plus.chizu;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;

/**
 * shiroikuma fork: the automation gate for the 保存復元 contract — v2 of the sister-app
 * contract, where the switch ships ON and the token is opt-in.
 *
 * <h3>Why the defaults inverted</h3>
 *
 * v1 shipped this app closed: the switch defaulted to false and every request had to carry a
 * 48-character secret 白い熊 had pasted from here into the caller. That is the wrong shape for
 * where the family went. <b>A pasted secret cannot survive a wipe</b>, and the case the contract
 * now exists to serve is 白い熊 応用管理 restoring apps <i>and their data</i> onto a clean phone,
 * where nothing has been configured and nobody has pasted anything. A gate that only works once
 * the phone is already set up is no gate for setting the phone up.
 *
 * <p>So {@code automation_enabled} defaults <b>true</b> and {@code automation_require_token}
 * defaults <b>false</b>. The switch stays rather than being deleted because it is the only way to
 * close this app off again, and a feature that can be turned on but never off is one 白い熊 cannot
 * retreat from.
 *
 * <h3>Idempotent about the token — required, not a nicety</h3>
 *
 * <b>A token handed to an app that does not require one is IGNORED, never an error.</b> Tokens
 * live in task arguments and workspace variables that outlive the setting they were pasted for,
 * so a caller still sending one — configured last year, or because another app on the batch does
 * want one — must be served. Refusing it would turn "白い熊 turned a switch off" into "half the
 * batch mysteriously fails".
 *
 * <p>The unauthenticated half of the surface is deliberate: the broadcast receivers only ever
 * write where they were told to and report what they did. Everything that moves data through a
 * caller-supplied descriptor lives behind {@link ChizuAutomationProvider}, which knows who is
 * calling — see {@link ChizuAutomationCallers}.
 *
 * <p>Switch, flag and token live in the device-local {@link ChizuBackup#PREFS_NAME} prefs file,
 * which is never part of any export — the token must never travel in a backup.
 */
public class ChizuAutomation {

	private static final String KEY_ENABLED = "automation_enabled";
	private static final String KEY_REQUIRE_TOKEN = "automation_require_token";
	private static final String KEY_TOKEN = "automation_token";

	private static final int TOKEN_BYTES = 24;

	private ChizuAutomation() {
	}

	/** Default ON — see the class comment; a clean phone has nobody to turn it on. */
	public static boolean isEnabled(@NonNull Context context) {
		return ChizuBackup.prefs(context).getBoolean(KEY_ENABLED, true);
	}

	/**
	 * <b>{@code commit()}, never {@code apply()} — this gate fails OPEN.</b>
	 *
	 * <p>v2 defaults the switch to true, so a write lost to a kill does not fall back to "off": it
	 * falls back to <b>ON</b>. And 応用管理 force-stops this app with a SIGKILL the instant an import
	 * succeeds, which gives no shutdown hook for a queued {@code apply()} to run in. Turning this
	 * app off is the one action 白い熊 has to shut a sister app out, so it must be on disk before
	 * the call returns.
	 */
	public static void setEnabled(@NonNull Context context, boolean enabled) {
		//noinspection ApplySharedPref
		ChizuBackup.prefs(context).edit().putBoolean(KEY_ENABLED, enabled).commit();
	}

	/** Default OFF — the token is an extra a caller may be asked for, not the gate. */
	public static boolean isTokenRequired(@NonNull Context context) {
		return ChizuBackup.prefs(context).getBoolean(KEY_REQUIRE_TOKEN, false);
	}

	/** {@code commit()} for the same reason as {@link #setEnabled} — this one fails open too. */
	public static void setTokenRequired(@NonNull Context context, boolean required) {
		//noinspection ApplySharedPref
		ChizuBackup.prefs(context).edit().putBoolean(KEY_REQUIRE_TOKEN, required).commit();
	}

	/**
	 * The whole gate, in one function.
	 *
	 * <p>Two checks written out at each entry point is how "disabled" and "bad token" drift apart
	 * across a family of apps — so every action, on the receivers and on the provider alike, ends
	 * here instead.
	 *
	 * @return null to proceed, otherwise the exact {@code ERROR:} line to answer with. The two
	 *         refusals stay distinct because they debug differently.
	 */
	@Nullable
	public static String refuse(@NonNull Context context, @Nullable String candidate) {
		if (!isEnabled(context)) {
			return "ERROR:automation disabled";
		}
		// A token sent to an app that does not want one is ignored here, never refused.
		if (isTokenRequired(context) && !matches(context, candidate)) {
			return "ERROR:bad token";
		}
		return null;
	}

	/** The token, generated on first read so the settings row always shows a value. */
	@NonNull
	public static synchronized String getToken(@NonNull Context context) {
		SharedPreferences prefs = ChizuBackup.prefs(context);
		String token = prefs.getString(KEY_TOKEN, null);
		if (token == null || token.isEmpty()) {
			token = generate();
			//noinspection ApplySharedPref
			prefs.edit().putString(KEY_TOKEN, token).commit();
		}
		return token;
	}

	@NonNull
	public static synchronized String regenerate(@NonNull Context context) {
		String token = generate();
		// the token 白い熊 is about to copy must be the token on disk, kill or no kill
		//noinspection ApplySharedPref
		ChizuBackup.prefs(context).edit().putString(KEY_TOKEN, token).commit();
		return token;
	}

	/**
	 * Constant-time comparison — never leak the token through timing. Kept for the case where
	 * the token <i>is</i> required; {@link #refuse} is the only caller that decides that.
	 */
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
