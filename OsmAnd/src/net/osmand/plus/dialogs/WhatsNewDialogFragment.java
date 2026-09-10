package net.osmand.plus.dialogs;

import static net.osmand.aidlapi.OsmAndCustomizationConstants.FRAGMENT_WHATS_NEW_ID;

import android.app.Dialog;
import android.content.DialogInterface;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.FragmentManager;

import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;
import net.osmand.plus.Version;
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.base.BaseAlertDialogFragment;
import net.osmand.plus.settings.datastorage.SharedStorageWarningFragment;
import net.osmand.plus.utils.AndroidUtils;
import net.osmand.util.Algorithms;

import java.lang.reflect.Field;


public class WhatsNewDialogFragment extends BaseAlertDialogFragment {

	public static final String TAG = WhatsNewDialogFragment.class.getSimpleName();

	private static boolean notShown = true;

	@NonNull
	@Override
	public Dialog onCreateDialog(Bundle savedInstanceState) {
		updateNightMode();
		String releaseNotes = getReleaseNotes(app);
		String appVersion = Version.getAppVersion(app);
		String message = releaseNotes != null ? releaseNotes : "Release " + appVersion;
		AlertDialog.Builder builder = createDialogBuilder();
		builder.setTitle(getString(R.string.whats_new) + " " + appVersion)
				.setMessage(message)
				.setNegativeButton(R.string.shared_string_close, (dialog, which) -> showSharedStorageWarningIfRequired());
		builder.setPositiveButton(R.string.read_more, (dialog, which) -> {
			showArticle();
			dismiss();
		});
		return builder.create();
	}

	// shiroikuma fork: black card with a yellow border, yellow buttons, yellow text
	@Override
	public void onStart() {
		super.onStart();
		Dialog dialog = getDialog();
		if (dialog instanceof AlertDialog && dialog.getWindow() != null) {
			float density = getResources().getDisplayMetrics().density;
			android.graphics.drawable.GradientDrawable card = new android.graphics.drawable.GradientDrawable();
			card.setColor(net.osmand.plus.chizu.ChizuTheme.getColor(app, net.osmand.plus.chizu.ChizuTheme.Slot.CARD_BACKGROUND));
			card.setCornerRadius(8 * density);
			card.setStroke(Math.max(1, (int) (2 * density)),
					net.osmand.plus.chizu.ChizuTheme.getColor(app, net.osmand.plus.chizu.ChizuTheme.Slot.ACCENT));
			dialog.getWindow().setBackgroundDrawable(
					new android.graphics.drawable.InsetDrawable(card, (int) (16 * density)));
			AlertDialog alertDialog = (AlertDialog) dialog;
			int accent = net.osmand.plus.chizu.ChizuTheme.getColor(app, net.osmand.plus.chizu.ChizuTheme.Slot.ACCENT);
			for (int which : new int[] {AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL}) {
				if (alertDialog.getButton(which) != null) {
					alertDialog.getButton(which).setTextColor(accent);
				}
			}
			// The release notes are this dialog's whole content, and they were the one part still
			// arriving in upstream's near-white: the title and message are painted by Material from
			// the dialog theme rather than through ColorUtilities, so the fork's overrides never
			// reached them. chizu_dialog_styles.xml now sets those colours for every alert dialog;
			// this stays because it is the dialog 白い熊 named, and it costs two lines to be sure.
			int text = net.osmand.plus.chizu.ChizuTheme.getColor(app, net.osmand.plus.chizu.ChizuTheme.Slot.TEXT);
			// The title id belongs to AppCompat's alert layout, not to this app and not to the
			// framework, so it is looked up by name: a compile-time reference to another library's
			// internal id is the kind of thing that breaks on an upstream dependency bump.
			paintText(dialog, getResources().getIdentifier("alertTitle", "id",
					requireContext().getPackageName()), text);
			paintText(dialog, android.R.id.message, text);
		}
	}

	private void paintText(@NonNull Dialog dialog, int viewId, int color) {
		if (viewId == 0) {
			return;
		}
		android.view.View view = dialog.findViewById(viewId);
		if (view instanceof android.widget.TextView) {
			((android.widget.TextView) view).setTextColor(color);
		}
	}

	private void showArticle() {
		MapActivity mapActivity = getMapActivity();
		if (mapActivity != null) {
			AndroidUtils.openUrl(mapActivity, R.string.docs_latest_version, nightMode);
		}
	}

	@Override
	public void onCancel(@NonNull DialogInterface dialog) {
		super.onCancel(dialog);
		showSharedStorageWarningIfRequired();
	}

	private void showSharedStorageWarningIfRequired() {
		MapActivity mapActivity = getMapActivity();
		if (mapActivity != null) {
			if (mapActivity.getFragmentsHelper().getFragment(SharedStorageWarningFragment.TAG) == null
					&& SharedStorageWarningFragment.dialogShowRequired(app)) {
				SharedStorageWarningFragment.showInstance(mapActivity.getSupportFragmentManager(), true);
			}
		}
	}

	public static boolean wasNotShown() {
		return notShown;
	}

	public static boolean shouldShowDialog(@NonNull OsmandApplication app) {
		if (!app.getAppCustomization().isFeatureEnabled(FRAGMENT_WHATS_NEW_ID)) {
			return false;
		}
		if (!app.getAppInitializer().checkAppVersionChanged() || !notShown) {
			return false;
		}
		// nothing to tell about: no release notes for this version, or the same notes were already shown
		String releaseNotes = getReleaseNotes(app);
		return releaseNotes != null
				&& !Algorithms.stringsEqual(getNotesKey(releaseNotes), app.getSettings().LAST_SHOWN_RELEASE_NOTES.get());
	}

	public static boolean showInstance(@NonNull FragmentManager fragmentManager, @NonNull OsmandApplication app) {
		if (AndroidUtils.isFragmentCanBeAdded(fragmentManager, TAG)) {
			notShown = false;
			String releaseNotes = getReleaseNotes(app);
			if (releaseNotes != null) {
				app.getSettings().LAST_SHOWN_RELEASE_NOTES.set(getNotesKey(releaseNotes));
			}
			WhatsNewDialogFragment fragment = new WhatsNewDialogFragment();
			fragment.show(fragmentManager, TAG);
			return true;
		}
		return false;
	}

	@Nullable
	private static String getReleaseNotes(@NonNull OsmandApplication app) {
		String version = Version.getAppVersion(app);
		if (version.length() >= 3) {
			try {
				Field field = R.string.class.getField("release_" + version.charAt(0) + "_" + version.charAt(2));
				Integer id = (Integer) field.get(null);
				if (id != null) {
					return app.getString(id);
				}
			} catch (Exception e) {
				// no release notes for this version
			}
		}
		return null;
	}

	@NonNull
	private static String getNotesKey(@NonNull String releaseNotes) {
		return String.valueOf(releaseNotes.hashCode());
	}
}
