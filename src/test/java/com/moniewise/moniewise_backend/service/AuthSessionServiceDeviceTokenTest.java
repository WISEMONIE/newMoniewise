package com.moniewise.moniewise_backend.service;

import org.junit.jupiter.api.Test;
import org.mockito.invocation.Invocation;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

class AuthSessionServiceDeviceTokenTest {

    @Test
    void getActivePushTargetsUsesPersistentDeviceTokensWithoutActiveSession() throws Exception {
        Fixture fixture = new Fixture();
        Object token = instance("com.moniewise.moniewise_backend.entity.UserDeviceToken");
        invoke(token, "setFcmToken", " durable-token ");
        invoke(token, "setDevicePlatform", "android");

        when(invoke(fixture.userDeviceTokenRepository, "findActiveByUserId", 42L))
                .thenReturn(List.of(token));
        when(invoke(fixture.authSessionRepository, "findActivePushSessionsByUserId", 42L))
                .thenReturn(List.of());
        when(invoke(fixture.userRepository, "findFcmTokenById", 42L))
                .thenReturn(null);

        List<?> targets = (List<?>) invoke(fixture.service, "getActivePushTargets", 42L);

        assertEquals(1, targets.size());
        assertEquals("durable-token", invoke(targets.get(0), "token"));
        assertEquals("ANDROID", invoke(targets.get(0), "devicePlatform"));
    }

    @Test
    void clearSessionFcmTokenByValueDeactivatesOnlyTheCurrentDeviceToken() throws Exception {
        Fixture fixture = new Fixture();
        Object user = instance("com.moniewise.moniewise_backend.entity.User");
        invoke(user, "setId", 77L);
        invoke(user, "setEmail", "samuel@example.com");

        Object session = instance("com.moniewise.moniewise_backend.entity.AuthSession");
        invoke(session, "setUser", user);
        invoke(session, "setSessionId", "session-1");
        invoke(session, "setFcmToken", "phone-token");

        when(invoke(fixture.authSessionRepository, "findByUserEmailAndSessionIdAndRevokedFalse",
                "samuel@example.com", "session-1"))
                .thenReturn(Optional.of(session));

        Object removedToken = invoke(fixture.service, "clearSessionFcmTokenByValue",
                "samuel@example.com", "session-1", null);

        assertEquals("phone-token", removedToken);
        assertTrue(wasInvoked(
                        fixture.userDeviceTokenRepository,
                        "deactivateByUserIdAndFcmToken",
                        77L,
                        "phone-token",
                        LocalDateTime.class,
                        "CLIENT_REMOVED_TOKEN"),
                "current device token should be deactivated");
        assertTrue(wasNeverInvoked(fixture.userRepository, "clearFcmTokenByToken"),
                "session token removal must not globally clear the legacy fallback");
    }

    private static Object instance(String className) throws Exception {
        return classFor(className).getDeclaredConstructor().newInstance();
    }

    private static Object invoke(Object target, String methodName, Object... args) throws Exception {
        Method method = findMethod(target.getClass(), methodName, args);
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw e;
        }
    }

    private static Method findMethod(Class<?> type, String methodName, Object[] args) {
        for (Method method : type.getMethods()) {
            if (!method.getName().equals(methodName) || method.getParameterCount() != args.length) {
                continue;
            }
            Class<?>[] parameterTypes = method.getParameterTypes();
            boolean matches = true;
            for (int i = 0; i < parameterTypes.length; i++) {
                if (args[i] != null && !wrap(parameterTypes[i]).isAssignableFrom(args[i].getClass())) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return method;
            }
        }
        throw new IllegalStateException("Method not found: " + type.getName() + "#" + methodName);
    }

    private static boolean wasNeverInvoked(Object mock, String methodName) {
        return mockingDetails(mock).getInvocations().stream()
                .noneMatch(invocation -> invocation.getMethod().getName().equals(methodName));
    }

    private static boolean wasInvoked(Object mock, String methodName, Object... expectedArgs) {
        return mockingDetails(mock).getInvocations().stream()
                .anyMatch(invocation -> invocationMatches(invocation, methodName, expectedArgs));
    }

    private static boolean invocationMatches(Invocation invocation, String methodName, Object[] expectedArgs) {
        if (!invocation.getMethod().getName().equals(methodName)) {
            return false;
        }
        Object[] actualArgs = invocation.getArguments();
        if (actualArgs.length != expectedArgs.length) {
            return false;
        }
        for (int i = 0; i < expectedArgs.length; i++) {
            Object expected = expectedArgs[i];
            Object actual = actualArgs[i];
            if (expected instanceof Class<?> expectedType) {
                if (actual == null || !expectedType.isAssignableFrom(actual.getClass())) {
                    return false;
                }
            } else if (!java.util.Objects.equals(expected, actual)) {
                return false;
            }
        }
        return true;
    }

    private static Class<?> wrap(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == long.class) return Long.class;
        if (type == int.class) return Integer.class;
        if (type == boolean.class) return Boolean.class;
        if (type == double.class) return Double.class;
        if (type == float.class) return Float.class;
        if (type == short.class) return Short.class;
        if (type == byte.class) return Byte.class;
        if (type == char.class) return Character.class;
        return type;
    }

    private static Class<?> classFor(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Class not found: " + name, e);
        }
    }

    private static final class Fixture {
        final Object authSessionRepository = mock(classFor("com.moniewise.moniewise_backend.repository.AuthSessionRepository"));
        final Object userRepository = mock(classFor("com.moniewise.moniewise_backend.repository.UserRepository"));
        final Object userDeviceTokenRepository = mock(classFor("com.moniewise.moniewise_backend.repository.UserDeviceTokenRepository"));
        final Object service = service();

        private Object service() {
            try {
                return classFor("com.moniewise.moniewise_backend.service.AuthSessionService")
                        .getConstructor(
                                classFor("com.moniewise.moniewise_backend.repository.AuthSessionRepository"),
                                classFor("com.moniewise.moniewise_backend.repository.UserRepository"),
                                classFor("com.moniewise.moniewise_backend.repository.UserDeviceTokenRepository"))
                        .newInstance(authSessionRepository, userRepository, userDeviceTokenRepository);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Could not create service fixture", e);
            }
        }
    }
}
