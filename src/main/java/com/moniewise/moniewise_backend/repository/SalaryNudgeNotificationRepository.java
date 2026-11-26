package com.moniewise.moniewise_backend.repository;

import com.moniewise.moniewise_backend.entity.SalaryNudgeNotification;
import com.moniewise.moniewise_backend.enums.SalaryNudgeWindow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface SalaryNudgeNotificationRepository extends JpaRepository<SalaryNudgeNotification, Long> {

    int countByUserIdAndNudgeWindowAndPeriodYearAndPeriodMonth(
            Long userId,
            SalaryNudgeWindow nudgeWindow,
            int periodYear,
            int periodMonth);

    boolean existsByUserIdAndNudgeWindowAndPeriodYearAndPeriodMonthAndSentDate(
            Long userId,
            SalaryNudgeWindow nudgeWindow,
            int periodYear,
            int periodMonth,
            LocalDate sentDate);

    int countByUserIdAndSentDate(Long userId, LocalDate sentDate);

    @Query("""
            SELECT n.variationIndex
            FROM SalaryNudgeNotification n
            WHERE n.userId = :userId
              AND n.nudgeWindow = :nudgeWindow
              AND n.periodYear = :periodYear
              AND n.periodMonth = :periodMonth
            ORDER BY n.sentAt ASC
            """)
    List<Integer> findVariationIndexesForPeriod(
            @Param("userId") Long userId,
            @Param("nudgeWindow") SalaryNudgeWindow nudgeWindow,
            @Param("periodYear") int periodYear,
            @Param("periodMonth") int periodMonth);
}
