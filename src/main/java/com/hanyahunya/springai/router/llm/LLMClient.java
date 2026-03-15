package com.hanyahunya.springai.router.llm;

import java.util.List;
import java.util.Map;

/**
 * LLM 공급자 추상화 인터페이스.
 * Claude, OpenAI, Gemini 등 구현체를 교체해도 나머지 코드는 변경 불필요.
 */
public interface LLMClient {

    /**
     * 대화 기록과 Tool 목록을 포함해 LLM에 요청합니다.
     *
     * @param messages 전체 대화 기록 (이전 턴 포함)
     * @param tools    AI가 호출 가능한 Tool 목록
     * @return LLM 응답 (대화 텍스트 or Tool 호출 결정)
     */
    LLMResponse chat(List<Message> messages, List<ToolSpec> tools);

    // ── DTO ──────────────────────────────────────────────────────

    record Message(
            String role,     // "user" | "assistant"
            String content
    ) {}

    record ToolSpec(
            String name,
            String description,
            Map<String, Object> parameterSchema   // JSON Schema
    ) {}

    record LLMResponse(
            Type type,
            String message,               // TEXT 응답일 때 AI 메시지
            String toolName,              // TOOL_CALL일 때 호출할 Tool 이름
            Map<String, Object> toolArgs  // TOOL_CALL일 때 파라미터
    ) {
        public enum Type {
            TEXT,       // 파라미터 수집 중 → 클라이언트에게 되묻기
            TOOL_CALL   // 파라미터 충족 → 메서드 호출
        }
    }
}
