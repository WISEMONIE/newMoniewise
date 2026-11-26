package com.moniewise.moniewise_backend.service;

import com.moniewise.moniewise_backend.dto.TransferFeeQuote;
import com.moniewise.moniewise_backend.entity.TransferFeeConfig;
import com.moniewise.moniewise_backend.enums.TransferFeeSource;
import com.moniewise.moniewise_backend.enums.TransferFeeTransferType;
import com.moniewise.moniewise_backend.enums.TransferFeeType;
import com.moniewise.moniewise_backend.repository.TransferFeeConfigRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
public class TransferFeeService {

    private final TransferFeeConfigRepository transferFeeConfigRepository;

    public TransferFeeService(TransferFeeConfigRepository transferFeeConfigRepository) {
        this.transferFeeConfigRepository = transferFeeConfigRepository;
    }

    public TransferFeeQuote quoteFee(
            String providerName,
            TransferFeeTransferType transferType,
            BigDecimal amount
    ) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Amount must be greater than zero");
        }

        TransferFeeConfig config = transferFeeConfigRepository
                .findFirstByProviderNameIgnoreCaseAndTransferTypeAndActiveTrue(providerName, transferType)
                .orElseGet(() -> defaultZeroFee(providerName, transferType));

        BigDecimal fee = calculateFee(config, amount);

        BigDecimal totalDebit;
        BigDecimal recipientReceives;

        if (config.getFeeSource() == TransferFeeSource.PLATFORM) {
            totalDebit = amount;
            recipientReceives = amount;
        } else {
            totalDebit = amount.add(fee);
            recipientReceives = amount;
        }

        return new TransferFeeQuote(
                providerName,
                transferType,
                config.getFeeType(),
                config.getFeeSource(),
                amount,
                fee,
                totalDebit,
                recipientReceives
        );
    }

    public TransferFeeQuote quoteIncomingFee(
            String providerName,
            TransferFeeTransferType transferType,
            BigDecimal grossAmount
    ) {
        if (grossAmount == null || grossAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Amount must be greater than zero");
        }

        TransferFeeConfig config = transferFeeConfigRepository
                .findFirstByProviderNameIgnoreCaseAndTransferTypeAndActiveTrue(providerName, transferType)
                .orElseGet(() -> defaultZeroFee(providerName, transferType));

        BigDecimal fee = calculateFee(config, grossAmount);

        if (fee.compareTo(grossAmount) > 0) {
            fee = grossAmount;
        }

        BigDecimal netCredit = grossAmount.subtract(fee);

        return new TransferFeeQuote(
                providerName,
                transferType,
                config.getFeeType(),
                config.getFeeSource(),
                grossAmount,
                fee,
                grossAmount,
                netCredit
        );
    }

    private BigDecimal calculateFee(TransferFeeConfig config, BigDecimal amount) {
        BigDecimal fee = BigDecimal.ZERO;

        if (config.getFeeType() == TransferFeeType.FLAT) {
            fee = safe(config.getFlatFee());
        }

        if (config.getFeeType() == TransferFeeType.PERCENTAGE) {
            fee = percentageFee(amount, config.getPercentageFee());
        }

        if (config.getFeeType() == TransferFeeType.FLAT_PLUS_PERCENTAGE) {
            fee = safe(config.getFlatFee()).add(
                    percentageFee(amount, config.getPercentageFee())
            );
        }

        if (config.getMinFee() != null && fee.compareTo(config.getMinFee()) < 0) {
            fee = config.getMinFee();
        }

        if (config.getMaxFee() != null && fee.compareTo(config.getMaxFee()) > 0) {
            fee = config.getMaxFee();
        }

        return fee.setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal percentageFee(BigDecimal amount, BigDecimal percentage) {
        return amount
                .multiply(safe(percentage))
                .divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
    }

    private BigDecimal safe(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    private TransferFeeConfig defaultZeroFee(String providerName, TransferFeeTransferType transferType) {
        TransferFeeConfig config = new TransferFeeConfig();
        config.setProviderName(providerName);
        config.setTransferType(transferType);
        config.setFeeType(TransferFeeType.FLAT);
        config.setFlatFee(BigDecimal.ZERO);
        config.setPercentageFee(BigDecimal.ZERO);
        config.setFeeSource(TransferFeeSource.ENVELOPE_BALANCE);
        config.setActive(true);
        return config;
    }
}