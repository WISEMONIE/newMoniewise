package com.moniewise.moniewise_backend.aml;

import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.Message;
import com.moniewise.moniewise_backend.entity.AdminBroadcast;
import com.moniewise.moniewise_backend.entity.OutboxEvent;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.enums.Role;
import com.moniewise.moniewise_backend.repository.AdminBroadcastRepository;
import com.moniewise.moniewise_backend.repository.OutboxEventRepository;
import com.moniewise.moniewise_backend.repository.UserRepository;
import com.moniewise.moniewise_backend.service.AdminBroadcastEmailDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Large admin sends against real PostgreSQL: one send per key, background
 * jobs that carry on after a restart, bulk pushes queued behind personal
 * alerts, and the upload and link checks.
 *
 * The outbox worker and the resumer are slowed right down here so each test
 * drives them by hand. Skipped unless AML_IT=true.
 */
@SpringBootTest(properties = {
        "moniewise.outbox.worker.fixed-delay-ms=3600000",
        "moniewise.admin-broadcast.resume-initial-delay-ms=3600000",
        "moniewise.admin-broadcast.stale-minutes=5"
})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "AML_IT", matches = "true")
class AdminBroadcastJobIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private UserRepository userRepository;
    @Autowired private AdminBroadcastRepository broadcasts;
    @Autowired private OutboxEventRepository outbox;
    @Autowired private AdminBroadcastEmailDispatcher dispatcher;
    @Autowired private com.moniewise.moniewise_backend.service.AdminBroadcastResumer resumer;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;

    @MockBean private FirebaseMessaging firebase;

    @BeforeEach
    void productionConstraints() throws Exception {
        for (String sql : AmlIntegrationTest.productionIndexes()) {
            jdbc.execute(sql);
        }
        when(firebase.send(any(Message.class))).thenReturn("projects/x/messages/1");
    }

    private User person() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        User u = new User();
        u.setEmail("job-it-" + tag + "@example.test");
        u.setPhone("0806" + ThreadLocalRandom.current().nextInt(1_000_000, 9_999_999));
        u.setPassword("not-used");
        u.setRole(Role.USER);
        u.setVerified(true);
        u.setCreatedAt(LocalDateTime.now().minusDays(30));
        u.setProfileData(new java.util.HashMap<>(Map.of("name", "Job Person")));
        u = userRepository.saveAndFlush(u);
        jdbc.update("INSERT INTO auth_sessions (user_id, session_id, revoked, created_at, fcm_token, device_platform, kind) "
                + "VALUES (?, ?, false, now(), ?, 'android', 'APP')", u.getId(), "s-" + tag, "fcm-" + tag);
        return u;
    }

    private int pushesQueuedFor(User u) {
        return jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE user_id = ?", Integer.class, u.getId());
    }

    private String body(String extra) {
        return "{\"title\":\"Hello\",\"message\":\"A short note.\",\"sendFcm\":true,\"sendEmail\":false," + extra + "}";
    }

    private AdminBroadcast waitUntilDone(Long id) throws InterruptedException {
        for (int i = 0; i < 300; i++) {
            AdminBroadcast row = broadcasts.findById(id).orElseThrow();
            if (row.getStatus() != AdminBroadcast.Status.SENDING) {
                return row;
            }
            Thread.sleep(100);
        }
        fail("broadcast " + id + " did not finish");
        return null;
    }

    // ── 1. One send per key ────────────────────────────────────────────────

    @Test
    void theSameSendTwiceGoesOutOnce() throws Exception {
        User u = person();
        String request = body("\"allUsers\":false,\"deliverFcmNow\":true,\"emails\":[\"" + u.getEmail()
                + "\"],\"requestKey\":\"" + UUID.randomUUID() + "\"");

        mvc.perform(post("/admin/notifications/special").with(user("ops@example.test").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alreadySent").value(false))
                .andExpect(jsonPath("$.fcmQueued").value(1));
        mvc.perform(post("/admin/notifications/special").with(user("ops@example.test").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alreadySent").value(true));

        assertEquals(1, pushesQueuedFor(u), "a retried request must not queue a second push");
        verify(firebase, times(1)).send(any(Message.class));
    }

    @Test
    void differentKeysAreDifferentSends() throws Exception {
        User u = person();
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/admin/notifications/special").with(user("ops@example.test").roles("ADMIN"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("\"allUsers\":false,\"emails\":[\"" + u.getEmail()
                                    + "\"],\"requestKey\":\"" + UUID.randomUUID() + "\"")))
                    .andExpect(jsonPath("$.alreadySent").value(false));
        }
        assertEquals(2, pushesQueuedFor(u));
    }

    // ── 2 & 3. Everyone: a background job, queued behind personal alerts ──

    @Test
    void everyoneIsAJobThatOnlyQueuesBulkPushes() throws Exception {
        User a = person();
        User b = person();
        String json = mvc.perform(post("/admin/notifications/special").with(user("ops@example.test").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("\"allUsers\":true,\"deliverFcmNow\":true,\"includeTestAccounts\":true")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recipients").value(-1))
                .andReturn().getResponse().getContentAsString();
        assertTrue(json.contains("background"), json);

        AdminBroadcast row = waitUntilDone(broadcasts.findTop100ByOrderByCreatedAtDesc().get(0).getId());
        assertEquals(AdminBroadcast.Status.SENT, row.getStatus());
        long everyone = userRepository.countBroadcastRecipients(true);
        assertEquals(everyone, row.getRecipients());
        assertEquals(everyone, row.getPushQueued());
        assertEquals(1, pushesQueuedFor(a));
        assertEquals(1, pushesQueuedFor(b));
        verify(firebase, never()).send(any(Message.class)); // queued only; the worker sends them
        Integer bulk = jdbc.queryForObject("SELECT min(priority) FROM outbox_events WHERE user_id IN (?, ?)",
                Integer.class, a.getId(), b.getId());
        assertEquals(OutboxEvent.PRIORITY_BULK, bulk.shortValue());
    }

    @Test
    void personalAlertsAreClaimedBeforeAnOlderBroadcast() {
        User u = person();
        jdbc.update("UPDATE outbox_events SET status = 'DONE' WHERE status IN ('PENDING', 'PROCESSING')");
        jdbc.update("INSERT INTO outbox_events (event_type, user_id, payload, status, retry_count, created_at, inbox_saved, priority) "
                + "VALUES ('SPECIAL_ANNOUNCEMENT', ?, '{}'::jsonb, 'PENDING', 0, now() - interval '1 hour', false, 1)", u.getId());
        jdbc.update("INSERT INTO outbox_events (event_type, user_id, payload, status, retry_count, created_at, inbox_saved, priority) "
                + "VALUES ('CREDIT', ?, '{}'::jsonb, 'PENDING', 0, now(), false, 0)", u.getId());

        List<OutboxEvent> first = new TransactionTemplate(transactionManager)
                .execute(s -> outbox.claimPendingEvents(1));
        assertEquals("CREDIT", first.get(0).getEventType(), "the credit alert goes ahead of the hour-old broadcast");
    }

    @Test
    void aSendThatStoppedCarriesOnAfterItsLastUser() throws Exception {
        User done = person();
        User next = person();
        User last = person();
        AdminBroadcast row = stalledJob(List.of(done, next, last), done.getId(), true);

        assertTrue(resumer.resumeStalledNow() >= 1);
        AdminBroadcast after = waitUntilDone(row.getId());

        assertEquals(AdminBroadcast.Status.SENT, after.getStatus());
        assertEquals(0, pushesQueuedFor(done), "the user handled before the restart is not sent again");
        assertEquals(1, pushesQueuedFor(next));
        assertEquals(1, pushesQueuedFor(last));
        assertEquals(3, after.getRecipients());
        assertEquals(3, after.getPushQueued());
        assertEquals(1, after.getAttempts());
    }

    @Test
    void aRunnerThatLostItsLeaseStopsWithoutSending() {
        User u = person();
        AdminBroadcast row = stalledJob(List.of(u), null, true);
        // Another server took the job over; the old runner's token is no longer valid.
        assertEquals(1, broadcasts.claimStalled(row.getId(), "new-runner", LocalDateTime.now(), LocalDateTime.now()));

        dispatcher.runJob(row.getId(), "old-runner");
        assertEquals(0, pushesQueuedFor(u));
        assertEquals(0, broadcasts.advance(row.getId(), "old-runner", u.getId(), 1, LocalDateTime.now()));
    }

    @Test
    void aSmallSendCutOffMidRequestIsMarkedFailedNotLeftSending() throws Exception {
        AdminBroadcast row = stalledJob(List.of(person()), null, false);
        resumer.resumeStalledNow();
        assertEquals(AdminBroadcast.Status.FAILED, broadcasts.findById(row.getId()).orElseThrow().getStatus());
    }

    @Test
    void aSendThatKeepsFailingIsGivenUpOn() throws Exception {
        AdminBroadcast row = stalledJob(List.of(person()), null, true);
        jdbc.update("UPDATE admin_broadcasts SET attempts = 5 WHERE id = ?", row.getId());
        resumer.resumeStalledNow();
        assertEquals(AdminBroadcast.Status.FAILED, broadcasts.findById(row.getId()).orElseThrow().getStatus());
    }

    private AdminBroadcast stalledJob(List<User> people, Long cursor, boolean background) {
        AdminBroadcast row = new AdminBroadcast();
        row.setType("SPECIAL_ANNOUNCEMENT");
        row.setTitle("Hello");
        row.setMessage("A short note.");
        row.setSendPush(true);
        row.setSendEmail(false);
        row.setAudience(people.size() + " chosen users");
        row.setIncludeTestAccounts(true);
        row.setStatus(AdminBroadcast.Status.SENDING);
        row.setSentBy("ops@example.test");
        row.setCreatedAt(LocalDateTime.now().minusMinutes(20));
        row.setUpdatedAt(LocalDateTime.now().minusMinutes(10));
        row.setBackground(background);
        row.setAllUsers(false);
        row.setRecipientIds(String.join(",", people.stream().map(p -> String.valueOf(p.getId())).toList()));
        row.setCursorUserId(cursor);
        row.setRecipients(cursor == null ? 0 : 1);
        row.setPushQueued(cursor == null ? 0 : 1);
        row.setTtlSeconds(86_400L);
        row.setRunnerToken("dead-runner");
        return broadcasts.saveAndFlush(row);
    }

    // ── 4 & 5. Picture bytes and button links ─────────────────────────────

    @Test
    void aFileThatOnlyClaimsToBeAPictureIsRefused() throws Exception {
        byte[] notAPicture = "<svg onload=alert(1)>".getBytes();
        mvc.perform(multipart("/admin/notifications/image")
                        .file(new MockMultipartFile("file", "x.png", "image/png", notAPicture))
                        .with(user("ops@example.test").roles("ADMIN")))
                .andExpect(status().isBadRequest());
        byte[] pngNamedJpeg = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0};
        mvc.perform(multipart("/admin/notifications/image")
                        .file(new MockMultipartFile("file", "x.jpg", "image/jpeg", pngNamedJpeg))
                        .with(user("ops@example.test").roles("ADMIN")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void buttonLinksMustBeHttpsOrAnAppPath() throws Exception {
        User u = person();
        for (String bad : List.of("http://site.test", "javascript:alert(1)", "//evil.test/x", "https://")) {
            mvc.perform(post("/admin/notifications/special").with(user("ops@example.test").roles("ADMIN"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("\"allUsers\":false,\"emails\":[\"" + u.getEmail() + "\"],\"ctaUrl\":\"" + bad + "\"")))
                    .andExpect(status().isBadRequest());
        }
        for (String good : List.of("https://wisemonie.com/offer", "/budgets")) {
            mvc.perform(post("/admin/notifications/special").with(user("ops@example.test").roles("ADMIN"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("\"allUsers\":false,\"emails\":[\"" + u.getEmail() + "\"],\"ctaUrl\":\"" + good + "\"")))
                    .andExpect(status().isOk());
        }
    }
}
