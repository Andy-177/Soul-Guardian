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
    private final List<Pattern> patterns;

    public FilterConfig(boolean enabled, Mode mode, String maskSymbol,
                        Collection<String> expressions) {
        this.enabled = enabled;
        this.mode = mode == null ? Mode.MASK : mode;
        this.maskSymbol = (maskSymbol == null || maskSymbol.isEmpty())
                ? DEFAULT_MASK_SYMBOL : maskSymbol;
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
        return new FilterConfig(false, Mode.MASK, DEFAULT_MASK_SYMBOL,
                Collections.<String>emptyList());
    }

    public boolean isEnabled() {
        return enabled;
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
        if (input == null) {
            return null;
        }
        if (!enabled || patterns.isEmpty()) {
            return input;
        }
        String text = input.toString();
        if (text.isEmpty()) {
            return input;
        }
        String detected = firstMatch(text);
        if (detected == null) {
            return input;
        }
        if (mode == Mode.BLOCK) {
            String blocked = blockMask(text);
            if (blocked.equals(text)) {
                return input;
            }
            return blocked;
        }
        String masked = maskMatches(text);
        if (masked.equals(text)) {
            return input;
        }
        return masked;
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

    private String maskMatches(String text) {
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
            sb.append(masked[i] ? maskSymbol : text.charAt(i));
        }
        return sb.toString();
    }

    /** Replaces every paragraph matched by a regex with mask symbols. */
    private String blockMask(String text) {
        StringBuilder out = new StringBuilder(text.length());
        int start = 0;
        for (int i = 0; i <= text.length(); i++) {
            if (i == text.length() || text.charAt(i) == '\n') {
                String paragraph = text.substring(start, i);
                if (!paragraph.isEmpty() && matchesAny(paragraph)) {
                    out.append(repeat(paragraph.length()));
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

    private String repeat(int times) {
        if (times <= 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder(maskSymbol.length() * times);
        for (int i = 0; i < times; i++) {
            sb.append(maskSymbol);
        }
        return sb.toString();
    }
}
