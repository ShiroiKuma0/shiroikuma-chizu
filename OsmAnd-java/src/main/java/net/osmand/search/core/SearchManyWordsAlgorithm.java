package net.osmand.search.core;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Consumer;

import com.google.protobuf.ByteString;

import gnu.trove.list.array.TIntArrayList;
import gnu.trove.list.array.TLongArrayList;
import gnu.trove.map.hash.TLongObjectHashMap;
import net.osmand.binary.BinaryMapAddressReaderAdapter.AddressRegion;
import net.osmand.binary.BinaryMapAddressReaderAdapter.CityBlocks;
import net.osmand.binary.BinaryMapIndexReader;
import net.osmand.binary.BinaryMapPoiReaderAdapter.PoiRegion;
import net.osmand.binary.CommonWords;
import net.osmand.binary.NameIndexInspector;
import net.osmand.binary.NameIndexInspector.PrefixNameValue;
import net.osmand.binary.OsmandOdb.AddressNameIndexDataAtom;
import net.osmand.binary.OsmandOdb.OsmAndPoiNameIndexDataAtom;
import net.osmand.util.MapUtils;
import net.osmand.util.SearchAlgorithms;

// TODO duplicate words
// TODO replace last dot as incomplete
// TODO sort tokens by actual frequency
// special cases
// 1. Abbrevations
// 2. Street intersection match
public class SearchManyWordsAlgorithm {
	
	static int SHIFT_FILE_IND = 12;
	static boolean SEARCH_POI = true;
	
	static class HashQuadTree<T> {
		TLongObjectHashMap<List<T>>[] indexByTileId;
		
		@SuppressWarnings("unchecked")
		public HashQuadTree(int maxZoom) {
			indexByTileId = new TLongObjectHashMap[maxZoom +1];
		}
		
		public void put(int z, long tileId, T value) {
	        if (indexByTileId[z] == null) {
	            indexByTileId[z] = new TLongObjectHashMap<>();
	        }
	        List<T> list = indexByTileId[z].get(tileId);
	        if (list == null) {
	            list = new ArrayList<>();
	            indexByTileId[z].put(tileId, list);
	        }
	        list.add(value);
	    }
		
		public void forEachMatchHigherZoom(int startZoom, long tileId, Consumer<List<T>> action) {
			startZoom --;
			tileId >>=2;
			forEachMatch(startZoom, tileId, action);
		}
		
		public void forEachMatch(int startZoom, long tileId, Consumer<List<T>> action) {
	        forEachMatch(startZoom, 0, tileId, action);
	    }
		
		public void forEachMatch(int startZoom, int endZoom, long tileId, Consumer<List<T>> action) {
			for (int z = startZoom; z >= endZoom; z--) {
	            if (indexByTileId[z] != null) {
	                List<T> res = indexByTileId[z].get(tileId);
	                if (res != null) {
	                    action.accept(res); 
	                }
	            }
	            tileId >>= 2;
	        }
		}
		
		public static long encodeTileId(int z, int x, int y) {
			if (z > 20) {
				throw new UnsupportedOperationException();
			}
			long il = (MapUtils.interleaveBits(x, y));
			return il;
		}
	}
	
	static class NameIndexAtom {
		
		int[] bbox;
		String name;
		AddressNameIndexDataAtom addr;
		OsmAndPoiNameIndexDataAtom poi;
		BinaryMapIndexReader file;
		long id;
		
		int[] bbox31; // if exists [xleft, yleft, xright, yright]
		long bboxTileId; // encodes zoom, tileX, tileY
		int bboxTileZoom;
		int x16, y16;
		
