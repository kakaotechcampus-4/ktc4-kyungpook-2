package com.itda.backend.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.itda.backend.domain.ChildStatus;
import com.itda.backend.dto.ChildRosterResponse;
import com.itda.backend.global.jwt.JwtCookie;
import com.itda.backend.global.jwt.JwtProvider;
import com.itda.backend.service.ChildService;

@WebMvcTest(ChildController.class)
@AutoConfigureMockMvc(addFilters = false)
class ChildControllerTest {

    private static final String BASE_URL = "/api/v1/institutions/me/children";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ChildService childService;

    @MockitoBean
    private JwtProvider jwtProvider;

    @MockitoBean
    private JwtCookie jwtCookie;

    @Test
    void getRoster_returnsChildren() throws Exception {
        given(childService.getRoster(any()))
                .willReturn(List.of(new ChildRosterResponse("1", "김하늘", "2020-01-01", ChildStatus.ACTIVE)));

        mockMvc.perform(get(BASE_URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("SUCCESS"))
                .andExpect(jsonPath("$.data[0].name").value("김하늘"))
                .andExpect(jsonPath("$.data[0].status").value("active"));
    }
}
