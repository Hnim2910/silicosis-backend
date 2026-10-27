package com.sillicosis.backend.visit.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

public record AttachImageRequest(@NotNull(message = "Vui lòng chọn ảnh.") UUID imageId) {
}
