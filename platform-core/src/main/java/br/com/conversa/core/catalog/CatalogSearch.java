package br.com.conversa.core.catalog;

import br.com.conversa.core.text.SearchText;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Finds catalog items by what the customer asked ("pizza de calabresa", "refrigerantes").
 * Catalogs of small businesses fit in memory, so matching runs over the whole list: every query
 * word must match the start of a word of the item (name, category, description or options),
 * ignoring case, accents and plurals. Name matches rank first; ties keep the catalog order.
 */
public final class CatalogSearch {

    private static final Set<String> STOP_WORDS = Set.of(
            "a", "o", "as", "os", "de", "da", "do", "das", "dos", "e", "com", "um", "uma", "no", "na");

    private CatalogSearch() {
    }

    public static List<CatalogItem> search(List<CatalogItem> catalog, String query, String category, int limit) {
        List<String> terms = SearchText.words(query).stream()
                .filter(word -> !STOP_WORDS.contains(word))
                .map(SearchText::singular)
                .toList();
        String wantedCategory = SearchText.normalize(category);
        record Scored(CatalogItem item, int score, int position) {
        }
        List<Scored> found = new ArrayList<>();
        for (int position = 0; position < catalog.size(); position++) {
            CatalogItem item = catalog.get(position);
            if (!wantedCategory.isEmpty() && !matchesCategory(item, wantedCategory)) {
                continue;
            }
            int score = score(item, terms);
            if (score > 0 || terms.isEmpty()) {
                found.add(new Scored(item, score, position));
            }
        }
        return found.stream()
                .sorted(Comparator.comparingInt(Scored::score).reversed().thenComparingInt(Scored::position))
                .limit(limit)
                .map(Scored::item)
                .toList();
    }

    private static boolean matchesCategory(CatalogItem item, String wanted) {
        List<String> categoryWords = SearchText.words(item.category()).stream().map(SearchText::singular).toList();
        return SearchText.words(wanted).stream()
                .map(SearchText::singular)
                .allMatch(word -> startsAnyWord(categoryWords, word));
    }

    /** 0 when some term matches nothing; otherwise higher for matches in the name. */
    private static int score(CatalogItem item, List<String> terms) {
        List<String> name = singularWords(item.name());
        List<String> category = singularWords(item.category());
        List<String> rest = new ArrayList<>(singularWords(item.description()));
        item.options().forEach(option -> rest.addAll(singularWords(option.name())));
        int score = 0;
        for (String term : terms) {
            if (startsAnyWord(name, term)) {
                score += 3;
            } else if (startsAnyWord(category, term)) {
                score += 2;
            } else if (startsAnyWord(rest, term)) {
                score += 1;
            } else {
                return 0;
            }
        }
        return score;
    }

    private static List<String> singularWords(String text) {
        return SearchText.words(text).stream().map(SearchText::singular).toList();
    }

    private static boolean startsAnyWord(List<String> words, String term) {
        return words.stream().anyMatch(word -> word.startsWith(term));
    }
}
