package curxxed.dev.switchboard.dispatch;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Detects agents repeating themselves or each other: the classic loop where two agents trade
 * "Thanks!" or re-post the same plan forever.
 */
final class ResponseSimilarity {

    /**
     * Word-trigram Jaccard similarity at or above which two replies count as the same. Changing one
     * word in a 30-word reply leaves about 0.8, so this catches lightly reworded repeats.
     */
    static final double NEAR_DUPLICATE = 0.75;

    private ResponseSimilarity() {}

    /** Lowercase, mentions and punctuation removed, whitespace collapsed. */
    static String normalize(String text) {
        return text.toLowerCase(Locale.ROOT)
                .replaceAll("@[a-z0-9_]+", " ")
                .replaceAll("[^\\p{L}\\p{N}\\s]", " ")
                .replaceAll("\\s+", " ")
                .strip();
    }

    /** @param a, b already normalized */
    static boolean sameReply(String a, String b) {
        if (a.equals(b)) return true;
        Set<String> sa = trigrams(a);
        Set<String> sb = trigrams(b);
        // Short replies have too few trigrams to compare; only exact matches count for them.
        if (sa.size() < 5 || sb.size() < 5) return false;
        Set<String> union = new HashSet<>(sa);
        union.addAll(sb);
        sa.retainAll(sb);
        return (double) sa.size() / union.size() >= NEAR_DUPLICATE;
    }

    private static Set<String> trigrams(String normalized) {
        String[] words = normalized.isEmpty() ? new String[0] : normalized.split(" ");
        Set<String> out = new HashSet<>();
        for (int i = 0; i + 2 < words.length; i++) out.add(words[i] + " " + words[i + 1] + " " + words[i + 2]);
        return out;
    }
}
