package local.folio;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

@Service
class SpreadsheetAnalysis {
    private static final Pattern LEADING = Pattern.compile("^(?:(?:what (?:is|are)|show(?: me)?|calculate)\\s+(?:the\\s+)?)?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern COMMAND = Pattern.compile(
            "^(total|sum|average|mean|count|minimum|min|lowest|maximum|max|highest)\\b(?:\\s+of\\b)?\\s*(.*)$",
            Pattern.CASE_INSENSITIVE);
    private static final int MAX_GROUPS = 100;
    private static final Pattern SHEET = Pattern.compile("\\s+in\\s+(?:sheet\\s+)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern BY = Pattern.compile("\\s+by\\s+", Pattern.CASE_INSENSITIVE);
    private static final Pattern WHERE = Pattern.compile("\\s+where\\s+", Pattern.CASE_INSENSITIVE);

    Optional<QuestionService.Answer> answer(String question, List<Document> documents) {
        var workbookSheets = documents.stream()
                .filter(document -> document.sheets() != null && !document.sheets().isEmpty())
                .flatMap(document -> document.sheets().stream().map(sheet -> new SourceSheet(document, sheet)))
                .toList();


        String text = LEADING.matcher(question.strip().replaceFirst("[?.!]+$", "")).replaceFirst("");
        text = text.replaceFirst("(?i)^how many\\s+", "count ");
        var command = COMMAND.matcher(text);
        if (!command.matches()) return Optional.empty();
        if (workbookSheets.isEmpty()) {
            boolean hasWorkbook = documents.stream().anyMatch(document -> document.format().equals("xlsx")
                    || document.format().equals("xls"));
            return hasWorkbook ? Optional.of(note("Upload an Excel sheet with a header row and data to calculate this answer."))
                    : Optional.empty();
        }
        Operation operation = Operation.from(command.group(1));
        String args = command.group(2).strip();
        String[] condition = WHERE.split(args, 2);
        String[] location = SHEET.split(condition[0], 2);
        String[] grouping = BY.split(location[0], 2);

        String metric = unquote(grouping[0]);
        boolean rowsOnly = operation == Operation.COUNT && (metric.isEmpty() || metric.equalsIgnoreCase("rows")
                || metric.equalsIgnoreCase("records"));
        String group = grouping.length == 2 ? unquote(grouping[1]) : null;
        String sheetName = location.length == 2 ? unquote(location[1]) : null;
        String[] filter = condition.length == 2 ? condition[1].split("=", 2) : new String[0];
        if (condition.length == 2 && (filter.length != 2 || unquote(filter[0]).isEmpty() || unquote(filter[1]).isEmpty())) {
            return Optional.of(note("Use an equality filter such as “where Region = West”."));
        }

        var matches = new ArrayList<Plan>();
        for (SourceSheet source : workbookSheets) {
            Document.Sheet sheet = source.sheet();
            if (sheet.rows().isEmpty() || sheetName != null && !sheet.name().equalsIgnoreCase(sheetName)) continue;
            Document.Row headers = sheet.rows().get(0);
            Integer measureColumn = rowsOnly ? null : column(headers, metric);
            Integer groupColumn = group == null ? null : column(headers, group);
            Integer filterColumn = filter.length == 0 ? null : column(headers, unquote(filter[0]));
            boolean matchesMetric = rowsOnly || measureColumn != null;
            if (matchesMetric && (group == null || groupColumn != null) && (filter.length == 0 || filterColumn != null)) {
                String filterValue = filter.length == 0 ? "" : unquote(filter[1]);
                matches.add(new Plan(source, operation, metric, measureColumn, group, groupColumn, filterColumn, filterValue));
            }
        }
        if (matches.size() != 1) {
            return Optional.of(note(matches.isEmpty()
                    ? "I couldn’t match those column names to a sheet. Use the exact first-row headers, for example “sum Revenue by Region”."
                    : "More than one sheet matches. Add “in SheetName” after the metric or grouping, or leave one workbook in the workspace."));
        }
        return Optional.of(calculate(matches.get(0)));
    }

    private static QuestionService.Answer calculate(Plan plan) {
        SourceSheet source = plan.source();
        var sheet = source.sheet();
        if (source.document().truncated()) {
            return note("This workbook was truncated during upload. Upload a smaller workbook or split the sheet before calculating totals.");
        }
        var results = new LinkedHashMap<String, Stats>();
        var sample = new ArrayList<String>();
        int matched = 0;
        int used = 0;
        int blanks = 0;
        for (int index = 1; index < sheet.rows().size(); index++) {
            var row = sheet.rows().get(index);
            if (plan.filterColumn() != null && !value(row, plan.filterColumn()).equalsIgnoreCase(plan.filterValue())) continue;
            matched++;
            BigDecimal value = BigDecimal.ONE;
            var measure = plan.measureColumn() == null ? null : row.cells().get(plan.measureColumn());
            if (plan.measureColumn() != null) {
                if (measure == null || measure.text().isBlank()) {
                    blanks++;
                    continue;
                }
                if (plan.operation() != Operation.COUNT) {
                    if (measure.number() == null) {
                        return note("Row " + row.number() + " in " + sheet.name() + " has a nonnumeric value in "
                                + plan.metric() + ". Convert the column to numbers or filter that row out.");
                    }
                    value = new BigDecimal(measure.number());
                }
            }
            String group = plan.groupColumn() == null ? "" : value(row, plan.groupColumn());
            results.computeIfAbsent(group, ignored -> new Stats()).add(value);
            if (results.size() > MAX_GROUPS) return note("This question returns more than 100 groups. Narrow it with a filter.");
            used++;
            if (sample.size() < 8) {
                sample.add("Row " + row.number() + (plan.groupColumn() == null ? "" : " · " + displayGroup(group))
                        + (measure == null ? "" : " · " + measure.text()));
            }
        }

        String title = plan.operation().label + (plan.measureColumn() == null ? " rows" : " of " + plan.metric());
        StringBuilder output = new StringBuilder();
        if (results.isEmpty()) {
            output.append(plan.operation() == Operation.COUNT && plan.groupColumn() == null
                    ? title + " = 0" : "No values matched the calculation.");
        } else {
            output.append(title);
            if (plan.groupColumn() == null) {
                output.append(" = ").append(results.values().iterator().next().result(plan.operation()));
            } else {
                output.append(" by ").append(plan.group());
                results.forEach((group, stats) -> output.append('\n').append(displayGroup(group))
                        .append(" · ").append(stats.result(plan.operation())));
            }
        }
        output.append("\n\n").append(used).append(" of ").append(sheet.rows().size() - 1).append(" data rows used");
        if (plan.filterColumn() != null) output.append(" (").append(matched).append(" matched the filter)");
        if (blanks > 0) output.append(" · ").append(blanks).append(" blank metric cells skipped");
        var evidence = new QuestionService.Source(source.document().name(), "Sheet " + sheet.name(),
                "Header row " + sheet.rows().get(0).number() + "\n"
                        + (sample.isEmpty() ? "No contributing rows." : "Example contributing rows\n" + String.join("\n", sample)));
        return new QuestionService.Answer(output.toString(), List.of(evidence), "spreadsheet");
    }

    private static String unquote(String text) {
        String value = text.strip();
        return value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")
                ? value.substring(1, value.length() - 1).strip() : value;
    }

    private static Integer column(Document.Row headers, String name) {
        var matches = headers.cells().entrySet().stream().filter(cell -> cell.getValue().text().equalsIgnoreCase(name)).toList();
        return matches.size() == 1 ? matches.get(0).getKey() : null;
    }

    private static String value(Document.Row row, int column) {
        var cell = row.cells().get(column);
        return cell == null ? "" : cell.text();
    }

    private static String displayGroup(String group) {
        return group.isEmpty() ? "(blank)" : group;
    }

    private static QuestionService.Answer note(String text) {
        return new QuestionService.Answer(text, List.of(), "spreadsheet");
    }

    private enum Operation {
        SUM("Sum"), AVERAGE("Average"), COUNT("Count"), MIN("Minimum"), MAX("Maximum");

        private final String label;

        Operation(String label) {
            this.label = label;
        }

        private static Operation from(String name) {
            return switch (name.toLowerCase(Locale.ROOT)) {
                case "total", "sum" -> SUM;
                case "average", "mean" -> AVERAGE;
                case "count" -> COUNT;
                case "minimum", "min", "lowest" -> MIN;
                default -> MAX;
            };
        }
    }

    private static final class Stats {
        private BigDecimal sum = BigDecimal.ZERO;
        private BigDecimal min;
        private BigDecimal max;
        private int count;

        private void add(BigDecimal value) {
            sum = sum.add(value);
            min = min == null ? value : min.min(value);
            max = max == null ? value : max.max(value);
            count++;
        }

        private String result(Operation operation) {
            BigDecimal value = switch (operation) {
                case SUM -> sum;
                case AVERAGE -> sum.divide(BigDecimal.valueOf(count), MathContext.DECIMAL128);
                case COUNT -> BigDecimal.valueOf(count);
                case MIN -> min;
                case MAX -> max;
            };
            return value.stripTrailingZeros().toPlainString();
        }
    }

    private record SourceSheet(Document document, Document.Sheet sheet) {}
    private record Plan(SourceSheet source, Operation operation, String metric, Integer measureColumn,
                        String group, Integer groupColumn, Integer filterColumn, String filterValue) {}
}
