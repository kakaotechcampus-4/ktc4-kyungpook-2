package com.itda.backend.dto.request.child;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itda.backend.dto.request.child.UpdateCareInfoRequest.MedicationRequest;

/**
 * 돌봄 정보 요청이 생성자에서 무엇을 다듬고, 다듬은 뒤 애너테이션이 무엇을 막는지 본다. 위반은 컨트롤러에서 400
 * INVALID_REQUEST 가 된다.
 */
class UpdateCareInfoRequestTest {

    private static ValidatorFactory factory;
    private static Validator validator;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeAll
    static void setUpValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    // --- 다듬기 ---

    @Test
    void 앞뒤_공백만_떼고_안쪽_공백은_둔다() {
        UpdateCareInfoRequest request = new UpdateCareInfoRequest(
                List.of(" 땅콩 ", "갑각류 (새우)"), List.of(new MedicationRequest("  ○○정 ", " 점심 식후 ")));

        assertThat(request.allergies()).containsExactly("땅콩", "갑각류 (새우)");
        assertThat(request.medications().getFirst().name()).isEqualTo("○○정");
        assertThat(request.medications().getFirst().time()).isEqualTo("점심 식후");
        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void 같은_알레르기는_처음_순서대로_하나로_합친다() {
        UpdateCareInfoRequest request = new UpdateCareInfoRequest(
                List.of("땅콩", "계란", " 땅콩 "), List.of());

        assertThat(request.allergies()).containsExactly("땅콩", "계란");
    }

    @Test
    void 복용_시간이_빠지거나_null_이면_빈_문자열이다() throws Exception {
        UpdateCareInfoRequest request = objectMapper.readValue("""
                {"allergies": [], "medications": [{"name": "○○정"}, {"name": "△△시럽", "time": null}]}
                """, UpdateCareInfoRequest.class);

        assertThat(request.medications()).extracting(MedicationRequest::time).containsExactly("", "");
        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void 두_목록을_모두_비워도_된다() {
        assertThat(validator.validate(new UpdateCareInfoRequest(List.of(), List.of()))).isEmpty();
    }

    // --- 개수 ---

    /** 20개는 합친 뒤의 개수다. 서로 다른 21개는 막는다. */
    @Test
    void 서로_다른_알레르기_21개는_위반이다() {
        assertThat(validator.validate(new UpdateCareInfoRequest(distinct(21), List.of()))).isNotEmpty();
        assertThat(validator.validate(new UpdateCareInfoRequest(distinct(20), List.of()))).isEmpty();
    }

    @Test
    void 중복이_섞인_21개는_합쳐서_20개면_통과한다() {
        List<String> allergies = new ArrayList<>(distinct(20));
        allergies.add("알레르기1");

        UpdateCareInfoRequest request = new UpdateCareInfoRequest(allergies, List.of());

        assertThat(request.allergies()).hasSize(20);
        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void 복용약_21개는_위반이다() {
        List<MedicationRequest> medications = IntStream.rangeClosed(1, 21)
                .mapToObj(i -> new MedicationRequest("약" + i, ""))
                .toList();

        assertThat(validator.validate(new UpdateCareInfoRequest(List.of(), medications))).isNotEmpty();
    }

    // --- 값 ---

    @Test
    void 공백뿐인_알레르기는_위반이다() {
        assertThat(validator.validate(new UpdateCareInfoRequest(List.of("   "), List.of()))).isNotEmpty();
    }

    /** 길이는 공백을 뗀 뒤에 잰다. 앞뒤 공백 때문에 30자를 넘는 것은 통과한다. */
    @Test
    void 알레르기는_30자까지다() {
        assertThat(validator.validate(new UpdateCareInfoRequest(List.of("가".repeat(31)), List.of()))).isNotEmpty();
        assertThat(validator.validate(new UpdateCareInfoRequest(List.of(" " + "가".repeat(30) + " "), List.of())))
                .isEmpty();
    }

    @Test
    void null_목록과_null_원소는_위반이다() {
        assertThat(validator.validate(new UpdateCareInfoRequest(null, List.of()))).isNotEmpty();
        assertThat(validator.validate(new UpdateCareInfoRequest(List.of(), null))).isNotEmpty();
        assertThat(validator.validate(new UpdateCareInfoRequest(Arrays.asList("땅콩", null), List.of())))
                .isNotEmpty();
        assertThat(validator.validate(new UpdateCareInfoRequest(List.of(), Arrays.asList((MedicationRequest) null))))
                .isNotEmpty();
    }

    @Test
    void 복용약_이름이_없거나_공백이면_위반이다() {
        assertThat(validator.validate(new UpdateCareInfoRequest(List.of(), List.of(new MedicationRequest(null, "")))))
                .isNotEmpty();
        assertThat(validator.validate(new UpdateCareInfoRequest(List.of(), List.of(new MedicationRequest("  ", "")))))
                .isNotEmpty();
    }

    @Test
    void 복용약_이름과_시간은_30자까지다() {
        assertThat(validator.validate(new UpdateCareInfoRequest(List.of(),
                List.of(new MedicationRequest("가".repeat(31), ""))))).isNotEmpty();
        assertThat(validator.validate(new UpdateCareInfoRequest(List.of(),
                List.of(new MedicationRequest("○○정", "가".repeat(31)))))).isNotEmpty();
    }

    private static List<String> distinct(int count) {
        return IntStream.rangeClosed(1, count).mapToObj(i -> "알레르기" + i).toList();
    }
}
