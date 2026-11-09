package com.itda.backend.service.record;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.zip.ZipInputStream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class RawTextExtractor {

    public static final Set<String> DOCUMENT_EXTENSIONS = Set.of("docx", "pdf");

    static final long DEFAULT_MAX_XML_BYTES = 5L * 1024 * 1024;

    static final long DEFAULT_MAX_TOTAL_BYTES = 30L * 1024 * 1024;

    private static final int MAX_ENTRIES = 1000;

    private static final List<byte[]> BINARY_SIGNATURES = List.of(
            new byte[] {(byte) 0x89, 'P', 'N', 'G'},
            new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF},
            new byte[] {'G', 'I', 'F', '8'},
            new byte[] {'I', 'I', 0x2A, 0x00},
            new byte[] {'M', 'M', 0x00, 0x2A},
            new byte[] {'B', 'M'},
            new byte[] {0x01, 0x00, 0x00, 0x00},
            new byte[] {(byte) 0xD7, (byte) 0xCD, (byte) 0xC6, (byte) 0x9A},
            new byte[] {'P', 'K', 0x03, 0x04},
            new byte[] {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0}
    );

    private final long maxXmlBytes;
    private final long maxTotalBytes;

    public RawTextExtractor() {
        this(DEFAULT_MAX_XML_BYTES, DEFAULT_MAX_TOTAL_BYTES);
    }

    RawTextExtractor(long maxXmlBytes, long maxTotalBytes) {
        this.maxXmlBytes = maxXmlBytes;
        this.maxTotalBytes = maxTotalBytes;
    }

    public Optional<String> extract(String extension, byte[] bytes) {
        if (bytes == null) {
            return Optional.empty();
        }
        try {
            String text = switch (extension) {
                case "csv", "txt" -> new String(bytes, StandardCharsets.UTF_8);
                case "docx" -> fromDocx(bytes);
                case "pdf" -> fromPdf(bytes);
                default -> null;
            };
            return Optional.ofNullable(text).filter(t -> !t.isBlank());
        } catch (IOException | RuntimeException | StackOverflowError e) {
            log.warn("failed to extract text from {} file: {}", extension, e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private String fromDocx(byte[] bytes) throws IOException {
        checkZipBudget(bytes);
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            StringJoiner lines = new StringJoiner("\n");
            for (IBodyElement element : doc.getBodyElements()) {
                if (element instanceof XWPFParagraph paragraph) {
                    lines.add(paragraph.getText());
                } else if (element instanceof XWPFTable table) {
                    for (XWPFTableRow row : table.getRows()) {
                        StringJoiner cells = new StringJoiner(" ");
                        for (XWPFTableCell cell : row.getTableCells()) {
                            cells.add(cellText(cell));
                        }
                        lines.add(cells.toString().strip());
                    }
                }
            }
            return lines.toString();
        }
    }

    void checkZipBudget(byte[] bytes) throws IOException {
        byte[] buffer = new byte[8192];
        long total = 0;
        long xml = 0;
        int entries = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            while (zip.getNextEntry() != null) {
                if (++entries > MAX_ENTRIES) {
                    throw new DocxBudgetExceededException("too many entries");
                }
                boolean first = true;
                boolean countsAsXml = true;
                int read;
                while ((read = zip.read(buffer)) > 0) {
                    if (first) {
                        countsAsXml = !startsWithBinarySignature(buffer, read);
                        first = false;
                    }
                    total += read;
                    if (countsAsXml) {
                        xml += read;
                    }
                    if (xml > maxXmlBytes) {
                        throw new DocxBudgetExceededException("xml parts inflate over " + maxXmlBytes + " bytes");
                    }
                    if (total > maxTotalBytes) {
                        throw new DocxBudgetExceededException("entries inflate over " + maxTotalBytes + " bytes");
                    }
                }
            }
        }
    }

    private static boolean startsWithBinarySignature(byte[] head, int length) {
        for (byte[] signature : BINARY_SIGNATURES) {
            if (length >= signature.length && Arrays.equals(head, 0, signature.length, signature, 0, signature.length)) {
                return true;
            }
        }
        return false;
    }

    static final class DocxBudgetExceededException extends IOException {

        DocxBudgetExceededException(String message) {
            super(message);
        }
    }

    private static String cellText(XWPFTableCell cell) {
        StringJoiner text = new StringJoiner(" ");
        for (XWPFParagraph paragraph : cell.getParagraphs()) {
            if (!paragraph.getText().isBlank()) {
                text.add(paragraph.getText().strip());
            }
        }
        return text.toString();
    }

    private String fromPdf(byte[] bytes) throws IOException {
        try (PDDocument doc = Loader.loadPDF(bytes, "", null, null, IOUtils.createTempFileOnlyStreamCache())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(doc);
        }
    }
}
