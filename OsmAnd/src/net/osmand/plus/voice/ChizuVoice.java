package net.osmand.plus.voice;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import net.osmand.plus.OsmandApplication;
import net.osmand.plus.chizu.ChizuDriveLog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 白い熊 地図: voice prompts rendered by 白い熊 音声 ahead of time and played by 地図 itself.
 * <p>
 * As the system TTS engine, 音声 renders a whole sentence before any of it is heard — 9–12 s for a
 * Japanese turn prompt with a street name — while OsmAnd says “turn now” only 2–10 s before the turn.
 * So the prompts that can be known in advance (each coming turn's “turn now”, the arrival) are sent
 * to 音声's render service as files when the route is set and after every turn
 * ({@link #prefetch}), and a prompt that is due ({@link #say}) plays its file at once when it is
 * there. Anything else is rendered on the spot in the urgent lane and played when ready.
 * <p>
 * The queue is 地図's own: a prompt still rendering when a newer one comes is dropped (it would be
 * heard too late), a ready prompt that waited too long behind another is dropped, and the arrival
 * clears everything else. Files are cached by text, so a prompt rendered once is instant from then on.
 * <p>
 * In use only when 音声 is the system TTS engine (so the voice is the same) and answers PING; until
 * then every prompt goes the plain TTS way, see {@link JsTtsCommandPlayer}. The contract is 音声's
 * docs/sister-app-contract-onse-render.md.
 */
public final class ChizuVoice {

	private static final String ONSE = "shiroikuma.onse";
	private static final String ACTION_RENDER = "shiroikuma.onse.action.RENDER";
	private static final String ACTION_CANCEL = "shiroikuma.onse.action.CANCEL_RENDER";
	private static final String ACTION_PING = "shiroikuma.onse.action.PING";
	private static final String REPLY_ACTION = "shiroikuma.chizu.action.ONSE_REPLY";
	private static final String PREFS = "chizu_voice";

	private static final long PING_EVERY_MS = 60_000;
	// the first sentence of a prompt is back in 1–4 s; this long without it means 音声 is not answering
	private static final long TTS_FALLBACK_MS = 8_000;
	// pieces of one prompt arrive within this; anything older not yet started is superseded
	private static final long JOIN_MS = 3_000;
	// how long a sentence may be late after the one before it ended before the rest is cut
	private static final long PART_WAIT_MS = 1_500;
	private static final long CACHE_DAYS = 14;

	private static ChizuVoice instance;

	private final OsmandApplication app;
	private final Handler handler = new Handler(Looper.getMainLooper());
	private final File dir;

	private boolean available;
	// 音声 1.0+016 is the first with speaker=tts, priority=high and 地図 in its <queries>
	private static final int[] MIN_ONSE = {1, 0, 16};
	private long lastPing;
	private String onseVersion = "-";
	private int seq;

	// text -> request id of the render that will produce it
	private final Map<String, String> inFlight = new HashMap<>();
	private final Map<String, Long> urgentSince = new HashMap<>();
	private String prefetchRequest;

	private final List<Item> queue = new ArrayList<>();
	@Nullable
	private MediaPlayer playing;
	@Nullable
	private Item playingItem;
	@Nullable
	private CommandPlayer focusOwner;
	private boolean focusHeld;

	/** One prompt: its sentences, each rendered and cached as a file of its own, played in a chain. */
	private static final class Item {
		final String text;
		final List<String> parts;
		final long createdAt = System.currentTimeMillis();
		final boolean arrival;
		long readyAt;
		int next; // the part to play next
		long partWaitSince;
		boolean dropped;

		Item(String text, boolean arrival) {
			this.text = text;
			this.parts = sentences(text);
			this.arrival = arrival;
		}
	}

	/**
	 * Split after each 。, so the first sentence (often cached: 「右方向です。」) plays while the next
	 * renders — VOICEVOX runs at about 0.8× real time, so a whole prompt can never be fast.
	 */
	@NonNull
	static List<String> sentences(@NonNull String text) {
		List<String> out = new ArrayList<>();
		int from = 0;
		for (int i = 0; i < text.length(); i++) {
			if (text.charAt(i) == '。') {
				String p = text.substring(from, i + 1).trim();
				if (!p.isEmpty() && !p.equals("。")) {
					out.add(p);
				}
				from = i + 1;
			}
		}
		String rest = text.substring(from).trim();
		if (!rest.isEmpty()) {
			out.add(rest);
		}
		return out;
	}

	private ChizuVoice(@NonNull OsmandApplication app) {
		this.app = app;
		File[] media = app.getExternalMediaDirs();
		File base = media != null && media.length > 0 && media[0] != null ? media[0] : app.getExternalFilesDir(null);
		dir = new File(base, "voice");
		dir.mkdirs();
		try {
			new File(dir, ".nomedia").createNewFile(); // the prompts stay out of the media library
		} catch (Exception ignored) {
		}
		pruneCache();
		ContextCompat.registerReceiver(app, new BroadcastReceiver() {
			@Override
			public void onReceive(Context context, Intent intent) {
				onReply(intent);
			}
		}, new IntentFilter(REPLY_ACTION), ContextCompat.RECEIVER_EXPORTED);
	}

	@NonNull
	public static synchronized ChizuVoice get(@NonNull OsmandApplication app) {
		if (instance == null) {
			instance = new ChizuVoice(app);
		}
		return instance;
	}

	public static boolean isEnabled(@NonNull Context context) {
		return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("enabled", true);
	}

	public static void setEnabled(@NonNull Context context, boolean enabled) {
		context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("enabled", enabled).apply();
	}

	/** True when prompts go through 音声's render service rather than the TTS engine. */
	public boolean isActive(@Nullable String language) {
		if (!isEnabled(app) || language == null || !language.startsWith("ja") || !onseIsTtsEngine()) {
			return false;
		}
		if (!available) {
			pingIfDue();
		}
		return available;
	}

	/** Route planning started: ask 音声 now, so the first prompt (“route set”) is already pre-rendered. */
	public void warmUp() {
		if (!available && isEnabled(app) && onseIsTtsEngine()) {
			pingIfDue();
		}
	}

	private boolean onseIsTtsEngine() {
		try {
			String engine = JsTtsCommandPlayer.chizuDefaultEngine();
			if (engine == null) {
				engine = android.provider.Settings.Secure.getString(app.getContentResolver(), "tts_default_synth");
			}
			return ONSE.equals(engine);
		} catch (Exception e) {
			return false;
		}
	}

	// ---------- what 地図 knows ahead ----------

	/** Renders these texts ahead (normal lane); the previous look-ahead batch is cancelled. */
	public void prefetch(@NonNull Collection<String> texts) {
		handler.post(() -> {
			List<String> todo = new ArrayList<>();
			for (String t : texts) {
				if (t == null) {
					continue;
				}
				for (String part : sentences(t)) {
					if (!partReady(part) && !inFlight.containsKey(part) && !todo.contains(part)) {
						todo.add(part);
					}
				}
			}
			if (prefetchRequest != null) {
				cancel(prefetchRequest);
				prefetchRequest = null;
			}
			if (todo.isEmpty()) {
				return;
			}
			prefetchRequest = render(todo, false);
			ChizuDriveLog.log("voice prefetch " + todo.size() + " request=" + prefetchRequest + ": " + todo);
		});
	}

	/** Every sentence of the prompt is rendered. */
	public boolean isReady(@NonNull String text) {
		for (String part : sentences(text)) {
			if (!partReady(part)) {
				return false;
			}
		}
		return true;
	}

	private boolean partReady(@NonNull String part) {
		return fileFor(part).isFile();
	}

	// ---------- a prompt that is due ----------

	/** Plays the prompt: its file at once when rendered, or once rendered in the urgent lane. */
	public void say(@NonNull String text, boolean arrival, @NonNull CommandPlayer player) {
		handler.post(() -> {
			focusOwner = player;
			Item item = new Item(text, arrival);
			if (arrival) {
				// nothing said after the arrival: old turn prompts are no news any more
				for (Item q : queue) {
					q.dropped = true;
				}
				stopPlaying();
			}
			for (Item q : queue) {
				// not started yet and older than this burst: a newer prompt says what matters now. One
				// prompt comes as a burst of pieces (“turn”, “then”, “and arrive”), which stay together
				if (!q.dropped && item.createdAt - q.createdAt > JOIN_MS) {
					q.dropped = true;
					ChizuDriveLog.log("voice drop, newer prompt: \"" + q.text + "\"");
				}
			}
			List<String> missing = new ArrayList<>();
			for (String part : item.parts) {
				if (!partReady(part) && !inFlight.containsKey(part)) {
					missing.add(part);
				}
			}
			if (missing.isEmpty()) {
				ChizuDriveLog.log("voice say prerendered \"" + text + "\"");
			} else {
				// one urgent request, a sentence per item: the first is back in about a second
				for (String part : missing) {
					urgentSince.put(part, System.currentTimeMillis());
				}
				render(missing, true);
				ChizuDriveLog.log("voice say render-now " + missing.size() + "/" + item.parts.size() + " \"" + text + "\"");
				handler.postDelayed(this::pump, TTS_FALLBACK_MS + 100);
			}
			queue.add(item);
			pump();
		});
	}

	/** Stops everything: OsmAnd's “interrupt route commands”. */
	public void stopAll() {
		handler.post(() -> {
			for (Item q : queue) {
				q.dropped = true;
			}
			queue.clear();
			stopPlaying();
		});
	}

	@Nullable
	private Item current;

	private void pump() {
		if (playing != null) {
			return;
		}
		long now = System.currentTimeMillis();
		if (current != null) {
			// the next sentence of the prompt that is playing
			if (current.dropped || current.next >= current.parts.size()) {
				current = null;
			} else if (partReady(current.parts.get(current.next))) {
				play(current);
				return;
			} else if (now - current.partWaitSince > PART_WAIT_MS) {
				ChizuDriveLog.log("voice rest not rendered in time, cut after part " + current.next + ": \"" + current.text + "\"");
				current = null;
			} else {
				handler.postDelayed(this::pump, 200);
				return;
			}
		}
		Iterator<Item> it = queue.iterator();
		while (it.hasNext()) {
			Item q = it.next();
			if (q.dropped) {
				it.remove();
				continue;
			}
			if (q.readyAt == 0 && !q.parts.isEmpty() && partReady(q.parts.get(0))) {
				q.readyAt = now;
			}
			if (q.readyAt == 0) {
				if (now - q.createdAt > TTS_FALLBACK_MS) {
					// 音声 does not answer (stopped, refused): this one and what follows go the TTS way
					ChizuDriveLog.log("voice onse silent for " + (now - q.createdAt) + " ms; back to the TTS engine");
					available = false;
					lastPing = 0;
					failQueuedToTts();
					continue;
				}
				return; // keep the order: wait for this one
			}
			it.remove();
			current = q;
			play(q);
			return;
		}
		releaseFocusIfIdle();
	}

	private void play(@NonNull Item item) {
		CommandPlayer owner = focusOwner;
		if (owner == null || owner.voiceRouter.isMute()) {
			return;
		}
		MediaPlayer mp = new MediaPlayer();
		try {
			int stream = owner.settings.AUDIO_MANAGER_STREAM.getModeValue(app.getRoutingHelper().getAppMode());
			mp.setAudioAttributes(new AudioAttributes.Builder()
					.setUsage(owner.settings.AUDIO_USAGE[stream].get())
					.setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
					.build());
			String part = item.parts.get(item.next);
			File file = fileFor(part);
			file.setLastModified(System.currentTimeMillis()); // in use: kept by the cache pruning
			mp.setDataSource(file.getAbsolutePath());
			mp.setOnCompletionListener(p -> onPlayEnd("done"));
			mp.setOnErrorListener((p, what, extra) -> {
				onPlayEnd("error " + what + "/" + extra);
				return true;
			});
			mp.prepare();
			owner.requestAudioFocus();
			focusHeld = true;
			playing = mp;
			playingItem = item;
			mp.start();
			ChizuDriveLog.log("voice file plays after " + (System.currentTimeMillis() - item.createdAt)
					+ " ms, part " + (item.next + 1) + "/" + item.parts.size() + ": \"" + part + "\"");
			item.next++;
			item.partWaitSince = 0;
		} catch (Exception e) {
			ChizuDriveLog.log("voice file failed: " + e);
			mp.release();
			playing = null;
			playingItem = null;
			if (item.next < item.parts.size()) {
				fileFor(item.parts.get(item.next)).delete();
			}
			current = null;
		}
	}

	private void onPlayEnd(@NonNull String how) {
		ChizuDriveLog.log("voice file " + how);
		if (playing != null) {
			playing.release();
		}
		playing = null;
		if (current != null) {
			current.partWaitSince = System.currentTimeMillis();
		}
		playingItem = null;
		pump();
	}

	private void stopPlaying() {
		if (playing != null) {
			try {
				playing.stop();
			} catch (Exception ignored) {
			}
			playing.release();
			ChizuDriveLog.log("voice file stopped: \"" + (playingItem != null ? playingItem.text : "") + "\"");
		}
		current = null;
		playing = null;
		playingItem = null;
		releaseFocusIfIdle();
	}

	private void releaseFocusIfIdle() {
		if (playing == null && focusOwner != null && focusHeld) {
			for (Item q : queue) {
				if (!q.dropped) {
					return;
				}
			}
			focusOwner.abandonAudioFocus();
			focusHeld = false;
		}
	}

	// ---------- the render service ----------

	@Nullable
	private String render(@NonNull List<String> texts, boolean urgent) {
		String id = "chizu-" + System.currentTimeMillis() + "-" + (++seq);
		try {
			JSONArray items = new JSONArray();
			for (int i = 0; i < texts.size(); i++) {
				JSONObject o = new JSONObject();
				o.put("id", String.valueOf(i));
				o.put("text", texts.get(i));
				o.put("out_path", fileFor(texts.get(i)).getAbsolutePath());
				items.put(o);
			}
			File batch = new File(dir, "batch_" + id + ".json");
			try (FileOutputStream out = new FileOutputStream(batch)) {
				out.write(new JSONObject().put("items", items).toString().getBytes(StandardCharsets.UTF_8));
			}
			Intent intent = request(ACTION_RENDER, id)
					.putExtra("batch_path", batch.getAbsolutePath())
					.putExtra("speaker", "tts")
					.putExtra("format", "wav")
					.putExtra("lang", "ja")
					.putExtra("rate", String.valueOf(app.accessibilityEnabled() ? app.getSettings().SPEECH_RATE.get() : 1f));
			if (urgent) {
				intent.putExtra("priority", "high");
			}
			app.sendBroadcast(intent);
			for (String t : texts) {
				inFlight.put(t, id);
			}
			return id;
		} catch (Exception e) {
			ChizuDriveLog.log("voice render request failed: " + e);
			return null;
		}
	}

	private void cancel(@NonNull String id) {
		app.sendBroadcast(request(ACTION_CANCEL, id));
	}

	@NonNull
	private Intent request(@NonNull String action, @NonNull String id) {
		return new Intent(action)
				.setPackage(ONSE)
				.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
				.putExtra("request_id", id)
				.putExtra("reply_action", REPLY_ACTION)
				.putExtra("reply_package", app.getPackageName());
	}

	private void pingIfDue() {
		long now = System.currentTimeMillis();
		if (now - lastPing < PING_EVERY_MS) {
			return;
		}
		lastPing = now;
		new Thread(() -> {
			try {
				// wakes 音声's process, also cold (contract: “Waking 音声”)
				app.getContentResolver().call(Uri.parse("content://shiroikuma.onse.automation"), "describe", null, null);
			} catch (Exception e) {
				ChizuDriveLog.log("voice onse wake failed: " + e);
			}
			app.sendBroadcast(request(ACTION_PING, "chizu-ping-" + System.currentTimeMillis()));
		}, "chizu-onse-ping").start();
	}

	private void onReply(@NonNull Intent intent) {
		String event = intent.getStringExtra("event");
		String id = intent.getStringExtra("request_id");
		if ("pong".equals(event)) {
			onseVersion = String.valueOf(intent.getStringExtra("version"));
			boolean ok = "OK".equals(intent.getStringExtra("result")) && versionAtLeast(onseVersion, MIN_ONSE);
			if (ok != available) {
				ChizuDriveLog.log("voice onse " + (ok ? "available" : "refuses: " + intent.getStringExtra("result"))
						+ " version=" + onseVersion);
			}
			available = ok;
			return;
		}
		if ("item".equals(event)) {
			String status = intent.getStringExtra("status");
			String out = intent.getStringExtra("out_path");
			String text = textFor(id, out);
			if (text != null) {
				inFlight.remove(text);
				Long since = urgentSince.remove(text);
				ChizuDriveLog.log("voice rendered " + status + (since != null ? " in " + (System.currentTimeMillis() - since) + " ms" : "")
						+ " render=" + intent.getStringExtra("render_ms") + " audio=" + intent.getStringExtra("duration_ms") + " ms: \"" + text + "\""
						+ ("OK".equals(status) ? "" : " " + intent.getStringExtra("error")));
			}
			pump();
			return;
		}
		if ("done".equals(event)) {
			String result = String.valueOf(intent.getStringExtra("result"));
			new File(dir, "batch_" + id + ".json").delete();
			if (id != null && id.equals(prefetchRequest)) {
				prefetchRequest = null;
			}
			inFlight.values().removeIf(v -> v.equals(id));
			if (result.startsWith("ERROR") && !result.startsWith("ERROR:cancelled")) {
				ChizuDriveLog.log("voice render " + id + " " + result + "; back to the TTS engine");
				available = false; // e.g. the speaker or a gate: plain TTS until the next good PING
				lastPing = System.currentTimeMillis();
				failQueuedToTts();
			}
			pump();
		}
	}

	/** Prompts waiting for a render that failed go to the TTS engine, as before. */
	private void failQueuedToTts() {
		CommandPlayer owner = focusOwner;
		for (Item q : queue) {
			if (!q.dropped && q.readyAt == 0) {
				q.dropped = true;
				if (owner instanceof JsTtsCommandPlayer) {
					((JsTtsCommandPlayer) owner).chizuSpeakTts(q.text, q.arrival);
				}
			}
		}
	}

	@Nullable
	private String textFor(@Nullable String id, @Nullable String outPath) {
		for (Map.Entry<String, String> e : inFlight.entrySet()) {
			if (e.getValue().equals(id) && outPath != null && fileFor(e.getKey()).getAbsolutePath().equals(outPath)) {
				return e.getKey();
			}
		}
		return null;
	}

	/** “1.0+016” against {1, 0, 16}. */
	static boolean versionAtLeast(@NonNull String version, @NonNull int[] min) {
		String[] f = version.split("[.+]");
		for (int i = 0; i < min.length; i++) {
			int v;
			try {
				v = i < f.length ? Integer.parseInt(f[i].replaceAll("\\D.*", "")) : 0;
			} catch (NumberFormatException e) {
				v = 0;
			}
			if (v != min[i]) {
				return v > min[i];
			}
		}
		return true;
	}

	@NonNull
	private File fileFor(@NonNull String text) {
		return new File(dir, hash(text) + ".wav");
	}

	@NonNull
	private static String hash(@NonNull String text) {
		try {
			byte[] d = MessageDigest.getInstance("SHA-1").digest(text.getBytes(StandardCharsets.UTF_8));
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < 12; i++) {
				sb.append(String.format("%02x", d[i]));
			}
			return sb.toString();
		} catch (Exception e) {
			return Integer.toHexString(text.hashCode());
		}
	}

	private void pruneCache() {
		File[] files = dir.listFiles();
		if (files == null) {
			return;
		}
		long cutoff = System.currentTimeMillis() - CACHE_DAYS * 24 * 3600_000L;
		for (File f : files) {
			if ((f.lastModified() < cutoff && !f.getName().equals(".nomedia")) || f.getName().startsWith("batch_") || f.getName().endsWith(".part")) {
				f.delete();
			}
		}
	}

	@NonNull
	public String describe() {
		return "onse=" + (available ? onseVersion : "unavailable") + " engine=" + (onseIsTtsEngine() ? ONSE : "other")
				+ " cached=" + (dir.list() == null ? 0 : dir.list().length);
	}
}
