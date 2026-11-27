package com.moniewise.moniewise_backend.aml;

import com.google.api.client.json.gson.GsonFactory;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.Message;
import com.moniewise.moniewise_backend.entity.AdminBroadcast;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.enums.Role;
import com.moniewise.moniewise_backend.repository.AdminBroadcastRepository;
import com.moniewise.moniewise_backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Admin pushes from the website, end to end against real PostgreSQL: the
 * broadcast request, the outbox, delivery, and the exact message handed to
 * Firebase (Firebase itself is replaced so nothing is really sent).
 * Skipped unless AML_IT=true; see scripts/run-aml-integration-tests.sh.
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "AML_IT", matches = "true")
class AdminPushIntegrationTest {

    private static final String IMAGE = "https://firebasestorage.googleapis.com/v0/b/wisemonie/o/push%2Fimages%2Fpromo.png?alt=media";

    @Autowired private MockMvc mvc;
    @Autowired private UserRepository userRepository;
    @Autowired private AdminBroadcastRepository history;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbc;

    @MockBean private FirebaseMessaging firebase;

    /** Production's session rules (V6 + V66), as in AmlIntegrationTest. */
    @org.junit.jupiter.api.BeforeEach
    void productionConstraints() {
        for (String sql : AmlIntegrationTest.productionIndexes()) {
            jdbc.execute(sql);
        }
    }

