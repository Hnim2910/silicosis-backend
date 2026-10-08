package com.sillicosis.backend.mail;

import java.nio.charset.StandardCharsets;

import org.springframework.core.NestedExceptionUtils;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.util.HtmlUtils;

import com.sillicosis.backend.config.AppProperties;

import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Gửi email bất đồng bộ. Không log địa chỉ người nhận hay nội dung. */
@Slf4j
@Service
@RequiredArgsConstructor
public class MailService {

	private final JavaMailSender mailSender;
	private final AppProperties props;

	/** Chạy sau khi transaction tạo OTP đã commit, trên thread riêng. */
	@Async
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onOtpRequested(OtpMailRequested event) {
		try {
			MimeMessage message = mailSender.createMimeMessage();
			MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
			helper.setFrom(props.mail().from());
			helper.setTo(event.email());
			helper.setSubject("[SilicoCare] Mã xác nhận đặt lại mật khẩu");
			helper.setText(otpHtml(event.fullName(), event.otp(), event.ttlMinutes()), true);
			mailSender.send(message);
			log.info("Đã gửi email OTP đặt lại mật khẩu");
		}
		catch (Exception ex) {
			log.warn("Gửi email OTP thất bại: {} (nguyên nhân: {})", ex.getClass().getSimpleName(),
					NestedExceptionUtils.getMostSpecificCause(ex).getClass().getSimpleName());
		}
	}

	static String otpHtml(String fullName, String otp, long ttlMinutes) {
		String name = HtmlUtils.htmlEscape(fullName == null ? "bạn" : fullName);
		return """
				<!DOCTYPE html>
				<html lang="vi">
				<body style="margin:0;padding:24px;background:#f4f6f8;font-family:Arial,Helvetica,sans-serif;color:#1f2933">
				  <div style="max-width:480px;margin:0 auto;background:#ffffff;border-radius:8px;padding:28px">
				    <h2 style="margin:0 0 16px;color:#0f766e">SilicoCare</h2>
				    <p>Xin chào %s,</p>
				    <p>Chúng tôi nhận được yêu cầu đặt lại mật khẩu cho tài khoản của bạn. Mã xác nhận của bạn là:</p>
				    <p style="font-size:30px;font-weight:bold;letter-spacing:8px;text-align:center;margin:24px 0">%s</p>
				    <p>Mã có hiệu lực trong <strong>%d phút</strong> và chỉ dùng được một lần.
				       Vui lòng không chia sẻ mã này cho bất kỳ ai, kể cả nhân viên SilicoCare.</p>
				    <p>Nếu bạn không yêu cầu đặt lại mật khẩu, hãy bỏ qua email này; mật khẩu của bạn vẫn giữ nguyên.</p>
				    <p style="color:#6b7280;font-size:12px;margin-top:28px">Email được gửi tự động, vui lòng không trả lời.</p>
				  </div>
				</body>
				</html>
				""".formatted(name, otp, ttlMinutes);
	}

}
