package com.sillicosis.backend.user.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.sillicosis.backend.user.Gender;
import com.sillicosis.backend.user.Role;
import com.sillicosis.backend.user.User;

public record UserResponse(UUID id, String email, String phone, String fullName, Role role, boolean enabled,
		LocalDate birthDate, Gender gender, String occupation, Instant createdAt, Instant updatedAt) {

	public static UserResponse from(User u) {
		return new UserResponse(u.getId(), u.getEmail(), u.getPhone(), u.getFullName(), u.getRole(), u.isEnabled(),
				u.getBirthDate(), u.getGender(), u.getOccupation(), u.getCreatedAt(), u.getUpdatedAt());
	}

	@Override
	public String toString() {
		return "UserResponse[id=" + id + "]";
	}

}
