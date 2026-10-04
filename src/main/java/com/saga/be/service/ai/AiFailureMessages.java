package com.saga.be.service.ai;

import com.saga.be.dto.ai.AiFailureResponse;
import java.util.Map;

/**
 * Turns a safe AI failure code into something a student or lecturer can read: a short Vietnamese
 * title, what happened, what to do next, and whether simply retrying can help. The code itself is
 * kept for support; the texts never carry provider details, keys or payloads.
 */
public final class AiFailureMessages {

	private record Text(String title, String message, String hint, boolean retryable) {}

	private static final Text TIMEOUT = new Text(
			"AI phản hồi quá lâu",
			"Nhà cung cấp AI không trả kết quả trong thời gian chờ.",
			"Commit hoặc task có nhiều nội dung (ví dụ commit merge pull request) dễ bị quá thời gian. Hãy thử lại sau ít phút.",
			true);
	private static final Text RESULT_INVALID = new Text(
			"Kết quả AI không hợp lệ",
			"AI trả về kết quả sai định dạng hoặc dẫn tới dữ liệu không có trong bằng chứng, nên SAGA không lưu kết quả này.",
			"Hãy thử lại. Nếu lỗi lặp lại, giảng viên nên chọn model mạnh hơn trong cài đặt AI của lớp.",
			true);
	private static final Text QUOTA = new Text(
			"Key AI đã hết hạn mức",
			"Key AI đang dùng đã hết lượt gọi cho phép.",
			"Giảng viên thêm model dự phòng hoặc đổi key trong cài đặt AI của lớp. Gói miễn phí thường được làm mới mỗi ngày.",
			false);
	private static final Text RATE_LIMITED = new Text(
			"AI đang bị giới hạn tần suất",
			"Có quá nhiều yêu cầu gửi tới nhà cung cấp AI trong thời gian ngắn.",
			"Hãy thử lại sau 1–2 phút.",
			true);
	private static final Text AUTH = new Text(
			"Key AI không hợp lệ",
			"Nhà cung cấp AI từ chối key đang dùng.",
			"Giảng viên kiểm tra hoặc cập nhật key trong cài đặt AI của lớp.",
			false);
	private static final Text PROVIDER_DOWN = new Text(
			"Nhà cung cấp AI đang gặp sự cố",
			"Dịch vụ AI tạm thời không phản hồi bình thường.",
			"Hãy thử lại sau ít phút.",
			true);
	private static final Text MODEL = new Text(
			"Model AI không còn được hỗ trợ",
			"Model AI đang cấu hình cho lớp không dùng được cho loại phân tích này.",
			"Giảng viên chọn model khác trong cài đặt AI của lớp.",
			false);
	private static final Text RUNTIME_OFF = new Text(
			"Hệ thống chưa bật AI",
			"Máy chủ AI chưa được bật hoặc chưa cấu hình xong.",
			"Liên hệ quản trị viên hệ thống.",
			false);
	private static final Text RUNTIME_OUTDATED = new Text(
			"Máy chủ AI chưa được cập nhật",
			"Máy chủ AI đang chạy phiên bản cũ, chưa hỗ trợ loại phân tích này.",
			"Liên hệ quản trị viên để cập nhật máy chủ AI.",
			false);
	private static final Text NO_KEY = new Text(
			"Lớp chưa có key AI",
			"Lớp chưa cấu hình key AI và chưa được phép dùng key hệ thống.",
			"Giảng viên nhập key AI cho lớp, hoặc bật cho phép dùng key hệ thống trong cài đặt AI.",
			false);
	private static final Text KEY_SETUP = new Text(
			"Cấu hình key AI trên máy chủ chưa đúng",
			"SAGA không mở được key AI của lớp để gửi đi.",
			"Liên hệ quản trị viên kiểm tra cấu hình mã hoá key AI.",
			false);
	private static final Text QUEUE_FULL = new Text(
			"Hàng đợi phân tích AI đang đầy",
			"Đang có quá nhiều yêu cầu phân tích chờ xử lý.",
			"Hãy thử lại sau ít phút.",
			true);
	private static final Text INTERRUPTED = new Text(
			"Phân tích bị gián đoạn",
			"Lần phân tích bị dừng giữa chừng, thường do máy chủ khởi động lại.",
			"Hãy thử lại.",
			true);
	private static final Text UNKNOWN = new Text(
			"Phân tích AI thất bại",
			"Đã có lỗi không xác định trong quá trình phân tích.",
			"Hãy thử lại. Nếu lỗi lặp lại, báo quản trị viên kèm mã lỗi.",
			true);

