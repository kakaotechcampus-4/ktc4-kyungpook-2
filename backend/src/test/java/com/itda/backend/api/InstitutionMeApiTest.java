package com.itda.backend.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;

import com.itda.backend.domain.organization.Organization;
import com.itda.backend.domain.user.User;
import com.itda.backend.fixture.OrganizationFixture;
import com.itda.backend.fixture.UserFixture;
import com.itda.backend.global.jwt.JwtCookie;
import com.itda.backend.global.jwt.JwtProvider;
import com.itda.backend.repository.organization.OrganizationRepository;
import com.itda.backend.repository.user.UserRepository;

/** O-01 내 기관 정보. 기관 코드가 생기기 전에 가입한 기관은 처음 열 때 코드가 붙는다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InstitutionMeApiTest {

    private static final String ME = "/api/v1/institutions/me";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    private Cookie auth(User user) {
        return new Cookie(JwtCookie.NAME, jwtProvider.createToken(String.valueOf(user.getId())));
    }

    private static String uniqueKakaoId() {
        return "me-" + UUID.randomUUID();
    }

    @Test
    void 코드가_없던_기관은_처음_열_때_코드가_붙고_다시_열어도_같다() throws Exception {
        Organization organization = organizationRepository.save(OrganizationFixture.center());
        assertThat(organization.getCode()).isNull();
        User staff = userRepository.save(UserFixture.organizationUser(uniqueKakaoId(), "이선생", organization.getId()));

        String first = JsonPath.read(mockMvc.perform(get(ME).cookie(auth(staff)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(String.valueOf(organization.getId())))
                .andExpect(jsonPath("$.data.name").value("햇살아동발달센터"))
                .andExpect(jsonPath("$.data.type").value("center"))
                .andReturn().getResponse().getContentAsString(), "$.data.code");
        String second = JsonPath.read(mockMvc.perform(get(ME).cookie(auth(staff)))
                .andReturn().getResponse().getContentAsString(), "$.data.code");

        assertThat(first).hasSize(Organization.CODE_LENGTH).isEqualTo(second);
        assertThat(organizationRepository.findById(organization.getId()).orElseThrow().getCode()).isEqualTo(first);
    }

    @Test
    void 보호자는_403() throws Exception {
        User parent = userRepository.save(UserFixture.parent(uniqueKakaoId(), "김보호"));

        mockMvc.perform(get(ME).cookie(auth(parent)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ORGANIZATION_NOT_ASSIGNED"));
    }
}
