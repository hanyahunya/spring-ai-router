package com.hanyahunya.springai.router.annotation;

import java.lang.annotation.*;

/**
 * 선택 어노테이션. AI Tool의 설명을 직접 지정해 정확도를 높입니다.
 *
 * 붙이지 않아도 @AIController 클래스의 모든 매핑 메서드는
 * 자동으로 Tool로 등록됩니다. (메서드명 + HTTP 메서드 + 경로로 설명 자동 생성)
 *
 * 붙이면 description이 우선 사용되어 AI가 더 정확하게 호출합니다.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AITool {
    /** AI에게 전달되는 이 메서드의 설명. 구체적일수록 정확도가 올라갑니다. */
    String description();
}