		NameIndexAtom(String name, AddressNameIndexDataAtom addr, OsmAndPoiNameIndexDataAtom poi,
				BinaryMapIndexReader file, long id) {
			this.name = name;
			this.addr = addr;
			this.poi = poi;
			this.file = file;
			this.id = id;
			calculateBbox(addr, poi);
		}
		
		
		private void calculateBbox(AddressNameIndexDataAtom addr, OsmAndPoiNameIndexDataAtom poi) {
			if (addr != null && addr.getXy16Count() >= 1) {
				int xy16 = addr.getXy16(0);
				this.x16 = (xy16 >>> 16);
				this.y16 = (xy16 & ((1 << 16) - 1));
				ByteString bbox = addr.getBbox();
				bboxTileZoom = 15;
				bboxTileId = HashQuadTree.encodeTileId(bboxTileZoom, x16 / 2, y16 / 2);
				if (bbox != null && addr.hasBbox()) {
					bbox31 = SearchAlgorithms.decodeBboxForNameAtomsBytes(bbox, x16, y16);
					if (bbox31 != null) {
						int z = 31;
						int xleft = bbox31[0], xright = bbox31[2];
						int ytop = bbox31[1], ybottom = bbox31[3];
						while (xleft != xright && ytop != ybottom) {
							z--;
							xleft >>= 1;
							xright >>= 1;
							ytop >>= 1;
							ybottom >>= 1;
						}
						bboxTileZoom = z;								
						bboxTileId = HashQuadTree.encodeTileId(z, xleft, ytop);
					}
				}
			} else if (poi != null) {
				this.x16 = poi.getX();
				this.y16 = poi.getY();
				bboxTileZoom = 16;
				bboxTileId = HashQuadTree.encodeTileId(bboxTileZoom, x16, y16);
			}			
		}
		
		
//		public int[] getBBox15() {
//			if(poi != null) {
//				x = poi.getX() << 15;
//				y = poi.getY() << 15;
//				return new int[] {poi.getX() << 15,  
//						
//				}
//			}
//		}
		
		@Override
		public final String toString() {
			String type = "";
			if (addr != null) {
				type = CityBlocks.getByType(addr.getType()).toString();
			} else if (poi != null) {
				type = "POI";
			}
			return String.format("%s (%.4f, %.4f) ",  type + " " + name, 
					MapUtils.get31LatitudeY(y16 << 15), MapUtils.get31LongitudeX(x16 << 15),
					name, id >> SHIFT_FILE_IND);
		}
	};
	

	static class SearchManyStats {
		long time = System.nanoTime();
		long readTime = 0;
		long combTime = 0;
		
		@Override
		public String toString() {
			return String.format("Search Stats %.1f ms - read %.1f ms, comp %.1f ms", time / 1e6, 
					readTime / 1e6, combTime / 1e6);
		}
		
		public void finish() {
			time = System.nanoTime() - time;
		}
	}
	
	static class SearchManyContext {
		List<BinaryMapIndexReader> files;
		SearchManyStats stats = new SearchManyStats();
		
	}
	
	static class SearchTokenCombination implements Comparable<SearchTokenCombination> {
		List<SearchToken> tokens = new ArrayList<>();
		
		// NameIndexAtom[][] -- should be double array to store list of combinations  
		List<NameIndexAtom> linearResults = new ArrayList<>();
		TLongArrayList tileIds = new TLongArrayList();
		TIntArrayList tileZooms = new TIntArrayList();
		HashQuadTree<Integer> quadTree = new HashQuadTree<>(16);
		

		public NameIndexAtom getAtom(int combination, int pos) {
			return linearResults.get(combination * tokens.size() + pos);
		}
		
		public List<NameIndexAtom> getAtoms(int combination) {
			int st = combination * tokens.size();
			return linearResults.subList(st, st + tokens.size());
		}
		public int getCombinations() {
			return tileIds.size();
		}

		private void addResult(List<NameIndexAtom> atoms) {
			addResult(null, 0, atoms);
		}

		private void addResult(SearchTokenCombination parent, int indx, List<NameIndexAtom> atoms) {
			for (NameIndexAtom a : atoms) {
				addResult(parent, indx, a);
			}
		}
		
		public int sumTokenAtomSize() {
			int sum = 0;
			for (SearchToken s : tokens) {
				sum += s.atoms.size();
			}
			return sum;
		}
		
		private void addResult(SearchTokenCombination parent, int pindx, NameIndexAtom a) {
			int pzoom = parent == null ? 0 : parent.tileZooms.get(pindx);
			int zoom = Math.max(pzoom, a.bboxTileZoom);
			long tileId = pzoom > a.bboxTileZoom ? parent.tileIds.get(pindx) : a.bboxTileId;
			int insIndx = this.tileIds.size();
			boolean dup = checkDuplicate(parent, pindx, a, zoom, tileId);
			if (dup) {
				return;
			}
			for (int i = 0; parent != null && i < parent.tokens.size(); i++) {
				this.linearResults.add(parent.linearResults.get(pindx * parent.tokens.size() + i));
			}
			
			this.linearResults.add(a);
			this.tileIds.add(tileId);
			this.tileZooms.add(zoom);
			quadTree.put(zoom, tileId, insIndx);
		}

