package com.saga.be.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "saga.ai")
public class AiAnalysisProperties {
	private int workers = 1;
	private int queueCapacity = 100;
	private int recoveryBatchSize = 50;
	private Duration staleRunningAfter = Duration.ofMinutes(15);
	private boolean enabled = false;
	private String primaryProvider = "openai";
	private int maxChangedFiles = 40;
	private int maxPatchBytes = 256 * 1024;
	private final OpenAi openai = new OpenAi();
	private final Runtime runtime = new Runtime();
	public int getWorkers() { return workers; }
	public void setWorkers(int workers) { this.workers = Math.max(1, workers); }
	public int getQueueCapacity() { return queueCapacity; }
	public void setQueueCapacity(int queueCapacity) { this.queueCapacity = Math.max(1, queueCapacity); }
	public int getRecoveryBatchSize() { return recoveryBatchSize; }
	public void setRecoveryBatchSize(int recoveryBatchSize) { this.recoveryBatchSize = Math.max(1, recoveryBatchSize); }
	public Duration getStaleRunningAfter() { return staleRunningAfter; }
	public void setStaleRunningAfter(Duration staleRunningAfter) { this.staleRunningAfter = staleRunningAfter == null ? Duration.ofMinutes(15) : staleRunningAfter; }
	public boolean isEnabled() { return enabled; }
	public void setEnabled(boolean enabled) { this.enabled = enabled; }
	public String getPrimaryProvider() { return primaryProvider; }
	public void setPrimaryProvider(String primaryProvider) { this.primaryProvider = primaryProvider == null ? "openai" : primaryProvider; }
	public int getMaxChangedFiles() { return maxChangedFiles; }
	public void setMaxChangedFiles(int value) { maxChangedFiles = Math.max(1, value); }
	public int getMaxPatchBytes() { return maxPatchBytes; }
	public void setMaxPatchBytes(int value) { maxPatchBytes = Math.max(1, value); }
	public OpenAi getOpenai() { return openai; }
	public Runtime getRuntime() { return runtime; }
	public static class Runtime { private boolean enabled=false; private String baseUrl=""; private String internalToken=""; private Duration timeout=Duration.ofSeconds(60); public boolean isEnabled(){return enabled;} public void setEnabled(boolean v){enabled=v;} public String getBaseUrl(){return baseUrl;} public void setBaseUrl(String v){baseUrl=v;} public String getInternalToken(){return internalToken;} public void setInternalToken(String v){internalToken=v;} public Duration getTimeout(){return timeout;} public void setTimeout(Duration v){timeout=v==null?Duration.ofSeconds(60):v;} }
	public static class OpenAi {
		private String apiKey = "";
		private String model = "gpt-5.6-sol";
		private String reasoningEffort = "medium";
		private String baseUrl = "https://api.openai.com";
		private int maxOutputTokens = 4000;
		public String getApiKey() { return apiKey; }
		public void setApiKey(String value) { apiKey = value; }
		public String getModel() { return model; }
		public void setModel(String value) { model = value; }
		public String getReasoningEffort() { return reasoningEffort; }
		public void setReasoningEffort(String value) { reasoningEffort = value; }
		public String getBaseUrl() { return baseUrl; }
		public void setBaseUrl(String value) { baseUrl = value; }
		public int getMaxOutputTokens() { return maxOutputTokens; }
		public void setMaxOutputTokens(int value) { maxOutputTokens = Math.max(1, value); }
	}
}
