# NOTES

## What I changed

The feature is a per-user redemption limit for each campaign. The default limit is 2, and a user who goes over it is rejected with a clear reason.

### Schema: `schema.sql`
- Added `max_redemptions_per_user INT NOT NULL DEFAULT 2` to the `campaign` table. Existing (seeded) campaigns get the default of 2 without any change to `data.sql`.
- **Why:**
- This is the mandotary step, but i do not did a best practice for production level. The best practice is create another sql file called `db_migration_[current_date].sql`, when any db update developer need to mannual write a alter tablesql file, cant indepent the ddl-auto update even in development environment.

### Entity: `Campaign.java`
- Added a `maxRedemptionsPerUser` field, with a getter and setter, mapped to the new column.
- **Why:**
- Another method also can use `Lombak` like `@Data`, `@AllArgsConstructor`, so we no need to put in contructor, getter and setter.

### Repositories
- `RedemptionRepository`: added `countByUserIdAndCampaignId(userId, campaignId)`, which counts how many times a user has redeemed in a campaign.
- `VoucherRepository`: added `findByCodeForUpdate(code)`, which takes a pessimistic write lock (`SELECT ... FOR UPDATE`) on the voucher row.
- `CampaignRepository`: added `findByIdForUpdate(id)`, which takes a pessimistic write lock on the campaign row.

### Service: `VoucherService.redeem()`
- Wrapped `redeem()` in `@Transactional`, so the whole check-then-write runs as one transaction.
- The voucher row is locked first, then the campaign row, always in that order.
- Added the limit check: if `used >= campaign.maxRedemptionsPerUser`, a `VoucherLimitReachException` is thrown with the message `User has reached the redemption limit for this campaign (N)`.
- A redemption whose voucher was later voided still counts toward the limit (every row in `redemption` is counted).
- The audit call now runs only after the transaction commits (via `TransactionSynchronization.afterCommit`), not inside it.
- **Why:**
- Add `@Transaction`, if the `.redeem()` method is failed, it will be rolled back, dont have dirty data in database.

### Error handling
- New `exception/VoucherLimitReachException`.
- New `advice/GlobalExceptionHandler`, which maps `VoucherLimitReachException` to **HTTP 409 Conflict** with `{"message": "..."}`.
- `VoucherController.redeem()` now returns `ResponseEntity<RedeemResponse>`.
- `RedeemResponse` has a new `errorCode` field and a `fail(errorCode, message)` overload. These are not used yet.
- Other failures, such as an unknown, redeemed or void voucher, still return HTTP 200 with `result: FAILED`, as before.
- **Why:**
- Can let the code more clean, use `@RestControllerAdvice` can handle more special case with a block of code, can just plugin in the usecase. Also easy for project scale up.

### Config: `application.yml`
- Enabled the H2 console for local debugging.
- **Why:**
- For developemt is easy to check database data, in production we can use `application-[env].yml` to control, each environment have it own configuration.

### Tests
- `VoucherServiceTest`: renamed the existing tests to `methodName_condition_expectedResult` and gave each a `@DisplayName`. Added `@Transactional` so each test rolls back and tests don't affect each other. New tests:
  - `redeem_userBelowLimit_returnsOk`
  - `redeem_userAtLimit_throwsVoucherLimitReachException`
  - `redeem_userAtLimit_doesNotChangeStockOrVoucher`
  - `redeem_userWithVoidedRedemption_countsTowardLimit`
  - `redeem_userAtLimitInOtherCampaign_returnsOk`
  - `redeem_campaignWithCustomLimit_enforcesCustomLimit`
  - `redeem_campaignWithoutExplicitLimit_defaultsToTwo`
- `RedemptionConcurrencyTest` (new): `redeem_parallelRequestsBySameUser_onlyLimitSucceed`. Three threads redeem different vouchers for the same user at the same moment, and exactly 2 succeed.
- `VoucherControllerTest` (new, `@WebMvcTest`): `redeem_validRequest_returns200WithOkResult` and `redeem_userAtLimit_returns409WithMessage`.
- Result: all 13 tests pass (`./gradlew test`).
- **Why:**

## Noticed but deliberately did not change

- **Hardcoded audit API key** in `AuditClient` (`API_KEY` constant), committed to source and sent in the request payload.
  - **Why not changed:**
- **Audit failures are logged without the exception** (`log.error("audit call failed")`), and the HTTP call has no timeout.
  - **Why not changed:**
- **`voidVoucher` doesn't restore campaign stock**, and it can void a voucher that is already redeemed.
  - **Why not changed:**
- **N+1 query in `CampaignStatsService`**: one redemption query per voucher. Its "redeemed" count is based on redemption rows, so voided vouchers are counted too.
  - **Why not changed:**
- **`findById(...).get()`** gives an unhandled `NoSuchElementException` and an HTTP 500 when a campaign is missing.
  - **Why not changed:**
- **Stock doesn't match the vouchers**: campaign 1 has `remaining_stock = 100` but only 6 vouchers. Stock is tracked separately instead of being derived from the vouchers.
  - **Why not changed:**
- **Failure responses are inconsistent**: most failures return HTTP 200 with `result: FAILED`, while the new limit rejection returns 409.
  - **Why not changed:**
- **Status values are magic strings** (`"ACTIVE"`, `"REDEEMED"`, `"VOID"`) instead of an enum.
  - **Why not changed:**
- **No migration tool**: `schema.sql` drops and recreates every table on startup, so there is no Flyway or Liquibase.
  - **Why not changed:**
- **Mixed dependency injection**: field `@Autowired` in some classes, constructor injection in others.
  - **Why not changed:**

## What I'd do next with another day

- Return the same error format for all redemption failures, using the `errorCode` field that is already added (for example `USER_LIMIT_REACHED`) and proper HTTP status codes.
- Decide with the product team whether voided redemptions should count toward the limit, and make it configurable if needed.
- Move the audit API key to configuration or a secret store, rotate it, and add a timeout and proper error logging to the audit call.
- Send audit events through a transactional outbox, so none are lost if the audit service is down.
- Replace `schema.sql` with Flyway migrations, so the new column is added with `ALTER TABLE` instead of a drop and recreate.
- Make the service ready to run on several instances: a shared database (for example Postgres) instead of in-memory H2 per instance, and migrations that run once.
- Add an index on `redemption(campaign_id, user_id)` for the limit count query.
- Let campaigns set the limit through an API, with validation (limit >= 1).
- Fix `voidVoucher` (restore stock, block voiding a redeemed voucher) and the stats N+1 query.
- API documentation with OpenAPI/ Swagger API

## Video
- The video i will change to a pull request (so can see where i did modify).