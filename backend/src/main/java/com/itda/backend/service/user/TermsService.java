package com.itda.backend.service.user;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.itda.backend.domain.user.TermKey;
import com.itda.backend.domain.user.TermsAgreement;
import com.itda.backend.domain.user.User;
import com.itda.backend.exception.user.UserErrorCode;
import com.itda.backend.exception.user.UserException;
import com.itda.backend.repository.user.TermsAgreementRepository;

import lombok.RequiredArgsConstructor;

/**
 * 보호자 서비스 약관 동의. 회원은 {@link UserService} 가 읽어 넘긴다 — 여기서 다시 읽지 않는다.
 *
 * <p>기관 담당자에게는 아직 약관이 없어 늘 동의한 것으로 본다.
 */
@Service
@RequiredArgsConstructor
public class TermsService {

    private final TermsAgreementRepository termsAgreementRepository;

    /**
     * 지금 판 줄이 없는 약관만 쌓는다. 같은 판에 다시 동의하면 아무것도 바꾸지 않는다.
     *
     * <p><b>부르는 쪽이 회원 행을 잠근 채로 불러야 한다</b>({@code UserRepository.findActiveByIdForUpdate}).
     * 같은 보호자의 요청이 겹쳐도 두 번째는 첫 번째가 커밋한 줄을 보고 넣지 않는다. UNIQUE 위반을 잡아 성공으로
     * 바꾸지 않는다 — 위반은 flush · 커밋 때 드러날 수 있고, 그 트랜잭션은 정상 커밋을 보장할 수 없다.
     *
     * <p>필수 약관 누락은 요청 DTO 가 400 으로 먼저 막는다. 여기서는 다른 호출 경로를 위한 안전장치다.
     */
    @Transactional
    public void agree(User user, Collection<TermKey> agreed) {
        if (!user.isParent()) {
            throw new UserException(UserErrorCode.PARENT_REQUIRED);
        }
        if (!agreed.containsAll(TermsCatalog.required())) {
            throw new IllegalArgumentException("필수 약관에 모두 동의해야 합니다.");
        }
        Set<TermKey> alreadyCurrent = termsAgreementRepository.findByUserId(user.getId()).stream()
                .filter(agreement -> TermsCatalog.currentVersion(agreement.getTermKey())
                        .equals(agreement.getVersion()))
                .map(TermsAgreement::getTermKey)
                .collect(Collectors.toSet());
        List<TermsAgreement> missing = agreed.stream()
                .distinct()
                .filter(key -> !alreadyCurrent.contains(key))
                .map(key -> TermsAgreement.agree(user.getId(), key, TermsCatalog.currentVersion(key)))
                .toList();
        termsAgreementRepository.saveAll(missing);
    }

    /** 가입 미완료면 false, 기관 담당자면 조회 없이 true, 보호자면 동의 기록을 한 번 읽어 판정한다. */
    @Transactional(readOnly = true)
    public boolean isAgreed(User user) {
        if (!user.isSignupCompleted()) {
            return false;
        }
        if (!user.isParent()) {
            return true;
        }
        return TermsCatalog.isAgreed(termsAgreementRepository.findByUserId(user.getId()));
    }
}
