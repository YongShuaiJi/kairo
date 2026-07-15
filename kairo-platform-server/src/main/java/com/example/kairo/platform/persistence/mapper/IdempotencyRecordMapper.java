package com.example.kairo.platform.persistence.mapper;

import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.Map;

/**
 * V1.6 acceptance safety: idempotency reservation/in-progress/completed protocol, fenced by
 * a per-reservation {@code ownerToken}. The atomic {@link #insertReservation} (PK collision =>
 * no row) reserves a key for an owning node and stamps its owner token; {@link #completeRecord}
 * and {@link #deleteReservation} only mutate a row whose {@code owner_token} matches, so a stale
 * owner whose lease was reclaimed can never complete or delete the new owner's reservation;
 * {@link #renewLease} is the owner's heartbeat that keeps a live owner's lease from expiring;
 * {@link #reclaimReservation} atomically transfers ownership (old token &rarr; new token) for an
 * expired lease to a same-request waiter; {@link #deleteExpired} race-safely removes an
 * overall-expired row so its primary key can be reused.
 */
public interface IdempotencyRecordMapper {

    /** The active record (IN_PROGRESS or COMPLETED) whose overall expiry hasn't passed, else null. */
    Map<String, Object> findRecord(@Param("idempotencyKey") String idempotencyKey);

    /**
     * Atomically reserve a key in IN_PROGRESS state stamped with the owner's fencing token.
     * Returns 1 on success; a PK violation (duplicate key) surfaces as a
     * {@code DuplicateKeyException} the caller treats as "exists".
     */
    int insertReservation(@Param("idempotencyKey") String idempotencyKey,
                          @Param("actor") String actor,
                          @Param("requestHash") String requestHash,
                          @Param("status") String status,
                          @Param("ownerToken") String ownerToken,
                          @Param("leaseExpiresAt") Timestamp leaseExpiresAt,
                          @Param("createdAt") Timestamp createdAt,
                          @Param("expiresAt") Timestamp expiresAt);

    /**
     * Cache a completed 2xx-4xx result. Only the IN_PROGRESS owner whose {@code ownerToken}
     * matches may complete; a stale owner (reclaimed) updates zero rows.
     */
    int completeRecord(@Param("idempotencyKey") String idempotencyKey,
                       @Param("ownerToken") String ownerToken,
                       @Param("responseStatus") int responseStatus,
                       @Param("responseJson") String responseJson,
                       @Param("updatedAt") Timestamp updatedAt);

    /**
     * Release a 5xx/failed IN_PROGRESS reservation so a retry can re-execute. Only the owner
     * whose {@code ownerToken} matches may release; a stale owner deletes zero rows.
     */
    int deleteReservation(@Param("idempotencyKey") String idempotencyKey,
                          @Param("ownerToken") String ownerToken);

    /**
     * Owner heartbeat: extend the lease while the owner's request is still executing. Only
     * succeeds for the current owner ({@code ownerToken} match, still IN_PROGRESS); returns 0
     * once the owner has completed or lost the reservation.
     */
    int renewLease(@Param("idempotencyKey") String idempotencyKey,
                   @Param("ownerToken") String ownerToken,
                   @Param("leaseExpiresAt") Timestamp leaseExpiresAt);

    /**
     * Atomically reclaim an expired lease for the same request (same actor + hash), transferring
     * ownership to {@code newOwnerToken}. Returns 1 if this caller won the reclaim, 0 if another
     * node reclaimed/completed first or the lease is still live.
     */
    int reclaimReservation(@Param("idempotencyKey") String idempotencyKey,
                           @Param("actor") String actor,
                           @Param("requestHash") String requestHash,
                           @Param("newOwnerToken") String newOwnerToken,
                           @Param("leaseExpiresAt") Timestamp leaseExpiresAt,
                           @Param("now") Timestamp now);

    /**
     * Race-safe cleanup of an overall-expired row (past {@code expires_at}) so its primary key can
     * be reused instead of colliding forever. Returns the number of rows deleted; concurrent
     * cleaners are safe because the DELETE is atomic (only one actually removes the row).
     */
    int deleteExpired(@Param("idempotencyKey") String idempotencyKey,
                       @Param("now") Timestamp now);
}