		private boolean checkDuplicate(SearchTokenCombination parent, int pindx, NameIndexAtom a, int zoom, long tileId) {
			final boolean[] matched = new boolean[1];
			quadTree.forEachMatch(zoom, zoom, tileId, indxs -> {
				for (int indx : indxs) {
					boolean m = linearResults.get((indx + 1) * tokens.size() - 1).id == a.id;
					for (int i = 0; m && parent != null && i < parent.tokens.size(); i++) {
						NameIndexAtom p = parent.linearResults.get(pindx * parent.tokens.size() + i);
						NameIndexAtom p2 = linearResults.get(indx * tokens.size() + i);
						if (p.id != p2.id) {
							m = false;
						}
					}
					matched[0] |= m;
				}
			});
			return matched[0];
		}

		@Override
		public int compareTo(SearchTokenCombination o) {
			int s1 = tokens.size();
			int s2 = o.tokens.size();
			if (s1 != s2) {
				return -Integer.compare(s1, s2);
			}
			s1 = sumTokenAtomSize();
			s2 = o.sumTokenAtomSize();
			return Integer.compare(s1, s2);
		}
		
		public String toString(boolean extended) {
			List<String> words = new ArrayList<>();
			for (SearchToken t : tokens) {
				words.add(t.originalWord);
			}
			return String.format("Combination %d %s - %d atoms: %s", 
					tokens.size(), words, getCombinations(), extended ? linearResults : Collections.EMPTY_LIST);
		}
		
		@Override
		public String toString() {
			return toString(false);
		}
	}
	
	
	
	static class SearchToken {
		int originalOrder = 0;
		int sortedOrder = 0;
		boolean incomplete;
		String originalWord;
		String word;
		List<NameIndexAtom> atoms = new ArrayList<>();
		TLongObjectHashMap<NameIndexAtom> index = new TLongObjectHashMap<>();
		HashQuadTree<NameIndexAtom> quadTree = new HashQuadTree<>(16);
		
		
		public SearchToken(String w, String original, int order) {
			originalWord = original;
			word = w;
			this.originalOrder = order;
		}
		
		@Override
		public String toString() {
			return String.format("%d. %s - %d atoms", originalOrder, originalWord, atoms.size());
		}
		
		private long makeId(int fileInd,long shiftToIndex) {
			if (fileInd > 1 << SHIFT_FILE_IND) {
				throw new IllegalStateException();
			}
			long id = (shiftToIndex << SHIFT_FILE_IND) + SHIFT_FILE_IND;
			return id;
		}

		// TODO properly calculate shiftToIndex (test)
		public void addAtom(String name, BinaryMapIndexReader b, int fileInd, AddressNameIndexDataAtom a, long shift) {
			long lid = makeId(fileInd, shift - a.getShiftToIndex(0));
			NameIndexAtom atom = new NameIndexAtom(name, a, null, b, lid);
			addAtom(name, atom);
		}

		// TODO properly calculate shiftToIndex
		public void addAtom(String name, BinaryMapIndexReader b, int fileInd, OsmAndPoiNameIndexDataAtom a, long shift) {
			long lid = makeId(fileInd, a.getShiftTo());
			NameIndexAtom atom = new NameIndexAtom(name, null, a, b, lid);
			addAtom(name, atom);
		}
		
		private void addAtom(String name, NameIndexAtom atom) {
			if (match(name)) {
				index.put(atom.id, atom);
				atoms.add(atom);
				quadTree.put(atom.bboxTileZoom, atom.bboxTileId, atom);
			}
		}

		private boolean match(String name) {
			// TODO collator, dot
			return word.equalsIgnoreCase(name);
		}
	}
	
	
	public static void mainTest(String[] subArgsArray) throws FileNotFoundException, IOException {
		long t = System.nanoTime();
		String query = subArgsArray[0];
		List<BinaryMapIndexReader> ls = new ArrayList<BinaryMapIndexReader>();
		for(int i = 1; i < subArgsArray.length; i++) {
			File fl = new File(subArgsArray[i]);
			if (fl.isFile()) {
				if (i == 1) {
					initFile(ls, new File(fl.getParentFile(), "regions.ocbf"));
				}
				initFile(ls, fl);
			} else {
				for (File f : fl.listFiles()) {
					initFile(ls, f);
				}
			}
		}
		System.out.println(String.format("Index files %.1f ms", (System.nanoTime() - t) / 1e6));
		SearchManyWordsAlgorithm a = new SearchManyWordsAlgorithm();
		a.searchTest(query, ls);
	}

	private static void initFile(List<BinaryMapIndexReader> ls, File f) throws IOException, FileNotFoundException {
		if (f.exists() && (f.getName().endsWith(".obf") || f.getName().equals("regions.ocbf"))) {
			BinaryMapIndexReader bir = new BinaryMapIndexReader(new RandomAccessFile(f, "r"), f);
			ls.add(bir);
		}
	}
	
