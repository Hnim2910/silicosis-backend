package com.sillicosis.backend.user.dto;

import jakarta.validation.constraints.NotNull;

/** {@code enabled=false} để khoá tài khoản, {@code true} để mở khoá (đồng thời xoá khoá tạm do đăng nhập sai). */
public record UpdateStatusRequest(@NotNull(message = "Thiếu trạng thái.") Boolean enabled) {
}
