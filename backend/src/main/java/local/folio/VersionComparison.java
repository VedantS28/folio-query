package local.folio;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

@Service
class VersionComparison {
    private static final int MAX_BLOCKS = 400;
    private static final int BLOCK_LENGTH = 1_200;
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");
    private static final Set<String> COMMON = Set.of("a", "an", "and", "as", "at", "be", "by", "for", "from",
            "in", "is", "it", "of", "on", "or", "the", "to", "was", "with");
    private final LocalModel model;

    VersionComparison(LocalModel model) {
        this.model = model;
    }

    Report compare(Document before, Document after, boolean explain) {
        if (before.truncated() || after.truncated()) {
            throw new IllegalArgumentException("Use complete sources for version comparison. A selected file was truncated.");
        }
        List<Block> oldBlocks = blocks(before);
        List<Block> newBlocks = blocks(after);
        List<Match> matches = align(oldBlocks, newBlocks);
        Set<Match> inOrder = orderedMatches(matches);
        boolean[] oldUsed = new boolean[oldBlocks.size()];
        var changes = new ArrayList<Change>();
        int nextMatch = 0;
        for (int index = 0; index < newBlocks.size(); index++) {
            Block right = newBlocks.get(index);
            if (nextMatch >= matches.size() || matches.get(nextMatch).after() != index) {
                changes.add(new Change("added", null, right.passage(), false));
                continue;
            }
            Match match = matches.get(nextMatch++);
            oldUsed[match.before()] = true;
            Block left = oldBlocks.get(match.before());
            boolean moved = !inOrder.contains(match);
            if (!left.normalized().equals(right.normalized())) {
                changes.add(new Change("modified", left.passage(), right.passage(), moved));
            } else if (moved) {
                changes.add(new Change("moved", left.passage(), right.passage(), true));
            }
        }
        for (int index = 0; index < oldBlocks.size(); index++) {
            if (!oldUsed[index]) changes.add(new Change("removed", oldBlocks.get(index).passage(), null, false));
        }

        int added = count(changes, "added");
        int removed = count(changes, "removed");
        int modified = count(changes, "modified");
        int moved = (int) changes.stream().filter(Change::reordered).count();
        Explanation summary = null;
        if (explain && !changes.isEmpty()) summary = explain(before.name(), after.name(), changes);
        return new Report(before.name(), after.name(), added, removed, modified, moved, List.copyOf(changes), summary);
    }

    private Explanation explain(String before, String after, List<Change> changes) {
        var evidence = new ArrayList<QuestionService.Source>();
        for (Change change : changes) {
            if (change.kind().equals("moved")) continue;
            if (change.before() != null) {
                evidence.add(new QuestionService.Source(before, "Earlier / " + change.before().location(),
                        shorten(change.before().text(), 520)));
            }
            if (change.after() != null) {
                evidence.add(new QuestionService.Source(after, "Revised / " + change.after().location(),
                        shorten(change.after().text(), 520)));
            }
            if (evidence.size() >= 7) break;
        }
        if (evidence.isEmpty()) return null;
        return model.answer("Explain what changed between the earlier and revised passages in this sample. "
                + "Identify practical implications only where the excerpts support them.", evidence).map(summary -> new Explanation(summary, List.copyOf(evidence))).orElse(null);
    }

    private static int count(List<Change> changes, String kind) {
        return (int) changes.stream().filter(change -> change.kind().equals(kind)).count();
    }

    private static List<Block> blocks(Document document) {
        var result = new ArrayList<Block>();
        for (Document.Section section : document.sections()) {
            String[] lines = section.text().split("\n", -1);
            var paragraph = new StringBuilder();
            int startLine = 1;
            for (int index = 0; index < lines.length; index++) {
                String line = lines[index].stripTrailing();
                if (line.isBlank() || line.matches("^#{1,6}\\s.+") || paragraph.length() + line.length() > BLOCK_LENGTH) {
                    addBlock(result, section.label(), startLine, paragraph.toString());
                    paragraph.setLength(0);
                }
                if (line.isBlank()) continue;
                if (line.matches("^#{1,6}\\s.+")) {
                    addBlock(result, section.label(), index + 1, line);
                    continue;
                }
                if (paragraph.isEmpty()) startLine = index + 1;
                else paragraph.append('\n');
                paragraph.append(line);
            }
            addBlock(result, section.label(), startLine, paragraph.toString());
        }
        return result;
    }

