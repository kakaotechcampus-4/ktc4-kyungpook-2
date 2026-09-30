package com.itda.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.Year;
import java.util.List;

import org.junit.jupiter.api.Test;

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
     * ⚠️ 알려진 한계(코드리뷰로 확인, 미해결) — 슬래시 형식과 겹치는 본문 오탐 문장이
     * 같은 파일에 "두 줄 이상" 있으면, 2줄 이상 가드를 통과해서 여전히 잘못 분리된다.
     * 이 테스트는 "고쳤다"는 게 아니라 "지금 실제로 이렇게 동작한다"는 현재 상태를
     * 기록해두는 것 — 실제 샘플로 더 나은 규칙을 찾기 전까지는 이 결과가 맞다.
     * 나중에 이 가드를 개선하면 이 테스트가 깨질 텐데, 그때 의도적으로 고친 것이다.
     */
    @Test
    void 오탐_문장이_두_줄_이상이면_가드를_통과해_여전히_잘못_분리된다() {
        List<SplitEntry> result = splitter.split("3/4 정도를 혼자 해냈다\n2/3 이상 먹었다");

        assertThat(result).hasSize(2);
        assertThat(result.get(0).content()).isEqualTo("정도를 혼자 해냈다");
        assertThat(result.get(1).content()).isEqualTo("이상 먹었다");
    }
}
