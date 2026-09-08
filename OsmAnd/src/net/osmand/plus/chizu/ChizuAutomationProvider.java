package net.osmand.plus.chizu;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.plus.OsmandApplication;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * shiroikuma fork: the data door — export this app's own state, and put it back, for a caller we
 * can identify. §2a of the 保存復元 contract.
 *
 * <h3>Why a provider and not another action on the receiver next door</h3>
 *
 * <b>A broadcast cannot tell you who sent it.</b> v1's answer to that was the shared secret; take
 * the secret away and a receiver has no idea who is asking — and since the caller supplies the
 * destination, "no idea who is asking" would mean any app on the phone can harvest this one's data.
 * A provider gets the caller's identity from the framework: see {@link ChizuAutomationCallers}.
 *
 * <p><b>And a list needs a synchronous answer.</b> 応用管理 draws a row per installed app before any
 * export exists; a broadcast round trip per app to fill a list is the wrong shape.
 *
 * <h3>What does NOT happen here</h3>
 *
 * The payload. {@code call()} validates, starts a foreground service and returns — tens of
 * megabytes over minutes inside a binder call would block the caller, report no progress, refuse
 * cancellation, and die silently if this process were killed. The bytes go through a descriptor the
 * caller opened ({@link ChizuBackup.FdDest}) and the terminal answer comes back on the broadcast
 * the family already proved on EMUI.
 *
 * <p><b>{@code import} exists only here.</b> It never gets a broadcast action: an import overwrites
 * this app's data, and the automation receivers are exported without a permission, so an import
 * there would let any app on the phone wipe 地図.
 *
 * <pre>
 * describe            → OK:{"app_id":…,"format":1,"contains":[…]}
 * export   fd [items] → OK:&lt;job_id&gt;, then OK:&lt;bytes&gt;|&lt;human&gt;|&lt;n&gt; categories by broadcast
 * import   fd         → OK:&lt;job_id&gt;, then OK:&lt;n&gt; restored by broadcast
 * cancel   job_id     → OK:cancelled
 * </pre>
 */
public class ChizuAutomationProvider extends ContentProvider {

	private static final String TAG = "ChizuAutomation";

	static final String METHOD_DESCRIBE = "describe";
	static final String METHOD_EXPORT = "export";
	static final String METHOD_IMPORT = "import";
	static final String METHOD_CANCEL = "cancel";

	static final String KEY_RESULT = "result";
	static final String KEY_FD = "fd";
	static final String KEY_TOKEN = "token";
	static final String KEY_JOB_ID = "job_id";
	static final String KEY_ITEMS = "items";
	static final String KEY_REPLY_ACTION = "reply_action";
	static final String KEY_REPLY_PACKAGE = "reply_package";
	static final String KEY_PROGRESS_ACTION = "progress_action";

	/** This app's archive format. Bumped when an older build could no longer read what we write. */
	private static final int FORMAT = 1;

	/**
	 * The oldest archive this build can still read.
	 *
	 * <p>Version skew has a direction: old data into a newer app is normally fine, because an app
	 * migrates its own storage; newer data into an older app is not. This is what lets a caller
	 * refuse the second case at discovery time, before anything is streamed.
	 */
	private static final int MIN_FORMAT_READABLE = 1;

	@Override
	public boolean onCreate() {
		return true;
	}

	/**
	 * Every method answers a {@link Bundle} with {@link #KEY_RESULT} — {@code OK…} or
	 * {@code ERROR:…}, the same vocabulary the broadcast contract uses, so a caller has one grammar
	 * to parse rather than two.
	 *
	 * <p><b>A refusal is returned, never thrown.</b> An exception across a binder reaches the caller
	 * as a {@code RuntimeException} carrying our stack trace, which tells 白い熊 nothing and tells a
	 * misbehaving caller rather more than it should.
	 */
	@Nullable
	@Override
	public Bundle call(@NonNull String method, @Nullable String arg, @Nullable Bundle extras) {
		Context context = getContext();
		if (context == null) {
			return fail("ERROR:not ready");
		}
		OsmandApplication app = (OsmandApplication) context.getApplicationContext();

		// WHO, before WHAT: a caller we cannot identify gets the same answer whatever it asked for.
		// Both checks read the binder transaction, so they must happen on this thread.
		String stranger = ChizuAutomationCallers.verify(app, getCallingPackage());
		if (stranger != null) {
			Log.w(TAG, "data door refused: " + stranger);
			return fail(stranger);
		}
		// Then this app's own switches — a token is ignored unless this app asks for one.
		String closed = ChizuAutomation.refuse(app, extras != null ? extras.getString(KEY_TOKEN) : null);
		if (closed != null) {
			return fail(closed);
		}

		switch (method) {
			case METHOD_DESCRIBE:
				return ok(describe(app));
			case METHOD_EXPORT:
				return start(app, extras, false);
			case METHOD_IMPORT:
				return start(app, extras, true);
			case METHOD_CANCEL:
				ChizuAutomationJobs.cancel(extras != null ? extras.getString(KEY_JOB_ID) : null);
				return ok("OK:cancelled");
			default:
				return fail("ERROR:unknown method: " + method);
		}
	}

