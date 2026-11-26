package com.moniewise.moniewise_backend.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

@Configuration
public class FirebaseConfig {

    private static final Logger logger = LoggerFactory.getLogger(FirebaseConfig.class);

    @Value("${firebase.storage.bucket:wisemonie-app.firebasestorage.app}")
    private String firebaseStorageBucket;

    @Bean
    public FirebaseApp firebaseApp() {
        if (!FirebaseApp.getApps().isEmpty()) {
            return FirebaseApp.getInstance();
        }
        try (InputStream serviceAccount = resolveCredentials()) {
            if (serviceAccount == null) {
                logger.warn("Firebase credentials not configured. Notification and storage features will be disabled.");
                return null;
            }
            FirebaseOptions options = FirebaseOptions.builder().setCredentials(GoogleCredentials.fromStream(serviceAccount)).setStorageBucket(firebaseStorageBucket).build();
            return FirebaseApp.initializeApp(options);
        } catch (Exception e) {
            logger.error("Failed to initialize Firebase", e);
            return null;
        }
    }

    @Bean
    public FirebaseMessaging firebaseMessaging(FirebaseApp firebaseApp) {
        if (firebaseApp == null) return null;
        return FirebaseMessaging.getInstance(firebaseApp);
    }

    private InputStream resolveCredentials() {
        String firebaseConfig = System.getenv("FIREBASE_SERVICE_ACCOUNT");
        if (firebaseConfig != null && !firebaseConfig.isBlank()) {
            return new ByteArrayInputStream(firebaseConfig.getBytes(StandardCharsets.UTF_8));
        }
        String firebaseConfigFile = System.getenv("FIREBASE_SERVICE_ACCOUNT_FILE");
        if (firebaseConfigFile != null && !firebaseConfigFile.isBlank()) {
            try {
                return new FileInputStream(firebaseConfigFile);
            } catch (Exception e) {
                logger.error("Failed to read Firebase service account file from FIREBASE_SERVICE_ACCOUNT_FILE", e);
            }
        }
        return null;
    }
}