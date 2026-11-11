package com.itda.backend.global.config;

import java.util.List;
import java.util.UUID;

import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.test.context.ContextConfigurationAttributes;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.ContextCustomizerFactory;

public class IsolatedH2ContextCustomizerFactory implements ContextCustomizerFactory {

    private static final String DATASOURCE_URL = "spring.datasource.url";
    private static final String SHARED_H2_URL = "jdbc:h2:mem:backend";

    private static final ContextCustomizer ISOLATE_H2 = (context, configuration) -> {
        var environment = context.getEnvironment();
        String url = environment.getProperty(DATASOURCE_URL, "");
        if (environment.matchesProfiles("test") && url.startsWith(SHARED_H2_URL + ";")) {
            String isolatedUrl = SHARED_H2_URL + "-" + UUID.randomUUID() + url.substring(SHARED_H2_URL.length());
            TestPropertyValues.of(DATASOURCE_URL + "=" + isolatedUrl).applyTo(environment);
        }
    };

    @Override
    public ContextCustomizer createContextCustomizer(
            Class<?> testClass, List<ContextConfigurationAttributes> configurationAttributes) {
        return ISOLATE_H2;
    }
}
