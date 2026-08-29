package com.saga.be.mail.template;

final class SagaEmailLayout {

	private SagaEmailLayout() {}

	static String document(String pageTitle, String innerHtml, String ctaLabel, String ctaUrl, String secondaryPanel) {
		String title = EmailHtml.escape(EmailHtml.blankTo(pageTitle, "SAGA"));
		String href = EmailHtml.safeHttpUrl(ctaUrl);
		String label = EmailHtml.escape(EmailHtml.blankTo(ctaLabel, "Open SAGA"));
		String button = href.isEmpty()
				? ""
				: """
					<table role="presentation" cellpadding="0" cellspacing="0" border="0" style="margin:0 0 24px 0;">
					  <tr>
					    <td align="center" bgcolor="#1d4ed8" style="border-radius:6px;">
					      <a href="%s" style="display:inline-block;padding:12px 22px;font-family:Arial,Helvetica,sans-serif;font-size:16px;line-height:20px;color:#ffffff;text-decoration:none;font-weight:bold;">%s</a>
					    </td>
					  </tr>
					</table>
					"""
						.formatted(href, label);
		String panel = secondaryPanel == null ? "" : secondaryPanel;
		return """
			<!DOCTYPE html>
			<html lang="en">
			<head>
			  <meta charset="UTF-8">
			  <meta name="viewport" content="width=device-width, initial-scale=1.0">
			  <meta http-equiv="x-ua-compatible" content="ie=edge">
			  <title>%s</title>
			</head>
			<body style="margin:0;padding:0;background-color:#eef2f7;">
			  <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" border="0" style="background-color:#eef2f7;margin:0;padding:0;">
			    <tr>
			      <td align="center" style="padding:24px 12px;">
			        <table role="presentation" width="600" cellpadding="0" cellspacing="0" border="0" style="width:100%%;max-width:600px;background-color:#ffffff;border-radius:8px;overflow:hidden;">
			          <tr>
			            <td style="background-color:#0f172a;padding:22px 28px;">
			              <p style="margin:0;font-family:Arial,Helvetica,sans-serif;font-size:22px;line-height:26px;color:#ffffff;font-weight:bold;letter-spacing:0.08em;">SAGA</p>
			              <p style="margin:6px 0 0 0;font-family:Arial,Helvetica,sans-serif;font-size:13px;line-height:18px;color:#cbd5e1;">Student Activity Graph Based Continuous Assessment</p>
			            </td>
			          </tr>
			          <tr>
			            <td style="padding:28px 28px 8px 28px;font-family:Arial,Helvetica,sans-serif;font-size:16px;line-height:24px;color:#1e293b;">
			              %s
			              %s
			              %s
			            </td>
			          </tr>
			          <tr>
			            <td style="padding:8px 28px 24px 28px;font-family:Arial,Helvetica,sans-serif;font-size:12px;line-height:18px;color:#64748b;border-top:1px solid #e2e8f0;">
			              SAGA — Student Activity Graph Based Continuous Assessment<br>
			              This is an automated email.
			            </td>
			          </tr>
			        </table>
			      </td>
			    </tr>
			  </table>
			</body>
			</html>
			"""
				.formatted(title, innerHtml, button, panel);
	}

	static String infoPanel(String... rows) {
		if (rows == null || rows.length == 0) {
			return "";
		}
		StringBuilder body = new StringBuilder();
		for (int i = 0; i + 1 < rows.length; i += 2) {
			body.append(
					"""
					<tr>
					  <td style="padding:8px 12px 2px 12px;font-family:Arial,Helvetica,sans-serif;font-size:12px;line-height:16px;color:#64748b;text-transform:uppercase;letter-spacing:0.04em;">%s</td>
					</tr>
					<tr>
					  <td style="padding:0 12px 10px 12px;font-family:Arial,Helvetica,sans-serif;font-size:15px;line-height:22px;color:#0f172a;font-weight:bold;">%s</td>
					</tr>
					"""
							.formatted(EmailHtml.escape(rows[i]), EmailHtml.escape(rows[i + 1])));
		}
		return """
			<table role="presentation" width="100%%" cellpadding="0" cellspacing="0" border="0" style="margin:0 0 8px 0;background-color:#f8fafc;border:1px solid #e2e8f0;border-radius:6px;">
			  %s
			</table>
			"""
				.formatted(body);
	}
}
