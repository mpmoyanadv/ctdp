package za.co.capi.dispute;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import za.co.capi.dispute.domain.model.DisputeStatus;
import za.co.capi.dispute.domain.model.DisputeTransitionPolicy;
import za.co.capi.dispute.domain.model.UserRole;
import za.co.capi.dispute.presentation.exception.ApiException;

import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DisputeTransitionPolicyTest {

    static Stream<Arguments> transitionTableRows() {
        return Stream.of(
                Arguments.of(DisputeStatus.LOGGED, EnumSet.of(
                        DisputeStatus.IN_REVIEW,
                        DisputeStatus.PENDING_CUSTOMER_ACTION,
                        DisputeStatus.PENDING_EXTERNAL_REVIEW,
                        DisputeStatus.ESCALATED,
                        DisputeStatus.REJECTED
                )),
                Arguments.of(DisputeStatus.IN_REVIEW, EnumSet.of(
                        DisputeStatus.PENDING_CUSTOMER_ACTION,
                        DisputeStatus.PENDING_EXTERNAL_REVIEW,
                        DisputeStatus.ESCALATED,
                        DisputeStatus.RESOLVED,
                        DisputeStatus.REFUNDED,
                        DisputeStatus.REJECTED
                )),
                Arguments.of(DisputeStatus.PENDING_CUSTOMER_ACTION, EnumSet.of(
                        DisputeStatus.IN_REVIEW,
                        DisputeStatus.ESCALATED,
                        DisputeStatus.RESOLVED,
                        DisputeStatus.REFUNDED,
                        DisputeStatus.REJECTED
                )),
                Arguments.of(DisputeStatus.PENDING_EXTERNAL_REVIEW, EnumSet.of(
                        DisputeStatus.IN_REVIEW,
                        DisputeStatus.ESCALATED,
                        DisputeStatus.RESOLVED,
                        DisputeStatus.REFUNDED,
                        DisputeStatus.REJECTED
                )),
                Arguments.of(DisputeStatus.ESCALATED, EnumSet.of(
                        DisputeStatus.IN_REVIEW,
                        DisputeStatus.PENDING_CUSTOMER_ACTION,
                        DisputeStatus.PENDING_EXTERNAL_REVIEW,
                        DisputeStatus.RESOLVED,
                        DisputeStatus.REFUNDED,
                        DisputeStatus.REJECTED
                )),
                Arguments.of(DisputeStatus.RESOLVED, EnumSet.noneOf(DisputeStatus.class)),
                Arguments.of(DisputeStatus.REFUNDED, EnumSet.noneOf(DisputeStatus.class)),
                Arguments.of(DisputeStatus.REJECTED, EnumSet.noneOf(DisputeStatus.class))
        );
    }

    @ParameterizedTest
    @MethodSource("transitionTableRows")
    void enforcesEveryRowOfStateTransitionTable(DisputeStatus source, Set<DisputeStatus> expectedAllowed) {
        assertThat(DisputeTransitionPolicy.allowedNextStatuses(source)).isEqualTo(expectedAllowed);

        for (DisputeStatus target : DisputeStatus.values()) {
            if (expectedAllowed.contains(target)) {
                assertThatCode(() -> DisputeTransitionPolicy.validateTransition(source, target, UserRole.STAFF))
                        .doesNotThrowAnyException();
                assertThatCode(() -> DisputeTransitionPolicy.validateTransition(source, target, UserRole.ADMIN))
                        .doesNotThrowAnyException();
            } else {
                assertThatThrownBy(() -> DisputeTransitionPolicy.validateTransition(source, target, UserRole.STAFF))
                        .isInstanceOf(ApiException.class)
                        .satisfies(ex -> assertThat(((ApiException) ex).getStatus().value()).isEqualTo(409));
            }
        }
    }

    @Test
    void enforcesRoleConstraintsAndNullChecksAcrossTransitions() {
        assertThatThrownBy(() -> DisputeTransitionPolicy.validateTransition(null, DisputeStatus.IN_REVIEW, UserRole.STAFF))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus().value()).isEqualTo(400));

        assertThatThrownBy(() -> DisputeTransitionPolicy.validateTransition(DisputeStatus.LOGGED, null, UserRole.STAFF))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus().value()).isEqualTo(400));

        // AFFILIATE cannot perform status transitions from any non-terminal state
        for (DisputeStatus target : DisputeStatus.values()) {
            assertThatThrownBy(() -> DisputeTransitionPolicy.validateTransition(DisputeStatus.PENDING_EXTERNAL_REVIEW, target, UserRole.AFFILIATE))
                    .isInstanceOf(ApiException.class)
                    .satisfies(ex -> assertThat(((ApiException) ex).getStatus().value()).isEqualTo(403));
        }

        // CUSTOMER may only escalate or return PENDING_CUSTOMER_ACTION -> IN_REVIEW
        assertThatCode(() -> DisputeTransitionPolicy.validateTransition(DisputeStatus.IN_REVIEW, DisputeStatus.ESCALATED, UserRole.CUSTOMER))
                .doesNotThrowAnyException();
        assertThatCode(() -> DisputeTransitionPolicy.validateTransition(DisputeStatus.PENDING_CUSTOMER_ACTION, DisputeStatus.IN_REVIEW, UserRole.CUSTOMER))
                .doesNotThrowAnyException();

        for (DisputeStatus forbiddenTarget : EnumSet.of(
                DisputeStatus.PENDING_CUSTOMER_ACTION,
                DisputeStatus.PENDING_EXTERNAL_REVIEW,
                DisputeStatus.RESOLVED,
                DisputeStatus.REFUNDED,
                DisputeStatus.REJECTED)) {
            assertThatThrownBy(() -> DisputeTransitionPolicy.validateTransition(DisputeStatus.IN_REVIEW, forbiddenTarget, UserRole.CUSTOMER))
                    .isInstanceOf(ApiException.class)
                    .satisfies(ex -> assertThat(((ApiException) ex).getStatus().value()).isEqualTo(403));
        }
    }
}
