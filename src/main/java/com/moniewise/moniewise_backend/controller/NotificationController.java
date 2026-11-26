package com.moniewise.moniewise_backend.controller;

import com.moniewise.moniewise_backend.dto.response.NotificationBulkReadResponse;
import com.moniewise.moniewise_backend.entity.Notification;
import com.moniewise.moniewise_backend.enums.NotificationType;
import com.moniewise.moniewise_backend.repository.NotificationRepository;
import com.moniewise.moniewise_backend.repository.UserRepository;
import com.moniewise.moniewise_backend.service.NotificationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

import static org.springframework.data.domain.Sort.Direction.DESC;

@RestController
@RequestMapping("/notifications")
public class NotificationController {

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;

    @Autowired
    public NotificationController(
            NotificationRepository notificationRepository,
            UserRepository userRepository,
            NotificationService notificationService
    ) {
        this.notificationRepository = notificationRepository;
        this.userRepository = userRepository;
        this.notificationService = notificationService;
    }

    /**
     * Main notification inbox endpoint.
     *
     * Frontend should call:
     * GET /notifications
     *
     * This returns ALL persisted notifications for the logged-in user,
     * including DISBURSEMENT_SUCCESS.
     */
    @GetMapping
    public ResponseEntity<Page<Notification>> getNotifications(
            @AuthenticationPrincipal UserDetails userDetails,
            @PageableDefault(size = 20, sort = "createdAt", direction = DESC) Pageable pageable
    ) {
        Long userId = getUserIdFromUserDetails(userDetails);

        return ResponseEntity.ok(
                notificationRepository.findByUserId(userId, pageable)
        );
    }

    /**
     * Unread notification list.
     *
     * Frontend can call:
     * GET /notifications/unread
     */
    @GetMapping("/unread")
    public ResponseEntity<Page<Notification>> getUnreadNotifications(
            @AuthenticationPrincipal UserDetails userDetails,
            @PageableDefault(size = 20, sort = "createdAt", direction = DESC) Pageable pageable
    ) {
        Long userId = getUserIdFromUserDetails(userDetails);

        return ResponseEntity.ok(
                notificationRepository.findByUserIdAndIsReadFalse(userId, pageable)
        );
    }

    /**
     * Unread count for badge.
     *
     * Frontend can call:
     * GET /notifications/unread-count
     */
    @GetMapping("/unread-count")
    public ResponseEntity<Map<String, Long>> getUnreadCount(
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        Long userId = getUserIdFromUserDetails(userDetails);

        long count = notificationRepository.countByUserIdAndIsReadFalse(userId);

        return ResponseEntity.ok(Map.of("count", count));
    }

    /**
     * Filter notifications by type.
     *
     * Example:
     * GET /notifications/type/DISBURSEMENT_SUCCESS
     */
    @GetMapping("/type/{type}")
    public ResponseEntity<Page<Notification>> getNotificationsByType(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable NotificationType type,
            @PageableDefault(size = 20, sort = "createdAt", direction = DESC) Pageable pageable
    ) {
        Long userId = getUserIdFromUserDetails(userDetails);

        return ResponseEntity.ok(
                notificationRepository.findByUserIdAndType(userId, type, pageable)
        );
    }

    /**
     * Mark one notification as read.
     *
     * Frontend can call:
     * PUT /notifications/{id}/read
     */
    @PutMapping("/{id}/read")
    public ResponseEntity<Notification> markNotificationAsRead(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long id
    ) {
        Long userId = getUserIdFromUserDetails(userDetails);

        Optional<Notification> notificationOpt =
                notificationRepository.findByIdAndUserId(id, userId);

        if (notificationOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Notification notification = notificationOpt.get();
        notification.setRead(true);
        notificationRepository.save(notification);

        return ResponseEntity.ok(notification);
    }

    /**
     * Mark all notifications as read.
     *
     * Frontend can call:
     * PUT /notifications/read-all
     */
    @PutMapping("/read-all")
    public ResponseEntity<NotificationBulkReadResponse> markAllNotificationsAsRead(
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        Long userId = getUserIdFromUserDetails(userDetails);

        return ResponseEntity.ok(
                notificationService.markAllNotificationsAsRead(userId)
        );
    }

    private Long getUserIdFromUserDetails(UserDetails userDetails) {
        if (userDetails == null) {
            throw new IllegalArgumentException("Authenticated user not found");
        }

        String username = userDetails.getUsername();

        return userRepository.findFirstByEmailOrderByCreatedAtAsc(username)
                .map(user -> user.getId())
                .orElseThrow(() -> new IllegalArgumentException("User not found for email: " + username));
    }
}