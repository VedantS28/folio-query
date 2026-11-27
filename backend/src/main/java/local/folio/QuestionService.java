package local.folio;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.onnx.allminilml6v2q.AllMiniLmL6V2QuantizedEmbeddingModel;

@Service
public class QuestionService {
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");
    private static final Set<String> STOP_WORDS = Set.of(
            "a", "an", "and", "about", "are", "as", "at", "be", "by", "can", "could", "did", "do", "does",
            "document", "documents", "file", "files", "for", "from", "give", "has", "have", "how", "i", "in",
            "is", "it", "just", "me", "of", "on", "or", "please", "say", "says", "show", "tell", "than", "that",
            "the", "their", "these", "this", "to", "was", "were", "what", "when", "where", "which", "who",
            "why", "will", "with", "would", "you", "your", "all", "text", "resume", "caan");
    private static final Pattern OVERVIEW = Pattern.compile(
            "\\b(summari[sz]e|summary|overview)\\b|\\bwhat (are|is) (the )?(main|key)\\b");
    private final LocalModel model;
    private volatile AllMiniLmL6V2QuantizedEmbeddingModel embeddingModel;

    private synchronized AllMiniLmL6V2QuantizedEmbeddingModel embeddingModel() {
        if (embeddingModel == null) embeddingModel = new AllMiniLmL6V2QuantizedEmbeddingModel();
        return embeddingModel;
    }

    public QuestionService(LocalModel model) {
        this.model = model;
    }

    public Answer answer(String question, List<Document> documents) {
        Set<String> query = terms(question);
        String lowerQuestion = question.toLowerCase(Locale.ROOT);
        boolean overview = OVERVIEW.matcher(lowerQuestion).find();
        var overviewTerms = Set.of("summarize", "summarise", "summary", "compare", "overview", "main",
                "key", "point", "detail", "finding", "takeaway", "topic", "theme", "difference", "between",
                "attachment", "attached", "upload", "uploaded", "content", "entire", "whole", "it", "its");
        if (overview) query.removeAll(overviewTerms);

        if (overview && query.isEmpty()) {
            return respond(question, overviewSources(documents), documents.size(), true);
        }

        var candidates = new ArrayList<Passage>();
        for (Document document : documents) {
            for (Document.Section section : document.sections()) {
                addPassages(candidates, document, section);
            }
        }
        var frequency = new HashMap<String, Integer>();
        for (Passage passage : candidates) {
            for (String term : query) {
                if (passage.terms().contains(term)) frequency.merge(term, 1, Integer::sum);
            }
        }

        var lexicalScores = new ArrayList<Double>();
        double maxLexicalScore = 0;
        for (Passage passage : candidates) {
            double score = 0;
            for (String term : query) {
                double weight = 1 + Math.log1p((double) candidates.size() / (1 + frequency.getOrDefault(term, 0)));
                if (passage.terms().contains(term)) score += weight;
                if (passage.locationTerms().contains(term)) score += weight * 0.22;
            }
            lexicalScores.add(score);
            maxLexicalScore = Math.max(maxLexicalScore, score);
        }

        double[] similarities = new double[candidates.size()];
        try {
            var embedder = embeddingModel();
            float[] questionVector = embedder.embed(question).content().vector();
            for (int start = 0; start < candidates.size(); start += 32) {
                int end = Math.min(start + 32, candidates.size());
                var segments = candidates.subList(start, end).stream()
                        .map(passage -> TextSegment.from(passage.source().excerpt())).toList();
                var vectors = embedder.embedAll(segments).content();
                for (int i = 0; i < vectors.size(); i++) {
                    float[] vector = vectors.get(i).vector();
                    double dot = 0, left = 0, right = 0;
                    for (int j = 0; j < vector.length; j++) {
                        dot += questionVector[j] * vector[j];
                        left += questionVector[j] * questionVector[j];
                        right += vector[j] * vector[j];
                    }
                    if (left > 0 && right > 0) similarities[start + i] = dot / Math.sqrt(left * right);
                }
            }
        } catch (RuntimeException | LinkageError exception) {
            System.err.println("Embedding retrieval unavailable: " + exception.getClass().getName());
        }

        var scored = new ArrayList<ScoredPassage>();
        for (int i = 0; i < candidates.size(); i++) {
            double keyword = maxLexicalScore > 0 ? lexicalScores.get(i) / maxLexicalScore : 0;
            double semantic = similarities[i] >= 0.30 ? similarities[i] : 0;
            double score = 0.55 * keyword + 0.45 * semantic;
            if (score > 0) scored.add(new ScoredPassage(candidates.get(i), score));
        }
        scored.sort(Comparator.comparingDouble(ScoredPassage::score).reversed());

        var selected = new ArrayList<Passage>();
        if (!scored.isEmpty()) {
            double cutoff = scored.get(0).score() * 0.42;
            var seen = new HashSet<Source>();
            for (ScoredPassage candidate : scored) {
                if (selected.size() == 8 || candidate.score() < cutoff) break;
                if (seen.add(candidate.passage().source())) selected.add(candidate.passage());
            }
        }
        List<Source> context = selected.stream().map(Passage::source).toList();
        if (context.isEmpty()) {
            return new Answer("I couldn’t find a matching passage in the uploaded files. Try a name, "
                    + "column heading, or phrase used in the source.", List.of(), "no-match");
        }

        return respond(question, context, overview ? Math.max(4, documents.size()) : 4, overview);
    }

