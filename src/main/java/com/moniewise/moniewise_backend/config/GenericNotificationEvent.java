package com.moniewise.moniewise_backend.config;

import com.moniewise.moniewise_backend.enums.NotificationType;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.util.Map;

@Getter
public class GenericNotificationEvent extends ApplicationEvent {
    private final String userId;
    private final NotificationType type;
    private final Map<String, Object> params; // Dynamic values (amounts, names)
    private final Long contextId1; // e.g., BudgetID
    private final Long contextId2; // e.g., EnvelopeID
    private final String actionUrl;

    public GenericNotificationEvent(Object source, String userId, NotificationType type, 
                                    Map<String, Object> params, Long contextId1, 
                                    Long contextId2, String actionUrl) {
        super(source);
        this.userId = userId;
        this.type = type;
        this.params = params;
        this.contextId1 = contextId1;
        this.contextId2 = contextId2;
        this.actionUrl = actionUrl;
    }
}