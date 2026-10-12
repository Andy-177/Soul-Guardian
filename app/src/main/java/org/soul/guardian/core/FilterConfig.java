package org.soul.guardian.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Immutable snapshot of the masking configuration plus the compiled rule set.
 * Safe to build off the main thread.
 *
 * <p>Every entry in the rule set is a regular expression: one expression per line of the
 * imported {@code .txt} / bundled asset, with {@code #} starting a comment line. Invalid
 * expressions are silently skipped.
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
    private final List<Pattern> patterns;

    public FilterConfig(boolean enabled, Mode mode, String maskSymbol, boolean testMode,
                        Collection<String> expressions) {
        this.enabled = enabled;
        this.mode = mode == null ? Mode.MASK : mode;
        this.maskSymbol = (maskSymbol == null || maskSymbol.isEmpty())
                ? DEFAULT_MASK_SYMBOL : maskSymbol;
        this.testMode = testMode;
        this.patterns = compile(expressions);
    }

    private static List<Pattern> compile(Collection<String> expressions) {
        if (expressions == null || expressions.isEmpty()) {
            return Collections.emptyList();
        }
        List<Pattern> compiled = new ArrayList<>(expressions.size());
        for (String expression : expressions) {
            if (expression == null) {
                continue;
            }
            String pattern = expression.trim();
            if (pattern.isEmpty()) {
                continue;
            }
            try {
                compiled.add(Pattern.compile(pattern));
            } catch (PatternSyntaxException ignored) {
                // skip malformed expressions so a single bad line cannot break everything
            }
        }
        return compiled;
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
        return !patterns.isEmpty();
    }

    public int ruleCount() {
        return patterns.size();
    }

    /** The compiled regular expressions, extracted from the banned word list. */
    public List<Pattern> patterns() {
        return patterns;
    }

    /** The symbol every regex match is replaced with. */
    public String maskSymbol() {
        return maskSymbol;
    }

    /**
     * Applies the configured rule to the given text. The original {@link CharSequence}
     * reference is returned when nothing changes so callers can compare by identity.
     *
     * <ul>
     *   <li>{@link Mode#MASK}: every regex match is replaced by the mask symbol.</li>
     *   <li>{@link Mode#BLOCK}: any paragraph (line) matched by a regex is replaced
     *       entirely by the mask symbol.</li>
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
        if (!enabled || patterns.isEmpty()) {
            return input;
        }
        if (firstMatch(text) == null) {
            return input;
        }
        if (mode == Mode.BLOCK) {
            String blocked = blockMask(text, symbol);
            if (blocked.equals(text)) {
                return input;
            }
            return blocked;
        }
        String masked = maskMatches(text, symbol);
        if (masked.equals(text)) {
            return input;
        }
        return masked;
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

    private String firstMatch(String text) {
        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(text);
            if (matcher.find()) {
                try {
                    String group = matcher.group();
                    return group == null ? "" : group;
                } catch (Throwable ignored) {
                    return "";
                }
            }
        }
        return null;
    }

    private boolean matchesAny(String text) {
        for (Pattern pattern : patterns) {
            if (pattern.matcher(text).find()) {
                return true;
            }
        }
        return false;
    }

    private String maskMatches(String text, String symbol) {
        boolean[] masked = new boolean[text.length()];
        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(text);
            while (matcher.find()) {
                int start = matcher.start();
                int end = Math.min(matcher.end(), masked.length);
                for (int i = Math.max(0, start); i < end; i++) {
                    masked[i] = true;
                }
            }
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            sb.append(masked[i] ? symbol : text.charAt(i));
        }
        return sb.toString();
    }

    /** Replaces every paragraph matched by a regex with mask symbols. */
    private String blockMask(String text, String symbol) {
        StringBuilder out = new StringBuilder(text.length());
        int start = 0;
        for (int i = 0; i <= text.length(); i++) {
            if (i == text.length() || text.charAt(i) == '\n') {
                String paragraph = text.substring(start, i);
                if (!paragraph.isEmpty() && matchesAny(paragraph)) {
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
}