    private Answer respond(String question, List<Source> context, int excerptCount, boolean overview) {
        var generated = model.answer(question, context);
        if (generated.isPresent()) {
            return new Answer(generated.get(), context, model.usesGroq() ? "groq-ai" : "local-ai");
        }
        String intro = overview
                ? "Here are excerpts from the files for a quick overview."
                : "Here are the passages that best match your question.";
        return new Answer(intro, context.stream().limit(excerptCount).toList(), "excerpts");
    }

    private static List<Source> overviewSources(List<Document> documents) {
        var sources = new ArrayList<Source>();
        for (Document document : documents) {
            var first = document.sections().get(0);
            String location = first.label().isEmpty() ? "Start of file" : first.label();
            sources.add(new Source(document.name(), location, abbreviate(first.text(), 700)));
        }
        for (Document document : documents) {
            if (sources.size() == 8) break;
            var last = document.sections().get(document.sections().size() - 1);
            if (document.sections().size() > 1) {
                sources.add(new Source(document.name(), last.label(), abbreviate(last.text(), 700)));
            } else if (last.text().length() > 1_400) {
                int start = last.text().length() - 700;
                if (Character.isLowSurrogate(last.text().charAt(start))) start++;
                sources.add(new Source(document.name(), "End of file", last.text().substring(start)));
            }
        }
        return sources;
    }

    private static void addPassages(List<Passage> target, Document document, Document.Section section) {
        String text = section.text();
        Set<String> locationTerms = terms(document.name() + " " + section.label());
        boolean table = document.format().equals("xlsx") || document.format().equals("xls");
        String tableHeader = table ? text.lines().findFirst().orElse("") : "";
        int lineNumber = 1;
        String[] lines = text.split("\n");
        for (String rawLine : lines) {
            String line = rawLine.strip();
            if (!line.isEmpty()) {
                var sentences = BreakIterator.getSentenceInstance(Locale.ROOT);
                sentences.setText(line);
                for (int start = sentences.first(), end = sentences.next(); end != BreakIterator.DONE;
                     start = end, end = sentences.next()) {
                    for (int chunk = start; chunk < end;) {
                        int limit = Math.min(chunk + 600, end);
                        if (limit < end && Character.isHighSurrogate(line.charAt(limit - 1))) limit--;
                        String excerpt = line.substring(chunk, limit).strip();
                        if (!excerpt.isEmpty()) {
                            String location = section.label().isEmpty() ? "Line " + lineNumber : section.label();
                            String supportingText = excerpt;
                            if (table && lineNumber > 1) {
                                supportingText = abbreviate(tableHeader, 350) + "\n" + excerpt;
                            } else if (!table && line.length() < 100 && lineNumber < lines.length) {
                                var context = new StringBuilder(excerpt);
                                for (int next = lineNumber; next < Math.min(lines.length, lineNumber + 8); next++) {
                                    if (lines[next].isBlank()) break;
                                    context.append("\n").append(lines[next].strip());
                                }
                                supportingText = abbreviate(context.toString(), 1500);
                            }
                            target.add(new Passage(new Source(document.name(), location, supportingText),
                                    terms(supportingText), locationTerms));
                        }
                        chunk = limit;
                    }
                }
            }
            lineNumber++;
        }
    }

    private static String abbreviate(String text, int limit) {
        if (text.length() <= limit) return text;
        int end = Character.isHighSurrogate(text.charAt(limit - 1)) ? limit - 1 : limit;
        return text.substring(0, end) + "…";
    }

    private static Set<String> terms(String text) {
        var words = WORD.matcher(text.toLowerCase(Locale.ROOT));
        Set<String> terms = new HashSet<>();
        while (words.find()) {
            String term = words.group();
            if (term.endsWith("s") && !term.endsWith("ss") && term.length() > 4) {
                term = term.substring(0, term.length() - 1);
            }
            if (!STOP_WORDS.contains(term)) terms.add(term);
        }
        return terms;
    }

    private record Passage(Source source, Set<String> terms, Set<String> locationTerms) {}

    private record ScoredPassage(Passage passage, double score) {}
    public record Source(String file, String location, String excerpt) {}
    public record Answer(String text, List<Source> sources, String mode) {}
}
