package net.osmand.plus.chizu;

import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.util.AttributeSet;
import android.view.View;
import android.widget.Button;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatButton;
import androidx.appcompat.widget.AppCompatCheckedTextView;
import androidx.core.content.ContextCompat;

import com.google.android.material.theme.MaterialComponentsViewInflater;

import net.osmand.plus.R;

/**
 * 白い熊 地図 +107: the themes' {@code viewInflaterClass} — the one place every stock dialog passes through.
 * Upstream builds some 150 alert dialogs; only the fork's own were given pill buttons, so every other one
 * showed bare-text OK / Cancel (the "Overlay map" list, 2026-11-22). Here the three buttons of a dialog's
 * button bar become pills when they are attached (the positive one the main pill, red when it deletes),
 * and a row of a choice list whose label ends in "…" — "Add more…" — is drawn as the action it is, not as
 * one more radio choice.
 */
@Keep
public class ChizuViewInflater extends MaterialComponentsViewInflater {

	@NonNull
	@Override
	protected AppCompatButton createButton(@NonNull Context context, @NonNull AttributeSet attrs) {
		AppCompatButton button = super.createButton(context, attrs);
		int id = button.getId();
		if (id == android.R.id.button1 || id == android.R.id.button2 || id == android.R.id.button3) {
			// styled on attach: by then the dialog has set the label and hidden the buttons it does not use
			button.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
				@Override
				public void onViewAttachedToWindow(@NonNull View v) {
					Button b = (Button) v;
					boolean main = b.getId() == android.R.id.button1;
					ChizuPills.styleButton(b, main, main && ChizuPills.deletes(b.getContext(), b.getText()));
				}

				@Override
				public void onViewDetachedFromWindow(@NonNull View v) {
				}
			});
		}
		return button;
	}

	@NonNull
	@Override
	protected AppCompatCheckedTextView createCheckedTextView(@NonNull Context context, @NonNull AttributeSet attrs) {
		return new ChoiceRow(context, attrs);
	}

	/**
	 * A row of a single- or multi-choice list. A label ending in "…" opens something instead of choosing:
	 * that row loses its selection mark and becomes a full-width pill with a › at its end. Decided on every
	 * {@code setText}, so a recycled row follows the label it is given.
	 */
	public static class ChoiceRow extends AppCompatCheckedTextView {

		// no initialisers: setText runs inside the super constructor, before they would
		private boolean action;
		private boolean captured;
		private Drawable[] marks;
		private Drawable ground;
		private int[] padding;

		public ChoiceRow(@NonNull Context context, @Nullable AttributeSet attrs) {
			super(context, attrs);
		}

		@Override
		public void setText(CharSequence text, BufferType type) {
			super.setText(text, type);
			String label = text == null ? "" : text.toString().trim();
			boolean opens = label.endsWith("…") || label.endsWith("...");
			if (opens != action) {
				action = opens;
				if (opens) {
					showAction();
				} else {
					showChoice();
				}
			}
		}

		private void showAction() {
			if (!captured) {
				captured = true;
				marks = getCompoundDrawablesRelative();
				ground = getBackground();
				padding = new int[] {getPaddingStart(), getPaddingTop(), getPaddingEnd(), getPaddingBottom()};
			}
			Context c = getContext();
			float d = getResources().getDisplayMetrics().density;
			int accent = ChizuMenuPills.accent(c);
			// the mark's place stays, empty, so the label keeps the edge of the choices above it
			Drawable spacer = null;
			if (marks[0] != null) {
				spacer = new ColorDrawable(0);
				spacer.setBounds(0, 0, marks[0].getIntrinsicWidth(), marks[0].getIntrinsicHeight());
			}
			Drawable chevron = ContextCompat.getDrawable(c, R.drawable.chizu_menu_chevron);
			if (chevron != null) {
				chevron = chevron.mutate();
				chevron.setTint(accent);
				chevron.setBounds(0, 0, chevron.getIntrinsicWidth(), chevron.getIntrinsicHeight());
			}
			setCompoundDrawablesRelative(spacer, null, chevron, null);
			int side = Math.round(8 * d);
			int above = Math.round(ChizuMenuPills.PILL_MARGIN_DP * d);
			setBackground(new InsetDrawable(ChizuPills.drawable(c, accent, 0, false), side, above, side, above));
			// an inset drawable brings padding of its own; the row keeps the list's
			setPaddingRelative(padding[0], padding[1], padding[2], padding[3]);
		}

		private void showChoice() {
			if (!captured) {
				return;
			}
			setCompoundDrawablesRelative(marks[0], marks[1], marks[2], marks[3]);
			setBackground(ground);
			setPaddingRelative(padding[0], padding[1], padding[2], padding[3]);
		}
	}
}
