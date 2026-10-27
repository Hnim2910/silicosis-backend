package com.sillicosis.backend.patient.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.sillicosis.backend.patient.Patient;
import com.sillicosis.backend.user.Gender;

public record PatientResponse(UUID id, String code, UUID userId, String fullName, LocalDate birthDate, Gender gender,
		String phone, String occupation, String workplace, String exposureHistory, String riskFactors,
		Instant createdAt, Instant updatedAt) {

	public static PatientResponse from(Patient p) {
		return new PatientResponse(p.getId(), p.getCode(), p.getUserId(), p.getFullName(), p.getBirthDate(),
				p.getGender(), p.getPhone(), p.getOccupation(), p.getWorkplace(), p.getExposureHistory(),
				p.getRiskFactors(), p.getCreatedAt(), p.getUpdatedAt());
	}

	@Override
	public String toString() {
		return "PatientResponse[id=" + id + "]";
	}

}
