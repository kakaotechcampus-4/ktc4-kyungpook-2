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
        List<SplitEntry> result = splitter.split("9/15(월) 자유놀이를 했다.");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).content()).isEqualTo("자유놀이를 했다.");
    }

    @Test
    void 연도가_있는_ISO_날짜_헤더도_인식한다() {
        List<SplitEntry> result = splitter.split("2026-08-21 오전 활동 중 …");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).entryDate()).isEqualTo(LocalDate.of(2026, 8, 21));
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
        List<SplitEntry> result = splitter.split("표지: 8월 관찰일지\n9/15 자유놀이를 했다.");

        assertThat(result).hasSize(2);
        assertThat(result.get(0).entryDate()).isNull();
        assertThat(result.get(0).content()).isEqualTo("표지: 8월 관찰일지");
        assertThat(result.get(1).content()).isEqualTo("자유놀이를 했다.");
    }
}