    private User customerWithPhone() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        User user = new User();
        user.setEmail("push-it-" + tag + "@example.test");
        user.setPhone("0805" + ThreadLocalRandom.current().nextInt(1_000_000, 9_999_999));
        user.setPassword(passwordEncoder.encode("x"));
        user.setRole(Role.USER);
        user.setVerified(true);
        user.setCreatedAt(LocalDateTime.now().minusDays(40));
        user.setProfileData(new HashMap<>(java.util.Map.of("name", "Ada Push")));
        user = userRepository.saveAndFlush(user);
        jdbc.update("INSERT INTO auth_sessions (user_id, session_id, revoked, created_at, fcm_token, device_platform, kind) "
                        + "VALUES (?, ?, false, now(), ?, 'android', 'APP')",
                user.getId(), "s-" + tag, "fcm-token-" + tag);
        return user;
    }

    private String body(String email, String image) {
        return "{\"title\":\"Weekend offer\",\"message\":\"Plan your weekend spend with Wisemonie.\","
                + (image == null ? "" : "\"imageUrl\":\"" + image + "\",")
                + "\"sendFcm\":true,\"sendEmail\":false,\"allUsers\":false,\"deliverFcmNow\":true,"
                + "\"emails\":[\"" + email + "\"]}";
    }

    /**
     * The message sent to this customer's phone. Other tests can leave pushes
     * queued that a background worker sends meanwhile, so pick ours by token.
     */
    private String sentJson(User customer) throws Exception {
        String token = jdbc.queryForObject(
                "SELECT fcm_token FROM auth_sessions WHERE user_id = ? AND revoked = false", String.class, customer.getId());
        ArgumentCaptor<Message> sent = ArgumentCaptor.forClass(Message.class);
        verify(firebase, atLeastOnce()).send(sent.capture());
        // The same JSON form the SDK sends to Google.
        for (Message m : sent.getAllValues()) {
            String json = GsonFactory.getDefaultInstance().toString(m);
            if (json.contains("\"token\":\"" + token + "\"")) {
                return json;
            }
        }
        throw new AssertionError("No push was sent to " + customer.getEmail());
    }

    @Test
    void aPushWithAPictureCarriesItForAndroidIosAndTheOpenApp() throws Exception {
        when(firebase.send(any(Message.class))).thenReturn("projects/x/messages/1");
        User customer = customerWithPhone();

        mvc.perform(post("/admin/notifications/special").with(user("ops@example.test").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(body(customer.getEmail(), IMAGE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recipients").value(1));

        String json = sentJson(customer);
        assertTrue(json.contains("\"notification\":{\"body\":\"Plan your weekend spend with Wisemonie.\",\"image\":\""),
                "the system tray picture: " + json);
        assertTrue(json.matches("(?s).*\"android\":\\{.*\"notification\":\\{.*\"image\":\"https://firebasestorage.*"),
                "Android: " + json);
        assertTrue(json.contains("\"mutable-content\":1"), "iOS lets the extension attach it: " + json);
        assertTrue(json.contains("\"fcm_options\":{\"image\":\"https://firebasestorage"), "iOS picture: " + json);
        assertTrue(json.contains("\"imageUrl\":\"https://firebasestorage"), "the open app reads it from data: " + json);

        String inbox = jdbc.queryForObject("SELECT image_url FROM notifications WHERE user_id = ? ORDER BY id DESC LIMIT 1",
                String.class, customer.getId());
        assertEquals(IMAGE, inbox, "the in-app notification keeps the picture too");
    }

    @Test
    void aPushWithoutAPictureIsUnchanged() throws Exception {
        when(firebase.send(any(Message.class))).thenReturn("projects/x/messages/2");
        User customer = customerWithPhone();

        mvc.perform(post("/admin/notifications/special").with(user("ops@example.test").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(body(customer.getEmail(), null)))
                .andExpect(status().isOk());

        String json = sentJson(customer);
        assertFalse(json.contains("\"image\""), json);
        assertFalse(json.contains("mutable-content"), json);
        assertFalse(json.contains("imageUrl"), json);
    }

    @Test
    void onlyHttpsPicturesAreAccepted() throws Exception {
        User customer = customerWithPhone();
        mvc.perform(post("/admin/notifications/special").with(user("ops@example.test").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(body(customer.getEmail(), "http://insecure.test/a.png")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void everySendIsRecordedWithWhoAndHowMany() throws Exception {
        when(firebase.send(any(Message.class))).thenReturn("projects/x/messages/3");
        User customer = customerWithPhone();

        mvc.perform(post("/admin/notifications/special").with(user("ops@example.test").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(body(customer.getEmail(), IMAGE)))
                .andExpect(status().isOk());

        AdminBroadcast row = history.findTop100ByOrderByCreatedAtDesc().get(0);
        assertEquals("SPECIAL_ANNOUNCEMENT", row.getType());
        assertEquals("ops@example.test", row.getSentBy());
        assertEquals(IMAGE, row.getImageUrl());
        assertEquals("1 chosen user", row.getAudience());
        assertEquals(AdminBroadcast.Status.SENT, row.getStatus());
        assertEquals(1, row.getRecipients());
        assertEquals(1, row.getPushQueued());

        mvc.perform(get("/admin/notifications/history").with(user("ops@example.test").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("Weekend offer"));
    }

    @Test
    void aTestSendToYourselfIsLabelledAsATest() throws Exception {
        when(firebase.send(any(Message.class))).thenReturn("projects/x/messages/4");
        User admin = customerWithPhone();

        mvc.perform(post("/admin/notifications/special").with(user(admin.getEmail()).roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(body(admin.getEmail(), null)))
                .andExpect(status().isOk());

        assertEquals("Test to " + admin.getEmail(), history.findTop100ByOrderByCreatedAtDesc().get(0).getAudience());
    }

    @Test
    void theAudienceCountSaysHowManyCanGetAPush() throws Exception {
        customerWithPhone();
        String json = mvc.perform(get("/admin/notifications/audience").with(user("ops@example.test").roles("ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
        assertTrue(node.get("allUsers").asLong() >= 1);
        assertTrue(node.get("reachableByPush").asLong() >= 1);
        assertTrue(node.get("reachableByPush").asLong() <= node.get("allUsers").asLong());
    }

    @Test
    void pictureUploadsMustBeASmallJpgPngOrGif() throws Exception {
        var admin = user("ops@example.test").roles("ADMIN");
        mvc.perform(multipart("/admin/notifications/image")
                        .file(new MockMultipartFile("file", "a.gif", "image/gif", new byte[]{1, 2, 3})).with(admin))
                .andExpect(status().isBadRequest());
        byte[] pngBytes = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0};
        mvc.perform(multipart("/admin/notifications/image")
                        .file(new MockMultipartFile("file", "fake.gif", "image/gif", pngBytes)).with(admin))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart("/admin/notifications/image")
                        .file(new MockMultipartFile("file", "a.webp", "image/webp", pngBytes)).with(admin))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart("/admin/notifications/image")
                        .file(new MockMultipartFile("file", "big.png", "image/png", new byte[1024 * 1024 + 1])).with(admin))
                .andExpect(status().isBadRequest());
    }

    @Test
    void customersCannotSendPushes() throws Exception {
        mvc.perform(post("/admin/notifications/special").with(user("someone@example.test").roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON).content(body("x@example.test", null)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/admin/notifications/history").with(user("someone@example.test").roles("USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void aGifGoesToIphoneAndTheAppWhileAndroidGetsTheStill() throws Exception {
        when(firebase.send(any(Message.class))).thenReturn("projects/x/messages/9");
        User customer = customerWithPhone();
        String gif = "https://firebasestorage.googleapis.com/v0/b/wisemonie/o/push%2Fimages%2Fpromo.gif?alt=media&token=a";
        String still = "https://firebasestorage.googleapis.com/v0/b/wisemonie/o/push%2Fimages%2Fpromo.gif.still.png?alt=media&token=b";
        String request = "{\"title\":\"Weekend offer\",\"message\":\"Plan your weekend spend with Wisemonie.\","
                + "\"imageUrl\":\"" + gif + "\",\"stillImageUrl\":\"" + still + "\","
                + "\"sendFcm\":true,\"sendEmail\":false,\"allUsers\":false,\"deliverFcmNow\":true,"
                + "\"emails\":[\"" + customer.getEmail() + "\"]}";

        mvc.perform(post("/admin/notifications/special").with(user("ops@example.test").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk());

        com.google.gson.JsonObject msg = com.google.gson.JsonParser.parseString(sentJson(customer)).getAsJsonObject();
        assertEquals(still, msg.getAsJsonObject("notification").get("image").getAsString(), "Android tray: the still");
        assertEquals(still, msg.getAsJsonObject("android").getAsJsonObject("notification").get("image").getAsString());
        assertEquals(gif, msg.getAsJsonObject("apns").getAsJsonObject("fcm_options").get("image").getAsString(),
                "iPhone: the GIF");
        assertEquals(gif, msg.getAsJsonObject("data").get("imageUrl").getAsString(), "the app's list: the GIF");

        java.util.Map<String, Object> inbox = jdbc.queryForMap(
                "SELECT image_url, still_image_url FROM notifications WHERE user_id = ? ORDER BY id DESC LIMIT 1",
                customer.getId());
        assertEquals(gif, inbox.get("image_url"));
        assertEquals(still, inbox.get("still_image_url"), "kept so a later re-send uses the still too");
    }

    @Test
    void aStillLinkMustBeHttps() throws Exception {
        User customer = customerWithPhone();
        String request = "{\"message\":\"Hi\",\"imageUrl\":\"https://x.test/a.gif\",\"stillImageUrl\":\"http://x.test/a.png\","
                + "\"sendFcm\":true,\"sendEmail\":false,\"allUsers\":false,\"emails\":[\"" + customer.getEmail() + "\"]}";
        mvc.perform(post("/admin/notifications/special").with(user("ops@example.test").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isBadRequest());
    }
}
