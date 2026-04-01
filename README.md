# spring-ai-router

> **🚧 This project is currently under active development and has not yet been released. Maven Central publication is not yet available.**

**[English](#english) | [日本語](#日本語) | [한국어](#한국어)**

---

<a name="english"></a>
# English

A Spring Boot library that lets AI Agents and on-device AI call your existing REST server using natural language — with no changes to your existing endpoints. Just add a few annotations.

No need to build a separate MCP server.

---

## Table of Contents

1. [Quick Start](#quick-start)
2. [Annotation Reference](#annotation-reference)
3. [Configuration](#configuration)
4. [How It Works](#how-it-works)
5. [Extension Points](#extension-points)

---

## Quick Start

### 1. Add Dependency

> **Not yet available on Maven Central.** Clone the repository and install locally for now.

```bash
git clone https://github.com/hanyahunya/spring-ai-router.git
cd spring-ai-router
./gradlew publishToMavenLocal
```

Then add to your project:

**Gradle**
```groovy
implementation 'com.hanyahunya:spring-ai-router:1.0.0-SNAPSHOT'
```

**Maven**
```xml
<dependency>
    <groupId>com.hanyahunya</groupId>
    <artifactId>spring-ai-router</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### 2. Configure Your API Key

`application.yml`
```yaml
spring-ai-router:
  provider: claude      # claude | openai | gemini
  api-key: ${AI_API_KEY}
```

### 3. Annotate Your Existing Controller

```java
@RestController
@AIController                          // ← add this
@RequestMapping("/users")
public class UserController {

    @PostMapping
    @AITool(description = "Creates a new user. email and username are required.")
    public User createUser(@RequestBody CreateUserRequest req) { ... }

    @GetMapping("/{id}")
    public User getUser(@PathVariable Long id) { ... }

    @DeleteMapping("/{id}")
    @AIHidden                          // ← hide from AI
    public void deleteUser(@PathVariable Long id) { ... }
}
```

### 4. Call with Natural Language

```bash
curl -X POST https://your-server.com/ai-chat \
  -H "Content-Type: application/json" \
  -d '{"message": "I want to sign up as John Doe"}'
```

If required parameters are missing, the AI asks for them naturally.

```json
{"status":"needs_input","sessionId":"abc-123",
 "message":"To complete the signup, I need your email address. What is your email?",
 "hint":"Send your next message to POST /ai-chat with the same sessionId."}
```

Continue the conversation with the same `sessionId`.

```bash
curl -X POST https://your-server.com/ai-chat \
  -H "Content-Type: application/json" \
  -d '{"sessionId":"abc-123","message":"john@example.com"}'
```

Once all parameters are collected, the actual method is called.

```json
{"status":"complete","sessionId":"abc-123",
 "message":"Successfully executed createUser.",
 "data":{"id":1,"name":"John Doe","email":"john@example.com"}}
```

That's it. No changes to your existing server required.

---

## Annotation Reference

### `@AIController`

**Target:** Class

Registers this controller as an AI-callable tool set. Methods inside are invisible to the AI unless this annotation is present.

```java
@RestController
@AIController
public class ProductController { ... }
```

---

### `@AITool`

**Target:** Method  
**Attribute:** `description` (String)

Describes what this method does to the AI. If omitted, a description is auto-generated from the HTTP method and path (e.g., `"GET /products"`), but a hand-written description improves routing accuracy significantly.

```java
@GetMapping("/products")
@AITool(description = "Returns a list of products. Can be filtered by category and max price.")
public List<Product> getProducts(
    @RequestParam String category,
    @RequestParam int maxPrice) { ... }
```

---

### `@AIHidden`

**Target:** Method

Excludes this method from the AI tool list. Use for admin-only APIs, internal utilities, or anything you don't want the AI to invoke.

```java
@DeleteMapping("/users/{id}")
@AIHidden
public void deleteUser(@PathVariable Long id) { ... }
```

---

### `@AIParam`

**Target:** Method parameter, RequestBody field

Adds a description to help the AI understand the purpose of a parameter. Optional, but especially useful when the field has enum values, format constraints, or non-obvious meaning.

**On a method parameter**
```java
@GetMapping("/orders/{id}")
public Order getOrder(
    @PathVariable
    @AIParam("The unique ID of the order to retrieve")
    Long id) { ... }
```

**On a RequestBody field**
```java
public class CreateOrderRequest {

    @AIParam("Customer email address")
    private String email;

    @AIParam("Payment method. One of: CARD | KAKAO_PAY | TOSS")
    private PaymentMethod paymentMethod;

    @AIParam("Delivery address")
    private String address;
}
```

---

## Configuration

```yaml
spring-ai-router:
  enabled: true                  # Enable/disable the library (default: true)
  provider: claude               # LLM provider: claude | openai | gemini (default: claude)
  api-key: ${AI_API_KEY}         # Required. API key for the chosen provider
  model:                         # Optional. Defaults to the provider's recommended model (see below)

  session:
    ttl-minutes: 30              # Session TTL in minutes (default: 30)
    max-turns: 20                # Max conversation turns per session (default: 20)

  limits:
    daily-max: -1                # Max LLM calls per day. -1 = unlimited (default: -1)
    monthly-max: -1              # Max LLM calls per month. -1 = unlimited (default: -1)
```

**Default models per provider**

| provider | default model |
|----------|---------------|
| `claude` | `claude-haiku-4-5-20251001` |
| `openai` | `gpt-4o-mini` |
| `gemini` | `gemini-2.5-flash` |

**Enable debug logging (token usage, prompts, etc.)**

```yaml
logging:
  level:
    com.hanyahunya.springai.router.llm.ClaudeClient: DEBUG
    com.hanyahunya.springai.router.llm.OpenAIClient: DEBUG
    com.hanyahunya.springai.router.llm.GeminiClient: DEBUG
```

---

## How It Works

### Request Flow

```
Client
  POST /ai-chat { "sessionId": "...", "message": "natural language request" }
        ↓
  AIChatController
    ├─ Check usage limits
    ├─ Load session history (SessionStore)
    └─ LLMClient.chat(history, toolList)
              ↓
         LLM Decision
          ├─ TEXT  → missing params → return needs_input, save session
          └─ TOOL_CALL → all params present → MethodInvoker.invoke()
                                                    ↓
                                              Execute method (reflection)
                                                    ↓
                                              return complete, delete session
```

### Response Format (NDJSON Streaming)

`/ai-chat` responds with NDJSON (Newline-Delimited JSON). Each line is an independent JSON object, flushed immediately as the server progresses.

| status | When | Key fields |
|--------|------|------------|
| `processing` | Immediately on receipt | `sessionId`, `message` |
| `needs_input` | When parameters are missing | `sessionId`, `message`, `hint` |
| `executing` | Just before method call | `sessionId`, `message` |
| `complete` | After successful execution | `sessionId`, `message`, `data` |
| `error` | On any error | `sessionId`, `message` |

### Prompt Caching

To reduce LLM costs, prompt caching is automatically applied for each provider.

- **Claude:** `cache_control: ephemeral` applied to system prompt + tool list (TTL 1h)
- **OpenAI:** `prompt_cache_retention: 24h` — instructions + tool list serve as the cache prefix
- **Gemini:** Explicit cache created via `cachedContents` API on app startup (TTL 1h, auto-renewed 60s before expiry)

Tool order is kept stable via alphabetical sorting of method and field names to maximize cache hit rates.

---

## Extension Points

All defaults are in-memory and reset on server restart. Register a Bean implementing the relevant interface to replace them.

### Replace SessionStore (e.g., Redis)

```java
@Bean
public SessionStore redisSessionStore(RedisTemplate<String, Object> redis) {
    return new RedisSessionStore(redis);
}
```

```java
public interface SessionStore {
    List<Message> load(String sessionId);
    void save(String sessionId, List<Message> messages);
    void delete(String sessionId);
}
```

### Replace UsageTracker (e.g., Database)

```java
@Bean
public UsageTracker dbUsageTracker(UsageRepository repo) {
    return new DbUsageTracker(repo);
}
```

```java
public interface UsageTracker {
    boolean canProceed();    // returns false when limit is exceeded
    void record();           // increment call count
    int getDailyCount();
    int getMonthlyCount();
}
```

### Replace LLMClient (Custom Provider)

Register a Bean implementing `LLMClient` to override the default provider entirely.

```java
@Bean
public LLMClient myCustomLlmClient() {
    return new MyCustomLLMClient();
}
```

---

<a name="日本語"></a>
# 日本語

既存のRESTサーバーに手を加えることなく、アノテーションを数個追加するだけで、AIエージェント・オンデバイスAIが自然言語でサーバー機能を呼び出せるようにするSpring Bootライブラリです。

MCPサーバーを別途開発するリソースがなくても、AI時代に対応できます。

---

## 目次

1. [クイックスタート](#クイックスタート)
2. [アノテーションリファレンス](#アノテーションリファレンス)
3. [設定一覧](#設定一覧)
4. [動作原理](#動作原理)
5. [拡張ポイント](#拡張ポイント)

---

## クイックスタート

### 1. 依存関係の追加

> **Maven Centralにはまだ公開されていません。** 現在はリポジトリをクローンしてローカルインストールしてください。

```bash
git clone https://github.com/hanyahunya/spring-ai-router.git
cd spring-ai-router
./gradlew publishToMavenLocal
```

プロジェクトに追加する場合:

**Gradle**
```groovy
implementation 'com.hanyahunya:spring-ai-router:1.0.0-SNAPSHOT'
```

**Maven**
```xml
<dependency>
    <groupId>com.hanyahunya</groupId>
    <artifactId>spring-ai-router</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### 2. APIキーの設定

`application.yml`
```yaml
spring-ai-router:
  provider: claude      # claude | openai | gemini
  api-key: ${AI_API_KEY}
```

### 3. 既存コントローラーにアノテーションを追加

```java
@RestController
@AIController                          // ← 追加
@RequestMapping("/users")
public class UserController {

    @PostMapping
    @AITool(description = "新しいユーザーを作成します。email と username が必須です。")
    public User createUser(@RequestBody CreateUserRequest req) { ... }

    @GetMapping("/{id}")
    public User getUser(@PathVariable Long id) { ... }

    @DeleteMapping("/{id}")
    @AIHidden                          // ← AIに非公開
    public void deleteUser(@PathVariable Long id) { ... }
}
```

### 4. 自然言語で呼び出す

```bash
curl -X POST https://your-server.com/ai-chat \
  -H "Content-Type: application/json" \
  -d '{"message": "田中太郎として会員登録したい"}'
```

必要なパラメーターが不足している場合、AIが自然言語で確認します。

```json
{"status":"needs_input","sessionId":"abc-123",
 "message":"会員登録にはメールアドレスが必要です。メールアドレスを教えてください。",
 "hint":"同じsessionIdでPOST /ai-chatに再度リクエストしてください。"}
```

同じ `sessionId` で続けて送信すると会話が継続されます。

```bash
curl -X POST https://your-server.com/ai-chat \
  -H "Content-Type: application/json" \
  -d '{"sessionId":"abc-123","message":"tanaka@example.comです"}'
```

パラメーターが揃うと実際のメソッドが呼び出され、結果が返ります。

```json
{"status":"complete","sessionId":"abc-123",
 "message":"Successfully executed createUser.",
 "data":{"id":1,"name":"田中太郎","email":"tanaka@example.com"}}
```

以上です。既存のサーバーは何も変更する必要はありません。

---

## アノテーションリファレンス

### `@AIController`

**対象:** クラス

このコントローラーをAIが呼び出せるToolの集合として登録します。このアノテーションがない場合、クラス内のメソッドはAIから見えません。

```java
@RestController
@AIController
public class ProductController { ... }
```

---

### `@AITool`

**対象:** メソッド  
**属性:** `description` (String)

このメソッドの役割をAIに説明します。省略すると `"GET /パス"` 形式で自動生成されますが、直接記述するほどルーティング精度が向上します。

```java
@GetMapping("/products")
@AITool(description = "商品一覧を取得します。カテゴリと最高価格でフィルタリングできます。")
public List<Product> getProducts(
    @RequestParam String category,
    @RequestParam int maxPrice) { ... }
```

---

### `@AIHidden`

**対象:** メソッド

このメソッドをAI Toolリストから除外します。管理者専用API・内部ユーティリティなどに使用してください。

```java
@DeleteMapping("/users/{id}")
@AIHidden
public void deleteUser(@PathVariable Long id) { ... }
```

---

### `@AIParam`

**対象:** メソッドパラメーター、RequestBodyの内部フィールド

パラメーターの意味をAIが理解しやすくするための説明を追加します。省略可能ですが、enumの制約やフォーマット条件がある場合に特に効果的です。

**メソッドパラメーターへの使用**
```java
@GetMapping("/orders/{id}")
public Order getOrder(
    @PathVariable
    @AIParam("取得する注文の一意なID")
    Long id) { ... }
```

**RequestBodyのフィールドへの使用**
```java
public class CreateOrderRequest {

    @AIParam("注文者のメールアドレス")
    private String email;

    @AIParam("支払い方法。CARD | KAKAO_PAY | TOSS のいずれか。")
    private PaymentMethod paymentMethod;

    @AIParam("配送先住所")
    private String address;
}
```

---

## 設定一覧

```yaml
spring-ai-router:
  enabled: true                  # ライブラリの有効/無効 (デフォルト: true)
  provider: claude               # LLMプロバイダー: claude | openai | gemini (デフォルト: claude)
  api-key: ${AI_API_KEY}         # 必須。対象プロバイダーのAPIキー
  model:                         # 省略時はプロバイダーのデフォルトモデルを使用 (下記参照)

  session:
    ttl-minutes: 30              # セッション保持時間 (分, デフォルト: 30)
    max-turns: 20                # 会話の最大ターン数 (デフォルト: 20)

  limits:
    daily-max: -1                # 1日あたりの最大LLM呼び出し回数。-1は無制限 (デフォルト: -1)
    monthly-max: -1              # 1ヶ月あたりの最大LLM呼び出し回数。-1は無制限 (デフォルト: -1)
```

**プロバイダー別デフォルトモデル**

| provider | デフォルトモデル |
|----------|----------------|
| `claude` | `claude-haiku-4-5-20251001` |
| `openai` | `gpt-4o-mini` |
| `gemini` | `gemini-2.5-flash` |

**デバッグログの有効化 (トークン使用量確認など)**

```yaml
logging:
  level:
    com.hanyahunya.springai.router.llm.ClaudeClient: DEBUG
    com.hanyahunya.springai.router.llm.OpenAIClient: DEBUG
    com.hanyahunya.springai.router.llm.GeminiClient: DEBUG
```

---

## 動作原理

### リクエストフロー

```
クライアント
  POST /ai-chat { "sessionId": "...", "message": "自然言語リクエスト" }
        ↓
  AIChatController
    ├─ 使用量上限チェック
    ├─ セッション履歴の読み込み (SessionStore)
    └─ LLMClient.chat(会話履歴, Toolリスト)
              ↓
         LLM判断
          ├─ TEXT  → パラメーター不足 → needs_input返却、セッション保存
          └─ TOOL_CALL → パラメーター充足 → MethodInvoker.invoke()
                                                  ↓
                                            実際のメソッド実行 (リフレクション)
                                                  ↓
                                            complete返却、セッション削除
```

### レスポンス形式 (NDJSONストリーミング)

`/ai-chat` はNDJSON(Newline-Delimited JSON)ストリーミングで応答します。各行が独立したJSONオブジェクトです。

| status | 発生タイミング | 主なフィールド |
|--------|----------------|----------------|
| `processing` | リクエスト受信直後 | `sessionId`, `message` |
| `needs_input` | パラメーター不足時 | `sessionId`, `message`, `hint` |
| `executing` | メソッド呼び出し直前 | `sessionId`, `message` |
| `complete` | メソッド実行完了後 | `sessionId`, `message`, `data` |
| `error` | エラー発生時 | `sessionId`, `message` |

### プロンプトキャッシング

LLM呼び出しコストを削減するため、プロバイダーごとにプロンプトキャッシングが自動適用されます。

- **Claude:** `cache_control: ephemeral` — systemプロンプト + Toolリストに適用 (TTL 1時間)
- **OpenAI:** `prompt_cache_retention: 24h` — instructions + Toolリストがキャッシュprefixとして機能
- **Gemini:** アプリ起動時に `cachedContents` APIでsystemプロンプト + Toolリストを明示的にキャッシュ作成 (TTL 1時間、期限60秒前に自動更新)

メソッド名・フィールド名のアルファベット順ソートにより、Tool順序を常に固定してキャッシュヒット率を最大化しています。

---

## 拡張ポイント

デフォルト実装はすべてインメモリ(サーバー再起動でリセット)です。対応するインターフェースを実装したBeanを登録すると自動的に置き換えられます。

### SessionStoreの置き換え (Redisなど)

```java
@Bean
public SessionStore redisSessionStore(RedisTemplate<String, Object> redis) {
    return new RedisSessionStore(redis);
}
```

```java
public interface SessionStore {
    List<Message> load(String sessionId);
    void save(String sessionId, List<Message> messages);
    void delete(String sessionId);
}
```

### UsageTrackerの置き換え (DBなど)

```java
@Bean
public UsageTracker dbUsageTracker(UsageRepository repo) {
    return new DbUsageTracker(repo);
}
```

```java
public interface UsageTracker {
    boolean canProceed();    // 上限超過時はfalseを返す
    void record();           // 呼び出し1回を記録
    int getDailyCount();
    int getMonthlyCount();
}
```

### LLMClientの置き換え (カスタムプロバイダー)

`LLMClient` インターフェースを実装してBeanとして登録すると、デフォルトプロバイダーの代わりに使用されます。

```java
@Bean
public LLMClient myCustomLlmClient() {
    return new MyCustomLLMClient();
}
```

---

<a name="한국어"></a>
# 한국어

기존 REST 서버를 그대로 두고, 어노테이션 몇 개만 추가하면 AI Agent · 온디바이스 AI가 자연어로 서버 기능을 호출할 수 있게 해주는 Spring Boot 라이브러리입니다.

MCP 서버를 새로 개발할 리소스 없이도 AI 시대에 바로 올라탈 수 있습니다.

---

## 목차

1. [빠른 시작](#빠른-시작)
2. [어노테이션 레퍼런스](#어노테이션-레퍼런스)
3. [설정 전체 목세](#설정-전체-목세)
4. [동작 원리](#동작-원리)
5. [확장 포인트](#확장-포인트)

---

## 빠른 시작

### 1. 의존성 추가

> **아직 Maven Central에 배포되지 않았습니다.** 현재는 저장소를 클론해서 로컬에 설치해주세요.

```bash
git clone https://github.com/hanyahunya/spring-ai-router.git
cd spring-ai-router
./gradlew publishToMavenLocal
```

프로젝트에 추가:

**Gradle**
```groovy
implementation 'com.hanyahunya:spring-ai-router:1.0.0-SNAPSHOT'
```

**Maven**
```xml
<dependency>
    <groupId>com.hanyahunya</groupId>
    <artifactId>spring-ai-router</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### 2. API 키 설정

`application.yml`
```yaml
spring-ai-router:
  provider: claude      # claude | openai | gemini
  api-key: ${AI_API_KEY}
```

### 3. 기존 컨트롤러에 어노테이션 추가

```java
@RestController
@AIController                          // ← 추가
@RequestMapping("/users")
public class UserController {

    @PostMapping
    @AITool(description = "새 유저를 생성합니다. email과 username이 필수입니다.")
    public User createUser(@RequestBody CreateUserRequest req) { ... }

    @GetMapping("/{id}")
    public User getUser(@PathVariable Long id) { ... }

    @DeleteMapping("/{id}")
    @AIHidden                          // ← AI에게 숨김
    public void deleteUser(@PathVariable Long id) { ... }
}
```

### 4. 자연어로 호출

```bash
curl -X POST https://your-server.com/ai-chat \
  -H "Content-Type: application/json" \
  -d '{"message": "홍길동으로 회원가입하고 싶어"}'
```

파라미터가 부족하면 AI가 자연어로 되물어봅니다.

```json
{"status":"needs_input","sessionId":"abc-123",
 "message":"회원가입을 위해 이메일 주소가 필요합니다. 이메일을 알려주세요.",
 "hint":"같은 sessionId로 POST /ai-chat에 다시 요청해주세요."}
```

같은 `sessionId`로 이어서 보내면 대화가 계속됩니다.

```bash
curl -X POST https://your-server.com/ai-chat \
  -H "Content-Type: application/json" \
  -d '{"sessionId":"abc-123","message":"hong@example.com이야"}'
```

파라미터가 모이면 실제 메서드를 호출하고 결과를 반환합니다.

```json
{"status":"complete","sessionId":"abc-123",
 "message":"Successfully executed createUser.",
 "data":{"id":1,"name":"홍길동","email":"hong@example.com"}}
```

이것으로 끝입니다. 기존 서버는 아무것도 변경하지 않아도 됩니다.

---

## 어노테이션 레퍼런스

### `@AIController`

**위치:** 클래스

이 컨트롤러를 AI가 호출 가능한 Tool 모음으로 등록합니다. 붙이지 않으면 해당 컨트롤러의 메서드는 AI에게 보이지 않습니다.

```java
@RestController
@AIController
public class ProductController { ... }
```

---

### `@AITool`

**위치:** 메서드  
**속성:** `description` (String)

AI에게 이 메서드의 역할을 설명합니다. 생략하면 `"GET /경로"` 형태로 자동 생성되지만, 직접 작성할수록 라우팅 정확도가 높아집니다.

```java
@GetMapping("/products")
@AITool(description = "상품 목록을 조회합니다. 카테고리와 가격 범위로 필터링할 수 있습니다.")
public List<Product> getProducts(
    @RequestParam String category,
    @RequestParam int maxPrice) { ... }
```

---

### `@AIHidden`

**위치:** 메서드

이 메서드를 AI Tool 목록에서 제외합니다. 관리자 전용 API, 내부 유틸리티 메서드 등에 사용하세요.

```java
@DeleteMapping("/users/{id}")
@AIHidden
public void deleteUser(@PathVariable Long id) { ... }
```

---

### `@AIParam`

**위치:** 메서드 파라미터, RequestBody 내부 필드

AI가 파라미터의 의미를 더 잘 이해하도록 설명을 추가합니다. 없어도 필드명으로 자동 유추하지만, enum 제약이나 포맷 조건이 있을 때 특히 효과적입니다.

**메서드 파라미터에 사용**
```java
@GetMapping("/orders/{id}")
public Order getOrder(
    @PathVariable
    @AIParam("조회할 주문의 고유 ID")
    Long id) { ... }
```

**RequestBody 내부 필드에 사용**
```java
public class CreateOrderRequest {

    @AIParam("주문자 이메일")
    private String email;

    @AIParam("결제 수단. CARD | KAKAO_PAY | TOSS 중 하나.")
    private PaymentMethod paymentMethod;

    @AIParam("배송지 주소")
    private String address;
}
```

---

## 설정 전체 목세

```yaml
spring-ai-router:
  enabled: true                  # 라이브러리 활성화 여부 (기본값: true)
  provider: claude               # LLM 공급자: claude | openai | gemini (기본값: claude)
  api-key: ${AI_API_KEY}         # 필수. 해당 공급자의 API 키
  model:                         # 생략 시 공급자별 기본 모델 사용 (아래 참고)

  session:
    ttl-minutes: 30              # 세션 유지 시간 (분, 기본값: 30)
    max-turns: 20                # 대화 최대 턴 수 (기본값: 20)

  limits:
    daily-max: -1                # 일일 최대 LLM 호출 횟수. -1은 무제한 (기본값: -1)
    monthly-max: -1              # 월별 최대 LLM 호출 횟수. -1은 무제한 (기본값: -1)
```

**공급자별 기본 모델**

| provider | 기본 모델 |
|----------|-----------|
| `claude` | `claude-haiku-4-5-20251001` |
| `openai` | `gpt-4o-mini` |
| `gemini` | `gemini-2.5-flash` |

**디버그 로그 활성화 (토큰 사용량 확인 등)**

```yaml
logging:
  level:
    com.hanyahunya.springai.router.llm.ClaudeClient: DEBUG
    com.hanyahunya.springai.router.llm.OpenAIClient: DEBUG
    com.hanyahunya.springai.router.llm.GeminiClient: DEBUG
```

---

## 동작 원리

### 요청 흐름

```
클라이언트
  POST /ai-chat { "sessionId": "...", "message": "자연어 요청" }
        ↓
  AIChatController
    ├─ 사용량 한도 체크
    ├─ 세션 기록 불러오기 (SessionStore)
    └─ LLMClient.chat(대화기록, Tool목록)
              ↓
         LLM 판단
          ├─ TEXT  → 파라미터 부족 → needs_input 반환, 세션 저장
          └─ TOOL_CALL → 파라미터 충족 → MethodInvoker.invoke()
                                              ↓
                                        실제 메서드 실행 (리플렉션)
                                              ↓
                                        complete 반환, 세션 삭제
```

### 응답 형식 (NDJSON 스트리밍)

`/ai-chat`은 NDJSON(Newline-Delimited JSON) 스트리밍으로 응답합니다. 각 줄이 독립된 JSON 객체입니다.

| status | 발생 시점 | 주요 필드 |
|--------|-----------|-----------|
| `processing` | 요청 수신 즉시 | `sessionId`, `message` |
| `needs_input` | 파라미터 부족 시 | `sessionId`, `message`, `hint` |
| `executing` | 메서드 호출 직전 | `sessionId`, `message` |
| `complete` | 메서드 실행 완료 | `sessionId`, `message`, `data` |
| `error` | 오류 발생 시 | `sessionId`, `message` |

### 프롬프트 캐싱

LLM 호출 비용을 줄이기 위해 공급자별로 프롬프트 캐싱이 자동 적용됩니다.

- **Claude:** `cache_control: ephemeral` — system prompt + Tool 목록에 적용 (TTL 1시간)
- **OpenAI:** `prompt_cache_retention: 24h` — instructions + Tool 목록이 캐시 prefix
- **Gemini:** 앱 시작 시 `cachedContents` API로 system prompt + Tool 목록 명시적 캐시 생성 (TTL 1시간, 만료 1분 전 자동 갱신)

Tool 순서가 달라지면 캐시 미스가 발생하므로, 메서드명과 필드명을 알파벳순으로 정렬해 항상 동일한 순서를 보장합니다.

---

## 확장 포인트

기본 구현은 모두 인메모리(서버 재시작 시 초기화)입니다. 인터페이스를 구현한 Bean을 등록하면 자동으로 교체됩니다.

### SessionStore 교체 (Redis 등)

```java
@Bean
public SessionStore redisSessionStore(RedisTemplate<String, Object> redis) {
    return new RedisSessionStore(redis);
}
```

```java
public interface SessionStore {
    List<Message> load(String sessionId);
    void save(String sessionId, List<Message> messages);
    void delete(String sessionId);
}
```

### UsageTracker 교체 (DB 등)

```java
@Bean
public UsageTracker dbUsageTracker(UsageRepository repo) {
    return new DbUsageTracker(repo);
}
```

```java
public interface UsageTracker {
    boolean canProceed();    // 한도 초과 여부 확인
    void record();           // 호출 1회 기록
    int getDailyCount();     // 오늘 호출 횟수
    int getMonthlyCount();   // 이번 달 호출 횟수
}
```

### LLMClient 교체 (커스텀 공급자)

`LLMClient` 인터페이스를 구현하고 Bean으로 등록하면 기본 공급자 대신 사용됩니다.

```java
@Bean
public LLMClient myCustomLlmClient() {
    return new MyCustomLLMClient();
}
```
