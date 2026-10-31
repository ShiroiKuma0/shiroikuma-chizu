package net.osmand.plus.chizu;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 白い熊 地図: Latin-script names in katakana, for the Japanese voice. Its reading engine spells out
 * any Latin word it does not know letter by letter, so a Czech street name came out as a string of
 * letter names. Czech spelling is phonetic, so the Czech rules give a Japanese-accented but clear
 * reading (“Strakonická” → ストラコニツカー); elsewhere a rough general reading is used. Only runs of
 * Latin letters are converted; numbers, short all-capital abbreviations and anything already in
 * Japanese are left as they are.
 */
public final class ChizuKana {

	private static final Pattern LATIN_WORD = Pattern.compile("[A-Za-z\\u00C0-\\u024F]+\\.?");
	private static final Pattern LATIN_RUN = Pattern.compile(
			"[A-Za-z\\u00C0-\\u024F]+\\.?(?:[ \\-]+[A-Za-z\\u00C0-\\u024F]+\\.?)*");

	private static final String[][] CZ_ABBREVIATIONS = {
			{"nám.", "náměstí"}, {"tř.", "třída"}, {"nábř.", "nábřeží"}, {"ul.", "ulice"},
			{"sv.", "svatého"}, {"nádr.", "nádraží"}, {"sídl.", "sídliště"}
	};

	// phoneme codes: consonants as strings, vowels as single letters a e i o u, long vowels with ':'
	private static final String VOWELS = "aeiou";

	private ChizuKana() {
	}

	/** {@code text} with every Latin-script name in katakana. */
	public static String toKatakana(String text, boolean czech) {
		if (text == null || text.isEmpty()) {
			return text;
		}
		Matcher m = LATIN_RUN.matcher(text);
		StringBuilder out = new StringBuilder();
		int last = 0;
		while (m.find()) {
			out.append(text, last, m.start());
			boolean nextToDigit = (m.start() > 0 && Character.isDigit(text.charAt(m.start() - 1)))
					|| (m.end() < text.length() && Character.isDigit(text.charAt(m.end())));
			// a road number (D1, E50, R4) stays as it is: the engine reads letter and number well
			out.append(nextToDigit ? m.group() : convertRun(m.group(), czech));
			last = m.end();
		}
		out.append(text.substring(last));
		return out.toString();
	}

	private static String convertRun(String run, boolean czech) {
		StringBuilder sb = new StringBuilder();
		Matcher w = LATIN_WORD.matcher(run);
		while (w.find()) {
			String word = w.group();
			String kana;
			if (isAbbreviation(word)) {
				kana = word; // ČD, OC: the engine spells these, as they should be
			} else {
				kana = convertWord(expand(word, czech), czech);
			}
			if (sb.length() > 0) {
				sb.append('・');
			}
			sb.append(kana);
		}
		return sb.toString();
	}

	private static boolean isAbbreviation(String word) {
		String w = word.endsWith(".") ? word.substring(0, word.length() - 1) : word;
		return w.length() <= 3 && w.equals(w.toUpperCase(Locale.ROOT));
	}

	private static String expand(String word, boolean czech) {
		String lower = word.toLowerCase(Locale.ROOT);
		if (czech) {
			for (String[] a : CZ_ABBREVIATIONS) {
				if (lower.equals(a[0])) {
					return a[1];
				}
			}
		}
		return lower.endsWith(".") ? lower.substring(0, lower.length() - 1) : lower;
	}

	// ---------- letters to phonemes ----------

