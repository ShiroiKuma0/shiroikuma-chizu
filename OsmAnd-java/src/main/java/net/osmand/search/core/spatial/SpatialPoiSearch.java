package net.osmand.search.core.spatial;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import gnu.trove.list.array.TIntArrayList;
import gnu.trove.set.hash.TLongHashSet;
import net.osmand.binary.BinaryMapIndexReader;
import net.osmand.binary.BinaryMapPoiReaderAdapter.PoiRegion;
import net.osmand.binary.BinaryMapPoiReaderAdapter.PoiSubType;
import net.osmand.osm.AbstractPoiType;
import net.osmand.osm.MapPoiTypes;
import net.osmand.osm.PoiCategory;
import net.osmand.osm.PoiFilter;
import net.osmand.osm.PoiType;
import net.osmand.search.core.TopIndexFilter;
import net.osmand.search.core.spatial.SpatialSearchToken.NameIndexAtom;
import net.osmand.search.core.spatial.SpatialSearchToken.NameIndexAtomXY;
import net.osmand.search.core.spatial.SpatialTextSearch.SpatialSearchFileCache;
import net.osmand.search.core.spatial.SpatialTextSearch.SpatialSearchGlobalCache;
import net.osmand.util.SearchAlgorithms;

public class SpatialPoiSearch {

	final MapPoiTypes poiTypes;
	StringPrefixTree<SpatialPoiType> poiTypesIndex = new StringPrefixTree<>();
	AtomicInteger ids = new AtomicInteger();
	Map<String, SpatialPoiType> byKey = new ConcurrentHashMap<>();
	Map<Integer, SpatialPoiType> byId = new ConcurrentHashMap<>();
	
	public static class SpatialPoiType {
		final AbstractPoiType singleType;
		final String poiAdditional;
		final List<String> names = new ArrayList<String>();
		final String key;
		final int id;
		List<AbstractPoiType> parentTypes;

		public SpatialPoiType(AbstractPoiType pt, int id) {
			this.singleType = pt;
			this.key = pt.getKeyName();
			this.id = id;
			this.poiAdditional = null;
		}
		
		public SpatialPoiType(String additional, String key, int id) {
			this.singleType = null;
			this.key = key;
			this.id = id;
			this.poiAdditional = additional;
		}
		
	}

	public SpatialPoiSearch(MapPoiTypes types) {
		this.poiTypes = types;
		for (PoiCategory pc : poiTypes.getCategories()) {
			if (pc == poiTypes.getOtherMapCategory()) {
				continue;
			}
			addToIndex(pc, null);
			for (PoiFilter pt : pc.getPoiFilters()) {
				if (pt.isTopVisible()) {
					addToIndex(pt, null);
				}
			}
			for (PoiType pt : pc.getPoiTypes()) {
				if (pt.isReference()) {
					continue;
				}
				addToIndex(pt, null);
				for (PoiType add : pt.getPoiAdditionals()) {
					if (add.isTopVisible() && !"no".equals(poiTypes.getBasePoiName(add))) {
						addToIndex(add, pt);
					}
				}
			}
		}
	}


	private void addToIndex(AbstractPoiType pt, PoiType parent) {
		if (byKey.containsKey(pt.getKeyName())) {
			if (pt.isAdditional()) {
				byKey.get(pt.getKeyName()).parentTypes.add(parent); 
				return;
			} else {
				throw new IllegalStateException();
			}
		}
		String basePoiName = poiTypes.getBasePoiName(pt);
		SpatialPoiType poiType = new SpatialPoiType(pt, ids.getAndIncrement());
		if (parent != null) {
			poiType.parentTypes = new ArrayList<>();
			poiType.parentTypes.add(parent);
		}
		if (!basePoiName.equals(pt.getTranslation())) {
			String[] split = pt.getTranslation().split(";");
			for (String tr : split) {
				poiType.names.add(SearchAlgorithms.alignChars(tr.trim()));
			}
		}
		addToIndex(basePoiName, poiType);
	}


