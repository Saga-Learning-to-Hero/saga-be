package com.saga.be.service.evidence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

public final class TaskFileStorage {

	private final Path root;

	public TaskFileStorage(Path root) {
		this.root = root.toAbsolutePath().normalize();
	}

	public Path pathFor(UUID taskId, UUID fileId) {
		Path path = root.resolve(taskId.toString()).resolve(fileId.toString()).normalize();
		if (!path.startsWith(root)) {
			throw new IllegalArgumentException("Resolved path escaped storage root.");
		}
		return path;
	}

	public void write(UUID taskId, UUID fileId, byte[] content) throws IOException {
		Path path = pathFor(taskId, fileId);
		Files.createDirectories(path.getParent());
		Files.write(path, content);
	}

	public byte[] read(UUID taskId, UUID fileId) throws IOException {
		return Files.readAllBytes(pathFor(taskId, fileId));
	}

	public void delete(UUID taskId, UUID fileId) throws IOException {
		Files.deleteIfExists(pathFor(taskId, fileId));
	}
}
