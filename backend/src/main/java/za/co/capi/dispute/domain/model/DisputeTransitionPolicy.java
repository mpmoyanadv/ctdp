package za.co.capi.dispute.domain.model;

import za.co.capi.dispute.presentation.exception.ApiException;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Explicit state-machine transition table and role authorization rules for {@link DisputeStatus}.
 *
 * <pre>
 * Current Status           | Allowed Next Statuses
 * -------------------------|-----------------------------------------------------------------------
 * LOGGED                   | IN_REVIEW, PENDING_CUSTOMER_ACTION, PENDING_EXTERNAL_REVIEW, ESCALATED, REJECTED
 * IN_REVIEW                | PENDING_CUSTOMER_ACTION, PENDING_EXTERNAL_REVIEW, ESCALATED, RESOLVED, REFUNDED, REJECTED
 * PENDING_CUSTOMER_ACTION  | IN_REVIEW, ESCALATED, RESOLVED, REFUNDED, REJECTED
 * PENDING_EXTERNAL_REVIEW  | IN_REVIEW, ESCALATED, RESOLVED, REFUNDED, REJECTED
 * ESCALATED                | IN_REVIEW, PENDING_CUSTOMER_ACTION, PENDING_EXTERNAL_REVIEW, RESOLVED, REFUNDED, REJECTED
 * RESOLVED / REFUNDED /    | (terminal — no transitions permitted; REJECTED may be re-opened as a
 * REJECTED                 |  new linked LOGGED dispute by the owning CUSTOMER)
 * </pre>
 */
public final class DisputeTransitionPolicy {

    private static final Map<DisputeStatus, Set<DisputeStatus>> ALLOWED_TRANSITIONS = new EnumMap<>(DisputeStatus.class);

    static {
        ALLOWED_TRANSITIONS.put(DisputeStatus.LOGGED, EnumSet.of(
                DisputeStatus.IN_REVIEW,
                DisputeStatus.PENDING_CUSTOMER_ACTION,
                DisputeStatus.PENDING_EXTERNAL_REVIEW,
                DisputeStatus.ESCALATED,
                DisputeStatus.REJECTED
        ));
        ALLOWED_TRANSITIONS.put(DisputeStatus.IN_REVIEW, EnumSet.of(
                DisputeStatus.PENDING_CUSTOMER_ACTION,
                DisputeStatus.PENDING_EXTERNAL_REVIEW,
                DisputeStatus.ESCALATED,
                DisputeStatus.RESOLVED,
                DisputeStatus.REFUNDED,
                DisputeStatus.REJECTED
        ));
        ALLOWED_TRANSITIONS.put(DisputeStatus.PENDING_CUSTOMER_ACTION, EnumSet.of(
                DisputeStatus.IN_REVIEW,
                DisputeStatus.ESCALATED,
                DisputeStatus.RESOLVED,
                DisputeStatus.REFUNDED,
                DisputeStatus.REJECTED
        ));
        ALLOWED_TRANSITIONS.put(DisputeStatus.PENDING_EXTERNAL_REVIEW, EnumSet.of(
                DisputeStatus.IN_REVIEW,
                DisputeStatus.ESCALATED,
                DisputeStatus.RESOLVED,
                DisputeStatus.REFUNDED,
                DisputeStatus.REJECTED
        ));
        ALLOWED_TRANSITIONS.put(DisputeStatus.ESCALATED, EnumSet.of(
                DisputeStatus.IN_REVIEW,
                DisputeStatus.PENDING_CUSTOMER_ACTION,
                DisputeStatus.PENDING_EXTERNAL_REVIEW,
                DisputeStatus.RESOLVED,
                DisputeStatus.REFUNDED,
                DisputeStatus.REJECTED
        ));
        ALLOWED_TRANSITIONS.put(DisputeStatus.RESOLVED, EnumSet.noneOf(DisputeStatus.class));
        ALLOWED_TRANSITIONS.put(DisputeStatus.REFUNDED, EnumSet.noneOf(DisputeStatus.class));
        ALLOWED_TRANSITIONS.put(DisputeStatus.REJECTED, EnumSet.noneOf(DisputeStatus.class));
    }

    private DisputeTransitionPolicy() {
    }

    public static Set<DisputeStatus> allowedNextStatuses(DisputeStatus current) {
        return ALLOWED_TRANSITIONS.getOrDefault(current, EnumSet.noneOf(DisputeStatus.class));
    }

    public static void validateTransition(DisputeStatus current, DisputeStatus target, UserRole actorRole) {
        if (current == null || target == null) {
            throw ApiException.badRequest("Current and target dispute statuses are required.");
        }
        if (current.isTerminal()) {
            throw ApiException.conflict("Dispute is already in terminal status (" + current + ") and cannot be modified.");
        }
        if (actorRole == UserRole.AFFILIATE) {
            throw ApiException.forbidden("403 Forbidden: External affiliates cannot perform internal status transitions.");
        }
        if (actorRole == UserRole.CUSTOMER && target != DisputeStatus.ESCALATED && target != DisputeStatus.IN_REVIEW) {
            throw ApiException.forbidden("403 Forbidden: Customers cannot directly transition dispute status to " + target + ".");
        }
        if (!allowedNextStatuses(current).contains(target)) {
            throw ApiException.conflict("Invalid status transition from " + current + " to " + target + ".");
        }
    }
}

