package com.itda.backend.global.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.itda.backend.service.OrganizationSeedService;

import lombok.RequiredArgsConstructor;

/**
 * 기동할 때 시연용 데이터를 넣는다. 스프링 수명주기에 붙이는 일만 하고,
 * 무엇을 어떻게 넣을지는 Service 가 정한다.
 *
 * <p>끄려면 {@code app.seed.organizations.enabled=false}. 다만 기관이 하나도 없으면
 * 새 기관 담당자를 만들 수 없어 로그인이 실패한다(UserService 참고) —
 * 로그인을 서비스하는 환경에서는 끄지 않는다.
 */
@Component
@ConditionalOnProperty(
        name = "app.seed.organizations.enabled",
        havingValue = "true",
        matchIfMissing = true)
@RequiredArgsConstructor
public class DataSeedRunner implements ApplicationRunner {

    private final OrganizationSeedService organizationSeedService;

    @Override
    public void run(ApplicationArguments args) {
        organizationSeedService.seedDemoOrganizations();
    }
}
