package com.itda.backend.service.user;

import java.sql.SQLException;
import java.util.Optional;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import com.itda.backend.domain.user.User;
import com.itda.backend.repository.organization.OrganizationRepository;
import com.itda.backend.service.organization.OrganizationCodeIssuer;
import com.itda.backend.repository.user.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class LoginRetryTest {
    private final LoginUserTransactionService transactions = mock(LoginUserTransactionService.class);
    private final UserService service = new UserService(
            mock(UserRepository.class), mock(OrganizationRepository.class), mock(OrganizationCodeIssuer.class),
            transactions, mock(TermsService.class));

    private DataIntegrityViolationException violation(String constraint) {
        return new DataIntegrityViolationException("constraint",
                new ConstraintViolationException("constraint", new SQLException("duplicate", "23505"), constraint));
    }

    @Test
    void duplicateKakaoIdLooksUpTheWinner() {
        User winner = User.pending("same");
        when(transactions.findOrCreate("same")).thenThrow(violation("uk_users_kakao_id"));
        when(transactions.findExisting("same")).thenReturn(Optional.of(winner));

        assertThat(service.findOrCreateByKakaoId("same")).isSameAs(winner);
        verify(transactions).findExisting("same");
    }

    @Test
    void unrelatedConstraintIsNotRetried() {
        DataIntegrityViolationException failure = violation("other_constraint");
        when(transactions.findOrCreate("same")).thenThrow(failure);

        assertThatThrownBy(() -> service.findOrCreateByKakaoId("same")).isSameAs(failure);
        verify(transactions, never()).findExisting(anyString());
    }

    @Test
    void missingWinnerPropagatesTheOriginalFailureWithoutAnotherInsert() {
        DataIntegrityViolationException failure = violation("uk_users_kakao_id");
        when(transactions.findOrCreate("same")).thenThrow(failure);
        when(transactions.findExisting("same")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findOrCreateByKakaoId("same")).isSameAs(failure);
        verify(transactions, times(1)).findOrCreate("same");
    }
}