	private void addToIndex(String basePoiName, SpatialPoiType poiType) {
		poiType.names.add(basePoiName);
		for (String name : poiType.names) {
			poiTypesIndex.put(name, poiType);
		}
		SpatialSearchContext.checkPoiTypeId(poiType.id);
		
		byId.put(poiType.id, poiType);
		byKey.put(poiType.key, poiType);
	}

	public void init(SpatialSearchGlobalCache cache, SpatialSearchFileCache fc, BinaryMapIndexReader bir,
			PoiRegion poiRegion) {
		List<String> cats = poiRegion.getCategories();
		List<List<String>> subcategories = poiRegion.getSubcategories();
		TIntArrayList categoryFreqs = poiRegion.getCategoryFreqs();
		List<TIntArrayList> subcatFreqs = poiRegion.getSubcategoryFreqs();
		for (int i = 0; i < cats.size(); i++) {
			List<String> lst = subcategories.get(i);
			int f = i < categoryFreqs.size() ? categoryFreqs.get(i) : 0;
			fc.poiFrequencies.put(cats.get(i), f);
			for (int j = 0; j < lst.size(); j++) {
				int ft = i < subcatFreqs.size() && j < subcatFreqs.get(i).size() ? subcatFreqs.get(i).get(j) : 0;
				fc.poiFrequencies.put(lst.get(j), ft);
			}
		}
		
		
		for (PoiSubType subType : poiRegion.getSubTypes()) {
			if (subType.text) {
				continue;
			}
			if (subType.isTopIndex()) {
				List<String> possibleValues = subType.possibleValues;
				for (int k = 0; k < possibleValues.size(); k++) {
					String topValueName = possibleValues.get(k);
					String valueKey = TopIndexFilter.getValueKey(topValueName);
					String fullKey = subType.name + "_" + valueKey;
					SpatialPoiType topValue = byKey.get(fullKey);
					if (topValue == null) {
						String poiTranslation = poiTypes.getPoiTranslation(valueKey, false);
						topValue = new SpatialPoiType(topValueName, fullKey, ids.getAndIncrement());
						if (!topValueName.equalsIgnoreCase(poiTranslation) && poiTranslation != null) {
							topValue.names.add(poiTranslation);
						}
						addToIndex(topValueName, topValue);
					}
					int freq = k < subType.possibleValuesFreqs.size() ? subType.possibleValuesFreqs.get(k) : 0;
					fc.poiFrequencies.put(topValue.key, freq);
				}
			}
			SpatialPoiType indSubType = byKey.get(subType.name);
			if (indSubType == null) {
				// skip top level additional
				continue;
			}
			fc.poiFrequencies.put(indSubType.key, subType.frequency);
		}
	}

	private record PoiCatSearch(SpatialPoiType pt, List<SpatialSearchToken> tokens, List<NameIndexAtom> atoms, int freq) implements Comparable<PoiCatSearch> {

		@Override
		public int compareTo(PoiCatSearch o) {
			int i1 = -Integer.compare(tokens.size(), o.tokens.size());
			if (i1 != 0) {
				return i1;
			}
			return -Integer.compare(freq, o.freq);
		}
	}
	
