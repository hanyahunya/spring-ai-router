package com.hanyahunya.springai.router.session;

import com.hanyahunya.springai.router.llm.LLMClient.Message;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 인메모리 세션 저장소 (기본값).
 * 서버 재시작 시 초기화되며, TTL 초과 세션은 다음 접근 시 자동 만료됩니다.
 */
public class InMemorySessionStore implements SessionStore {

    private final Map<String, Entry> store = new ConcurrentHashMap<>();
    private final long ttlMillis;

    public InMemorySessionStore(int ttlMinutes) {
        this.ttlMillis = (long) ttlMinutes * 60 * 1000;
    }

    @Override
    public List<Message> load(String sessionId) {
        Entry entry = store.get(sessionId);
        if (entry == null || isExpired(entry)) {
            store.remove(sessionId);
            return new ArrayList<>();
        }
        entry.lastAccessed = Instant.now();
        return new ArrayList<>(entry.messages);
    }

    @Override
    public void save(String sessionId, List<Message> messages) {
        store.compute(sessionId, (k, e) -> {
            if (e == null) e = new Entry();
            e.messages     = new ArrayList<>(messages);
            e.lastAccessed = Instant.now();
            return e;
        });
    }

    @Override
    public void delete(String sessionId) {
        store.remove(sessionId);
    }

    private boolean isExpired(Entry e) {
        return Instant.now().toEpochMilli() - e.lastAccessed.toEpochMilli() > ttlMillis;
    }

    private static class Entry {
        List<Message> messages     = new ArrayList<>();
        Instant       lastAccessed = Instant.now();
    }
}
