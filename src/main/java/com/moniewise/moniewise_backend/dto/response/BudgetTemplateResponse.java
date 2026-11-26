package com.moniewise.moniewise_backend.dto.response;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class BudgetTemplateResponse {
    private Long id;
    private String name;
    private List<Map<String, Object>> envelopeDefinitions;
    private int envelopeCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