	private static List<String> phonemes(String w, boolean czech) {
		List<String> p = new ArrayList<>();
		int n = w.length();
		for (int i = 0; i < n; i++) {
			char c = w.charAt(i);
			char next = i + 1 < n ? w.charAt(i + 1) : 0;
			switch (c) {
				case 'a': p.add("a"); break;
				case 'á': p.add("a:"); break;
				case 'e': case 'ë': p.add("e"); break;
				case 'é': p.add("e:"); break;
				case 'ě':
					softenBefore(p, true);
					p.add("e");
					break;
				case 'i': case 'ï':
					if (czech) {
						softenBefore(p, false);
					}
					p.add("i");
					break;
				case 'í':
					if (czech) {
						softenBefore(p, false);
					}
					p.add("i:");
					break;
				case 'y': p.add(czech ? "i" : (isVowelChar(next) ? "j" : "i")); break;
				case 'ý': p.add("i:"); break;
				case 'o': p.add("o"); break;
				case 'ó': p.add("o:"); break;
				case 'ö': p.add("e"); break;
				case 'u': p.add("u"); break;
				case 'ú': case 'ů': p.add("u:"); break;
				case 'ü': p.add("i"); break;
				case 'c':
					if (next == 'h') {
						p.add(czech ? "h" : "ch");
						i++;
					} else if (czech) {
						p.add("ts");
					} else {
						p.add(next == 'e' || next == 'i' || next == 'y' ? "s" : "k");
					}
					break;
				case 'č': p.add("ch"); break;
				case 'š': p.add("sh"); break;
				case 'ž': p.add("zh"); break;
				case 'ř': p.add("rzh"); break;
				case 'ď': p.add("dj"); break;
				case 'ť': p.add("tj"); break;
				case 'ň': p.add("nj"); break;
				case 'j': p.add(czech ? "j" : "zh"); break;
				case 'w': p.add(czech ? "v" : "w"); break;
				case 'q': p.add("k"); if (czech) { p.add("v"); } break;
				case 'x': p.add("k"); p.add("s"); break;
				case 's':
					if (!czech && next == 'c' && i + 2 < n && w.charAt(i + 2) == 'h') {
						p.add("sh"); // German sch
						i += 2;
					} else if (!czech && next == 'h') {
						p.add("sh");
						i++;
					} else {
						p.add("s");
					}
					break;
				case 'ß': p.add("s"); break;
				default:
					if (c >= 'a' && c <= 'z') {
						p.add(String.valueOf(c));
					}
			}
		}
		if (czech) {
			devoice(p);
		}
		// a doubled letter is one sound (Barrandov, Schönbrunner)
		for (int i = p.size() - 1; i > 0; i--) {
			if (!isVowel(p.get(i)) && p.get(i).equals(p.get(i - 1))) {
				p.remove(i);
			}
		}
		// t + s is the one sound of c (Vinohradská → -atská → ヴィノフラツカー)
		for (int i = p.size() - 1; i > 0; i--) {
			if ("s".equals(p.get(i)) && "t".equals(p.get(i - 1))) {
				p.set(i - 1, "ts");
				p.remove(i);
			}
		}
		return p;
	}

	private static boolean isVowelChar(char c) {
		return "aeiouáéíóúůýě".indexOf(c) >= 0;
	}

	/** Czech d t n before i, í, ě are soft; b p v f m before ě take a j (mě → mje). */
	private static void softenBefore(List<String> p, boolean eCaron) {
		if (p.isEmpty()) {
			if (eCaron) {
				p.add("j");
			}
			return;
		}
		int k = p.size() - 1;
		String c = p.get(k);
		switch (c) {
			case "d": p.set(k, "dj"); break;
			case "t": p.set(k, "tj"); break;
			case "n": p.set(k, "nj"); break;
			default:
				if (eCaron && ("b".equals(c) || "p".equals(c) || "v".equals(c) || "f".equals(c) || "m".equals(c))) {
					p.add("j");
				}
		}
	}

	private static final String VOICELESS = "|p|t|k|s|f|h|ts|ch|sh|tj|";

	/** Voiced consonants turn voiceless at the end of a word and before a voiceless one (Vinohradská → -atská). */
	private static void devoice(List<String> p) {
		for (int i = p.size() - 1; i >= 0; i--) {
			String c = p.get(i);
			boolean end = i == p.size() - 1;
			boolean beforeVoiceless = !end && VOICELESS.contains("|" + p.get(i + 1) + "|");
			if (!end && !beforeVoiceless) {
				continue;
			}
			switch (c) {
				case "d": p.set(i, "t"); break;
				case "z": p.set(i, "s"); break;
				case "zh": p.set(i, "sh"); break;
				case "b": p.set(i, "p"); break;
				case "v": p.set(i, "f"); break;
				case "dj": p.set(i, "tj"); break;
				case "g": p.set(i, "k"); break;
				case "rzh": p.set(i, "rsh"); break;
			}
		}
		// ř after a voiceless consonant is voiceless too (Příkopy → プシーコピ)
		for (int i = 1; i < p.size(); i++) {
			if ("rzh".equals(p.get(i)) && VOICELESS.contains("|" + p.get(i - 1) + "|")) {
				p.set(i, "rsh");
			}
		}
	}

	// ---------- phonemes to katakana ----------

	private static boolean isVowel(String ph) {
		return VOWELS.indexOf(ph.charAt(0)) >= 0;
	}