	public static void main(String[] args) throws IOException {
		File folder = new File("/Users/victorshcherb/osmand/maps/");
		String pattern = "Germany_baden";
		String query = "Berlin hauptstrasse";
		query = "Kelterstraße Kernen im Remstal";
		query = "Deutschland Kelterstraße Kernen im Remstal";
		
//		pattern = "Us_";
		query = "USA Salt Lake City Pennsylvania Street";
//		"бровари Сільпо"
		// "пузата хата саксанського"
		long t = System.nanoTime();
		
		List<BinaryMapIndexReader> ls = new ArrayList<BinaryMapIndexReader>();
		for (File f : folder.listFiles()) {
			if (f.getName().startsWith(pattern)) {
				initFile(ls, f);
			}
		}
		System.out.println(String.format("Index files %.1f ms", (System.nanoTime() - t) / 1e6));
		SearchManyWordsAlgorithm a = new SearchManyWordsAlgorithm();
		a.searchTest(query, ls);
		// 2nd run
		a.searchTest(query, ls);
	}
	
	

	// TODO '.' shouldn't split words, '-' - should
	private List<SearchToken> splitWords(String input) {
		List<String> words = SearchAlgorithms.splitAndNormalize(input);
		String[] sp = input.split(SearchPhrase.ALLDELIMITERS_WITH_HYPHEN);
		List<SearchToken> tokens = new ArrayList<>();
		int order = 0;
		for (String w : words) {
			tokens.add(new SearchToken(w, words.size() == sp.length? sp[order] : w, order++));
		}
		return tokens;
	}
	
	private void sortTokens(List<SearchToken> tokens) {
		Collections.sort(tokens, new Comparator<SearchToken>() {
			@Override
			public int compare(SearchToken o1, SearchToken o2) {
				int c1 = CommonWords.getCommonSearch(o1.word);
				int c2 = CommonWords.getCommonSearch(o2.word);
				if(c1 != c2) {
					return Integer.compare(c1, c2);
				}
				c1 = o1.atoms.size();
				c2 = o2.atoms.size();
				if(c1 != c2) {
					return Integer.compare(c1, c2);
				}
				return o1.word.compareTo(o2.word);
			}
			
		});
		for(int i = 0; i < tokens.size(); i++) {
			tokens.get(i).sortedOrder = i;
		}
	}
	

	private List<SearchTokenCombination> findObjCombinations(List<SearchToken> tokens) {
		LinkedList<SearchTokenCombination> candidates = new LinkedList<>();
		candidates.add(new SearchTokenCombination());
		List<SearchTokenCombination> result = new ArrayList<>();
		while (!candidates.isEmpty()) {
			SearchTokenCombination parent = candidates.removeFirst();
			if (parent.getCombinations() > 0) {
				result.add(parent);
			}
			for (SearchToken token : tokens) {
				if (parent.tokens.isEmpty() || token.sortedOrder < parent.tokens.get(0).sortedOrder) {
//					System.out.println("REVIEW " + parent + " " + token);
					SearchTokenCombination next = new SearchTokenCombination();
					next.tokens.add(token);
					next.tokens.addAll(parent.tokens);
					if (parent.tokens.isEmpty()) {
						next.addResult(token.atoms);
					} else {
						for (int i = 0; i < parent.tileIds.size(); i++) {
							long tileId = parent.tileIds.get(i);
							int zoom = parent.tileZooms.get(i);
							final int indx = i;
							token.quadTree.forEachMatch(zoom, tileId, t -> {
								next.addResult(parent, indx, t);
							});
						}
					}
					// reverse search quad tree 
					final SearchTokenCombination p = parent;
					for (final NameIndexAtom a : token.atoms) {
						parent.quadTree.forEachMatchHigherZoom(a.bboxTileZoom, a.bboxTileId, indxs -> {
							for (int indx : indxs) {
								next.addResult(p, indx, a);
							}
						});
					}
					
					candidates.add(next);
				}
			}
		}
		return result;
		
	}



	
	public void searchTest(String input, List<BinaryMapIndexReader> files) throws IOException {
		SearchManyContext ctx = new SearchManyContext();
		ctx.files = files;
		// 1. prepare tokens
		List<SearchToken> tokens = splitWords(input);
		// 2. read atoms
		for (SearchToken t : tokens) {
			readAtoms(ctx, t);
		}
		// 3. sort tokens 
		sortTokens(tokens);
		// 4. find combinations
		ctx.stats.combTime -= System.nanoTime();
		List<SearchTokenCombination> combinations = findObjCombinations(tokens);
		ctx.stats.combTime += System.nanoTime();
		
		Collections.sort(combinations);
		if (combinations.size() > 0) {
			SearchTokenCombination result = combinations.get(0);
			System.out.println("--------");
			System.out.println("Main: " + result);
			for(int i = 0; i < result.getCombinations(); i++) {
				System.out.println(result.getAtoms(i));
			}
			System.out.println("--------");
		}
		
		System.out.println("\nTokens: " + tokens);
		System.out.println("All Results: ");
		for (SearchTokenCombination s : combinations) {
			if (s.tokens.size() >= 2) {
				System.out.println("  " + s.toString(false));
			}
		}
		
		ctx.stats.finish();
		System.out.println(ctx.stats);
		System.out.println();
	}

	


