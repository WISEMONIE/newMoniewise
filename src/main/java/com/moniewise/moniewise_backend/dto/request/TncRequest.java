package com.moniewise.moniewise_backend.dto.request;

import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor

public class TncRequest {
    private boolean accepted;

    public boolean isAccepted() {
        return accepted;
    }
}
