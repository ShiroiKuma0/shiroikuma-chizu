package net.osmand.plus.voice;

import static net.osmand.IndexConstants.TTSVOICE_INDEX_EXT_JS;
import static net.osmand.IndexConstants.VOICE_PROVIDER_SUFFIX;

import android.content.Intent;
import android.media.AudioAttributes;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.PlatformUtil;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;
import net.osmand.plus.api.AudioFocusHelperImpl;
import net.osmand.plus.routing.VoiceRouter;
import net.osmand.plus.settings.backend.ApplicationMode;
import net.osmand.plus.settings.backend.preferences.OsmandPreference;

import org.apache.commons.logging.Log;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class JsTtsCommandPlayer extends CommandPlayer {

	private static final Log log = PlatformUtil.getLog(JsTtsCommandPlayer.class);

	private static final String PEBBLE_ALERT = "PEBBLE_ALERT";
	private static final String JA = "\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}ー、。";
	private static final java.util.regex.Pattern JAPANESE_SPACE = java.util.regex.Pattern.compile(
			"(?<=[" + JA + "])[\\s\\u3000]+|[\\s\\u3000]+(?=[" + JA + "])");

	private static TextToSpeech mTts;

	private final HashMap<String, String> params = new HashMap<>();

	/**
	 * Since TTS requests are asynchronous, playCommands() can be called before
	 * the TTS engine is done. We use this field to keep track of concurrent tts
	 * activity. Where tts activity is defined as the time between tts.speak()
	 * and the call back to onUtteranceCompletedListener().  This allows us to
	 * optimize use of requesting and abandoning audio focus.
	 */
	private static int ttsRequests;
	// 白い熊 地図: utterances whose audio has started and not yet finished
	private final java.util.Set<String> audibleUtterances = new java.util.HashSet<>();

	// 白い熊 地図: give the audio focus back when the TTS engine never reports an utterance as done
	private static final long FOCUS_WATCHDOG_MS = 4000;
	private static final Handler focusWatchdogHandler = new Handler(Looper.getMainLooper());
	private final Runnable focusWatchdog = this::checkFocusStillNeeded;
	private float cSpeechRate = 1;
	private boolean speechAllowed;

	// Only for debugging
	private static String ttsVoiceStatus = "-";
	private static String ttsVoiceUsed = "-";

	protected JsTtsCommandPlayer(@NonNull OsmandApplication app,
	                             @NonNull ApplicationMode applicationMode,
	                             @NonNull VoiceRouter voiceRouter,
	                             @NonNull File voiceProviderDir) throws CommandPlayerException {
		super(app, applicationMode, voiceRouter, voiceProviderDir);

		if (app.accessibilityEnabled()) {
			cSpeechRate = settings.SPEECH_RATE.get();
		}
		initializeEngine();
		params.put(TextToSpeech.Engine.KEY_PARAM_STREAM, settings.AUDIO_MANAGER_STREAM
				.getModeValue(app.getRoutingHelper().getAppMode()).toString());
	}

	@NonNull
	@Override
	public File getTtsFileFromDir(@NonNull File voiceProviderDir) {
		String fileName = voiceProviderDir.getName().replace(VOICE_PROVIDER_SUFFIX, "_" + TTSVOICE_INDEX_EXT_JS);
		return new File(voiceProviderDir, fileName);
	}

	private void initializeEngine() {
		internalClear();

		if (mTts == null) {
			ttsVoiceStatus = "-";
			ttsVoiceUsed = "-";
			ttsRequests = 0;

			final TextToSpeech[] textToSpeech = new TextToSpeech[1];
			textToSpeech[0] = new TextToSpeech(app, status -> {
				if (mTts != textToSpeech[0]) {
					log.info("Obsolete TTS instance finished initializing. Shutting it down.");
					if (textToSpeech[0] != null) {
						textToSpeech[0].shutdown();
					}
					return;
				}
				if (status != TextToSpeech.SUCCESS) {
					ttsVoiceStatus = "NO INIT SUCCESS";
					internalClear();
					app.showToastMessage(R.string.tts_initialization_error);
				} else {
					TextToSpeech tts = mTts;
					if (tts != null) {
						Locale locale = new LocaleBuilder(app, tts, language).buildLocale();
						onSuccessfulTtsInit(locale, cSpeechRate);
					}
				}
			});
			mTts = textToSpeech[0];

			// 白い熊 地図: progress, not just completion. An engine that renders the whole utterance
			// before it plays (VOICEVOX in 白い熊 音声 takes up to ~15 s) calls start() at once and
			// hands over audio only when rendered, so the music is ducked at the first audio of an
			// utterance and given back when no utterance is audible any more.
			// The callbacks are on a binder thread.
			mTts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
				@Override
				public void onStart(String utteranceId) {
					// dispatched before the engine renders anything: too early for the focus
				}

				@Override
				public void onAudioAvailable(String utteranceId, byte[] audio) {
					onUtteranceAudioStart(utteranceId); // the engine hands over rendered audio now
				}

				@Override
				public void onDone(String utteranceId) {
					onUtteranceFinished(utteranceId, "done");
				}

				@Override
				public void onError(String utteranceId) {
					onUtteranceFinished(utteranceId, "error");
				}

				@Override
				public void onStop(String utteranceId, boolean interrupted) {
					onUtteranceFinished(utteranceId, "stopped");
				}
			});
		}
	}

	private void onSuccessfulTtsInit(@NonNull Locale locale, float speechRate) {
		speechAllowed = true;
		TextToSpeech mTts = JsTtsCommandPlayer.mTts;
		if(mTts != null) {
			switch (mTts.isLanguageAvailable(locale)) {
				case TextToSpeech.LANG_NOT_SUPPORTED:
					ttsVoiceStatus = locale.getDisplayName() + ": LANG_NOT_SUPPORTED";
					ttsVoiceUsed = getVoiceUsed();
					break;
				case TextToSpeech.LANG_MISSING_DATA:
					ttsVoiceStatus = locale.getDisplayName() + ": LANG_MISSING_DATA";
					ttsVoiceUsed = getVoiceUsed();
					break;
				case TextToSpeech.LANG_AVAILABLE:
					ttsVoiceStatus = locale.getDisplayName() + ": LANG_AVAILABLE";
				case TextToSpeech.LANG_COUNTRY_AVAILABLE:
					ttsVoiceStatus = "-".equals(ttsVoiceStatus)
							? locale.getDisplayName() + ": LANG_COUNTRY_AVAILABLE"
							: ttsVoiceStatus;
				case TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE:
					try {
						mTts.setLanguage(locale);
					} catch (Exception e) {
						log.error(e);
						if (mTts.isLanguageAvailable(Locale.getDefault()) > 0) {
							mTts.setLanguage(Locale.getDefault());
						} else {
							app.showToastMessage("TTS language not available");
						}
					}
					if (speechRate != 1) {
						mTts.setSpeechRate(speechRate);
					}
					ttsVoiceStatus = "-".equals(ttsVoiceStatus)
							? locale.getDisplayName() + ": LANG_COUNTRY_VAR_AVAILABLE"
							: ttsVoiceStatus;
					ttsVoiceUsed = getVoiceUsed();
					break;
			}
		}
	}

	@NonNull
	private String getVoiceUsed() {
		try {
			if (mTts.getVoice() != null) {
				return mTts.getVoice().toString() + " (API " + Build.VERSION.SDK_INT + ")";
			}
		} catch (Exception e) {
			log.error(e);
		}
		return "-";
	}

	// Called from the calculating route thread.
	@NonNull
	@Override
	public synchronized List<String> playCommands(@NonNull CommandBuilder builder) {
		List<String> execute = builder.execute(); //list of strings, the speech text, play it
		StringBuilder bld = new StringBuilder();
		for (String s : execute) {
			bld.append(s).append(' ');
		}
		// 白い熊 地図: a space next to Japanese is read as a pause (“100メートル　先”), so it goes
		String joined = JAPANESE_SPACE.matcher(bld).replaceAll("");
		bld.setLength(0);
		bld.append(joined);
		// 白い熊 地図: a prompt that came out empty (its announcement switched off) says nothing, so it
		// must not take the audio focus either — that muted the music on every silent reroute
		if (bld.toString().trim().isEmpty()) {
			net.osmand.plus.chizu.ChizuDriveLog.log("voice empty prompt skipped");
			return execute;
		}
		net.osmand.plus.chizu.ChizuDriveLog.log("voice say \"" + bld.toString().trim() + "\" requests=" + ttsRequests);
		sendAlertToPebble(bld.toString());
		if (mTts != null && !voiceRouter.isMute() && speechAllowed) {
			if (ttsRequests++ == 0) {
				if (!isFocusAtAudioStart()) {
					requestAudioFocus(); // BT SCO: the link must be up before the audio comes
				}
				mTts.setAudioAttributes(new AudioAttributes.Builder()
						.setUsage(settings.AUDIO_USAGE[settings.AUDIO_MANAGER_STREAM.getModeValue(app.getRoutingHelper().getAppMode())].get())
						.setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
						.build());
				// Delay first prompt of each batch to allow BT SCO link being established, or when VOICE_PROMPT_DELAY is set >0 for the other stream types
				if (app != null) {
					Integer streamModeValue = settings.AUDIO_MANAGER_STREAM.getModeValue(app.getRoutingHelper().getAppMode());
					OsmandPreference<Integer> pref = settings.VOICE_PROMPT_DELAY[streamModeValue];
					int vpd = pref == null ? 0 : pref.getModeValue(app.getRoutingHelper().getAppMode());
					if (vpd > 0) {
						ttsRequests++;
						mTts.playSilentUtterance(vpd, TextToSpeech.QUEUE_ADD, "" + System.currentTimeMillis());
					}
				}
			}
			log.debug("ttsRequests=" + ttsRequests);
			params.put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "" + System.currentTimeMillis());
			if (isFocusAtAudioStart() || AudioFocusHelperImpl.playbackAuthorized) {
				mTts.speak(bld.toString(), TextToSpeech.QUEUE_ADD, params);
			} else {
				stop();
			}
			// Audio focus is taken in onStart() and given back in onDone() (BT SCO: when all are done).
		}
		// #5966: TTS Utterance for debugging
		if (app != null && settings.DISPLAY_TTS_UTTERANCE.get()) {
			app.showToastMessage(bld.toString());
		}
		return execute;
	}

	private boolean isFocusAtAudioStart() {
		return settings.AUDIO_MANAGER_STREAM.getModeValue(app.getRoutingHelper().getAppMode()) != 0;
	}

	private synchronized void onUtteranceAudioStart(@NonNull String utteranceId) {
		if (!audibleUtterances.add(utteranceId)) {
			return; // a later chunk of an utterance already playing
		}
		net.osmand.plus.chizu.ChizuDriveLog.log("voice audio starts, audible=" + audibleUtterances.size());
		if (!isFocusAtAudioStart() || audibleUtterances.size() > 1) {
			return; // the focus is held already
		}
		requestAudioFocus();
		if (!AudioFocusHelperImpl.playbackAuthorized) {
			stop(); // a call or another app holds the audio: say nothing, as before
			return;
		}
		focusWatchdogHandler.removeCallbacks(focusWatchdog);
		focusWatchdogHandler.postDelayed(focusWatchdog, FOCUS_WATCHDOG_MS);
	}

	private synchronized void onUtteranceFinished(@NonNull String utteranceId, @NonNull String how) {
		ttsRequests = Math.max(0, ttsRequests - 1);
		audibleUtterances.remove(utteranceId);
		net.osmand.plus.chizu.ChizuDriveLog.log("voice audio " + how + ", audible=" + audibleUtterances.size()
				+ " requests=" + ttsRequests);
		if (isFocusAtAudioStart() ? audibleUtterances.isEmpty() : ttsRequests == 0) {
			focusWatchdogHandler.removeCallbacks(focusWatchdog);
			abandonAudioFocus();
		}
	}

	private synchronized void checkFocusStillNeeded() {
		if (ttsRequests <= 0) {
			return;
		}
		TextToSpeech tts = mTts;
		if (tts != null && tts.isSpeaking()) {
			focusWatchdogHandler.postDelayed(focusWatchdog, FOCUS_WATCHDOG_MS);
			return;
		}
		net.osmand.plus.chizu.ChizuDriveLog.log("voice focus watchdog: engine silent, released with requests=" + ttsRequests);
		ttsRequests = 0;
		audibleUtterances.clear();
		abandonAudioFocus();
	}

	private void sendAlertToPebble(@NonNull String bld) {
		Intent i = new Intent("com.getpebble.action.SEND_NOTIFICATION");
		Map<String, Object> data = new HashMap<>();
		data.put("title", "Voice");
		data.put("body", bld);
		JSONObject jsonData = new JSONObject(data);
		String notificationData = new JSONArray().put(jsonData).toString();
		i.putExtra("messageType", PEBBLE_ALERT);
		i.putExtra("sender", "OsmAnd");
		i.putExtra("notificationData", notificationData);
		if (app != null) {
			app.sendBroadcast(i);
			log.info("Send message to pebble " + bld);
		}
	}

	@NonNull
	@Override
	public CommandBuilder newCommandBuilder() {
		JsCommandBuilder commandBuilder = new JsCommandBuilder(this);
		commandBuilder.setJSContext(jsScope);
		commandBuilder.setParameters(settings.METRIC_SYSTEM.get().toTTSString(), true);
		return commandBuilder;
	}

	@Override
	public void updateAudioStream(int streamType) {
		super.updateAudioStream(streamType);
		params.put(TextToSpeech.Engine.KEY_PARAM_STREAM, streamType + "");
	}

	@Override
	public void stop() {
		ttsRequests = 0;
		synchronized (this) {
			audibleUtterances.clear();
		}
		// Before onInit the engine connection is still being set up under TextToSpeech's internal lock, so
		// stop() would wait for a slow engine on the main thread (ANR). Nothing can be speaking before onInit.
		if (mTts != null && speechAllowed) {
			mTts.stop();
		}
		abandonAudioFocus();
	}

	@Override
	public void clear() {
		super.clear();
		internalClear();
	}

	private void internalClear() {
		ttsRequests = 0;
		speechAllowed = false;
		if (mTts != null) {
			mTts.shutdown();
			mTts = null;
		}
		abandonAudioFocus();
		ttsVoiceStatus = "-";
		ttsVoiceUsed = "-";
	}

	@Override
	public boolean supportsStructuredStreetNames() {
		return true;
	}

	@NonNull
	public static String getTtsVoiceStatus() {
		return ttsVoiceStatus;
	}

	@NonNull
	public static String getTtsVoiceUsed() {
		return ttsVoiceUsed;
	}

	public static boolean isMyData(@NonNull File dir) {
		String name = dir.getName();
		if (!name.contains("tts")) {
			return false;
		}
		File file = getLangFile(dir);
		return file != null && file.exists();
	}

	@Nullable
	public static File getLangFile(@NonNull File dir) {
		String name = dir.getName();
		if (!name.contains("tts")) {
			return null;
		}
		String langName = name.replace(VOICE_PROVIDER_SUFFIX, "");
		return new File(dir, langName + "_" + TTSVOICE_INDEX_EXT_JS);
	}
}