	private void readAtoms(SearchManyContext ctx, SearchToken t) throws IOException {
		int fileInd = 0;
		for (BinaryMapIndexReader b : ctx.files) {
			for (AddressRegion m : b.getAddressIndexes()) {
				ctx.stats.readTime -= System.nanoTime();
				NameIndexInspector indx = b.readFullNameIndex(m, t.word);
				ctx.stats.readTime += System.nanoTime();
				for (PrefixNameValue prefix : indx.getPrefixes()) {
					addAtoms(t, b, fileInd, prefix);
				}
			}
			for (PoiRegion m : b.getPoiIndexes()) {
				ctx.stats.readTime -= System.nanoTime();
				NameIndexInspector indx = b.readFullNameIndex(m, t.word);
				ctx.stats.readTime += System.nanoTime();
				for (PrefixNameValue prefix : indx.getPrefixes()) {
					addAtoms(t, b, fileInd, prefix);
				}
			}
			fileInd++;
		}
	}

	private void addAtoms(SearchToken t, BinaryMapIndexReader b, int fileInd, PrefixNameValue prefix) {
		int INT_BITS = 32;
		String curSuffix = null;
		List<String> suffixes = new ArrayList<>();
		boolean addr = prefix.addr != null;
		for (String s : addr ? prefix.addr.getSuffixesDictionaryList() : 
				prefix.poi.getSuffixesDictionaryList()) {
			curSuffix = SearchAlgorithms.nameIndexDecodeDictionarySuffix(curSuffix, s);
			suffixes.add(prefix.key + curSuffix);
		}
		if (addr) {
			for (AddressNameIndexDataAtom a : prefix.addr.getAtomList()) {
				for (int i = 0; i < a.getSuffixesBitsetCount(); i++) {
					int suffBit = a.getSuffixesBitset(i);
					for (int j = 0; j < INT_BITS && suffBit != 0; j++) {
						if (suffBit % 2 == 1) {
							int ind = i * INT_BITS + j;
							t.addAtom(suffixes.get(ind), b, fileInd, a, prefix.shift);
						}
						suffBit >>>= 1;
					}
				}
			}
		} else if (SEARCH_POI) {
			for (OsmAndPoiNameIndexDataAtom a : prefix.poi.getAtomsList()) {
				for (int i = 0; i < a.getSuffixesBitsetCount(); i++) {
					int suffBit = a.getSuffixesBitset(i);
					for (int j = 0; j < INT_BITS && suffBit != 0; j++) {
						if (suffBit % 2 == 1) {
							int ind = i * INT_BITS + j;
							t.addAtom(suffixes.get(ind), b, fileInd, a, prefix.shift);
						}
						suffBit >>>= 1;
					}
				}
			}
		}
	}
	

	public void documentation(String input, List<BinaryMapIndexReader> files) {
		// 1. Split words
		// 2.1 Global cache Read common words for files
		// 2.2 Global cache Read all top poi categories for files
		
		// 3. Sort words & meta information for words
		// 3.1 Calculate poi categories for words & combinations!
		// 3.2 Calculate common & frequent numbers based on files
		// 3.3 Assign Common & frequent labels from Global file
		
		////////////// Iteration on 1st group Files? 100km ////////////
		// 4. Read 
		// 4.1 Incomplete words? assign prefix ?
		// 4.2 Read Per word List<AddressNameIndexDataAtom>, List<OsmAndPoiNameIndexDataAtom>
		// 4.3 Read & Cache for non-frequent common words
		
		////////////// SEARCH ALGORITHM //////////////
		// 5.1 Run rare words (by counts & labels)
		// 5.2 Run with frequent words
		// 5.3 Expand poi categories
		
		// Search categories
		// Phase I - only rare + words replaced abbrrevations
		// Phase II - all words + replaced abbrevations
		// Phase III - all words + replaced abbrevations
		
		// After search operations - Expand POI Type filters for results
		
	}



	
	
	
}