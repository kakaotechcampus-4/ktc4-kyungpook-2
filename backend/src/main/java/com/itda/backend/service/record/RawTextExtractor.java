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

/**
 * 업로드한 원본 파일에서 기록 분리({@link JournalEntrySplitter})에 넘길 텍스트를 뽑는다 (#383).
 *
 * <p>원본 파일은 건드리지 않는다 — 여기서 만든 텍스트는 분리 단계에만 쓰고 저장하지 않는다.
 * 못 뽑으면(깨진 파일, 암호 걸린 pdf, 지원하지 않는 형식) 비어서 돌아가고, 호출한 쪽은 업로드를
 * 그대로 두고 분리만 건너뛴다.
 *
 * <p>docx 는 문단과 표를 문서 순서대로 뽑는다. <b>표는 한 행이 한 줄</b>이다 — 일지 표(날짜 / 출처기관 /
 * 관찰 내용)는 행이 날짜로 시작해 분리기가 그 줄을 날짜 헤더로 읽고, 표지 표(아동명 / 김하늘 / 생년월일 /
 * 2018-03-02)는 행이 날짜로 시작하지 않아 생년월일을 날짜 헤더로 잘못 읽지 않는다. 칸 안의 여러 문단은
 * 공백으로 잇는다.
 *
 * <p>ponytail: 날짜가 첫 칸이 아닌 표, 내용 컨트롤(양식 필드) 안의 글자는 아직 못 읽는다 — 실제 기관
 * 양식이 모이면 넓힌다. pdf 는 글자 위치 순서로 뽑고, 쪽마다 반복되는 머리글은 그대로 남는다(검증이
 * 개인정보를 막는다). 사진만 있는 pdf 는 글자가 없어 빈 텍스트가 된다 — OCR 은 범위 밖이다.
 */
@Slf4j
@Component
public class RawTextExtractor {

    /** 문서 형식. 첫 날짜 앞의 머리말(제목·아동 이름·생년월일)을 기록으로 만들지 않는다. */
    public static final Set<String> DOCUMENT_EXTENSIONS = Set.of("docx", "pdf");

    /**
     * docx 압축 안에서 XML 로 읽힐 수 있는 파일이 풀리는 바이트의 합 (#424). POI 는 XML 을 DOM 으로 올려 XML 1MB 마다 힙을
     * 대략 13~16MB 쓴다(-Xmx512m 실측, PR 참고). 5MB 면 한 건에 힙 약 96MB, 동시 2건({@code app.raw-record.upload}) 약
     * 160MB 다. 일지 문서로는 수백 쪽이다.
     */
    static final long DEFAULT_MAX_XML_BYTES = 5L * 1024 * 1024;

    /** docx 압축 안 모든 파일이 풀리는 바이트의 합 (#424). 사진처럼 파싱하지 않는 파일도 POI 가 메모리에 다 푼다. */
    static final long DEFAULT_MAX_TOTAL_BYTES = 30L * 1024 * 1024;

    /** docx 압축 안 파일 수. POI 기본값(1000)과 같다 — 사전 검사가 작은 파일 수백만 개를 훑느라 오래 걸리지 않게 둔다. */
    private static final int MAX_ENTRIES = 1000;

    /** XML 이 아니라고 확신할 수 있는 앞머리 — 문서에 흔히 드는 그림 · 내장 파일. 여기 없으면 XML 로 센다. */
    private static final List<byte[]> BINARY_SIGNATURES = List.of(
            new byte[] {(byte) 0x89, 'P', 'N', 'G'},                 // PNG
            new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF},       // JPEG
            new byte[] {'G', 'I', 'F', '8'},                          // GIF
            new byte[] {'I', 'I', 0x2A, 0x00},                        // TIFF
            new byte[] {'M', 'M', 0x00, 0x2A},                        // TIFF
            new byte[] {'B', 'M'},                                    // BMP
            new byte[] {0x01, 0x00, 0x00, 0x00},                      // EMF
            new byte[] {(byte) 0xD7, (byte) 0xCD, (byte) 0xC6, (byte) 0x9A}, // WMF
            new byte[] {'P', 'K', 0x03, 0x04},                        // 내장 Office 파일
            new byte[] {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0}  // 내장 OLE
    );

    private final long maxXmlBytes;
    private final long maxTotalBytes;

    public RawTextExtractor() {
        this(DEFAULT_MAX_XML_BYTES, DEFAULT_MAX_TOTAL_BYTES);
    }

    /** 시험에서 상한을 바꿔 "POI 혼자서는 읽는 파일인데 사전 검사가 막았다"를 보이려고 둔다. */
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
            // StackOverflowError: 깊게 중첩된 pdf 객체를 읽다 날 수 있다. 업로드는 이미 저장됐으니 분리만 건너뛴다.
            // 파일 내용은 로그에 남기지 않는다 — 아동 기록이다.
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

    /**
     * POI 에 넘기기 전에 압축을 한 번 풀어 보며 실제로 풀리는 바이트를 센다 (#424). POI 는 docx 압축 안 파일을 전부 메모리에 풀고
     * XML 은 DOM 으로 올린다. 기본 설정은 파일 하나를 100배까지 풀도록 허용해 20MB 파일이 힙(512MB)을 넘게 풀릴 수 있다.
     *
     * <p><b>이름이나 확장자로 고르지 않는다.</b> POI 는 관계 정보({@code _rels/*.rels})가 가리키는 파트를 본문으로 읽으므로
     * 이름이 {@code .xml} 이 아니어도 XML 로 파싱한다. 그래서 내용으로 가른다 — 그림 · 내장 파일의 앞머리로 확신할 수 있는
     * 파일만 XML 에서 빼고 나머지는 모두 XML 로 센다. 그림 앞머리로 시작하는 파일은 XML 로 읽히지 않는다(XML 은 {@code <}
     * 나 BOM 으로 시작한다). 그림은 전체 합 상한만 받는다.
     *
     * <p>머리글의 크기 값은 믿지 않고 실제로 읽어 센다. POI 전역 설정({@code ZipSecureFile})은 쓰지 않는다.
     */
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

    /** docx 압축이 상한보다 크게 풀린다. 못 읽은 파일로 둔다 — {@link #extract} 가 다른 읽기 실패와 같이 받는다. */
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
        // 큰 pdf 의 내부 스트림은 힙 대신 임시 파일에 둔다 — 업로드 한도(20MB) 안에서도 객체 그래프가 커질 수 있다.
        try (PDDocument doc = Loader.loadPDF(bytes, "", null, null, IOUtils.createTempFileOnlyStreamCache())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(doc);
        }
    }
}
