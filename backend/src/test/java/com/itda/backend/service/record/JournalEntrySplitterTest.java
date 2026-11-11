package com.itda.backend.service.record;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.Year;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.itda.backend.service.record.JournalEntrySplitter.SplitEntry;

class JournalEntrySplitterTest {

    private final JournalEntrySplitter splitter = new JournalEntrySplitter();

    @Test
    void 빈_내용은_빈_목록을_반환한다() {
        assertThat(splitter.split("")).isEmpty();
        assertThat(splitter.split(null)).isEmpty();
    }

    @Test
    void 날짜_헤더가_없으면_전체를_한_건으로_처리한다() {
        List<SplitEntry> result = splitter.split("오늘 있었던 일\n그냥 이어지는 문장");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).entryDate()).isNull();
        assertThat(result.get(0).content()).isEqualTo("오늘 있었던 일\n그냥 이어지는 문장");
    }

    @Test
    void 슬래시_날짜_헤더마다_새_기록으로_나눈다() {
        List<SplitEntry> result = splitter.split(
                "9/15 자유놀이 중 블록을 높이 쌓았다.\n9/16 미술 시간에 그림을 완성함.");

        assertThat(result).hasSize(2);
        assertThat(result.get(0).content()).isEqualTo("자유놀이 중 블록을 높이 쌓았다.");
        assertThat(result.get(0).entryDate()).isEqualTo(LocalDate.of(Year.now().getValue(), 9, 15));
        assertThat(result.get(1).content()).isEqualTo("미술 시간에 그림을 완성함.");
        assertThat(result.get(1).entryDate()).isEqualTo(LocalDate.of(Year.now().getValue(), 9, 16));
    }

    @Test
    void 요일이_붙은_헤더도_인식한다() {
        List<SplitEntry> result = splitter.split(
                "9/15(월) 자유놀이를 했다.\n9/16(화) 미술 시간.");

        assertThat(result).hasSize(2);
        assertThat(result.get(0).content()).isEqualTo("자유놀이를 했다.");
        assertThat(result.get(1).content()).isEqualTo("미술 시간.");
    }

    @Test
    void 연도가_있는_ISO_날짜_헤더도_인식한다() {
        List<SplitEntry> result = splitter.split(
                "2026-08-21 오전 활동 중 …\n2026.08.22 오후 활동 중 …");

        assertThat(result).hasSize(2);
        assertThat(result.get(0).entryDate()).isEqualTo(LocalDate.of(2026, 8, 21));
        assertThat(result.get(1).entryDate()).isEqualTo(LocalDate.of(2026, 8, 22));
    }

    @Test
    void 헤더_다음_여러_줄은_한_기록으로_합쳐진다() {
        List<SplitEntry> result = splitter.split(
                "9/15 자유놀이 중 블록을 높이 쌓았다.\n선생님이 칭찬해줬다.\n\n9/16 미술 시간.");

        assertThat(result).hasSize(2);
        assertThat(result.get(0).content()).isEqualTo("자유놀이 중 블록을 높이 쌓았다.\n선생님이 칭찬해줬다.");
    }

    @Test
    void 헤더_앞에_내용이_있으면_날짜없는_기록으로_먼저_들어간다() {
        List<SplitEntry> result = splitter.split(
                "표지: 8월 관찰일지\n9/15 자유놀이를 했다.\n9/16 미술 시간.");

        assertThat(result).hasSize(3);
        assertThat(result.get(0).entryDate()).isNull();
        assertThat(result.get(0).content()).isEqualTo("표지: 8월 관찰일지");
        assertThat(result.get(1).content()).isEqualTo("자유놀이를 했다.");
    }

    @Test
    void 슬래시_형식_문장이_한_줄뿐이면_날짜_헤더로_보지_않는다() {
        assertThat(splitter.split("3/4 정도를 혼자 해냈다").get(0).content())
                .isEqualTo("3/4 정도를 혼자 해냈다");
        assertThat(splitter.split("2/3 이상 먹었다").get(0).content())
                .isEqualTo("2/3 이상 먹었다");
    }

    @Test
    void 짧은_형식은_점_구분을_날짜로_인식하지_않는다() {
        assertThat(splitter.split("1.5 시간 동안 집중했다").get(0).content())
                .isEqualTo("1.5 시간 동안 집중했다");
        assertThat(splitter.split("10.30 경 등원했다").get(0).content())
                .isEqualTo("10.30 경 등원했다");
        assertThat(splitter.split("0.5 정도만 참여했다").get(0).content())
                .isEqualTo("0.5 정도만 참여했다");
    }

    @Test
    void 분수_표기가_여러_줄이어도_날짜_헤더로_보지_않는다() {
        List<SplitEntry> result = splitter.split("3/4 정도를 혼자 해냈다\n2/3 이상 먹었다");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).entryDate()).isNull();
        assertThat(result.get(0).content()).isEqualTo("3/4 정도를 혼자 해냈다\n2/3 이상 먹었다");
    }

    @Test
    void 날짜_헤더가_하나뿐이어도_그_날짜를_쓴다() {
        List<SplitEntry> result = splitter.split("8/21 오늘 급식을 잘 먹었다.\n자유놀이 시간에 블록을 쌓았다.");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).entryDate()).isEqualTo(LocalDate.of(Year.now().getValue(), 8, 21));
        assertThat(result.get(0).content()).isEqualTo("오늘 급식을 잘 먹었다.\n자유놀이 시간에 블록을 쌓았다.");
    }

    @Test
    void 날짜로_성립하지_않는_숫자는_헤더로_보지_않는다() {
        List<SplitEntry> result = splitter.split("13/45 그냥 숫자로 시작하는 줄\n13/46 또 그런 줄");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).entryDate()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "8/21 이상 행동이 관찰됨",
            "8/21 이상하게 조용했다",
            "9/15 정도를 벗어난 반응을 보임",
            "8/21 수준 높은 집중을 보임",
    })
    void 일이_12를_넘으면_뒤에_수량어가_와도_날짜로_읽는다(String line) {
        List<SplitEntry> result = splitter.split(line);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).entryDate()).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "3/4를 혼자 해냈다",
            "2/3밖에 먹지 않았다",
            "1/2씩 나눠 먹었다",
            "3/4만 참여했다",
    })
    void 숫자에_조사가_바로_붙으면_날짜_헤더로_보지_않는다(String line) {
        List<SplitEntry> result = splitter.split(line);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).entryDate()).isNull();
        assertThat(result.get(0).content()).isEqualTo(line);
    }

    @Test
    void 분수_모양_숫자도_수량어가_없으면_날짜로_읽는다() {
        List<SplitEntry> result = splitter.split("3/4 오늘 급식을 잘 먹었다.");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).entryDate()).isEqualTo(LocalDate.of(Year.now().getValue(), 3, 4));
        assertThat(result.get(0).content()).isEqualTo("오늘 급식을 잘 먹었다.");
    }

    @Test
    void 날짜만_한_줄에_쓴_형식도_날짜로_읽는다() {
        List<SplitEntry> result = splitter.split("3/4\n오늘 급식을 잘 먹었다.");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).entryDate()).isEqualTo(LocalDate.of(Year.now().getValue(), 3, 4));
        assertThat(result.get(0).content()).isEqualTo("오늘 급식을 잘 먹었다.");
    }

    @Test
    void 꺾인_줄은_잇고_새_날짜와_빈_줄과_날짜만_있는_줄은_그대로_둔다() {
        String pdfText = "2026-03-02 학교 지속 시간까지 필\n요 이상으로 보호자와 \n실랑이가 있었음.\n"
                + "2026-03-08 학교 손을 잡고 이동함.\n\n"
                + "3/4\n오늘 급식을 잘 먹었다.";

        List<SplitEntry> result = splitter.split(splitter.joinWrappedLines(pdfText));

        assertThat(result).extracting(SplitEntry::content).containsExactly(
                "학교 지속 시간까지 필요 이상으로 보호자와 실랑이가 있었음.",
                "학교 손을 잡고 이동함.",
                "오늘 급식을 잘 먹었다.");
    }

    @Test
    void 상한을_넘으면_한_건_넘긴_데서_멈춘다() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            text.append(LocalDate.of(2026, 1, 1).plusDays(i)).append(" 관찰 ").append(i).append('\n');
        }

        List<SplitEntry> entries = new JournalEntrySplitter().split(text.toString(), 3);

        assertThat(entries).hasSizeBetween(4, 5);
        assertThat(entries.get(0).content()).isEqualTo("관찰 0");
    }

    @Test
    void 상한_안이면_상한_없이_나눈_것과_같다() {
        String text = "9/15 블록을 쌓음\n이어진 문장\n\n9/16 점심을 먹음\r\n9/17 낮잠";

        assertThat(new JournalEntrySplitter().split(text, 3)).isEqualTo(new JournalEntrySplitter().split(text));
        assertThat(new JournalEntrySplitter().split(text)).hasSize(3);
    }
}
