package local.folio;

import java.util.List;
import java.util.Map;

public record Document(String name, String format, List<Section> sections, boolean truncated, List<Sheet> sheets) {
    public record Section(String label, String text) {}
    public record Sheet(String name, List<Row> rows) {}
    public record Row(int number, Map<Integer, Cell> cells) {}
    public record Cell(String text, String number) {}
}
