package com.saga.be.service.evidence;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TaskFileStorageTest {

	@TempDir
	Path temp;

	@Test
	void writesReadsAndDeletesUnderTaskDirectory() throws Exception {
		TaskFileStorage storage = new TaskFileStorage(temp);
		UUID taskId = UUID.fromString("11111111-1111-1111-1111-111111111111");
		UUID fileId = UUID.fromString("22222222-2222-2222-2222-222222222222");
		byte[] body = {1, 2, 3, 4};
		storage.write(taskId, fileId, body);
		Path stored = temp.resolve(taskId.toString()).resolve(fileId.toString());
		assertTrue(Files.exists(stored));
		assertArrayEquals(body, storage.read(taskId, fileId));
		storage.delete(taskId, fileId);
		assertFalse(Files.exists(stored));
	}
}