    private static void addBlock(List<Block> blocks, String section, int line, String text) {
        if (text.isBlank()) return;
        if (blocks.size() >= MAX_BLOCKS) {
            throw new IllegalArgumentException("This comparison exceeds 400 passages per version. Try smaller sections.");
        }
        String location = section.isEmpty() ? "Line " + line : section + " · extracted line " + line;
        String normalized = text.strip().replaceAll("\\s+", " ");
        var tokens = new HashSet<String>();
        var words = WORD.matcher(normalized.toLowerCase(Locale.ROOT));
        while (words.find()) {
            if (!COMMON.contains(words.group())) tokens.add(words.group());
        }
        blocks.add(new Block(new Passage(location, text.strip()), normalized, tokens));
    }

    private static List<Match> align(List<Block> before, List<Block> after) {
        var positions = new HashMap<String, ArrayList<Integer>>();
        for (int index = 0; index < before.size(); index++) {
            positions.computeIfAbsent(before.get(index).normalized(), key -> new ArrayList<>()).add(index);
        }
        var exact = new ArrayList<Match>();
        boolean[] usedBefore = new boolean[before.size()];
        boolean[] usedAfter = new boolean[after.size()];
        var next = new HashMap<String, Integer>();
        for (int index = 0; index < after.size(); index++) {
            String content = after.get(index).normalized();
            var candidates = positions.get(content);
            int offset = next.getOrDefault(content, 0);
            if (candidates == null || offset >= candidates.size()) continue;
            int earlier = candidates.get(offset);
            next.put(content, offset + 1);
            usedBefore[earlier] = true;
            usedAfter[index] = true;
            exact.add(new Match(earlier, index));
        }

        var candidates = new ArrayList<Candidate>();
        for (int left = 0; left < before.size(); left++) {
            if (usedBefore[left]) continue;
            for (int right = 0; right < after.size(); right++) {
                if (usedAfter[right]) continue;
                double score = similarity(before.get(left).terms(), after.get(right).terms());
                if (score >= 0.48) candidates.add(new Candidate(new Match(left, right), score));
            }
        }
        candidates.sort(Comparator.comparingDouble(Candidate::score).reversed()
                .thenComparingInt(candidate -> Math.abs(candidate.match().before() - candidate.match().after())));
        for (Candidate candidate : candidates) {
            Match match = candidate.match();
            if (usedBefore[match.before()] || usedAfter[match.after()]) continue;
            usedBefore[match.before()] = true;
            usedAfter[match.after()] = true;
            exact.add(match);
        }
        exact.sort(Comparator.comparingInt(Match::after));
        return exact;
    }

    private static double similarity(Set<String> left, Set<String> right) {
        var smaller = left.size() <= right.size() ? left : right;
        var larger = left.size() <= right.size() ? right : left;
        long overlap = smaller.stream().filter(larger::contains).count();
        return overlap < 2 ? 0 : 2.0 * overlap / (left.size() + right.size());
    }

    // The longest sequence of matches in the same order is the set that did not need moving.
    private static Set<Match> orderedMatches(List<Match> matches) {
        int[] lengths = new int[matches.size()];
        int[] predecessor = new int[matches.size()];
        Arrays.fill(predecessor, -1);
        int last = -1;
        for (int index = 0; index < matches.size(); index++) {
            lengths[index] = 1;
            for (int previous = 0; previous < index; previous++) {
                if (matches.get(previous).before() < matches.get(index).before()
                        && lengths[previous] + 1 > lengths[index]) {
                    lengths[index] = lengths[previous] + 1;
                    predecessor[index] = previous;
                }
            }
            if (last < 0 || lengths[index] > lengths[last]) last = index;
        }
        var result = new HashSet<Match>();
        for (int index = last; index >= 0; index = predecessor[index]) result.add(matches.get(index));
        return result;
    }

    private static String shorten(String text, int limit) {
        if (text.length() <= limit) return text;
        int end = Character.isHighSurrogate(text.charAt(limit - 1)) ? limit - 1 : limit;
        return text.substring(0, end) + "…";
    }

    record Report(String before, String after, int added, int removed, int modified, int moved,
                  List<Change> changes, Explanation explanation) {}
    record Explanation(String text, List<QuestionService.Source> sources) {}
    record Change(String kind, Passage before, Passage after, boolean reordered) {}
    record Passage(String location, String text) {}
    private record Block(Passage passage, String normalized, Set<String> terms) {}
    private record Match(int before, int after) {}
    private record Candidate(Match match, double score) {}
}
