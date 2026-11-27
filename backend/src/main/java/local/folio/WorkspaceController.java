package local.folio;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WorkspaceController {
    private static final int MAX_FILE_BYTES = 5 * 1024 * 1024;
    private static final int MAX_QUESTION_BYTES = 6 * 1024 * 1024;
    private static final int MAX_FILES = 8;

    private final DocumentReader reader;
    private final QuestionService questions;
    private final LocalModel model;
    private final VersionComparison comparison;
    private final SpreadsheetAnalysis spreadsheet;
    private final ObjectMapper json;

    public WorkspaceController(DocumentReader reader, QuestionService questions, LocalModel model, ObjectMapper json,
                               VersionComparison comparison, SpreadsheetAnalysis spreadsheet) {
        this.reader = reader;
        this.questions = questions;
        this.model = model;
        this.json = json;
        this.comparison = comparison;
        this.spreadsheet = spreadsheet;
    }

    @GetMapping("/api/status")
    public Map<String, Object> status(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return Map.of("status", "up", "modelConfigured", model.enabled(), "modelProvider", model.usesGroq() ? "groq" : model.enabled() ? "local" : "none");
    }

    @PostMapping(path = "/api/extract", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public Document extract(@RequestParam String name, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        response.setHeader("Cache-Control", "no-store");
        if (!validName(name)) throw new IllegalArgumentException("Choose a file with a valid name.");
        byte[] bytes = readBody(request, MAX_FILE_BYTES, "Files must be 5 MiB or smaller.");
        if (bytes.length == 0) throw new IllegalArgumentException("This file is empty.");
        try {
            return reader.read(name, bytes);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new IllegalArgumentException("This file could not be read. Check whether it is damaged or protected.");
        }
    }

    @PostMapping(path = "/api/ask", consumes = MediaType.APPLICATION_JSON_VALUE)
    public QuestionService.Answer ask(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setHeader("Cache-Control", "no-store");
        var input = jsonBody(request, Question.class);
        validate(input);
        String question = input.question().strip();
        boolean followUp = input.previousQuestions() != null && !input.previousQuestions().isEmpty()
                && question.matches("(?is).*\\b(he|him|his|she|her|hers|they|them|their|theirs|it|its|those|these|that|this)\\b.*");
        String context = followUp
                ? "Previous user questions: " + String.join(" | ", input.previousQuestions())
                        + "\nCurrent question: " + question
                : question;
        List<Document> selected = mentionedDocuments(question, input.documents());
        if (selected.size() == input.documents().size() && followUp) {
            for (int i = input.previousQuestions().size() - 1; i >= 0; i--) {
                List<Document> previousSelection = mentionedDocuments(input.previousQuestions().get(i), input.documents());
                if (previousSelection.size() < input.documents().size()) {
                    selected = previousSelection;
                    break;
                }
            }
        }
        List<Document> documents = selected;
        return spreadsheet.answer(question, documents)
                .orElseGet(() -> questions.answer(question, documents, context));
    }

    private static List<Document> mentionedDocuments(String question, List<Document> documents) {
        if (documents.size() < 2) return documents;
        String lower = question.toLowerCase(Locale.ROOT);
        List<Document> exact = documents.stream()
                .filter(document -> containsName(lower, document.name().toLowerCase(Locale.ROOT)))
                .toList();
        if (!exact.isEmpty()) return exact;
        List<Document> stems = documents.stream().filter(document -> {
            String name = document.name().toLowerCase(Locale.ROOT);
            int dot = name.lastIndexOf('.');
            String stem = dot > 0 ? name.substring(0, dot) : name;
            return stem.length() >= 4 && containsName(lower, stem);
        }).toList();
        return stems.size() == 1 ? stems : documents;
    }

    private static boolean containsName(String text, String name) {
        return Pattern.compile("(?<![\\p{L}\\p{N}_])" + Pattern.quote(name)
                + "(?![\\p{L}\\p{N}_])").matcher(text).find();
    }

    @PostMapping(path = "/api/compare", consumes = MediaType.APPLICATION_JSON_VALUE)
    public VersionComparison.Report compare(HttpServletRequest request) throws IOException {
        var input = jsonBody(request, Comparison.class);
        if (input == null) throw new IllegalArgumentException("Choose two versions.");
        validateDocument(input.before());
        validateDocument(input.after());
        return comparison.compare(input.before(), input.after(), input.explain());
    }

    private <T> T jsonBody(HttpServletRequest request, Class<T> type) throws IOException {
        try {
            return json.readValue(readBody(request, MAX_QUESTION_BYTES, "This request is too large."), type);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("The request is invalid.");
        }
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalidInput(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().header("Cache-Control", "no-store")
                .body(Map.of("error", exception.getMessage() == null ? "Invalid input." : exception.getMessage()));
    }

    private static byte[] readBody(HttpServletRequest request, int maximum, String message) throws IOException {
        if (request.getContentLengthLong() > maximum) throw new IllegalArgumentException(message);
        byte[] bytes = request.getInputStream().readNBytes(maximum + 1);
        if (bytes.length > maximum) throw new IllegalArgumentException(message);
        return bytes;
    }

    private static void validate(Question input) {
        if (input == null || input.question() == null || input.question().isBlank()
                || input.question().length() > 1200) {
            throw new IllegalArgumentException("Enter a question of up to 1,200 characters.");
        }
        if (input.documents() == null || input.documents().isEmpty() || input.documents().size() > MAX_FILES) {
            throw new IllegalArgumentException("Add between 1 and 8 files.");
        }
        if (input.previousQuestions() != null && (input.previousQuestions().size() > 2
                || input.previousQuestions().stream().anyMatch(previous -> previous == null || previous.isBlank()
                        || previous.length() > 1200))) {
            throw new IllegalArgumentException("Invalid conversation context.");
        }
        input.documents().forEach(WorkspaceController::validateDocument);
    }

    private static void validateDocument(Document document) {
        if (document == null || !validName(document.name()) || document.format() == null
                || document.format().length() > 20 || document.sections() == null
                || document.sections().isEmpty() || document.sections().size() > DocumentReader.MAX_SECTIONS) {
            throw new IllegalArgumentException("One of the documents is invalid.");
        }
        int characters = 0;
        for (Document.Section section : document.sections()) {
            if (section == null || section.text() == null || section.label() == null
                    || section.label().length() > 100) {
                throw new IllegalArgumentException("One of the document sections is invalid.");
            }
            characters += section.text().length();
            if (characters > DocumentReader.MAX_CHARS) throw new IllegalArgumentException("One document is too large.");
        }
        if (document.sheets() != null && !document.sheets().isEmpty()) validateSheets(document.sheets());
    }

    private static final java.util.regex.Pattern NUMBER =
            java.util.regex.Pattern.compile("-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d{1,3})?");

    private static void validateSheets(List<Document.Sheet> sheets) {
        if (sheets.size() > DocumentReader.MAX_SECTIONS) throw new IllegalArgumentException("Too many sheets.");
        int total = 0;
        for (Document.Sheet sheet : sheets) {
            if (sheet == null || sheet.name() == null || sheet.name().length() > 100 || sheet.rows() == null
                    || sheet.rows().size() > DocumentReader.MAX_TABLE_CELLS) {
                throw new IllegalArgumentException("Invalid sheet data.");
            }
            int lastRow = 0;
            for (Document.Row row : sheet.rows()) {
                if (row == null || row.number() <= lastRow || row.number() > 1_048_576 || row.cells() == null) {
                    throw new IllegalArgumentException("Invalid sheet row.");
                }
                lastRow = row.number();
                total += row.cells().size();
                if (total > DocumentReader.MAX_TABLE_CELLS) throw new IllegalArgumentException("Too many sheet cells.");
                row.cells().forEach((column, cell) -> {
                    if (column == null || column < 0 || column >= 120 || cell == null
                            || cell.text() == null || cell.text().length() > 32_767 || cell.number() != null
                            && (cell.number().length() > 32 || !NUMBER.matcher(cell.number()).matches())) {
                        throw new IllegalArgumentException("Invalid sheet cell.");
                    }
                });
            }
        }
    }

    private static boolean validName(String name) {
        return name != null && !name.isBlank() && name.length() <= 255
                && name.indexOf('/') < 0 && name.indexOf('\\') < 0
                && name.codePoints().noneMatch(Character::isISOControl);
    }

    public record Question(String question, List<Document> documents, List<String> previousQuestions) {}
    public record Comparison(Document before, Document after, boolean explain) {}
}
