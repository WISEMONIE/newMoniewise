package com.moniewise.moniewise_backend.dto.response;

import com.moniewise.moniewise_backend.entity.FeeReserve;
import com.moniewise.moniewise_backend.entity.FeeReserveEstimate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record FeeReserveResponse(
        BigDecimal balance,
        BigDecimal estimatedTotalFees,
        BigDecimal shortfall,
        List<EstimateDetail> estimates,
        LocalDateTime updatedAt
) {

    public record EstimateDetail(
            Long budgetId,
            Long envelopeId,
            BigDecimal envelopeAmount,
            BigDecimal nipFee,
            BigDecimal stampDuty,
            BigDecimal markup,
            BigDecimal total
    ) {
        public static EstimateDetail from(FeeReserveEstimate e) {
            return new EstimateDetail(
                    e.getBudgetId(), e.getEnvelopeId(), e.getEnvelopeAmount(),
                    e.getEstimatedNipFee(), e.getEstimatedStampDuty(), e.getEstimatedMarkup(),
                    e.getEstimatedTotal()
            );
        }
    }

    public static FeeReserveResponse from(FeeReserve reserve, List<FeeReserveEstimate> estimates) {
        if (reserve == null) {
            return new FeeReserveResponse(
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, List.of(), null
            );
        }
        return new FeeReserveResponse(
                reserve.getBalance(),
                reserve.getEstimatedTotalFees(),
                reserve.getShortfall(),
                estimates.stream().map(EstimateDetail::from).toList(),
                reserve.getUpdatedAt()
        );
    }
}
