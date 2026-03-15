package com.hanyahunya.springai.router.annotation;

import java.lang.annotation.*;

/**
 * 파라미터 또는 필드에 붙여 AI에게 추가 설명을 제공하는 어노테이션.
 * 없어도 이름으로 자동 유추하지만, 있으면 정확도가 올라갑니다.
 *
 * ── 메서드 파라미터에 사용 ──────────────────────────────────────
 * <pre>{@code
 * @PostMapping("/users")
 * public User createUser(
 *     @RequestBody
 *     @AIParam("생성할 유저 정보. email과 username은 필수입니다.")
 *     CreateUserRequest req
 * ) { ... }
 * }</pre>
 *
 * ── @RequestParam / @PathVariable 에 사용 ──────────────────────
 * <pre>{@code
 * @GetMapping("/users/{id}")
 * public User getUser(
 *     @PathVariable
 *     @AIParam("조회할 유저의 고유 ID")
 *     Long id
 * ) { ... }
 * }</pre>
 *
 * ── RequestBody 내부 필드에 사용 ───────────────────────────────
 * <pre>{@code
 * public class CreateUserRequest {
 *     @AIParam("유저 이메일 주소. 중복 불가.")
 *     private String email;
 *
 *     @AIParam("유저 이름. 2~20자.")
 *     private String username;
 *
 *     @AIParam("결제 수단. CREDIT_CARD | KAKAO_PAY | TOSS 중 하나.")
 *     private PaymentMethod paymentMethod;
 * }
 * }</pre>
 */
@Target({ElementType.PARAMETER, ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AIParam {
    /**
     * AI에게 전달되는 이 파라미터/필드의 설명.
     * enum 가능 값, 제약 조건, 예시 등을 적어주면 정확도가 올라갑니다.
     */
    String value();
}
