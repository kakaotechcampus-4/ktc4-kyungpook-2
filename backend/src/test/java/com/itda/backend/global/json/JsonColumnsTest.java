package com.itda.backend.global.json;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class JsonColumnsTest {

    private static final String COLUMN = "matching_result.mentioned_child_ids";

    @Test
    void 배열은_숫자와_숫자_문자열을_읽는다() {
        assertThat(JsonColumns.readLongsAllowingText("[15, \"17\", \" 18 \"]", COLUMN, 1L))
                .containsExactly(15L, 17L, 18L);
    }

    @Test
    void 배열만_읽는_함수는_객체를_빈_목록으로_본다() {
        assertThat(JsonColumns.readLongsAllowingText("{\"other\": 12}", COLUMN, 1L)).isEmpty();
    }

    @Test
    void 값까지_읽는_함수는_객체의_값도_읽는다() {
        assertThat(JsonColumns.readLongValuesAllowingText("{\"other\": 12, \"friend\": \"13\"}", COLUMN, 1L))
                .containsExactly(12L, 13L);
        assertThat(JsonColumns.readLongValuesAllowingText("[12, \"13\"]", COLUMN, 1L)).containsExactly(12L, 13L);
        assertThat(JsonColumns.readLongValuesAllowingText("12", COLUMN, 1L)).isEmpty();
    }

    @Test
    void 배열만_읽는_함수는_잘못된_원소가_하나라도_있으면_빈_목록을_준다() {
        assertThat(JsonColumns.readLongsAllowingText("[42, \"invalid\"]", COLUMN, 1L)).isEmpty();
        assertThat(JsonColumns.readLongsAllowingText("[42, null]", COLUMN, 1L)).isEmpty();
        assertThat(JsonColumns.readLongsAllowingText("[42, true]", COLUMN, 1L)).isEmpty();
        assertThat(JsonColumns.readLongsAllowingText("[42, {\"id\": 1}]", COLUMN, 1L)).isEmpty();
    }

    @Test
    void 값까지_읽는_함수는_잘못된_원소만_건너뛴다() {
        assertThat(JsonColumns.readLongValuesAllowingText("[42, \"invalid\", null]", COLUMN, 1L))
                .containsExactly(42L);
    }

    @Test
    void 숫자가_아닌_값의_원문을_로그에_남기지_않는다(CapturedOutput output) {
        assertThat(JsonColumns.readLongsAllowingText("[\"김비밀\\n다음줄\", 3]", COLUMN, 7L)).isEmpty();

        assertThat(output).contains(COLUMN, "id=7").doesNotContain("김비밀", "다음줄");
    }

    @Test
    void 깨진_JSON_의_원문을_로그에_남기지_않는다(CapturedOutput output) {
        assertThat(JsonColumns.readLongsAllowingText("[김비밀]", COLUMN, 8L)).isEmpty();

        assertThat(output).contains(COLUMN, "id=8").doesNotContain("김비밀");
    }
}
