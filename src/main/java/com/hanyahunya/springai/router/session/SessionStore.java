package com.hanyahunya.springai.router.session;

import com.hanyahunya.springai.router.llm.LLMClient.Message;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 대화 세션 저장소 인터페이스.
 *
 * 기본값: {@link InMemorySessionStore} (재시작 시 초기화)
 * 교체: 이 인터페이스를 구현한 Bean을 등록하면 자동으로 대체됩니다.
 */
public interface SessionStore {
    List<Message> load(String sessionId);
    void save(String sessionId, List<Message> messages);
    void delete(String sessionId);
}
