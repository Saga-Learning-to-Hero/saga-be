package com.saga.be.controller;

import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.saga.be.dto.academic.LecturerDirectoryResponse;
import com.saga.be.service.academic.AdminLecturerService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class AdminLecturerControllerWebTest {

	@Mock
	private AdminLecturerService lecturers;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(new AdminLecturerController(lecturers)).build();
	}

	@Test
	void listExposesLecturerProfileIdAndUserIdSeparately() throws Exception {
		UUID profileId = UUID.fromString("11111111-1111-1111-1111-111111111111");
		UUID userId = UUID.fromString("22222222-2222-2222-2222-222222222222");
		when(lecturers.list(isNull(), isNull()))
				.thenReturn(List.of(new LecturerDirectoryResponse(profileId, userId, "Lan", "lan@fe.edu.vn", true)));
		mockMvc.perform(get("/api/admin/lecturers"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].lecturerProfileId").value(profileId.toString()))
				.andExpect(jsonPath("$[0].userId").value(userId.toString()))
				.andExpect(jsonPath("$[0].fullName").value("Lan"))
				.andExpect(jsonPath("$[0].email").value("lan@fe.edu.vn"))
				.andExpect(jsonPath("$[0].active").value(true))
				.andExpect(jsonPath("$[0].id").doesNotExist());
	}
}
