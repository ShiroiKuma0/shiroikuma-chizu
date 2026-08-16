package net.osmand.plus.chizu;

import android.content.Context;
import android.graphics.Bitmap;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;

import androidx.annotation.ColorInt;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.car.app.model.Action;
import androidx.car.app.model.CarColor;
import androidx.car.app.model.CarIcon;
import androidx.car.app.model.CarLocation;
import androidx.car.app.model.ForegroundCarColorSpan;
import androidx.car.app.model.Place;
import androidx.car.app.model.PlaceMarker;
import androidx.core.graphics.drawable.IconCompat;

import net.osmand.plus.R;
import net.osmand.plus.utils.ColorUtilities;

/**
 * 白い熊 地図 Android Auto look: yellow on black, as far as the car API allows.
 *
 * <p>Colors resolve through {@link ColorUtilities}, so the in-app theming page's overrides
 * reach the car screen too. The exception is {@code CarColor.PRIMARY}/{@code SECONDARY},
 * which the host resolves itself from the {@code ChizuCarAppTheme} style named by the
 * {@code androidx.car.app.theme} manifest meta-data — the host process cannot see our
 * runtime overrides, so those two carry the static defaults.
 *
 * <p><b>What the car API does and does not let us paint.</b> Icon tints, row secondary
 * lines, filled buttons and the navigation card background are ours. A row <i>title</i> is
 * not: {@code Row.setTitle} validates against {@code CarTextConstraints.TEXT_AND_ICON},
 * which rejects a color span outright. Nor is the surrounding chrome — list background,
 * header bar, scrollbar — the host draws that and offers no hook.
 *
 * <p>A background color is only legal where the template's {@code ActionsConstraints} allow
 * it: body actions (Pane, MessageTemplate) take it on any action, while navigation and map
 * action strips restrict it to the single primary action. Ignoring that throws at build
 * time and takes the whole car session down with it, which is why the filled-button helpers
 * come in a body flavour and a primary flavour rather than one do-everything call.
 */
public class ChizuCar {

	private ChizuCar() {
	}

	// ---------------------------------------------------------------- colors

	/**
	 * Yellow — every glyph and every accent the car screen draws.
	 */
	@NonNull
	public static CarColor accent(@NonNull Context ctx) {
		return bothModes(ColorUtilities.getColor(ctx, R.color.map_button_icon_color_dark, 1.0f));
	}

	/**
	 * Black — what the yellow sits on: the navigation card behind the turn instructions.
	 */
	@NonNull
	public static CarColor surface(@NonNull Context ctx) {
		return bothModes(ColorUtilities.getColor(ctx, R.color.card_and_list_background_dark, 1.0f));
	}

	/**
	 * Content drawn ON the accent fill — a filled button's glyph, so it stays legible
	 * whatever accent the theming page has been set to.
	 */
	@NonNull
	public static CarColor onAccent(@NonNull Context ctx) {
		return bothModes(ColorUtilities.getColor(ctx, R.color.chizu_on_accent_dark, 1.0f));
	}

	/**
	 * The yellow of a row's second line — distances, addresses, opening hours.
	 */
	@NonNull
	public static CarColor secondaryText(@NonNull Context ctx) {
		return bothModes(ColorUtilities.getColor(ctx, R.color.text_color_secondary_dark, 1.0f));
	}

	/**
	 * One color for both the day and the night variant: the fork is black-yellow either way.
	 * Alpha is dropped — the host composites over chrome we do not control, so a translucent
	 * yellow would land on an unknown backdrop.
	 */
	@NonNull
	private static CarColor bothModes(@ColorInt int color) {
		int opaque = 0xFF000000 | (color & 0x00FFFFFF);
		return CarColor.createCustom(opaque, opaque);
	}

	// ---------------------------------------------------------------- icons

	/**
	 * A monochrome resource glyph, tinted yellow.
	 */
	@NonNull
	public static CarIcon icon(@NonNull Context ctx, @DrawableRes int iconRes) {
		return tinted(IconCompat.createWithResource(ctx, iconRes), accent(ctx));
	}

	/**
	 * A monochrome resource glyph in the on-accent color, for use inside a filled button.
	 */
	@NonNull
	public static CarIcon onAccentIcon(@NonNull Context ctx, @DrawableRes int iconRes) {
		return tinted(IconCompat.createWithResource(ctx, iconRes), onAccent(ctx));
	}

	/**
	 * A bitmap the app rendered itself, tinted yellow. For bitmaps that already carry a
	 * meaningful color — a favourite's category color, a map marker's color, a multicolour
	 * POI glyph — pass them through untinted instead; those are app-themed already.
	 */
	@NonNull
	public static CarIcon icon(@NonNull Context ctx, @NonNull Bitmap bitmap) {
		return tinted(IconCompat.createWithBitmap(bitmap), accent(ctx));
	}

	@NonNull
	public static CarIcon tinted(@NonNull IconCompat icon, @NonNull CarColor tint) {
		return new CarIcon.Builder(icon).setTint(tint).build();
	}

	// ---------------------------------------------------------------- actions

	/**
	 * An icon-only car button: yellow glyph on the host's own button shape. Legal in every
	 * action strip, since it sets no background color.
	 */
	@NonNull
	public static Action.Builder action(@NonNull Context ctx, @DrawableRes int iconRes) {
		return new Action.Builder().setIcon(icon(ctx, iconRes));
	}

	/**
	 * A filled yellow button for a <b>body</b> action — {@code Pane.addAction},
	 * {@code MessageTemplate.addAction} — where the constraints allow a background color on
	 * any action. The host picks the title color for contrast against the fill.
	 */
	@NonNull
	public static Action.Builder filledAction(@NonNull Context ctx) {
		return new Action.Builder().setBackgroundColor(accent(ctx));
	}

	/**
	 * A filled yellow button for an <b>action strip</b> — navigation and map strips only
	 * accept a background color on the primary action, and only one action may be primary.
	 */
	@NonNull
	public static Action.Builder primaryAction(@NonNull Context ctx) {
		return new Action.Builder()
				.setFlags(Action.FLAG_PRIMARY)
				.setBackgroundColor(accent(ctx));
	}

	// ---------------------------------------------------------------- map pins

	/**
	 * The pin the host drops on the map for a list row that carries {@link
	 * androidx.car.app.model.Place} metadata. Upstream leaves it unset, so the host paints its
	 * own default; this makes it yellow like the rest of the car screen.
	 */
	@NonNull
	public static Place place(@NonNull Context ctx, double latitude, double longitude) {
		return new Place.Builder(CarLocation.create(latitude, longitude))
				.setMarker(new PlaceMarker.Builder().setColor(accent(ctx)).build())
				.build();
	}

	// ---------------------------------------------------------------- text

	/**
	 * Paints a row's secondary line yellow, keeping whatever spans it already carries
	 * (distance, duration). Only for fields whose constraints permit a color span —
	 * {@code Row.addText} and {@code GridItem.setText}. A row title is not one of them.
	 */
	@NonNull
	public static CharSequence colored(@NonNull Context ctx, @NonNull CharSequence text) {
		if (TextUtils.isEmpty(text)) {
			return text;
		}
		SpannableString spannable = new SpannableString(text);
		spannable.setSpan(ForegroundCarColorSpan.create(secondaryText(ctx)),
				0, spannable.length(), Spanned.SPAN_INCLUSIVE_INCLUSIVE);
		return spannable;
	}
}
