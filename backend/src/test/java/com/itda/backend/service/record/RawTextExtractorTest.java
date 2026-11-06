package com.itda.backend.service.record;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;

/** 업로드 원본에서 기록 분리용 텍스트를 뽑는다 (#383). 못 뽑으면 비어서 돌아와야 한다. */
class RawTextExtractorTest {

    private final RawTextExtractor extractor = new RawTextExtractor();

    @Test
    void txt_는_그대로_읽는다() {
        assertThat(extractor.extract("txt", "9/15 블록".getBytes(StandardCharsets.UTF_8))).contains("9/15 블록");
    }

    @Test
    void docx_는_문단과_표를_문서_순서대로_뽑고_표는_한_행을_한_줄로_만든다() throws IOException {
        byte[] docx = docx(List.of("관찰일지"),
                List.of(List.of("2026-09-15", "센터", "블록을 쌓음")));

        assertThat(extractor.extract("docx", docx)).contains("관찰일지\n2026-09-15 센터 블록을 쌓음");
    }

    /** 칸 안의 여러 문단을 붙여 쓰면 "…지름.손을…" 처럼 문장이 엉킨다. 공백으로 잇는다. */
    @Test
    void docx_칸_안의_여러_문단은_공백으로_잇는다() throws IOException {
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XWPFTable table = doc.createTable(1, 2);
            table.getRow(0).getCell(0).setText("2026-09-15");
            table.getRow(0).getCell(1).setText("급식실 입장 직후 소리를 지름.");
            table.getRow(0).getCell(1).addParagraph().createRun().setText("손을 잡고 30까지 세자 편안해짐.");
            doc.write(out);

            assertThat(extractor.extract("docx", out.toByteArray()))
                    .contains("2026-09-15 급식실 입장 직후 소리를 지름. 손을 잡고 30까지 세자 편안해짐.");
        }
    }

    @Test
    void pdf_는_글자를_뽑는다() throws IOException {
        assertThat(extractor.extract("pdf", pdf("2026-09-15 block tower")))
                .hasValueSatisfying(text -> assertThat(text).contains("2026-09-15 block tower"));
    }

    @Test
    void 깨진_파일이나_지원하지_않는_형식은_비어서_돌아온다() {
        assertThat(extractor.extract("pdf", "%PDF-1.4 fake".getBytes())).isEmpty();
        assertThat(extractor.extract("docx", "not a zip".getBytes())).isEmpty();
        assertThat(extractor.extract("hwp", "x".getBytes())).isEmpty();
        assertThat(extractor.extract("txt", null)).isEmpty();
    }

    @Test
    void 글자가_없는_pdf_는_비어서_돌아온다() throws IOException {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.addPage(new PDPage());
            doc.save(out);
            assertThat(extractor.extract("pdf", out.toByteArray())).isEmpty();
        }
    }

    /** 문단 몇 줄과 표 하나로 docx 를 만든다. 표가 비어 있으면 표를 넣지 않는다. */
    static byte[] docx(List<String> paragraphs, List<List<String>> rows) throws IOException {
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            paragraphs.forEach(text -> doc.createParagraph().createRun().setText(text));
            if (!rows.isEmpty()) {
                XWPFTable table = doc.createTable(rows.size(), rows.get(0).size());
                for (int r = 0; r < rows.size(); r++) {
                    for (int c = 0; c < rows.get(r).size(); c++) {
                        table.getRow(r).getCell(c).setText(rows.get(r).get(c));
                    }
                }
            }
            doc.write(out);
            return out.toByteArray();
        }
    }

    private static byte[] pdf(String line) throws IOException {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(72, 720);
                content.showText(line);
                content.endText();
            }
            doc.save(out);
            return out.toByteArray();
        }
    }
}
