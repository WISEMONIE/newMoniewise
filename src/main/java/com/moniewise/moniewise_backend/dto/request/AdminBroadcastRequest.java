package com.moniewise.moniewise_backend.dto.request;

import lombok.Getter;
import lombok.Setter;

import javax.validation.constraints.Size;
import java.util.List;

@Getter
@Setter
public class AdminBroadcastRequest {

    @Size(max = 120, message = "Title must be 120 characters or fewer")
    private String title;

    @Size(max = 2000, message = "Message must be 2000 characters or fewer")
    private String message;

    @Size(max = 160, message = "Email subject must be 160 characters or fewer")
    private String emailSubject;

    @Size(max = 5000, message = "Email body must be 5000 characters or fewer")
    private String emailBody;

    @Size(max = 60, message = "Tag must be 60 characters or fewer")
    private String tag;

    @Size(max = 120, message = "CTA label must be 120 characters or fewer")
    private String ctaLabel;

    @Size(max = 500, message = "CTA URL must be 500 characters or fewer")
    private String ctaUrl;

    @Size(max = 500, message = "Footer note must be 500 characters or fewer")
    private String footerNote;

    /** Optional picture for the push: a public https link, usually from POST /admin/notifications/image. */
    @Size(max = 1000, message = "Image link must be 1000 characters or fewer")
    private String imageUrl;

    /** For a GIF picture: its first frame as PNG, shown on Android. */
    @Size(max = 1000, message = "Still image link must be 1000 characters or fewer")
    private String stillImageUrl;

    /** One per compose on the website. A repeat with the same key is not sent again. */
    @Size(max = 64, message = "Request key must be 64 characters or fewer")
    @javax.validation.constraints.Pattern(regexp = "^[A-Za-z0-9-]*$", message = "Request key has invalid characters")
    private String requestKey;

    private Boolean sendFcm;
    private Boolean sendEmail;
    private Boolean allUsers;
    private Boolean includeTestAccounts;
    private Boolean deliverFcmNow;
    private Long ttlSeconds;

    private List<Long> userIds;
    private List<String> emails;
}
