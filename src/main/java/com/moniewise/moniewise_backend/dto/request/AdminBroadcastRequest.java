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

    private Boolean sendFcm;
    private Boolean sendEmail;
    private Boolean allUsers;
    private Boolean includeTestAccounts;
    private Boolean deliverFcmNow;
    private Long ttlSeconds;

    private List<Long> userIds;
    private List<String> emails;
}
