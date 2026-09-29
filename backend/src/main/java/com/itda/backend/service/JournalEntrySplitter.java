package com.itda.backend.service;

import java.time.LocalDate;
import java.time.MonthDay;
import java.time.Year;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

// ponytail: 실제 관찰일지 샘플이 아직 없어서(기관협력 담당에게 요청 중) 날짜 헤더 패턴은
// 노션 "기록 분리 기능" 문서의 예시(`9/15 ...`, `9/16 ...`)와 팀이 논의한 ISO 형식을
// 추정해 만든 최선의 추측이다. 실제 샘플이 오면 이 클래스(패턴/파싱)만 바꾸면 된다 —
// 호출부(RawRecordService)는 안 바뀐다.
@Component
public class JournalEntrySplitter {

    // 줄 시작에서: "YYYY-MM-DD"/"YYYY.MM.DD" 또는 "M/D"/"M.D", 뒤에 괄호 요일(선택),
    // 그 다음 공백·콜론·대시 중 하나 이상으로 헤더가 끝난다.
    private static final Pattern DATE_HEADER = Pattern.compile(
            "^(?:(\\d{4})[-.](\\d{1,2})[-.](\\d{1,2})|(\\d{1,2})[/.](\\d{1,2}))"
                    + "\\s*(?:\\([월화수목금토일]\\)|[월화수목금토일]요일)?\\s*[:\\-]?\\s*(.*)$");

    public record SplitEntry(LocalDate entryDate, String content) {
    }

    public List<SplitEntry> split(String text) {
        List<SplitEntry> result = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return result;
        }

        String[] lines = text.split("\\R", -1);
        LocalDate currentDate = null;
        StringBuilder buffer = new StringBuilder();

        for (String line : lines) {
            Matcher m = DATE_HEADER.matcher(line.strip());
            if (m.matches()) {
                flush(result, currentDate, buffer);
                currentDate = parseDate(m);
                buffer = new StringBuilder(m.group(6));
            } else if (!line.isBlank()) {
                if (!buffer.isEmpty()) {
                    buffer.append('\n');
                }
                buffer.append(line.strip());
            }
        }
        flush(result, currentDate, buffer);

        // 날짜 헤더가 하나도 없으면(패턴이 안 맞는 파일) 전체를 1건으로 처리한다.
        if (result.isEmpty() && !text.isBlank()) {
            result.add(new SplitEntry(null, text.strip()));
        }
        return result;
    }

    private void flush(List<SplitEntry> result, LocalDate date, StringBuilder buffer) {
        String content = buffer.toString().strip();
        if (!content.isEmpty()) {
            result.add(new SplitEntry(date, content));
        }
    }

    private LocalDate parseDate(Matcher m) {
        try {
            if (m.group(1) != null) {
                return LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
            }
            // 연도 없는 "M/D" — 실제 샘플이 없어 연도를 알 방법이 없다. 업로드 시점의 연도로 추정한다.
            MonthDay md = MonthDay.of(Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)));
            return md.atYear(Year.now().getValue());
        } catch (java.time.DateTimeException e) {
            return null;
        }
    }
}