	public void processPoiCategories(SpatialSearchContext ctx, List<SpatialSearchToken> tokens) {
		Map<SpatialPoiType, PoiCatSearch> res = new LinkedHashMap<>();
		for (SpatialSearchToken t : tokens) {
			List<SpatialPoiType> poiTypes = poiTypesIndex.match(t.getPrefixMatcher(ctx.stats));
			for (SpatialPoiType a : poiTypes) {
				boolean match = false;
				for (String n : a.names) {
					if (t.getMainCollator().matches(n)) {
						match = true;
						break;
					}
				}
				if (match) {
					int total = 0;
					for (SpatialSearchFileCache l : ctx.internalFile) {
						if (l.poiFrequencies != null) {
							Integer freq = l.poiFrequencies.get(a.key);
							if (freq != null) {
								total += freq;
							}
						}
					}
//					System.out.println(a.names + " " + a.key + " " + total);
					PoiCatSearch cs = res.get(a);
					if (cs == null) {
						cs = new PoiCatSearch(a, new ArrayList<>(), new ArrayList<>(), total);
						res.put(a, cs);
					}
					if (cs.tokens.contains(t)) {
						continue;
					}
					NameIndexAtomXY xy = new NameIndexAtomXY(null, null, null);
					NameIndexAtom atom = new NameIndexAtom(a.names.get(0), SpatialSearchToken.POI_CATEGORY_TYPE, 
							a.id, 0, null, false, -total, total, xy, 0);
					cs.atoms.add(atom);
					cs.tokens.add(t);
				}
			}
		}
		
		List<PoiCatSearch> finalRes = new ArrayList<>(res.values());
		Collections.sort(finalRes);
		if (finalRes.size() > ctx.settings.LIMIT_POI_CATEGORY_BY_FREQ) {
			finalRes = finalRes.subList(0, ctx.settings.LIMIT_POI_CATEGORY_BY_FREQ);
		}
		for (PoiCatSearch pc : finalRes) {
			for (int i = 0; i < pc.tokens.size(); i++) {
				pc.tokens.get(i).addAtom(pc.atoms.get(i));
			}
		}
	}

/////////////////////////////////////////////////////////////////////////////////////////////
/////////////////////////////////////////////////////////////////////////////////////////////
//	public static class SearchAmenityTypesAPI extends SearchBaseAPI {
//
//		public final static String STD_POI_FILTER_PREFIX = "std_";
//		private static final int BBOX_RADIUS = 10000;
//
//		private Map<String, PoiType> translatedNames = new LinkedHashMap<>();
//		private List<AbstractPoiType> topVisibleFilters;
//		private List<PoiCategory> categories;
//		private List<CustomSearchPoiFilter> customPoiFilters = new ArrayList<>();
//		private Map<String, Integer> activePoiFilters = new HashMap<>();
//		private MapPoiTypes types;

//
//		public SearchAmenityTypesAPI(MapPoiTypes types) {
//			super(ObjectType.POI_TYPE);
//			this.types = types;
//		}
//
//		public void clearCustomFilters() {
//			this.customPoiFilters.clear();
//			this.activePoiFilters.clear();
//		}
//
//		public void addCustomFilter(CustomSearchPoiFilter poiFilter, int priority) {
//			this.customPoiFilters.add(poiFilter);
//			if (priority > 0) {
//				this.activePoiFilters.put(poiFilter.getFilterId(), priority);
//			}
//		}
//
//		public void setActivePoiFiltersByOrder(List<String> filterOrder) {
//			for (int i = 0; i < filterOrder.size(); i++) {
//				this.activePoiFilters.put(filterOrder.get(i), i);
//			}
//		}
//
//		public Map<String, PoiTypeResult> getPoiTypeResults(NameStringMatcher nm, NameStringMatcher nmAdditional) {
//			Map<String, PoiTypeResult> results = new LinkedHashMap<>();
//			for (AbstractPoiType pf : topVisibleFilters) {
//				PoiTypeResult res = checkPoiType(nm, pf);
//				if(res != null) {
//					results.put(res.pt.getKeyName(), res);
//				}
//			}
//			// don't spam results with unsearchable additionals like 'description', 'email', ...
//			// if (nmAdditional != null) {
//			//	addAditonals(nmAdditional, results, types.getOtherMapCategory());
//			// }
//			for (PoiCategory c : categories) {
//				PoiTypeResult res = checkPoiType(nm, c);
//				if (res != null) {
//					results.put(res.pt.getKeyName(), res);
//				}
//				if (nmAdditional != null) {
//					addAditonals(nmAdditional, results, c);
//				}
//				for (PoiFilter pf : c.getPoiFilters()) {
//					PoiTypeResult filtRes = checkPoiType(nm, pf);
//					if (filtRes != null) {
//						results.put(filtRes.pt.getKeyName(), filtRes);
//					}
//				}
//			}
//			Map<String, PoiTypeResult> additionals = new LinkedHashMap<>();
//			Iterator<Entry<String, PoiType>> it = translatedNames.entrySet().iterator();
//			while (it.hasNext()) {
//				Entry<String, PoiType> e = it.next();
//				PoiType pt = e.getValue();
//				if (pt.getCategory() != types.getOtherMapCategory() && !pt.isReference()) {
//					PoiTypeResult res = checkPoiType(nm, pt);
//					if (res != null) {
//						results.put(res.pt.getKeyName(), res);
//					}
//					if (nmAdditional != null) {
//						addAditonals(nmAdditional, additionals, pt);
//					}
//				}
//			}
//			results.putAll(additionals); // results ordered by: top, categories, types, additional
//			return results;
//		}
//
//		private void addAditonals(NameStringMatcher nm, Map<String, PoiTypeResult> results, AbstractPoiType pt) {
//			List<PoiType> additionals = pt.getPoiAdditionals();
//			if (additionals != null) {
//				for (PoiType a : additionals) {
//					if (a.getReferenceType() != null) {
//						// ignore reference types as duplicates
//						continue;
//					}
//					PoiTypeResult existingResult = results.get(a.getKeyName());
//					if (existingResult != null) {
//						PoiAdditionalCustomFilter f ;
//						if (existingResult.pt instanceof PoiAdditionalCustomFilter) {
//							f = (PoiAdditionalCustomFilter) existingResult.pt;
//						} else {
//							f = new PoiAdditionalCustomFilter(types, (PoiType) existingResult.pt);
//						}
//						if (!f.additionalPoiTypes.contains(a)) {
//							f.additionalPoiTypes.add(a);
//						}
//						existingResult.pt = f;
//					} else {
//						String enTranslation = a.getEnTranslation().toLowerCase();
//						if (!"no".equals(enTranslation) ) {
//							PoiTypeResult ptr = checkPoiType(nm, a);
//							if (ptr != null && ptr.pt != null && ptr.pt.isTopVisible()) {
//								results.put(a.getKeyName(), ptr);
//							}
//						}
//					}
//				}
//			}
//		}
//
//		private PoiTypeResult checkPoiType(NameStringMatcher nm, AbstractPoiType pf) {
//			PoiTypeResult res = null;
//			if (nm.matches(pf.getTranslation())) {
//				res = addIfMatch(nm, pf.getTranslation(), pf, res);
//			}
//			if (nm.matches(pf.getEnTranslation())) {
//				res = addIfMatch(nm, pf.getEnTranslation(), pf, res);
//			}
//			if (nm.matches(pf.getKeyName())) {
//				res = addIfMatch(nm, pf.getKeyName().replace('_', ' '), pf, res);
//			}
//
//			if (nm.matches(pf.getSynonyms())) {
//				String[] synonyms = pf.getSynonyms().split(";");
//				for (String synonym : synonyms) {
//					res = addIfMatch(nm, synonym, pf, res);
//				}
//			}
//			return res;
//		}
//
//		private PoiTypeResult addIfMatch(NameStringMatcher nm, String s, AbstractPoiType pf, PoiTypeResult res) {
//			if (nm.matches(s)) {
//				if (res == null) {
//					res = new PoiTypeResult();
//					res.pt = pf;
//				}
//				res.foundWords.add(s);
//
//			}
//			return res;
//		}
//
//		private void initPoiTypes() {
//			if (translatedNames.isEmpty()) {
//				translatedNames = types.getAllTranslatedNames(false);
//				topVisibleFilters = types.getTopVisibleFilters();
//				topVisibleFilters.remove(types.getOsmwiki());
//				categories = types.getCategories(false);
//
//				if (DISPLAY_DEFAULT_POI_TYPES) {
//					List<String> order = new ArrayList<>();
//					for (AbstractPoiType p : topVisibleFilters) {
//						order.add(getStandardFilterId(p));
//					}
//					CustomSearchPoiFilter nearestPois = new CustomSearchPoiFilter() {
//
//						@Override
//						public boolean isEmpty() {
//							return false;
//						}
//
//						@Override
//						public boolean accept(PoiCategory type, String subcategory) {
//							return true;
//						}
//
//						@Override
//						public ResultMatcher<Amenity> wrapResultMatcher(ResultMatcher<Amenity> matcher) {
//							return matcher;
//						}
//
//						@Override
//						public String getName() {
//							return "Neareset POIs";
//						}
//
//						@Override
//						public Object getIconResource() {
//							return null;
//						}
//
//						@Override
//						public String getFilterId() {
//							return "nearest_pois";
//						}
//					};
//					setActivePoiFiltersByOrder(order);
//					addCustomFilter(nearestPois, 100);
//				}
//			}
//		}
//
//		@Override
//		public boolean search(SearchPhrase phrase, SearchResultMatcher resultMatcher) throws IOException {
//			boolean showTopFiltersOnly = !phrase.isUnknownSearchWordPresent();
//			NameStringMatcher nm = phrase.getFirstUnknownNameStringMatcher();
//
//			initPoiTypes();
//			if (showTopFiltersOnly) {
//				for (AbstractPoiType pt : topVisibleFilters) {
//					SearchResult res = new SearchResult(phrase);
//					res.localeName = pt.getTranslation();
//					res.object = pt;
//					addPoiTypeResult(phrase, resultMatcher, showTopFiltersOnly, getStandardFilterId(pt), res);
//				}
//
//			} else {
//				boolean includeAdditional = !phrase.hasMoreThanOneUnknownSearchWord();
//				NameStringMatcher nmAdditional = includeAdditional ?
//						new NameStringMatcher(phrase.getFirstUnknownSearchWord(), StringMatcherMode.CHECK_EQUALS_FROM_SPACE) : null;
//				Map<String, PoiTypeResult> poiTypes = getPoiTypeResults(nm, nmAdditional);
//				poiTypes = filterTypes(poiTypes);
//				PoiTypeResult wikiCategory = poiTypes.get(OSM_WIKI_CATEGORY);
//				PoiTypeResult wikiType = poiTypes.get(WIKI_PLACE);
//				if (wikiCategory != null && wikiType != null) {
//					poiTypes.remove(WIKI_PLACE);
//				}
//				for (PoiTypeResult ptr : poiTypes.values()) {
//					boolean match = !phrase.isFirstUnknownSearchWordComplete();
//					if (!match) {
//						for (String foundName : ptr.foundWords) {
//							CollatorStringMatcher csm = new CollatorStringMatcher(foundName, StringMatcherMode.CHECK_ONLY_STARTS_WITH);
//							match = csm.matches(phrase.getUnknownSearchPhrase());
//							if (match) {
//								break;
//							}
//						}
//					}
//					if (match) {
//						SearchResult res = new SearchResult(phrase);
//						if (OSM_WIKI_CATEGORY.equals(ptr.pt.getKeyName())) {
//							res.localeName = ptr.pt.getTranslation() + " (" + types.getAllLanguagesTranslationSuffix() + ")";
//						} else {
//							res.localeName = ptr.pt.getTranslation();
//						}
//						res.object = ptr.pt;
//						addPoiTypeResult(phrase, resultMatcher, showTopFiltersOnly, getStandardFilterId(ptr.pt),
//								res);
//					}
//				}
//			}
//			for (int i = 0; i < customPoiFilters.size(); i++) {
//				CustomSearchPoiFilter csf = customPoiFilters.get(i);
//				if (showTopFiltersOnly || nm.matches(csf.getName())) {
//					SearchResult res = new SearchResult(phrase);
//					res.localeName = csf.getName();
//					res.object = csf;
//					addPoiTypeResult(phrase, resultMatcher, showTopFiltersOnly, csf.getFilterId(), res);
//				}
//			}
//			searchTopIndexPoiAdditional(phrase, resultMatcher);
//			return true;
//		}
//		
//		// filter out types that are not in the category
//		private Map<String, PoiTypeResult> filterTypes(Map<String, PoiTypeResult> poiTypes) {
//			Map<String, PoiTypeResult> filtered = new LinkedHashMap<>();
//			for (PoiTypeResult ptr : poiTypes.values()) {
//				if (ptr.pt instanceof PoiAdditionalCustomFilter pt) {
//					if (pt.getPoiAdditionalCategory() != null) {
//						filtered.put(ptr.pt.getKeyName(), ptr);
//					} else {
//						pt.additionalPoiTypes.forEach(t -> {
//							if (t.getPoiAdditionalCategory() != null) {
//								filtered.put(ptr.pt.getKeyName(), ptr);
//							}
//						});
//					}
//				} else {
//					filtered.put(ptr.pt.getKeyName(), ptr);
//				}
//			}
//			return filtered;
//		}
//
//		private void addPoiTypeResult(SearchPhrase phrase, SearchResultMatcher resultMatcher, boolean showTopFiltersOnly,
//									  String stdFilterId, SearchResult res) {
//			res.priorityDistance = 0;
//			res.objectType = ObjectType.POI_TYPE;
//			res.firstUnknownWordMatches = true;
//			if (showTopFiltersOnly) {
//				if (activePoiFilters.containsKey(stdFilterId)) {
//					res.priority = getPoiTypePriority(stdFilterId);
//					resultMatcher.publish(res);
//				}
//			} else {
//				phrase.countUnknownWordsMatchMainResult(res);
//				res.priority = SEARCH_AMENITY_TYPE_PRIORITY;
//				resultMatcher.publish(res);
//			}
//		}
//
//		private int getPoiTypePriority(String stdFilterId) {
//			Integer i = activePoiFilters.get(stdFilterId);
//			if ( i == null) {
//				return SEARCH_AMENITY_TYPE_PRIORITY;
//			}
//			return SEARCH_AMENITY_TYPE_PRIORITY + i.intValue();
//		}
//
//
//
//		public String getStandardFilterId(AbstractPoiType poi) {
//			return STD_POI_FILTER_PREFIX + poi.getKeyName();
//		}
//
//		@Override
//		public boolean isSearchMoreAvailable(SearchPhrase phrase) {
//			return false;
//		}
//
//		@Override
//		public int getSearchPriority(SearchPhrase p) {
//			if (p.hasObjectType(ObjectType.POI) || p.hasObjectType(ObjectType.POI_TYPE)) {
//				return -1;
//			}
//			if (!p.isNoSelectedType() && !p.isUnknownSearchWordPresent()) {
//				return -1;
//			}
//			SearchWord lastSelectedWord = p.getLastSelectedWord();
//			if (lastSelectedWord != null && ObjectType.isAddress(lastSelectedWord.getType())) {
//				return -1;
//			}
//			return SEARCH_AMENITY_TYPE_API_PRIORITY;
//		}
//

//	}

//	private Map<BinaryMapIndexReader, Set<String>> poiAdditionalTopIndexCache = new HashMap<>();
//
//	private void initPoiAdditionalTopIndex(BinaryMapIndexReader r) throws IOException {
//		if (poiAdditionalTopIndexCache.containsKey(r)) {
//			return;
//		}
//		List<PoiSubType> poiSubTypes = r.getTopIndexSubTypes();
//		if (poiSubTypes.size() == 0) {
//			return;
//		}
//		Set<String> names = new HashSet<>();
//		for (PoiSubType subType : poiSubTypes) {
//			if (subType.possibleValues == null) {
//				continue;
//			}
//			names.addAll(subType.possibleValues);
//		}
//		List<String> translation = new ArrayList<>();
//		for (String v : names) {
//			String translate = getTopIndexTranslation(v);
//			translation.add(translate);
//		}
//		names.addAll(translation);
//		if (names.size() > 0) {
//			poiAdditionalTopIndexCache.put(r, names);
//		}
//	}
//
//	public void searchTopIndexPoiAdditional(SearchPhrase phrase, SearchResultMatcher resultMatcher) throws IOException {
//		if (phrase.isEmpty()) {
//			return;
//		}
//		int BBOX_RADIUS = 1000;
//		Iterator<BinaryMapIndexReader> offlineIndexes = phrase.getRadiusOfflineIndexes(BBOX_RADIUS,
//				SearchPhraseDataType.POI);
//		NameStringMatcher nm = phrase.getMainUnknownNameStringMatcher();
//		Map<String, HashSet<String>> matchedValues = new HashMap<>();
//		while (offlineIndexes.hasNext()) {
//			BinaryMapIndexReader r = offlineIndexes.next();
//			initPoiAdditionalTopIndex(r);
//			if (!poiAdditionalTopIndexCache.containsKey(r)) {
//				continue;
//			}
//			if (nm.matches(poiAdditionalTopIndexCache.get(r))) {
//				TopIndexMatch match = matchTopIndex(r, phrase);
//				if (match != null) {
//					if (matchedValues.containsKey(match.subType.name)
//							&& matchedValues.get(match.subType.name).contains(match.value)) {
//						continue;
//					}
//					SearchResult res = new SearchResult(phrase);
//					res.localeName = match.translatedValue;
//					res.object = new TopIndexFilter(match.subType, types, match.value);
//					addPoiTypeResult(phrase, resultMatcher, false, null, res);
//					HashSet<String> values = matchedValues.computeIfAbsent(match.subType.name, s -> new HashSet<>());
//					values.add(match.value);
//				}
//			}
//		}
//	}
//
//	private TopIndexMatch matchTopIndex(BinaryMapIndexReader r, SearchPhrase phrase) throws IOException {
//		String search = phrase.getUnknownSearchPhrase();
//		boolean complete = phrase.isFirstUnknownSearchWordComplete();
//		List<PoiSubType> poiSubTypes = r.getTopIndexSubTypes();
//		String lang = phrase.getSettings().getLang();
//		List<TopIndexMatch> matches = new ArrayList<>();
//		Collator collator = OsmAndCollator.primaryCollator();
//		NameStringMatcher nm = new NameStringMatcher(search, CHECK_ONLY_STARTS_WITH);
//		for (PoiSubType subType : poiSubTypes) {
//			String topIndexValue = null;
//			String translate = null;
//			List<String> possibleValues = new ArrayList<>(subType.possibleValues);
//			Collections.sort(possibleValues);
//			for (String s : possibleValues) {
//				translate = getTopIndexTranslation(s);
//				String normalizeBrand = s.toLowerCase(Locale.ROOT);
//				if (complete) {
//					if (CollatorStringMatcher.cmatches(collator, search, normalizeBrand,
//							StringMatcherMode.CHECK_ONLY_STARTS_WITH)) {
//						topIndexValue = s;
//						break;
//					} else {
//						if (CollatorStringMatcher.cmatches(collator, search, translate,
//								StringMatcherMode.CHECK_ONLY_STARTS_WITH)) {
//							topIndexValue = s;
//							break;
//						}
//					}
//				} else if (nm.matches(s) || nm.matches(translate)) {
//					topIndexValue = s;
//					break;
//				}
//			}
//			if (topIndexValue != null) {
//				TopIndexMatch topIndexMatch = new TopIndexMatch(subType, topIndexValue, translate);
//				if (!Algorithms.isEmpty(lang) && subType.name.contains(":" + lang)) {
//					return topIndexMatch;
//				}
//				matches.add(topIndexMatch);
//			}
//		}
//		for (TopIndexMatch m : matches) {
//			if (!m.subType.name.contains(":")) {
//				return m;
//			}
//		}
//		if (matches.size() > 0) {
//			return matches.get(0);
//		}
//		return null;
//	}
//
//	private String getTopIndexTranslation(String value) {
//		String key = TopIndexFilter.getValueKey(value);
//		String translate = null; // types.getPoiTranslation(key); // TODO;
//		if (translate.toLowerCase(Locale.ROOT).equals(key)) {
//			translate = value;
//		}
//		return translate;
//	}
//
//	private static class TopIndexMatch {
//		TopIndexMatch(PoiSubType subType, String value, String translatedValue) {
//			this.subType = subType;
//			this.value = value;
//			this.translatedValue = translatedValue;
//		}
//
//		PoiSubType subType;
//		String value;
//		String translatedValue;
//	}

}
