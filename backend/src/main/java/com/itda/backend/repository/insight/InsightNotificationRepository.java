package com.itda.backend.repository.insight;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.itda.backend.domain.insight.InsightNotification;
import com.itda.backend.domain.insight.InsightNotificationStatus;

public interface InsightNotificationRepository extends JpaRepository<InsightNotification, Long> {

    Optional<InsightNotification> findFirstByChildIdAndPeriodFromAndPeriodToOrderByRevisionDesc(
            Long childId, LocalDate periodFrom, LocalDate periodTo);

    List<InsightNotification> findByChildIdAndPeriodFromAndPeriodToOrderByRevisionDesc(
            Long childId, LocalDate periodFrom, LocalDate periodTo);

    List<InsightNotification> findByStatus(InsightNotificationStatus status);
}
