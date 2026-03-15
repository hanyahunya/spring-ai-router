package com.hanyahunya.springai.router.usage;


import com.hanyahunya.springai.router.config.SpringAIRouterProperties;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 인메모리 사용량 추적기 (기본값).
 * 서버 재시작 시 카운터가 초기화됩니다.
 * 영속성이 필요하면 {@link UsageTracker}를 구현하여 Bean으로 등록하세요.
 */
public class InMemoryUsageTracker implements UsageTracker {

    private final SpringAIRouterProperties props;
    private final AtomicInteger dailyCount   = new AtomicInteger(0);
    private final AtomicInteger monthlyCount = new AtomicInteger(0);

    private LocalDate   currentDay   = LocalDate.now();
    private YearMonth   currentMonth = YearMonth.now();

    public InMemoryUsageTracker(SpringAIRouterProperties props) {
        this.props = props;
    }

    @Override
    public synchronized boolean canProceed() {
        resetIfNeeded();
        int dMax = props.getLimits().getDailyMax();
        int mMax = props.getLimits().getMonthlyMax();
        if (dMax != -1 && dailyCount.get()   >= dMax) return false;
        if (mMax != -1 && monthlyCount.get() >= mMax) return false;
        return true;
    }

    @Override
    public synchronized void record() {
        resetIfNeeded();
        dailyCount.incrementAndGet();
        monthlyCount.incrementAndGet();
    }

    @Override public int getDailyCount()   { resetIfNeeded(); return dailyCount.get(); }
    @Override public int getMonthlyCount() { resetIfNeeded(); return monthlyCount.get(); }

    private synchronized void resetIfNeeded() {
        LocalDate  today     = LocalDate.now();
        YearMonth  thisMonth = YearMonth.now();
        if (!today.equals(currentDay)) {
            dailyCount.set(0);
            currentDay = today;
        }
        if (!thisMonth.equals(currentMonth)) {
            monthlyCount.set(0);
            currentMonth = thisMonth;
        }
    }
}
