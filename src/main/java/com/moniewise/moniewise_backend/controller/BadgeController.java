package com.moniewise.moniewise_backend.controller;

import com.moniewise.moniewise_backend.dto.response.UserBadgeResponse;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.security.AuthenticatedUserHolder;
import com.moniewise.moniewise_backend.service.BadgeAwardService;
import com.moniewise.moniewise_backend.service.UserService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;

@RestController
@RequestMapping("/badges")
public class BadgeController {

    private final BadgeAwardService badgeAwardService;
    private final UserService userService;
    private final AuthenticatedUserHolder userHolder;

    public BadgeController(BadgeAwardService badgeAwardService,
                           UserService userService,
                           AuthenticatedUserHolder userHolder) {
        this.badgeAwardService = badgeAwardService;
        this.userService = userService;
        this.userHolder = userHolder;
    }

    @GetMapping("/me")
    public ResponseEntity<List<UserBadgeResponse>> getMyBadges(
            @RequestParam(defaultValue = "false") boolean unseenOnly,
            Principal principal) {
        User user = currentUser(principal);
        return ResponseEntity.ok(badgeAwardService.getUserBadges(user.getId(), unseenOnly));
    }

    @PatchMapping("/me/{userBadgeId}/seen")
    public ResponseEntity<UserBadgeResponse> markSeen(@PathVariable Long userBadgeId, Principal principal) {
        User user = currentUser(principal);
        return ResponseEntity.ok(badgeAwardService.markSeen(user.getId(), userBadgeId));
    }

    @GetMapping(value = "/me/{userBadgeId}/share-card.svg", produces = "image/svg+xml")
    public ResponseEntity<String> getShareCard(@PathVariable Long userBadgeId, Principal principal) {
        User user = currentUser(principal);
        return ResponseEntity.ok()
                .contentType(MediaType.valueOf("image/svg+xml"))
                .body(badgeAwardService.buildShareCardSvg(user.getId(), userBadgeId));
    }

    private User currentUser(Principal principal) {
        return userHolder.isPresent() ? userHolder.getUser() : userService.findByEmail(principal.getName());
    }
}
