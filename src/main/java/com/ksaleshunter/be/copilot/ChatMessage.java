package com.ksaleshunter.be.copilot;

import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** chat_messages — AI 코파일럿 대화. product_id 가 NULL 이면 전역 대화. 화면을 옮겨도 이어진다. */
@Entity
@Table(name = "chat_messages")
public class ChatMessage {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Column(name = "product_id")
	private Long productId;

	@Column(nullable = false, length = 10)
	private String role; // USER | ASSISTANT

	@Column(nullable = false, columnDefinition = "text")
	private String content;

	@Column(length = 20)
	private String intent; // QUERY | COMMAND | CLARIFY

	@Column(name = "created_at", nullable = false)
	private OffsetDateTime createdAt;

	protected ChatMessage() {
	}

	private ChatMessage(Long userId, Long productId, String role, String content, String intent) {
		this.userId = userId;
		this.productId = productId;
		this.role = role;
		this.content = content;
		this.intent = intent;
		this.createdAt = OffsetDateTime.now();
	}

	/** 사용자 발화. intent 는 AI 가 분류하므로 비워 둔다. */
	public static ChatMessage user(Long userId, Long productId, String content) {
		return new ChatMessage(userId, productId, "USER", content, null);
	}

	/** AI 답변. intent 는 AI 응답의 QUERY / COMMAND / CLARIFY 를 그대로 저장한다. */
	public static ChatMessage assistant(Long userId, Long productId, String content, String intent) {
		return new ChatMessage(userId, productId, "ASSISTANT", content, intent);
	}
}
