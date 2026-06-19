package com.example.runtimemock.platform.crypto;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnExpression(
        "'${runtime-mock.platform.worker.enabled:false}' == 'true' || "
                + "'${runtime-mock.platform.recording.ingestion.enabled:false}' == 'true'")
public class EncryptionConfiguration {

    @Bean
    KeyEncryptionService keyEncryptionService(EncryptionProperties properties) {
        return new LocalKeyEncryptionService(properties);
    }

    @Bean
    EnvelopeEncryptionService envelopeEncryptionService(KeyEncryptionService keyEncryptionService) {
        return new EnvelopeEncryptionService(keyEncryptionService);
    }
}
