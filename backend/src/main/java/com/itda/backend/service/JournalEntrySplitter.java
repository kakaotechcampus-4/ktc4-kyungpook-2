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

    // 줄 시작에서: "YYYY-MM-DD"/"YYYY.MM.DD" 또는 "M/D"(짧은 형식은 슬래시만 — 코드리뷰로
    // #71에서 확인됨: "3/4 정도를 혼자 해냈다"/"0.5 정도만 참여했다" 같은 본문이 점 구분과
    // 겹쳐서 날짜로 오탐했다. 점은 4자리 연도 형식에만 쓰고 짧은 형식에서는 뺀다), 뒤에
    // 괄호 요일(선택), 그 다음 공백·콜론·대시 중 하나 이상으로 헤더가 끝난다.
    private static final Pattern DATE_HEADER = Pattern.compile(
            "^(?:(\\d{4})[-.](\\d{1,2})[-.](\\d{1,2})|(\\d{1,2})/(\\d{1,2}))"
                    + "\\s*(?:\\([월화수목금토일]\\)|[월화수목금토일]요일)?\\s*[:\\-]?\\s*(.*)$");

    public record SplitEntry(LocalDate entryDate, String content) {
    }

    public List<SplitEntry> split(String text) {
        List<SplitEntry> result = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return result;
        }

        String[] lines = text.split("\\R", -1);

        // 헤더 후보가 한 줄뿐이면 날짜 헤더가 아니라 본문일 가능성이 크다(코드리뷰 반영, #71) —
        // 잘못 쪼개서 본문 앞부분을 잃는 것보다, 하루치 일지 하나를 entryDate 없이
        // 통짜로 두는 편이 안전하다.
        //
        // ⚠️ 알려진 한계(코드리뷰로 확인됨, 미해결): "3/4 정도를 혼자 해냈다"와
        // "2/3 이상 먹었다"처럼 슬래시 형식과 우연히 겹치는 본문 문장이 같은 파일에
        // *두 줄 이상* 있으면, 이 카운트 가드를 통과해서 여전히 잘못 분리된다 — 이 가드는
        // "오탐 후보가 딱 한 줄"인 경우만 막는다. 실제 관찰일지 샘플 없이는 "정도를"/"이상"
        // 같은 조사로 이어지는 문장과 진짜 날짜 헤더를 구분할 근거가 없어서, 지금은 이
        // 잔여 위험을 문서화·테스트로만 남겨둔다(JournalEntrySplitterTest 참고). 샘플이
        // 오면 이 부분을 다시 볼 것.
        long headerCandidateCount = 0;
        for (String line : lines) {
            if (DATE_HEADER.matcher(line.strip()).matches()) {
                headerCandidateCount++;
            }
        }
        if (headerCandidateCount < 2) {
            result.add(new SplitEntry(null, text.strip()));
            return result;
        }

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
