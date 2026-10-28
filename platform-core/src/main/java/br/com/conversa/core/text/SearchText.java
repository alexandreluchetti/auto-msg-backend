package br.com.conversa.core.text;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Text normalization for matching what customers type against business data: case, accents and
 * punctuation are ignored ("Calabresa", "calabresa!" and "CALABRÉSA" are the same).
 */
public final class SearchText {

    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern NOT_ALPHANUMERIC = Pattern.compile("[^a-z0-9]+");

    private SearchText() {
    }

    /** Lowercase, without accents, words separated by single spaces. */
    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String decomposed = Normalizer.normalize(text, Normalizer.Form.NFD);
        String plain = MARKS.matcher(decomposed).replaceAll("").toLowerCase(Locale.ROOT);
        return NOT_ALPHANUMERIC.matcher(plain).replaceAll(" ").trim();
    }

    public static List<String> words(String text) {
        String normalized = normalize(text);
        return normalized.isEmpty() ? List.of() : Arrays.asList(normalized.split(" "));
    }

    /** Naive Portuguese singular, enough for menu words ("pizzas" → "pizza"). */
    public static String singular(String word) {
        return word.length() > 3 && word.endsWith("s") ? word.substring(0, word.length() - 1) : word;
    }

    /** Edit distance, for "did you mean" suggestions on short names. */
    public static int distance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }
}
