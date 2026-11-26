package com.moniewise.moniewise_backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "gemini")
public class GeminiProperties {
    private String apiKey;

    // Code-level defaults for model and url — these are overridden by the
    // application.properties / env-var bindings when the deployed JAR includes
    // those lines.  Having the defaults here means an older compiled JAR that
    // is missing the property lines (the root cause of model=<missing> in the
    // server log) still works correctly without any container restart.
    private String model = "gemini-2.5-flash";
    private String url   = "https://generativelanguage.googleapis.com/v1beta/models";

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }
}
