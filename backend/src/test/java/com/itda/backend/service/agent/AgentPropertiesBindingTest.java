package com.itda.backend.service.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.itda.backend.service.matching.MatchingProperties;
import com.itda.backend.service.validation.ValidationProperties;

/**
 * application.yml 의 에이전트 설정이 실제로 읽히는지 본다. 키 이름이 틀어지면 값이 null 로 들어와
 * 재시도 없이 바로 실패하는데, 다른 테스트는 설정을 손으로 만들어 넣어서 이걸 잡지 못한다.
 */
class AgentPropertiesBindingTest {

    @EnableConfigurationProperties({AiAgentProperties.class, MatchingProperties.class, ValidationProperties.class})
    static class Config {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(Config.class);

    @Test
    void AI_접속_설정을_읽는다() {
        runner.run(context -> {
            AiAgentProperties ai = context.getBean(AiAgentProperties.class);
            assertThat(ai.baseUrl()).isNotBlank();
            assertThat(ai.connectTimeout()).isEqualTo(Duration.ofSeconds(3));
            assertThat(ai.readTimeout()).isEqualTo(Duration.ofSeconds(70));
            assertThat(ai.retryBackoffs()).containsExactly(Duration.ofSeconds(2), Duration.ofSeconds(5));
        });
    }

    @Test
    void 워커_설정을_단계별로_읽는다() {
        runner.run(context -> {
            for (WorkerProperties worker : List.of(
                    context.getBean(MatchingProperties.class).worker(),
                    context.getBean(ValidationProperties.class).worker())) {
                assertThat(worker.enabled()).isTrue();
                assertThat(worker.delayMs()).isEqualTo(5000);
                assertThat(worker.batchSize()).isEqualTo(10);
            }
        });
    }
}
