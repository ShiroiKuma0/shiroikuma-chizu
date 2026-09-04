package net.osmand.plus.chizu;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Binder;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * shiroikuma fork: who is allowed through the data door, and how that is decided.
 *
 * <p>Ported from 自由作業盤's {@code core/automation/AutomationCallers.kt}, which is deliberately
 * app-independent — the pins below are the family's, not this app's.
 *
 * <h3>Why not a token</h3>
 *
 * The token this replaces was a 48-character secret 白い熊 pasted from one app's settings into
 * another's. It cannot survive a wipe, which is fatal for the case the family now exists to serve:
 * 応用管理 restoring apps and their data onto a clean phone, where nothing is configured yet.
 *
 * <h3>Why not a {@code shiroikuma.*} prefix</h3>
 *
 * Because that is not an identity. What makes {@code getCallingPackage()} worth anything is that a
 * package name <b>cannot be taken while the real package is installed</b> — package names are not
 * a namespace anyone owns, so any sideloaded app may call itself {@code shiroikuma.evil} and pass
 * a prefix test. Since the caller supplies the descriptor an export is written into, a prefix check
 * would hand such an app the complete data of every sister app in turn: strictly weaker than the
 * token it replaces.
 *
 * <h3>What is actually checked, in order</h3>
 *
 * <ol>
 * <li><b>An exact name</b> from {@link #CALLERS}.</li>
 * <li><b>The uid agrees.</b> {@code getCallingPackage()} reflects the caller's <i>declared</i>
 *     attribution, and packages sharing a uid are not distinguished by it, so it is confirmed
 *     against the uid the kernel reports — which cannot be borrowed.</li>
 * <li><b>The signing certificate matches a pinned hash.</b> This closes the real gap:
 *     <i>whichever caller package is absent from the device is a name anyone can take</i>, and the
 *     clean-phone case this contract exists for is precisely a device where not everything is
 *     installed yet. The moment the assumption is weakest is the moment it is most needed.</li>
 * </ol>
 *
 * <p>Re-derive a pin rather than trusting it:
 * <pre>apksigner verify --print-certs &lt;that app's signed release APK&gt; | grep 'SHA-256 digest'</pre>
 * Every app in the family has its own keystore, so there is no shared signing key to compare
 * against and each caller must be pinned by name — which is also why a {@code signature}-level
 * permission was never an option. If a caller's key is rotated its calls stop working and the fix
 * is these constants: a signing key changing unnoticed is exactly what a pin is for.
 */
public class ChizuAutomationCallers {

	/**
	 * The apps allowed to drive this one's data door. 応用管理 backs up and restores; 自由作業盤
	 * runs the 保存復元 batch. An entry added here is a deliberate act.
	 */
	private static final Map<String, String> CALLERS = new HashMap<>();

	static {
		CALLERS.put("shiroikuma.oyokanri",
				"9c585f4d118cb97ff653f949a8872875548403b9083ce6b9baa2e8f0c55ac6cc");
		CALLERS.put("shiroikuma.jiyusagyoban",
				"efd0d352192651593a92288ecdc64fc87262ec8648c24ed8f51a5587d46ac602");
	}

	private ChizuAutomationCallers() {
	}

	/**
	 * @param declared what the framework says the caller is — never what the caller told us.
	 * @return null when the caller may proceed, otherwise the {@code ERROR:} line to answer with.
	 *         A refusal says which of the three checks failed: each is a different mistake with a
	 *         different fix, and 白い熊 reads them verbatim on the far side of an IPC boundary.
	 */
	@Nullable
	public static String verify(@NonNull Context context, @Nullable String declared) {
		if (declared == null || declared.isEmpty()) {
			return "ERROR:caller unknown";
		}
		String pin = CALLERS.get(declared);
		if (pin == null) {
			return "ERROR:caller not permitted: " + declared;
		}
		// The kernel's answer, not the caller's: a package may declare an attribution it does not
		// own, but the uid cannot be borrowed.
		if (!uidOwns(context, declared)) {
			return "ERROR:caller uid mismatch: " + declared;
		}
		String signature = signingSha256(context, declared);
		if (signature == null) {
			return "ERROR:caller signature unreadable: " + declared;
		}
		// Constant-time, like the token compare it replaces. The value is a public hash, but the
		// habit is worth keeping and costs nothing.
		if (!MessageDigest.isEqual(signature.getBytes(), pin.getBytes())) {
			return "ERROR:caller signature mismatch: " + declared;
		}
		return null;
	}

	private static boolean uidOwns(@NonNull Context context, @NonNull String declared) {
		String[] real;
		try {
			real = context.getPackageManager().getPackagesForUid(Binder.getCallingUid());
		} catch (Exception e) {
			return false;
		}
		if (real == null) {
			return false;
		}
		for (String name : real) {
			if (declared.equals(name)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The SHA-256 of the caller's current signing certificate, lower-case hex.
	 *
	 * <p>{@code signingInfo} rather than the deprecated {@code signatures}: a rotated key reports
	 * its whole history and we want the certificate actually in force. Both branches exist because
	 * this app's minSdk is 24 — on a 24–27 device {@code GET_SIGNING_CERTIFICATES} is accepted and
	 * {@code signingInfo} comes back null, so without the fallback the door would refuse every
	 * caller: a total failure that never appears on 白い熊's phone and would only surface on an
	 * older one. Before key rotation existed, {@code signatures} <i>was</i> the signing certificate.
	 *
	 * <p>Exactly one signer, or we decline to guess — every app in this family has one key and has
	 * never rotated it, so "several signers, one of which matches" is not a question this needs to
	 * answer.
	 */
	@Nullable
	private static String signingSha256(@NonNull Context context, @NonNull String pkg) {
		try {
			PackageManager pm = context.getPackageManager();
			Signature[] certs;
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
				SigningInfo info =
						pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo;
				certs = info != null ? info.getApkContentsSigners() : null;
			} else {
				//noinspection deprecation
				certs = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures;
			}
			if (certs == null || certs.length != 1) {
				return null;
			}
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(certs[0].toByteArray());
			StringBuilder builder = new StringBuilder(digest.length * 2);
			for (byte value : digest) {
				builder.append(String.format(Locale.US, "%02x", value));
			}
			return builder.toString();
		} catch (Exception e) {
			return null;
		}
	}
}