	private static final Map<String, Text> BY_CODE = Map.ofEntries(
			Map.entry("AI_PROVIDER_TIMEOUT", TIMEOUT),
			Map.entry("AI_PROVIDER_RESULT_INVALID", RESULT_INVALID),
			Map.entry("AI_ANALYSIS_RESULT_INVALID", RESULT_INVALID),
			Map.entry("AI_PROVIDER_QUOTA_EXHAUSTED", QUOTA),
			Map.entry("AI_PROVIDER_RATE_LIMITED", RATE_LIMITED),
			Map.entry("AI_PROVIDER_AUTH_FAILED", AUTH),
			Map.entry("AI_PROVIDER_UNAVAILABLE", PROVIDER_DOWN),
			Map.entry("AI_PROVIDER_FAILED", PROVIDER_DOWN),
			Map.entry("AI_ANALYSIS_PROVIDER_FAILED", PROVIDER_DOWN),
			Map.entry("AI_PROVIDER_MODEL_NOT_FOUND", MODEL),
			Map.entry("AI_MODEL_NOT_SUPPORTED", MODEL),
			Map.entry("AI_MODEL_CAPABILITY_UNSUPPORTED", MODEL),
			Map.entry("AI_PROVIDER_NOT_SUPPORTED", MODEL),
			Map.entry("AI_RUNTIME_NOT_CONFIGURED", RUNTIME_OFF),
			Map.entry("AI_RUNTIME_DISABLED", RUNTIME_OFF),
			Map.entry("AI_RUNTIME_UNAVAILABLE", RUNTIME_OFF),
			Map.entry("AI_PROVIDER_NOT_CONFIGURED", RUNTIME_OFF),
			Map.entry("AI_RUNTIME_OUTDATED", RUNTIME_OUTDATED),
			Map.entry("AI_CONTRACT_VERSION_UNSUPPORTED", RUNTIME_OUTDATED),
			Map.entry("AI_CREDENTIAL_UNAVAILABLE", NO_KEY),
			Map.entry("AI_CREDENTIAL_DECRYPTION_FAILED", KEY_SETUP),
			Map.entry("AI_CREDENTIAL_ENCRYPTION_FAILED", KEY_SETUP),
			Map.entry("AI_CREDENTIAL_ENVELOPE_INVALID", KEY_SETUP),
			Map.entry("AI_CREDENTIAL_ENVELOPE_SOURCE_MISSING", KEY_SETUP),
			Map.entry("AI_CREDENTIAL_ENVELOPE_UNAVAILABLE", KEY_SETUP),
			Map.entry("AI_CREDENTIAL_KEY_VERSION_UNSUPPORTED", KEY_SETUP),
			Map.entry("AI_CREDENTIAL_MASTER_KEY_NOT_CONFIGURED", KEY_SETUP),
			Map.entry("AI_CREDENTIAL_TRANSPORT_ENCRYPTION_FAILED", KEY_SETUP),
			Map.entry("AI_CREDENTIAL_TRANSPORT_KEY_NOT_CONFIGURED", KEY_SETUP),
			Map.entry("AI_CREDENTIAL_TRANSPORT_NOT_CONFIGURED", KEY_SETUP),
			Map.entry("AI_COURSE_CREDENTIAL_REQUIRES_REMOTE_PROVIDER", KEY_SETUP),
			Map.entry("AI_QUEUE_CAPACITY_EXCEEDED", QUEUE_FULL),
			Map.entry("AI_RUNNING_STALE_RECOVERED", INTERRUPTED));

	private AiFailureMessages() {}

	/** Null when there is no failure code; an unknown code gets a generic, still readable text. */
	public static AiFailureResponse describe(String code) {
		if (code == null || code.isBlank()) {
			return null;
		}
		Text text = BY_CODE.getOrDefault(code.trim(), UNKNOWN);
		return new AiFailureResponse(code.trim(), text.title(), text.message(), text.hint(), text.retryable());
	}
}
