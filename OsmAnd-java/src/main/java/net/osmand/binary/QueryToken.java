package net.osmand.binary;

import gnu.trove.list.array.TIntArrayList;
import net.osmand.Collator;
import net.osmand.CollatorStringMatcher;
import net.osmand.util.SearchAlgorithms;

import java.util.*;

public class QueryToken {
    final String query;
    final String suffixQuery;
    final List<Prefix> prefixes;
    final Collator collator;
    final CollatorStringMatcher.StringMatcherMode matcherMode;
    
    record Prefix(String key, int offset) {}

    class SuffixMask {
        TIntArrayList masks;
        final Prefix prefix;
        private boolean passThrough;
        private List<String> legacyDictionary;
        private List<String> compactDictionary;
        private Set<String> querySuffixTokens;

        SuffixMask(Prefix prefix) {
            this.prefix = prefix;
        }

        void setDictionary(List<String> suffixDictionary) {
            passThrough = suffixDictionary == null;
            if (prefix.key() == null || suffixDictionary == null) {
                return;
            }
            
            if (suffixDictionary.size() == 1 && suffixDictionary.get(0).isEmpty()) {
                passThrough = query != null && CollatorStringMatcher.cmatches(collator, prefix.key(), query, matcherMode);
                return;
            }
            legacyDictionary = suffixDictionary;
        }

        void setCompactDictionary(List<String> suffixDictionary) {
            // Compact OBFs reuse one dictionary for two suffix kinds: partial suffixes and separated
            // suffixes marked by a leading space. The query side removes the matched prefix token once,
            // leaving only separated query tokens that must be present in the atom suffix set.
            compactDictionary = suffixDictionary == null ? Collections.emptyList() : suffixDictionary;
            if (querySuffixTokens == null) {
                querySuffixTokens = new LinkedHashSet<>();
                boolean prefixTokenRemoved = false;
                for (String token : SearchAlgorithms.splitAndNormalize(suffixQuery)) {
                    if (!prefixTokenRemoved && isPrefixToken(token)) {
                        prefixTokenRemoved = true;
                        continue;
                    }
                    querySuffixTokens.add(token);
                }
            }
        }

        boolean shouldPassThrough() {
            return passThrough;
        }

        boolean isCompactMatched(List<Integer> suffixIndexes, String extraSuffix) {
            // Compact matching has two independent checks:
            // 1. the trie key must represent a valid full prefix token, either directly or via a partial suffix;
            // 2. every remaining query token must match a separated suffix from dictionary/index/extraSuffix.
            Set<String> atomSuffixes = new HashSet<>();
            boolean partialMatched = query == null || prefix.key() == null
                    || CollatorStringMatcher.cmatches(collator, prefix.key(), query, matcherMode);
            if (suffixIndexes != null) {
                for (int suffixIndex : suffixIndexes) {
                    if ((suffixIndex & 1) == 1) {
                        // Odd values inline pure decimal separated suffixes and never consume dictionary slots.
                        atomSuffixes.add(String.valueOf(suffixIndex >>> 1));
                    } else {
                        // Even values reference the shared compact dictionary. A leading space marks a
                        // separated suffix; no leading space means a partial suffix for the current trie key.
                        int dictionaryIndex = suffixIndex >>> 1;
                        if (dictionaryIndex >= 0 && dictionaryIndex < compactDictionary.size()) {
                            String suffix = compactDictionary.get(dictionaryIndex);
                            if (suffix.startsWith(" ")) {
                                atomSuffixes.add(suffix.substring(1));
                            } else if (prefix.key() != null
                                    // Absence of the leading-space marker already identifies a partial suffix.
                                    // prefix + suffix is used only to validate the full token behind this trie key.
                                    && CollatorStringMatcher.cmatches(collator, prefix.key() + suffix, query, matcherMode)) {
                                partialMatched = true;
                            }
                        }
                    }
                }
            }
            if (extraSuffix != null && !extraSuffix.isEmpty()) {
                // extraSuffix carries rare suffixes over the 128-entry dictionary cap, using the same
                // leading-space marker convention as dictionary entries.
                for (String suffix : splitExtraSuffixes(extraSuffix)) {
                    if (suffix.startsWith(" ")) {
                        atomSuffixes.add(suffix.substring(1));
                    } else if (prefix.key() != null
                            // Extra partial suffixes follow the same rule: marker absence distinguishes
                            // the kind, and prefix + suffix only checks whether the full token matches.
                            && CollatorStringMatcher.cmatches(collator, prefix.key() + suffix, query, matcherMode)) {
                        partialMatched = true;
                    }
                }
            }
            if (!partialMatched) {
                return false;
            }
            if (querySuffixTokens == null || querySuffixTokens.isEmpty()) {
                return true;
            }
            for (String querySuffixToken : querySuffixTokens) {
                boolean tokenMatched = false;
                for (String atomSuffix : atomSuffixes) {
                    if (CollatorStringMatcher.cmatches(collator, atomSuffix, querySuffixToken, matcherMode)) {
                        tokenMatched = true;
                        break;
                    }
                }
                if (!tokenMatched) {
                    return false;
                }
            }
            return true;
        }

