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
    // group 6 은 날짜와 본문 사이의 구분자(공백·요일·콜론)다 — 비어 있으면 숫자에 조사가 바로 붙은 것이다.
    private static final Pattern DATE_HEADER = Pattern.compile(
            "^(?:(\\d{4})[-.](\\d{1,2})[-.](\\d{1,2})|(\\d{1,2})/(\\d{1,2}))"
                    + "(\\s*(?:\\([월화수목금토일]\\)|[월화수목금토일]요일)?\\s*[:\\-]?\\s*)(.*)$");

    // 분수 뒤에 붙는 수량 표현. "3/4 정도를 혼자 해냈다", "2/3 이상 먹었다".
    private static final Pattern QUANTITY_WORD =
            Pattern.compile("^(정도|이상|이하|미만|가량|쯤|만큼|남짓|수준)");

    // 분수로 읽힐 수 있는 날짜의 일(日) 상한. 흔히 쓰는 분수는 분모가 이 범위 안에 있고(1/2 · 3/4 · 2/3),
    // 일이 12 를 넘으면(8/21 · 9/15) 분수로 쓰는 일이 없어 날짜로 확정할 수 있다.
    private static final int AMBIGUOUS_DAY_MAX = 12;

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
            Matcher m = dateHeaderOf(line);
            if (m != null) {
                flush(result, currentDate, buffer);
                currentDate = parseDate(m);
                buffer = new StringBuilder(m.group(7));
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

    /**
     * 이 줄이 날짜 헤더면 매칭 결과를, 아니면 {@code null} 을 돌려준다.
     *
     * <p>전에는 "헤더 후보가 두 줄 미만이면 아예 분리하지 않는" 식으로 막았는데(#71), 그러면
     * <b>날짜 헤더가 하나뿐인 파일이 날짜를 통째로 잃었다</b> — 하루치를 한 파일로 올리는 게 가장
     * 흔한 형태인데 그게 전부 업로드 날짜로 저장됐다(멘토 리뷰 PR #129). 줄 수로 재던 것을 줄마다
     * 판정하는 방식으로 바꿨다.
     *
     * <p><b>분수 검사는 분수로 읽힐 수 있는 숫자에만 건다.</b> {@code 8/21} 처럼 일이 12 를 넘으면
     * 분수로 쓰는 일이 없으므로 바로 날짜로 본다 — 이 구분이 없으면
     * {@code 8/21 이상 행동이 관찰됨} 같은 줄이 "이상"에 걸려 날짜를 잃는다(코드리뷰로 확인).
     * 관찰일지에서 "이상 행동"은 흔한 표현이라 그대로 두면 고치려던 버그를 다시 만든다.
     *
     * <p><b>남은 한계</b> — 위 구분은 일이 12 를 넘는 날짜만 구해 준다. {@code 3/4 이상 행동이 관찰됨}
     * 처럼 분수로도 읽히는 숫자({@code month < day <= 12}, 달력의 약 18%)에 수량어가 이어지면 여전히
     * 날짜를 잃는다. 본문은 그대로 남고 {@code RawRecordService} 의 폴백(같은 파일 첫 날짜 → 파일명
     * → 업로드 날짜)이 받으므로 기록이 사라지지는 않는다. 실제 기관 일지 표본이 모이면 다시 본다.
     */
    private Matcher dateHeaderOf(String line) {
        Matcher m = DATE_HEADER.matcher(line.strip());
        if (!m.matches()) {
            return null;
        }
        // "13/45" 처럼 날짜로 성립하지 않는 숫자는 헤더가 아니다 — 쪼개 봐야 날짜를 못 채운다.
        if (parseDate(m) == null) {
            return null;
        }
        return looksLikeFraction(m) ? null : m;
    }

    /**
     * {@code M/D} 가 날짜가 아니라 분수로 읽히는지 본다.
     *
     * <p>두 가지를 본다. 숫자 뒤에 구분자 없이 조사가 바로 붙으면({@code 3/4를}, {@code 2/3밖에})
     * 문장 안의 분수다 — 날짜 헤더는 공백이나 콜론으로 본문과 떨어진다. 떨어져 있더라도 뒤에
     * 수량을 뜻하는 말이 오면({@code 3/4 정도를}) 역시 분수다.
     */
    private boolean looksLikeFraction(Matcher m) {
        if (m.group(4) == null) {
            return false; // 네 자리 연도 형식은 분수로 읽힐 일이 없다
        }
        int month = Integer.parseInt(m.group(4));
        int day = Integer.parseInt(m.group(5));
        if (day > AMBIGUOUS_DAY_MAX || month >= day) {
            return false; // 8/21 · 10/7 — 분수로 쓰지 않는 숫자 조합
        }
        // 구분자가 없더라도 뒤에 아무것도 없으면("3/4" 한 줄) 날짜를 제목처럼 쓴 것이다.
        // isEmpty 여야 한다 — isBlank 는 공백 한 칸도 참이라 "3/4 오늘…" 까지 분수로 본다.
        boolean particleAttached = m.group(6).isEmpty() && !m.group(7).isEmpty();
        return particleAttached || QUANTITY_WORD.matcher(m.group(7)).find();
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
