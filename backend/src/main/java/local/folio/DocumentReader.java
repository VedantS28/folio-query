package local.folio;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.util.NumberToTextConverter;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Service;

@Service
public class DocumentReader {
    static final int MAX_CHARS = 100_000;
    static final int MAX_SECTIONS = 350;
    static final int MAX_TABLE_CELLS = 20_000;
    private static final Set<String> TEXT_TYPES = Set.of(
            "txt", "md", "markdown", "csv", "tsv", "log", "json", "jsonl", "xml", "yaml", "yml", "toml",
            "ini", "properties", "sql", "java", "kt", "kts", "groovy", "scala", "py", "js", "jsx", "ts",
            "tsx", "html", "htm", "css", "scss", "c", "cpp", "h", "hpp", "cs", "go", "rs", "rb", "php",
            "sh", "ps1", "bat", "swift", "r", "dart", "vue", "svelte", "ipynb");
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cc}&&[^\\t\\r\\n]]");

    public Document read(String name, byte[] bytes) throws IOException {
        int dot = name.lastIndexOf('.');
        String extension = dot >= 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        if (name.equalsIgnoreCase("Dockerfile") || name.equalsIgnoreCase("Makefile")) {
            extension = "txt";
        }
        var sections = new Sections();
        var sheets = new ArrayList<Document.Sheet>();
        switch (extension) {
            case "pdf" -> pdf(bytes, sections);
            case "xlsx", "xls" -> workbook(bytes, sections, sheets);
            case "docx" -> word(bytes, sections);
            default -> {
                if (!TEXT_TYPES.contains(extension)) {
                    throw new IllegalArgumentException("Unsupported format. Try PDF, Excel, Word or a plain text file.");
                }
                String decoded = decode(bytes);
                if (CONTROL.matcher(decoded).find()) {
                    throw new IllegalArgumentException("This file does not contain plain text.");
                }
                sections.add("", decoded);
            }
        }
        if (sections.items.isEmpty()) {
            throw new IllegalArgumentException("No readable text found. Image-only PDFs need an OCR text layer.");
        }
        return new Document(name, extension, List.copyOf(sections.items), sections.truncated, List.copyOf(sheets));
    }

    private void pdf(byte[] bytes, Sections sections) throws IOException {
        try (var pdf = Loader.loadPDF(bytes)) {
            var stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            int pageLimit = Math.min(pdf.getNumberOfPages(), MAX_SECTIONS);
            for (int page = 1; page <= pageLimit && !sections.full(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                var output = new LimitedWriter(MAX_CHARS - sections.characters);
                stripper.writeText(pdf, output);
                sections.add("Page " + page, output.text.toString());
                sections.truncated |= output.truncated;
            }
            sections.truncated |= pdf.getNumberOfPages() > pageLimit;
        }
    }

    private void workbook(byte[] bytes, Sections sections, List<Document.Sheet> sheets) throws IOException {
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            var formatter = new DataFormatter(Locale.ROOT);
            formatter.setUseCachedValuesForFormulaCells(true);
            int capturedCells = 0;
            for (var sheet : workbook) {
                if (sections.full() || capturedCells >= MAX_TABLE_CELLS) {
                    sections.truncated = true;
                    break;
                }
                var rows = new StringBuilder();
                var typedRows = new ArrayList<Document.Row>();
                for (var row : sheet) {
                    var cells = new ArrayList<String>();
                    var typedCells = new LinkedHashMap<Integer, Document.Cell>();
                    int first = Math.max(row.getFirstCellNum(), 0);
                    int last = Math.min(row.getLastCellNum(), 120);
                    sections.truncated |= row.getLastCellNum() > last;
                    for (int column = first; column < last; column++) {
                        var cell = row.getCell(column);
                        String display = formatter.formatCellValue(cell).strip().replaceAll("\\R", " ");
                        if (display.isEmpty()) continue;
                        CellType type = cell.getCellType() == CellType.FORMULA
                                ? cell.getCachedFormulaResultType() : cell.getCellType();
                        String number = type == CellType.NUMERIC && !DateUtil.isCellDateFormatted(cell)
                                ? NumberToTextConverter.toText(cell.getNumericCellValue()) : null;
                        cells.add(org.apache.poi.ss.util.CellReference.convertNumToColString(column) + "=" + display);
                        typedCells.put(column, new Document.Cell(display, number));
                    }
                    if (cells.isEmpty()) continue;
                    if (capturedCells + typedCells.size() > MAX_TABLE_CELLS) {
                        sections.truncated = true;
                        break;
                    }
                    rows.append("Row ").append(row.getRowNum() + 1).append(": ")
                            .append(String.join(" | ", cells)).append('\n');
                    if (rows.length() > MAX_CHARS - sections.characters) break;
                    capturedCells += typedCells.size();
                    typedRows.add(new Document.Row(row.getRowNum() + 1, Map.copyOf(typedCells)));
                }
                sections.add("Sheet " + sheet.getSheetName(), rows.toString());
                if (!typedRows.isEmpty()) sheets.add(new Document.Sheet(sheet.getSheetName(), List.copyOf(typedRows)));
            }
        }
    }

    private void word(byte[] bytes, Sections sections) throws IOException {
        try (var document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            var paragraphs = new StringBuilder();
            for (var element : document.getBodyElements()) {
                if (element instanceof org.apache.poi.xwpf.usermodel.XWPFParagraph paragraph) {
                    paragraphs.append(paragraph.getText()).append('\n');
                } else if (element instanceof org.apache.poi.xwpf.usermodel.XWPFTable table) {
                    paragraphs.append(table.getText()).append('\n');
                }
                if (paragraphs.length() > MAX_CHARS) break;
            }
            sections.add("", paragraphs.toString());
        }
    }

    private String decode(byte[] bytes) {
        Charset charset = StandardCharsets.UTF_8;
        int offset = 0;
        if (bytes.length >= 2) {
            int bom = (Byte.toUnsignedInt(bytes[0]) << 8) | Byte.toUnsignedInt(bytes[1]);
            if (bom == 0xfeff || bom == 0xfffe) {
                charset = StandardCharsets.UTF_16;
            } else if (bytes.length >= 3 && bom == 0xefbb && Byte.toUnsignedInt(bytes[2]) == 0xbf) {
                offset = 3;
            }
        }
        try {
            return charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset)).toString();
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("Save plain text as UTF-8 or UTF-16 and upload it again.");
        }
    }

    private static final class Sections {
        private final List<Document.Section> items = new ArrayList<>();
        private int characters;
        private boolean truncated;

        private boolean full() {
            if (items.size() >= MAX_SECTIONS || characters >= MAX_CHARS) {
                truncated = true;
                return true;
            }
            return false;
        }

        private void add(String label, String rawText) {
            String text = rawText.replace("\r\n", "\n").replace('\r', '\n').strip();
            if (text.isEmpty() || full()) return;
            int available = MAX_CHARS - characters;
            if (text.length() > available) {
                int end = Character.isHighSurrogate(text.charAt(available - 1)) ? available - 1 : available;
                text = text.substring(0, end);
                truncated = true;
            }
            if (text.isBlank()) return;
            characters += text.length();
            items.add(new Document.Section(label, text));
        }
    }

    private static final class LimitedWriter extends Writer {
        private final StringBuilder text = new StringBuilder();
        private final int limit;
        private boolean truncated;

        private LimitedWriter(int limit) {
            this.limit = limit;
        }

        @Override
        public void write(char[] data, int offset, int length) {
            int available = limit - text.length();
            text.append(data, offset, Math.min(length, available));
            truncated |= length > available;
        }

        @Override public void flush() {}
        @Override public void close() {}
    }
}
