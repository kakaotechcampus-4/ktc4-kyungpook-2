package com.itda.backend.service.record;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;

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
