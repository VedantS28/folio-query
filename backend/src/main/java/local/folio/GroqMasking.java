package local.folio;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class GroqMasking {
    private static final String NAME = "\\p{Lu}[\\p{L}'’.-]+(?:[ \\t]+\\p{Lu}[\\p{L}'’.-]+){1,3}";
    private static final Pattern LABELED_NAME = Pattern.compile(
            "(?im:\\b(?:full name|first name|last name|patient name|employee name|customer name|name|owner|contact|author|prepared by)"
            + "[ \\t]*[:=][ \\t]*)(" + NAME + "|\\p{Lu}[\\p{L}'’.-]+)");
    private static final Pattern CONTACT_HEADER = Pattern.compile(
            "(?m)\\A\\s*(" + NAME + ")[ \\t]*\\n(?=.{0,180}(?:@|\\+\\d)|"
            + "(?:(?:[^\\n]*\\n){0,3})(?i:education|technical skills|work experience)\\b)");
    private static final Pattern PRIVATE_VALUE = Pattern.compile(
            "(?i)[\\p{L}\\p{N}._%+-]+@[\\p{L}\\p{N}.-]+\\.[\\p{L}]{2,}"
            + "|\\b(?:https?://|www\\.)[^\\s<>\"\\])]+"
            + "|\\b(?:gsk_|gh[pousr]_|github_pat_|sk-|AIza)[a-z0-9_-]{16,}\\b"
            + "|\\b(?:api[ _-]?key|access[ _-]?token|password|secret|passport(?: number)?|"
            + "social security(?: number)?|ssn|aadhaar(?: number)?|account number|card number|"
            + "date of birth|dob|home address|mailing address|address)[ \\t]*[:=][ \\t]*(?<value>[^\\r\\n,;|]{1,160})"
            + "|\\b\\d{3}-\\d{2}-\\d{4}\\b"
            + "|\\b\\d{4}[ -]\\d{4}[ -]\\d{4}(?:[ -]\\d{4})?\\b"
            + "|(?<![\\p{L}\\p{N}])(?:\\+\\d{1,3}[ .-]?)?(?:\\(\\d{2,4}\\)|\\d{2,5})[ .-]?"
            + "\\d{3,5}[ .-]?\\d{4}(?![\\p{L}\\p{N}])");

    private final Map<String, String> tokensByValue = new LinkedHashMap<>();
    private final Map<String, String> valuesByToken = new LinkedHashMap<>();
    private final String prefix = "FOLIO_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12) + "_";
    private final Pattern token = Pattern.compile(Pattern.quote(prefix) + "\\d+_END");
    private final Pattern knownIdentifiers;

    GroqMasking(List<QuestionService.Source> sources, String question, String context) {
        for (QuestionService.Source source : sources) {
            remember(source.file());
            collectNames(source.excerpt(), CONTACT_HEADER);
            collectNames(source.excerpt(), LABELED_NAME);
            collectNames(source.location(), LABELED_NAME);
        }
        collectNames(question, LABELED_NAME);
        collectNames(context, LABELED_NAME);
        String alternatives = tokensByValue.keySet().stream()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .map(Pattern::quote).reduce((left, right) -> left + "|" + right).orElse("(?!)");
        knownIdentifiers = Pattern.compile("(?<![\\p{L}\\p{N}])(?:" + alternatives
                + ")(?![\\p{L}\\p{N}])", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    String filename(String value) {
        return remember(value);
    }

    String mask(String text) {
        // Detect full sensitive values before replacing names inside them.
        String masked = PRIVATE_VALUE.matcher(text).replaceAll(match -> {
            String value = match.group(1);
            if (value == null) return Matcher.quoteReplacement(remember(match.group()));
            String label = match.group().substring(0, match.start(1) - match.start());
            return Matcher.quoteReplacement(label + remember(value.strip()));
        });
        return knownIdentifiers.matcher(masked).replaceAll(match -> Matcher.quoteReplacement(remember(match.group())));
    }

    String restore(String text) {
        return token.matcher(text).replaceAll(match ->
                Matcher.quoteReplacement(valuesByToken.getOrDefault(match.group(), match.group())));
    }

    private void collectNames(String text, Pattern pattern) {
        pattern.matcher(text).results().map(match -> match.group(1)).forEach(this::remember);
    }

    private String remember(String value) {
        return tokensByValue.computeIfAbsent(value, original -> {
            String placeholder = prefix + (valuesByToken.size() + 1) + "_END";
            valuesByToken.put(placeholder, original);
            return placeholder;
        });
    }
}
