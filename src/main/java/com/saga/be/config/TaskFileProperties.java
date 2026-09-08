package com.saga.be.config;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "saga.task-file")
public class TaskFileProperties {

	private Path directory = Path.of("data", "task-files");
	private long maxBytes = 10_485_760L;
	private int maxFilesPerTask = 20;

	public Path getDirectory() {
		return directory;
	}

	public void setDirectory(Path directory) {
		this.directory = directory;
	}

	public long getMaxBytes() {
		return maxBytes;
	}

	public void setMaxBytes(long maxBytes) {
		this.maxBytes = maxBytes;
	}

	public int getMaxFilesPerTask() {
		return maxFilesPerTask;
	}

	public void setMaxFilesPerTask(int maxFilesPerTask) {
		this.maxFilesPerTask = maxFilesPerTask;
	}
}
