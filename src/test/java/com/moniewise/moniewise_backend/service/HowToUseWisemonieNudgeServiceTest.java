package com.moniewise.moniewise_backend.service;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class HowToUseWisemonieNudgeServiceTest {

    @Test
    void sendHowToUseNudgeIfAllowedSkipsUsersWithoutReadyWallet() throws Exception {
        Fixture fixture = new Fixture();
        Object user = verifiedUser(42L, "new-user@example.com");

        boolean sent = invokeSend(fixture.service, user);

        assertFalse(sent);
        verifyNoInteractions(fixture.notificationRepository);
        verifyNoInteractions(fixture.budgetRepository);
        verifyNoInteractions(fixture.nudgeRepository);
        verifyNoInteractions(fixture.salaryNudgeRepository);
        verifyNoInteractions(fixture.legacyBudgetNudgeRepository);
        verifyNoInteractions(fixture.notificationService);
    }

    @Test
    void sendHowToUseNudgeIfAllowedSkipsUnverifiedUsersBeforeCheckingWallet() throws Exception {
        Fixture fixture = new Fixture();
        Object user = user(43L, "unverified@example.com", false);

        boolean sent = invokeSend(fixture.service, user);

        assertFalse(sent);
        verifyNoInteractions(fixture.walletRepository);
        verifyNoInteractions(fixture.nudgeRepository);
        verifyNoInteractions(fixture.notificationService);
    }

    private static boolean invokeSend(Object service, Object user) throws Exception {
        Class<?> serviceClass = classFor("com.moniewise.moniewise_backend.service.HowToUseWisemonieNudgeService");
        Class<?> userClass = classFor("com.moniewise.moniewise_backend.entity.User");
        Method method = serviceClass.getMethod("sendHowToUseNudgeIfAllowed", userClass, LocalDateTime.class);
        try {
            return (Boolean) method.invoke(service, user, LocalDateTime.now());
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw e;
        }
    }

    private static Object verifiedUser(Long id, String email) throws Exception {
        return user(id, email, true);
    }

    private static Object user(Long id, String email, boolean verified) throws Exception {
        Class<?> userClass = classFor("com.moniewise.moniewise_backend.entity.User");
        Object user = userClass.getDeclaredConstructor().newInstance();
        userClass.getMethod("setId", Long.class).invoke(user, id);
        userClass.getMethod("setEmail", String.class).invoke(user, email);
        userClass.getMethod("setVerified", boolean.class).invoke(user, verified);
        return user;
    }

    private static Class<?> classFor(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Class not found: " + name, e);
        }
    }

    private static final class Fixture {
        final Object userRepository = mock(classFor("com.moniewise.moniewise_backend.repository.UserRepository"));
        final Object notificationRepository = mock(classFor("com.moniewise.moniewise_backend.repository.NotificationRepository"));
        final Object walletRepository = mock(classFor("com.moniewise.moniewise_backend.repository.WalletRepository"));
        final Object budgetRepository = mock(classFor("com.moniewise.moniewise_backend.repository.BudgetRepository"));
        final Object nudgeRepository = mock(classFor("com.moniewise.moniewise_backend.repository.EngagementNudgeNotificationRepository"));
        final Object salaryNudgeRepository = mock(classFor("com.moniewise.moniewise_backend.repository.SalaryNudgeNotificationRepository"));
        final Object legacyBudgetNudgeRepository = mock(classFor("com.moniewise.moniewise_backend.repository.BudgetEngagementNudgeRepository"));
        final Object notificationService = mock(classFor("com.moniewise.moniewise_backend.service.NotificationService"));
        final Object service = service();

        private Object service() {
            try {
                return classFor("com.moniewise.moniewise_backend.service.HowToUseWisemonieNudgeService")
                        .getConstructor(
                                classFor("com.moniewise.moniewise_backend.repository.UserRepository"),
                                classFor("com.moniewise.moniewise_backend.repository.NotificationRepository"),
                                classFor("com.moniewise.moniewise_backend.repository.WalletRepository"),
                                classFor("com.moniewise.moniewise_backend.repository.BudgetRepository"),
                                classFor("com.moniewise.moniewise_backend.repository.EngagementNudgeNotificationRepository"),
                                classFor("com.moniewise.moniewise_backend.repository.SalaryNudgeNotificationRepository"),
                                classFor("com.moniewise.moniewise_backend.repository.BudgetEngagementNudgeRepository"),
                                classFor("com.moniewise.moniewise_backend.service.NotificationService"))
                        .newInstance(
                                userRepository,
                                notificationRepository,
                                walletRepository,
                                budgetRepository,
                                nudgeRepository,
                                salaryNudgeRepository,
                                legacyBudgetNudgeRepository,
                                notificationService);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Could not create service fixture", e);
            }
        }
    }
}
