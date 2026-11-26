package com.moniewise.moniewise_backend.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WithdrawalFeeServiceTest {

    @Test
    void calculateWithdrawalFeeReturnsFlatFee() throws Exception {
        assertEquals(BigDecimal.valueOf(15), invoke("calculateWithdrawalFee", BigDecimal.valueOf(1000)));
    }

    @Test
    void calculateTotalDebitAddsFeeToAmount() throws Exception {
        assertEquals(BigDecimal.valueOf(1015), invoke("calculateTotalDebit", BigDecimal.valueOf(1000)));
    }

    @Test
    void calculateWithdrawalFeeRejectsInvalidAmount() {
        assertThrows(IllegalArgumentException.class, () -> invoke("calculateWithdrawalFee", BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class, () -> invoke("calculateWithdrawalFee", null));
    }

    private BigDecimal invoke(String methodName, BigDecimal amount) throws Exception {
        Class<?> serviceClass = Class.forName("com.moniewise.moniewise_backend.service.WithdrawalFeeService");
        Object service = serviceClass.getDeclaredConstructor().newInstance();
        Method method = serviceClass.getMethod(methodName, BigDecimal.class);
        try {
            return (BigDecimal) method.invoke(service, amount);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw e;
        }
    }
}
