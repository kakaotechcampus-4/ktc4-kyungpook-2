package com.itda.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.Year;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.itda.backend.service.JournalEntrySplitter.SplitEntry;

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

    // --- 코드리뷰 반영(#71): 날짜 헤더 오탐 회귀 테스트 -------------------------------

    /**
     * 헤더 후보가 한 줄뿐이면 날짜 헤더로 취급하지 않는다 — 실제 일지는 하루에 기록이
     * 여러 건이라 날짜 헤더도 여러 번 나오는 게 정상이고, 한 줄만 매칭되면 그건 본문일
     * 가능성이 크다. 잘못 쪼개서 본문 앞부분을 잃는 것보다 통짜로 두는 게 안전하다.
     */
    @Test
    void 슬래시_형식_문장이_한_줄뿐이면_날짜_헤더로_보지_않는다() {
        assertThat(splitter.split("3/4 정도를 혼자 해냈다").get(0).content())
                .isEqualTo("3/4 정도를 혼자 해냈다");
        assertThat(splitter.split("2/3 이상 먹었다").get(0).content())
                .isEqualTo("2/3 이상 먹었다");
    }

    /** 짧은 형식(M/D)은 점 구분을 더 이상 인식하지 않는다 — 소수점·시간 표기와 겹쳐서 오탐했었다. */
    @Test
    void 짧은_형식은_점_구분을_날짜로_인식하지_않는다() {
        assertThat(splitter.split("1.5 시간 동안 집중했다").get(0).content())
                .isEqualTo("1.5 시간 동안 집중했다");
        assertThat(splitter.split("10.30 경 등원했다").get(0).content())
                .isEqualTo("10.30 경 등원했다");
        assertThat(splitter.split("0.5 정도만 참여했다").get(0).content())
                .isEqualTo("0.5 정도만 참여했다");
    }

    /**
     * 분수 표기가 여러 줄이어도 더 이상 잘못 분리되지 않는다 (멘토 리뷰 PR #129 반영).
     *
     * <p>예전에는 "헤더 후보가 두 줄 미만"일 때만 막아서 이 경우가 통과했고, 알려진 한계로
     * 남겨뒀었다. 이제는 줄마다 "날짜 뒤에 수량을 뜻하는 말이 오는지"를 보므로 줄 수와 무관하다.
     */
    @Test
    void 분수_표기가_여러_줄이어도_날짜_헤더로_보지_않는다() {
        List<SplitEntry> result = splitter.split("3/4 정도를 혼자 해냈다\n2/3 이상 먹었다");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).entryDate()).isNull();
        assertThat(result.get(0).content()).isEqualTo("3/4 정도를 혼자 해냈다\n2/3 이상 먹었다");
    }

    /**
     * 날짜 헤더가 하나뿐인 파일도 그 날짜를 쓴다 (멘토 리뷰 PR #129).
     *
     * <p>하루치를 한 파일로 올리는 게 가장 흔한 형태인데, 예전 가드는 이걸 전부 날짜 없는
     * 기록으로 만들었다. 그러면 8월 21일에 관찰한 내용을 10월 7일에 올렸을 때 관찰 날짜가
     * 업로드 날짜로 저장된다(RawRecordService 의 폴백).
     */
    @Test
    void 날짜_헤더가_하나뿐이어도_그_날짜를_쓴다() {
        List<SplitEntry> result = splitter.split("8/21 오늘 급식을 잘 먹었다.\n자유놀이 시간에 블록을 쌓았다.");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).entryDate()).isEqualTo(LocalDate.of(Year.now().getValue(), 8, 21));
        assertThat(result.get(0).content()).isEqualTo("오늘 급식을 잘 먹었다.\n자유놀이 시간에 블록을 쌓았다.");
    }

    /** 날짜로 성립하지 않는 숫자는 헤더가 아니다 — 쪼개 봐야 날짜를 못 채운다. */
    @Test
    void 날짜로_성립하지_않는_숫자는_헤더로_보지_않는다() {
        // 수량어("이상한")가 아니라 날짜 검사에 걸리는지 보려고 평범한 문장을 쓴다(코드리뷰 반영).
        List<SplitEntry> result = splitter.split("13/45 그냥 숫자로 시작하는 줄\n13/46 또 그런 줄");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).entryDate()).isNull();
    }

    /**
     * 일(日)이 12를 넘으면 분수로 쓰지 않으므로 수량어 검사를 걸지 않는다 (코드리뷰 반영).
     *
     * <p>관찰일지에서 "이상 행동"은 흔한 표현인데, 수량어 검사를 모든 날짜에 걸면 이 줄이
     * "이상"에 걸려 날짜를 잃는다 — 고치려던 버그를 다른 입력에서 되살리는 셈이다.
     */
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

    /** 숫자에 조사가 바로 붙으면 문장 안의 분수다 — 날짜 헤더는 공백·콜론으로 본문과 떨어진다. */
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

    /** 분수로 읽힐 수 있는 숫자여도 띄어쓰고 평범한 문장이 이어지면 날짜로 본다. */
    @Test
    void 분수_모양_숫자도_수량어가_없으면_날짜로_읽는다() {
        List<SplitEntry> result = splitter.split("3/4 오늘 급식을 잘 먹었다.");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).entryDate()).isEqualTo(LocalDate.of(Year.now().getValue(), 3, 4));
        assertThat(result.get(0).content()).isEqualTo("오늘 급식을 잘 먹었다.");
    }

    /**
     * 날짜만 따로 한 줄에 쓰는 형식도 읽는다 (코드리뷰 반영).
     *
     * <p>뒤에 아무것도 없으면 구분자도 비는데, 이걸 "조사가 바로 붙었다"로 보면 분수 모양 숫자만
     * 골라 날짜를 잃는다 — {@code 8/21} 은 일이 12 를 넘어 우회되므로 티가 안 난다.
     */
    @Test
    void 날짜만_한_줄에_쓴_형식도_날짜로_읽는다() {
        List<SplitEntry> result = splitter.split("3/4\n오늘 급식을 잘 먹었다.");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).entryDate()).isEqualTo(LocalDate.of(Year.now().getValue(), 3, 4));
        assertThat(result.get(0).content()).isEqualTo("오늘 급식을 잘 먹었다.");
    }
}
