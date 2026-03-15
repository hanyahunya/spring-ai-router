package com.hanyahunya.springai.router.usage;

/**
 * LLM 호출 횟수 추적 인터페이스.
 *
 * 기본값: {@link InMemoryUsageTracker}
 * 교체: 이 인터페이스를 구현한 Bean을 등록하면 자동으로 대체됩니다.
 *
 * 영속성이 필요하다면 (재시작 후에도 카운터 유지):
 * 이 인터페이스를 구현하여 원하는 저장소(Redis, DB 등)와 연동하세요.
 */
public interface UsageTracker {
    /** 호출 가능 여부 확인 (한도 초과 시 false) */
    boolean canProceed();
    /** 호출 1회 기록 */
    void record();
    /** 오늘 호출 횟수 */
    int getDailyCount();
    /** 이번 달 호출 횟수 */
    int getMonthlyCount();
}