	/**
	 * What this app would export, answered without exporting anything.
	 *
	 * <p>Returned from the call rather than written into the archive, deliberately: 応用管理 must
	 * draw a row <b>before an export exists</b>, and at restore must judge compatibility before
	 * streaming tens of megabytes into an app that would reject them — which it cannot do if the
	 * header is buried inside an encrypted archive.
	 *
	 * <p>{@code requires_launch_first} is false: a provider call starts this process, and the data
	 * service waits for the app to finish initializing before it collects anything, so a freshly
	 * installed 地図 can be restored without being opened first.
	 */
	@NonNull
	private String describe(@NonNull OsmandApplication app) {
		try {
			JSONObject json = new JSONObject();
			json.put("app_id", app.getPackageName());
			PackageInfo info = app.getPackageManager().getPackageInfo(app.getPackageName(), 0);
			//noinspection deprecation
			json.put("version_code", info.versionCode);
			json.put("version_name", info.versionName != null ? info.versionName : "");
			json.put("format", FORMAT);
			json.put("min_format_readable", MIN_FORMAT_READABLE);
			json.put("requires_launch_first", false);
			json.put("contains", new JSONArray(contains(app)));
			return "OK:" + json.toString();
		} catch (Exception e) {
			Log.e(TAG, "describe failed", e);
			return "ERROR:" + e.getClass().getSimpleName();
		}
	}

	/**
	 * The group labels a default export would carry — short human strings 応用管理 renders verbatim,
	 * so this app describes itself rather than being described.
	 *
	 * <p>Groups whose every part starts unticked are left out: the maps and the downloaded voices
	 * are gigabytes, re-obtainable from OsmAnd's own servers, and are not what a default backup is.
	 */
	@NonNull
	private List<String> contains(@NonNull OsmandApplication app) {
		List<String> labels = new ArrayList<>();
		try {
			Map<String, String> groups = new LinkedHashMap<>();
			Set<String> ticked = new LinkedHashSet<>();
			for (ChizuBackup.Cat cat : ChizuBackup.catalogue(app)) {
				if (cat.parent == null) {
					groups.put(cat.id, cat.label);
				} else if (cat.defaultSelected && ChizuBackup.travels(app, cat.type)) {
					// what the archive will actually hold, not what the app conceptually holds —
					// 応用管理 counts this against the export and shows the difference before it runs
					ticked.add(cat.parent);
				}
			}
			for (Map.Entry<String, String> group : groups.entrySet()) {
				if (ticked.contains(group.getKey())) {
					labels.add(group.getValue());
				}
			}
		} catch (Exception e) {
			// a describe on a half-woken process still answers; an empty list is honest
			Log.w(TAG, "cannot list categories for describe", e);
		}
		return labels;
	}

	/**
	 * Hand the descriptor to a foreground service and get out of the way.
	 *
	 * <p>The descriptor is <b>duplicated</b> before it leaves this method: the one in {@code extras}
	 * belongs to the binder transaction and is closed the moment {@code call()} returns, so a
	 * service reading it afterwards would find it shut. That is a bug you only see under load, so it
	 * is not left to the service to remember.
	 *
	 * <p>On a refused start the service closes the duplicate itself — closing it here a second time
	 * would be a different bug in place of the leak.
	 */
	@NonNull
	private Bundle start(@NonNull OsmandApplication app, @Nullable Bundle extras, boolean importing) {
		if (extras == null) {
			return fail("ERROR:no descriptor");
		}
		//noinspection deprecation
		ParcelFileDescriptor fd = extras.getParcelable(KEY_FD);
		if (fd == null) {
			return fail("ERROR:no descriptor");
		}
		ParcelFileDescriptor dup;
		try {
			dup = fd.dup();
		} catch (Exception e) {
			return fail("ERROR:descriptor unusable");
		}
		String jobId = ChizuAutomationJobs.begin();
		String why = ChizuAutomationDataService.start(app, jobId, dup, importing, extras);
		if (why != null) {
			ChizuAutomationJobs.finish(jobId);
			return fail(why);
		}
		return ok("OK:" + jobId);
	}

	@NonNull
	private Bundle ok(@NonNull String result) {
		Bundle bundle = new Bundle();
		bundle.putString(KEY_RESULT, result);
		return bundle;
	}

	@NonNull
	private Bundle fail(@NonNull String why) {
		Bundle bundle = new Bundle();
		bundle.putString(KEY_RESULT, why);
		return bundle;
	}

	// A provider that is only ever call()ed still has to answer these. Refusing loudly beats
	// returning an empty cursor, which reads downstream as "there is no data" rather than
	// "wrong door".

	@Nullable
	@Override
	public Cursor query(@NonNull Uri uri, @Nullable String[] projection, @Nullable String selection,
			@Nullable String[] selectionArgs, @Nullable String sortOrder) {
		throw new UnsupportedOperationException("automation is call() only");
	}

	@Nullable
	@Override
	public String getType(@NonNull Uri uri) {
		return null;
	}

	@Nullable
	@Override
	public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
		throw new UnsupportedOperationException("automation is call() only");
	}

	@Override
	public int delete(@NonNull Uri uri, @Nullable String selection, @Nullable String[] selectionArgs) {
		throw new UnsupportedOperationException("automation is call() only");
	}

	@Override
	public int update(@NonNull Uri uri, @Nullable ContentValues values, @Nullable String selection,
			@Nullable String[] selectionArgs) {
		throw new UnsupportedOperationException("automation is call() only");
	}
}