	private static String convertWord(String word, boolean czech) {
		List<String> p = phonemes(word, czech);
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < p.size(); i++) {
			String ph = p.get(i);
			if (isVowel(ph)) {
				sb.append(vowelKana(ph.charAt(0)));
				if (ph.length() > 1) {
					sb.append('ー');
				}
				continue;
			}
			String next = i + 1 < p.size() ? p.get(i + 1) : null;
			if ("j".equals(ph)) {
				// j between a vowel and a consonant (or at the end) is the second half of a diphthong: spojka → スポイカ
				boolean afterVowel = i > 0 && isVowel(p.get(i - 1));
				if (next == null || !isVowel(next)) {
					sb.append(afterVowel ? "イ" : "イ");
					continue;
				}
			}
			if (next != null && "j".equals(next) && i + 2 < p.size() && isVowel(p.get(i + 2))
					&& !"j".equals(ph)) {
				// consonant + j + vowel: mje → ミェ, bje → ビェ
				sb.append(syllable(ph, 'i')).append(smallVowel(p.get(i + 2).charAt(0)));
				if (p.get(i + 2).length() > 1) {
					sb.append('ー');
				}
				i += 2;
				continue;
			}
			if (next != null && isVowel(next)) {
				sb.append(syllable(ph, next.charAt(0)));
				if (next.length() > 1) {
					sb.append('ー');
				}
				i++;
			} else {
				sb.append(alone(ph, next));
			}
		}
		return sb.toString();
	}

	private static String vowelKana(char v) {
		return String.valueOf("アエイオウ".charAt("aeiou".indexOf(v)));
	}

	private static String smallVowel(char v) {
		return String.valueOf("ァェィォゥ".charAt("aeiou".indexOf(v)));
	}

	/** Consonant + vowel; the rows in the order a i u e o. */
	private static String syllable(String c, char v) {
		int k = "aiueo".indexOf(v);
		String[] row;
		switch (c) {
			case "k": row = new String[] {"カ", "キ", "ク", "ケ", "コ"}; break;
			case "g": row = new String[] {"ガ", "ギ", "グ", "ゲ", "ゴ"}; break;
			case "s": row = new String[] {"サ", "シ", "ス", "セ", "ソ"}; break;
			case "z": row = new String[] {"ザ", "ジ", "ズ", "ゼ", "ゾ"}; break;
			case "t": row = new String[] {"タ", "ティ", "トゥ", "テ", "ト"}; break;
			case "d": row = new String[] {"ダ", "ディ", "ドゥ", "デ", "ド"}; break;
			case "n": row = new String[] {"ナ", "ニ", "ヌ", "ネ", "ノ"}; break;
			case "h": row = new String[] {"ハ", "ヒ", "フ", "ヘ", "ホ"}; break;
			case "f": row = new String[] {"ファ", "フィ", "フ", "フェ", "フォ"}; break;
			case "m": row = new String[] {"マ", "ミ", "ム", "メ", "モ"}; break;
			case "j": row = new String[] {"ヤ", "イ", "ユ", "イェ", "ヨ"}; break;
			case "r": case "l": row = new String[] {"ラ", "リ", "ル", "レ", "ロ"}; break;
			case "v": row = new String[] {"ヴァ", "ヴィ", "ヴ", "ヴェ", "ヴォ"}; break;
			case "w": row = new String[] {"ワ", "ウィ", "ウ", "ウェ", "ウォ"}; break;
			case "b": row = new String[] {"バ", "ビ", "ブ", "ベ", "ボ"}; break;
			case "p": row = new String[] {"パ", "ピ", "プ", "ペ", "ポ"}; break;
			case "ts": row = new String[] {"ツァ", "ツィ", "ツ", "ツェ", "ツォ"}; break;
			case "ch": case "tj": row = new String[] {"チャ", "チ", "チュ", "チェ", "チョ"}; break;
			case "sh": case "rsh": row = new String[] {"シャ", "シ", "シュ", "シェ", "ショ"}; break;
			case "zh": case "dj": case "rzh": row = new String[] {"ジャ", "ジ", "ジュ", "ジェ", "ジョ"}; break;
			case "nj": row = new String[] {"ニャ", "ニ", "ニュ", "ニェ", "ニョ"}; break;
			default: return alone(c, null) + vowelKana(v);
		}
		return row[k];
	}

	/** A consonant with no vowel after it. */
	private static String alone(String c, String next) {
		switch (c) {
			case "k": return "ク";
			case "g": return "グ";
			case "s": return "ス";
			case "z": return "ズ";
			case "t": return "ト";
			case "d": return "ド";
			case "n": return "ン";
			case "h": return "フ";
			case "f": return "フ";
			case "m": return "ム";
			case "j": return "イ";
			case "r": case "l": return "ル";
			case "v": return "ヴ";
			case "w": return "ウ";
			case "b": return "ブ";
			case "p": return "プ";
			case "ts": return "ツ";
			case "ch": return "チ";
			case "tj": return "チ";
			case "sh": case "rsh": return "シュ";
			case "zh": case "rzh": return "ジュ";
			case "dj": return "ジ";
			case "nj": return "ニ";
			default: return "";
		}
	}
}
