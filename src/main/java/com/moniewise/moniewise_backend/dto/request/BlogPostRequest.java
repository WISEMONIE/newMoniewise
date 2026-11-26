package com.moniewise.moniewise_backend.dto.request;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

@Getter
@Setter
@NoArgsConstructor
public class BlogPostRequest {

    @NotBlank
    @Size(max = 300)
    private String title;

    @NotBlank
    private String content;

    @Size(max = 500)
    private String excerpt;

    private String coverImageUrl;

    private boolean publish;
}
