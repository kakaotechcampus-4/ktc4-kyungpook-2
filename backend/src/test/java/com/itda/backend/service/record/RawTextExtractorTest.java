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

    private static final long MB = 1024 * 1024;

    @Test
    void docx_XML_이_상한을_넘게_풀리면_못_읽은_파일로_둔다() throws IOException {
        byte[] docx = Docx.withBody("word/document.xml", bodyXml(6 * MB)).bytes();

        assertThat(extractor.extract("docx", docx)).isEmpty();
        assertThat(new RawTextExtractor(20 * MB, 60 * MB).extract("docx", docx))
                .hasValueSatisfying(text -> assertThat(text).startsWith("2026-09-15 첫 기록"));
    }

    @Test
    void docx_본문_이름을_바꿔도_같은_상한에서_막는다() throws IOException {
        byte[] docx = Docx.withBody("word/document.dat", bodyXml(MB + 1024)).bytes();

        assertThat(new RawTextExtractor(MB, 60 * MB).extract("docx", docx)).isEmpty();
        assertThat(new RawTextExtractor(2 * MB, 60 * MB).extract("docx", docx))
                .hasValueSatisfying(text -> assertThat(text).startsWith("2026-09-15 첫 기록"));
    }

    @Test
    void docx_관계_파일이_상한을_넘게_풀려도_막는다() throws IOException {
        byte[] docx = Docx.withBody("word/document.xml", bodyXml(1024))
                .rootRelsPadding(MB + 1024)
                .bytes();

        assertThat(new RawTextExtractor(MB, 60 * MB).extract("docx", docx)).isEmpty();
        assertThat(new RawTextExtractor(2 * MB, 60 * MB).extract("docx", docx)).isPresent();
    }

    @Test
    void docx_그림_이름이어도_내용이_그림이_아니면_XML_로_센다() throws IOException {
        byte[] docx = Docx.withBody("word/document.xml", bodyXml(1024))
                .extra("word/media/image1.png", randomWords(MB + 1024, 3).getBytes(StandardCharsets.UTF_8))
                .bytes();

        assertThat(new RawTextExtractor(MB, 60 * MB).extract("docx", docx)).isEmpty();
    }

    @Test
    void docx_그림은_전체_상한만_받는다() throws IOException {
        Docx docx = Docx.withBody("word/document.xml", bodyXml(1024));
        for (int i = 0; i < 4; i++) {
            docx.extra("word/media/image" + i + ".png", png(MB - 1024, i));
        }
        byte[] bytes = docx.bytes();

        assertThat(new RawTextExtractor(MB, 5 * MB).extract("docx", bytes)).isPresent();
        assertThat(new RawTextExtractor(MB, 3 * MB).extract("docx", bytes)).isEmpty();
    }

    @Test
    void 사전_검사는_정상_docx_를_통과시킨다() throws IOException {
        extractor.checkZipBudget(docx(List.of("관찰일지"), List.of(List.of("2026-09-15", "센터", "블록을 쌓음"))));
    }

    private static String bodyXml(long bytes) {
        StringBuilder xml = new StringBuilder(
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                        + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
                        + "<w:p><w:r><w:t>2026-09-15 첫 기록</w:t></w:r></w:p>");
        String filler = randomWords(1000, 42);
        while (xml.length() < bytes) {
            xml.append("<w:p><w:r><w:t>").append(filler, 0, 1000).append("</w:t></w:r></w:p>");
            filler = filler.substring(7) + filler.substring(0, 7);
        }
        return xml.append("</w:body></w:document>").toString();
    }

    private static byte[] png(long length, long seed) {
        byte[] head = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
        byte[] body = randomWords(length - head.length, seed).getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[head.length + body.length];
        System.arraycopy(head, 0, out, 0, head.length);
        System.arraycopy(body, 0, out, head.length, body.length);
        return out;
    }

    private static String randomWords(long length, long seed) {
        java.util.Random random = new java.util.Random(seed);
        StringBuilder text = new StringBuilder();
        while (text.length() < length) {
            int wordLength = 2 + random.nextInt(7);
            for (int i = 0; i < wordLength; i++) {
                text.append((char) ('a' + random.nextInt(26)));
            }
            text.append(' ');
        }
        return text.substring(0, (int) length);
    }

    private static final class Docx {

        private static final String MAIN = "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml";

        private final String bodyName;
        private final String body;
        private final java.util.Map<String, byte[]> extras = new java.util.LinkedHashMap<>();
        private long rootRelsPadding;

        private Docx(String bodyName, String body) {
            this.bodyName = bodyName;
            this.body = body;
        }

        static Docx withBody(String name, String body) {
            return new Docx(name, body);
        }

        Docx rootRelsPadding(long bytes) {
            this.rootRelsPadding = bytes;
            return this;
        }

        Docx extra(String name, byte[] content) {
            extras.put(name, content);
            return this;
        }

        byte[] bytes() throws IOException {
            String contentTypes = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                    + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                    + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                    + "<Default Extension=\"png\" ContentType=\"image/png\"/>"
                    + "<Override PartName=\"/" + bodyName + "\" ContentType=\"" + MAIN + "\"/>"
                    + "</Types>";
            String rootRels = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + (rootRelsPadding > 0 ? "<!-- " + randomWords(rootRelsPadding, 7) + " -->" : "")
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\""
                    + " Target=\"" + bodyName + "\"/>"
                    + "</Relationships>";
            try (ByteArrayOutputStream out = new ByteArrayOutputStream();
                    java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(out)) {
                put(zip, "[Content_Types].xml", contentTypes);
                put(zip, "_rels/.rels", rootRels);
                put(zip, bodyName, body);
                for (java.util.Map.Entry<String, byte[]> extra : extras.entrySet()) {
                    put(zip, extra.getKey(), extra.getValue());
                }
                zip.finish();
                return out.toByteArray();
            }
        }

        private static void put(java.util.zip.ZipOutputStream zip, String name, String content) throws IOException {
            put(zip, name, content.getBytes(StandardCharsets.UTF_8));
        }

        private static void put(java.util.zip.ZipOutputStream zip, String name, byte[] content) throws IOException {
            zip.putNextEntry(new java.util.zip.ZipEntry(name));
            zip.write(content);
            zip.closeEntry();
        }
    }

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
