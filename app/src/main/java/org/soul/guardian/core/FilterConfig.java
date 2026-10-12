package org.soul.guardian.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Immutable snapshot of the masking configuration plus the compiled rule set.
 * Safe to build off the main thread.
 *
 * <p>Every rule line (one per line of an imported {@code .txt} or bundled asset) is classified
 * by its leading marker:
 * <ul>
 *   <li>no marker, or a leading {@code \} — literal plain text to mask; matched with
 *       {@code indexOf} and never compiled as a regular expression</li>
 *   <li>{@code %} — a regular expression to mask</li>
 *   <li>{@code !} — literal plain text; a match equal to it is left unmasked (hard exclude)</li>
 *   <li>{@code `} — literal plain text whose occurrences are left unmasked (soft exclude)</li>
 *   <li>{@code @} — a regular expression whose matches are left unmasked</li>
 *   <li>{@code #} at column 0 — comment line, ignored</li>
 * </ul>
 *
 * <p>A leading backslash escapes the marker, so {@code \#text} is the literal text {@code #text}
 * and {@code \%text} is the literal text {@code %text}. The backslash is only meaningful as the
 * first character of a line.
 *
 * <p>Plain-text rules are evaluated before the regular expressions. For every match, an equal
 * hard-exclude entry skips the whole match, then any soft-exclude occurrence or regex-exclude
 * match clears the covered characters inside the match.
 */
public final class FilterConfig {

    public enum Mode {
        MASK,
        BLOCK
    }

    public static final String DEFAULT_MASK_SYMBOL = "*";

    private final boolean enabled;
    private final Mode mode;
    private final String maskSymbol;
    private final boolean testMode;

    private final List<String> plainRules;
    private final List<Pattern> regexRules;
    private final Set<String> hardExcludes;
    private final Set<String> softExcludes;
    private final List<Pattern> regexExcludes;
    private final LiteralMatcher plainMatcher;
    private final LiteralMatcher softMatcher;

    public FilterConfig(boolean enabled, Mode mode, String maskSymbol, boolean testMode,
                        Collection<String> rules) {
        this.enabled = enabled;
        this.mode = mode == null ? Mode.MASK : mode;
        this.maskSymbol = (maskSymbol == null || maskSymbol.isEmpty())
                ? DEFAULT_MASK_SYMBOL : maskSymbol;
        this.testMode = testMode;

        List<String> plain = new ArrayList<>();
        List<Pattern> regex = new ArrayList<>();
        Set<String> hard = new LinkedHashSet<>();
        Set<String> soft = new LinkedHashSet<>();
        List<Pattern> regexExclude = new ArrayList<>();
        classify(rules, plain, regex, hard, soft, regexExclude);
        this.plainRules = plain;
        this.regexRules = regex;
        this.hardExcludes = hard;
        this.softExcludes = soft;
        this.regexExcludes = regexExclude;

        List<String> maskablePlain = new ArrayList<>(plain.size());
        for (String rule : plain) {
            if (!hard.contains(rule)) {
                maskablePlain.add(rule);
            }
        }
        this.plainMatcher = new LiteralMatcher(maskablePlain);
        this.softMatcher = new LiteralMatcher(soft);
    }

    private static void classify(Collection<String> rules, List<String> plain, List<Pattern> regex,
                                 Set<String> hard, Set<String> soft, List<Pattern> regexExclude) {
        if (rules == null) {
            return;
        }
        for (String raw : rules) {
            if (raw == null) {
                continue;
            }
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            char marker = line.charAt(0);
            if (marker == '#') {
                continue;
            }
            if (marker == '\\') {
                addPlain(line.substring(1), plain);
            } else if (marker == '%') {
                addRegex(line.substring(1), regex);
            } else if (marker == '@') {
                addRegex(line.substring(1), regexExclude);
            } else if (marker == '!') {
                addPlain(line.substring(1), hard);
            } else if (marker == '`') {
                addPlain(line.substring(1), soft);
            } else {
                addPlain(line, plain);
            }
        }
    }

    private static void addPlain(String text, Collection<String> target) {
        if (text != null && !text.isEmpty() && !target.contains(text)) {
            target.add(text);
        }
    }

    private static void addRegex(String expression, List<Pattern> target) {
        if (expression == null || expression.isEmpty()) {
            return;
        }
        try {
            target.add(Pattern.compile(expression));
        } catch (PatternSyntaxException ignored) {
            // skip malformed expressions so a single bad line cannot break everything
        }
    }

    public static FilterConfig disabled() {
        return new FilterConfig(false, Mode.MASK, DEFAULT_MASK_SYMBOL, false,
                Collections.<String>emptyList());
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Debug switch: when on, {@link #filter(CharSequence)} masks every character regardless of
     * the enable flag or the compiled rules.
     */
    public boolean isTestMode() {
        return testMode;
    }

    public Mode getMode() {
        return mode;
    }

    public boolean hasRules() {
        return !plainRules.isEmpty() || !regexRules.isEmpty();
    }

    public int ruleCount() {
        return plainRules.size() + regexRules.size();
    }

    /** The symbol every masked character is replaced with. */
    public String maskSymbol() {
        return maskSymbol;
    }

    /**
     * Applies the configured rule to the given text. The original {@link CharSequence}
     * reference is returned when nothing changes so callers can compare by identity.
     *
     * <ul>
     *   <li>{@link Mode#MASK}: every rule match is replaced by the mask symbol.</li>
     *   <li>{@link Mode#BLOCK}: any paragraph containing a rule match is replaced entirely by the
     *       mask symbol.</li>
     * </ul>
     */
    public CharSequence filter(CharSequence input) {
        return apply(input, maskSymbol);
    }

    /**
     * Like {@link #filter(CharSequence)} but keeps the result the same length as the input by
     * collapsing the mask symbol to a single code point. Used for text drawn directly through
     * {@code Canvas}, where changing the length would desync cached text measurements.
     */
    public CharSequence filterEqualLength(CharSequence input) {
        return apply(input, singleSymbol());
    }

    private CharSequence apply(CharSequence input, String symbol) {
        if (input == null) {
            return null;
        }
        String text = input.toString();
        if (text.isEmpty()) {
            return input;
        }
        if (testMode) {
            String masked = maskAll(text, symbol);
            return masked.equals(text) ? input : masked;
        }
        if (!enabled || !hasRules()) {
            return input;
        }
        String masked = mode == Mode.BLOCK ? blockMask(text, symbol) : maskText(text, symbol);
        return masked.equals(text) ? input : masked;
    }

    /** Replaces every character with the mask symbol, keeping line breaks intact. */
    private String maskAll(String text, String symbol) {
        StringBuilder sb = new StringBuilder(text.length() * symbol.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                sb.append(c);
            } else {
                sb.append(symbol);
            }
        }
        return sb.toString();
    }

    private String singleSymbol() {
        int codePoint = maskSymbol.codePointAt(0);
        return new String(Character.toChars(codePoint));
    }

    private String maskText(String text, String symbol) {
        boolean[] flags = maskFlags(text);
        return render(text, flags, symbol);
    }

    private String render(String text, boolean[] flags, String symbol) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            sb.append(flags[i] ? symbol : text.charAt(i));
        }
        return sb.toString();
    }

    /**
     * Computes which characters must be masked: every plain-text and regex match, minus the
     * characters cleared by the hard, soft and regex exclusion lists.
     */
    private boolean[] maskFlags(String text) {
        int length = text.length();
        boolean[] flags = new boolean[length];

        plainMatcher.process(text, flags, true);

        for (Pattern pattern : regexRules) {
            Matcher matcher = pattern.matcher(text);
            while (matcher.find()) {
                int start = matcher.start();
                int end = matcher.end();
                if (end <= start) {
                    continue;
                }
                if (!hardExcludes.contains(text.substring(start, end))) {
                    mark(flags, start, end);
                }
            }
        }

        softMatcher.process(text, flags, false);

        for (Pattern pattern : regexExcludes) {
            Matcher matcher = pattern.matcher(text);
            while (matcher.find()) {
                int start = matcher.start();
                int end = matcher.end();
                if (end > start) {
                    clear(flags, start, end);
                }
            }
        }

        return flags;
    }

    private static void mark(boolean[] flags, int start, int end) {
        for (int i = start; i < end; i++) {
            flags[i] = true;
        }
    }

    private static void clear(boolean[] flags, int start, int end) {
        for (int i = start; i < end; i++) {
            flags[i] = false;
        }
    }

    /** Replaces every paragraph containing a masked character with mask symbols. */
    private String blockMask(String text, String symbol) {
        StringBuilder out = new StringBuilder(text.length());
        int start = 0;
        for (int i = 0; i <= text.length(); i++) {
            if (i == text.length() || text.charAt(i) == '\n') {
                String paragraph = text.substring(start, i);
                if (!paragraph.isEmpty() && hasMasked(maskFlags(paragraph))) {
                    out.append(repeat(paragraph.length(), symbol));
                } else {
                    out.append(paragraph);
                }
                if (i < text.length()) {
                    out.append('\n');
                }
                start = i + 1;
            }
        }
        return out.toString();
    }

    private static boolean hasMasked(boolean[] flags) {
        for (boolean flag : flags) {
            if (flag) {
                return true;
            }
        }
        return false;
    }

    private String repeat(int times, String symbol) {
        if (times <= 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder(symbol.length() * times);
        for (int i = 0; i < times; i++) {
            sb.append(symbol);
        }
        return sb.toString();
    }

    /**
     * Aho-Corasick automaton over UTF-16 {@code char} units. It locates every occurrence of a
     * fixed set of literal patterns in a single linear pass over the text, so plain-text rules
     * never touch the regex engine.
     */
    private static final class LiteralMatcher {

        private final List<Map<Character, Integer>> children = new ArrayList<>();
        private final List<Integer> fail = new ArrayList<>();
        private final List<List<String>> outputs = new ArrayList<>();

        LiteralMatcher(Collection<String> patterns) {
            newNode();
            if (patterns != null) {
                for (String pattern : patterns) {
                    if (pattern != null && !pattern.isEmpty()) {
                        insert(pattern);
                    }
                }
            }
            buildFailureLinks();
        }

        private int newNode() {
            children.add(new HashMap<Character, Integer>());
            fail.add(0);
            outputs.add(new ArrayList<String>());
            return children.size() - 1;
        }

        private void insert(String pattern) {
            int node = 0;
            for (int i = 0; i < pattern.length(); i++) {
                char c = pattern.charAt(i);
                Integer next = children.get(node).get(c);
                if (next == null) {
                    next = newNode();
                    children.get(node).put(c, next);
                }
                node = next;
            }
            outputs.get(node).add(pattern);
        }

        private void buildFailureLinks() {
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            for (Integer child : children.get(0).values()) {
                fail.set(child, 0);
                queue.add(child);
            }
            while (!queue.isEmpty()) {
                int node = queue.poll();
                for (Map.Entry<Character, Integer> entry : children.get(node).entrySet()) {
                    char c = entry.getKey();
                    int child = entry.getValue();
                    int f = fail.get(node);
                    while (f != 0 && !children.get(f).containsKey(c)) {
                        f = fail.get(f);
                    }
                    Integer target = children.get(f).get(c);
                    fail.set(child, target != null ? target : 0);
                    outputs.get(child).addAll(outputs.get(fail.get(child)));
                    queue.add(child);
                }
            }
        }

        /** Marks (value {@code true}) or clears (value {@code false}) every covered character. */
        void process(String text, boolean[] flags, boolean value) {
            if (children.size() <= 1 || text.isEmpty()) {
                return;
            }
            int node = 0;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                while (node != 0 && !children.get(node).containsKey(c)) {
                    node = fail.get(node);
                }
                Integer next = children.get(node).get(c);
                node = next != null ? next : 0;
                List<String> matches = outputs.get(node);
                for (int m = 0; m < matches.size(); m++) {
                    int start = i - matches.get(m).length() + 1;
                    if (start < 0) {
                        continue;
                    }
                    int end = i + 1;
                    for (int k = start; k < end; k++) {
                        flags[k] = value;
                    }
                }
            }
        }
    }
}
