package com.itda.backend.service.record;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({RawRecordProperties.class, DirectUploadProperties.class})
public class RawRecordConfig {
}
