package com.itda.backend.global.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.itda.backend.exception.agent.AgentQueueFullException;
import com.itda.backend.exception.invitation.InvitationErrorCode;
import com.itda.backend.exception.invitation.InvitationException;
import com.itda.backend.exception.record.RawRecordEntryLimitException;
import com.itda.backend.exception.record.RawRecordStorageException;
import com.itda.backend.exception.record.RawRecordUploadBusyException;
import com.itda.backend.exception.validation.ValidationResultErrorCode;
import com.itda.backend.exception.validation.ValidationResultValidationException;

class BusinessExceptionLogLevelTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Level previousLevel;

    @BeforeEach
    void attach() {
        previousLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
        logger.setLevel(previousLevel);
    }

    private Level levelOf(BusinessException e) {
        handler.handleBusinessException(e);
        assertThat(appender.list).hasSize(1);
        return appender.list.get(0).getLevel();
    }

    @Test
    void 업로드_제한으로_거절한_요청은_INFO_로_남긴다() {
        assertThat(levelOf(new RawRecordEntryLimitException("entries=501"))).isEqualTo(Level.INFO);
    }

    @Test
    void 동시_업로드_제한은_INFO_로_남긴다() {
        assertThat(levelOf(new RawRecordUploadBusyException("busy"))).isEqualTo(Level.INFO);
    }

    @Test
    void 처리_줄이_꽉_차_거절한_요청은_INFO_로_남긴다() {
        assertThat(levelOf(new AgentQueueFullException(1L, 1000, 1, 1000))).isEqualTo(Level.INFO);
    }

    @Test
    void 검증_결과_요청_거절은_INFO_로_남긴다() {
        assertThat(levelOf(new ValidationResultValidationException(ValidationResultErrorCode.OUTDATED, "outdated")))
                .isEqualTo(Level.INFO);
    }

    @Test
    void 그_밖의_4xx_도메인_오류는_WARN_으로_남긴다() {
        assertThat(levelOf(new InvitationException(InvitationErrorCode.INVALID_INVITE_CODE))).isEqualTo(Level.WARN);
    }

    @Test
    void 저장_실패는_ERROR_로_남긴다() {
        assertThat(levelOf(new RawRecordStorageException("s3 down", new RuntimeException()))).isEqualTo(Level.ERROR);
    }
}