        private List<String> splitExtraSuffixes(String extraSuffix) {
            // Writer serializes over-cap suffixes with String.join(" ", suffixes). Separated suffixes
            // already start with a space marker, so delimiter + marker becomes a double-space boundary.
            List<String> suffixes = new ArrayList<>();
            String[] parts = extraSuffix.split(" ", -1);
            for (int i = 0; i < parts.length; i++) {
                if (parts[i].isEmpty()) {
                    if (i + 1 < parts.length && !parts[i + 1].isEmpty()) {
                        suffixes.add(" " + parts[++i]);
                    }
                } else {
                    suffixes.add(parts[i]);
                }
            }
            return suffixes;
        }

        private boolean isPrefixToken(String token) {
            return token != null && prefix.key() != null
                    && (token.startsWith(prefix.key()) || CollatorStringMatcher.cmatches(collator, prefix.key(), token, matcherMode));
        }
        
        boolean isMatched(int maskIndex, int mask) {
            ensureLegacyMasks();
            return masks != null && maskIndex < masks.size() && (masks.get(maskIndex) & mask) != 0;
        }

        private void ensureLegacyMasks() {
            if (masks != null || legacyDictionary == null || query == null) {
                return;
            }
            // Legacy OBFs use suffixesBitset against the dictionary. Compact OBFs bypass this path and
            // evaluate suffixesBitsetIndex/extraSuffix in isCompactMatched(...).
            masks = new TIntArrayList();
            for (int index = 0; index < legacyDictionary.size(); index++) {
                addSuffix(index, legacyDictionary.get(index));
            }
        }

        private void addSuffix(int index, String suffix) {
            if (suffix == null || index < 0) {
                return;
            }
            String fullKey = prefix.key() + suffix;
            if (CollatorStringMatcher.cmatches(collator, fullKey, query, matcherMode)) {
                int intWordIndex = index >> 5; // word selection where index >> 5 == index / 32
                while (masks.size() <= intWordIndex) { // each int word in masks list holds 32 suffix flags
                    masks.add(0);
                }
                int bitOffset = index & 31; // selection of bit inside the word where index & 31 == index % 32 and stays in 0..31
                int wordMask = 1 << bitOffset; // building a one-bit mask
                int prev = masks.get(intWordIndex);
                masks.set(intWordIndex, prev | wordMask);
            }
        }
    }

    QueryToken(String query, Collator collator, CollatorStringMatcher.StringMatcherMode matcherMode, List<Prefix> prefixes) {
        this(query, query, collator, matcherMode, prefixes);
    }

    QueryToken(String query, String suffixQuery, Collator collator, CollatorStringMatcher.StringMatcherMode matcherMode, List<Prefix> prefixes) {
        this.query = query;
        this.suffixQuery = suffixQuery;
        this.collator = collator;
        this.matcherMode = matcherMode;

        if (prefixes == null || prefixes.isEmpty()) {
            this.prefixes = Collections.emptyList();
        } else {
            this.prefixes = new ArrayList<>(prefixes);
            this.prefixes.sort((left, right) -> {
                int lengthCompare = Integer.compare(right.key.length(), left.key().length());
                if (lengthCompare != 0) {
                    return lengthCompare;
                }
                return left.key().compareTo(right.key());
            });
        }
    }
}
