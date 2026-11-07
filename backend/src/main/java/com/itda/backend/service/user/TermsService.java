package com.itda.backend.service.user;

import java.util.Collection;
import java.util.List;
import java.util.Map;
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
 * 서비스 약관 동의. 회원은 {@link UserService} 가 읽어 넘긴다 — 여기서 다시 읽지 않는다.
 *
 * <p>보호자 · 기관 담당자 모두 자기 역할의 필수 약관({@link TermsCatalog#required})에 동의해야 한다.
 */
@Service
@RequiredArgsConstructor
public class TermsService {

    private final TermsAgreementRepository termsAgreementRepository;

    /**
     * 동의를 기록한다. 기록할 (약관, 판) 줄이 이미 있으면 아무것도 바꾸지 않는다.
     *
     * <p><b>부르는 쪽이 회원 행을 잠근 채로 불러야 한다</b>({@code UserRepository.findActiveByIdForUpdate}).
     * 같은 회원의 요청이 겹쳐도 두 번째는 첫 번째가 커밋한 줄을 보고 넣지 않는다. UNIQUE 위반을 잡아 성공으로
     * 바꾸지 않는다 — 위반은 flush · 커밋 때 드러날 수 있고, 그 트랜잭션은 정상 커밋을 보장할 수 없다.
     *
     * <p>보낸 약관은 그 역할의 필수 약관과 같아야 한다. 다른 역할의 약관이 섞이거나 하나라도 빠지면 아무것도 쌓지 않고
     * 400 이다. 요청 DTO 는 역할을 몰라 여기서 본다.
     *
     * <p>어느 판으로 기록하나:
     * <ul>
     *   <li>{@code versions} 가 있으면 동의 화면이 읽은 판이다. 키가 보낸 약관과 같아야 하고(아니면 400), 판이 모두
     *       지금 판이어야 한다 — 하나라도 다르면 화면을 연 뒤 판이 바뀐 것이라 아무것도 쌓지 않고
     *       409 TERMS_VERSION_CHANGED. 같으면 그 판(본문 판)으로 기록한다</li>
     *   <li>없으면 본문을 보여 주지 못한 옛 화면이다. 라벨 판({@link TermsCatalog#labelOnlyVersion})으로 기록한다 —
     *       본문을 읽지 않은 동의가 본문 판에 남지 않게</li>
     * </ul>
     *
     * @param versions 약관마다 동의 화면이 읽은 판. 판 없는 요청을 받는 호환 기간에는 {@code null} 일 수 있다
     */
    @Transactional
    public void agree(User user, Collection<TermKey> agreed, Map<TermKey, String> versions) {
        if (!user.isSignupCompleted()) {
            throw new UserException(UserErrorCode.SIGNUP_NOT_COMPLETED);
        }
        Set<TermKey> required = TermsCatalog.required(user.getRole());
        if (!required.containsAll(agreed) || !agreed.containsAll(required)) {
            throw new UserException(UserErrorCode.INVALID_TERMS_AGREEMENT);
        }
        if (versions != null) {
            if (!versions.keySet().equals(required)) {
                throw new UserException(UserErrorCode.INVALID_TERMS_AGREEMENT);
            }
            boolean changed = required.stream()
                    .anyMatch(key -> !TermsCatalog.currentVersion(key).equals(versions.get(key)));
            if (changed) {
                throw new UserException(UserErrorCode.TERMS_VERSION_CHANGED);
            }
        }
        Set<String> existing = termsAgreementRepository.findByUserId(user.getId()).stream()
                .map(agreement -> rowKey(agreement.getTermKey(), agreement.getVersion()))
                .collect(Collectors.toSet());
        List<TermsAgreement> missing = required.stream()
                .map(key -> TermsAgreement.agree(user.getId(), key,
                        versions != null ? TermsCatalog.currentVersion(key) : TermsCatalog.labelOnlyVersion(key)))
                .filter(row -> !existing.contains(rowKey(row.getTermKey(), row.getVersion())))
                .toList();
        termsAgreementRepository.saveAll(missing);
    }

    private static String rowKey(TermKey key, String version) {
        return key.name() + "@" + version;
    }

    /** 가입 미완료면 false, 아니면 동의 기록을 한 번 읽어 그 역할의 필수 약관으로 판정한다. */
    @Transactional(readOnly = true)
    public boolean isAgreed(User user) {
        if (!user.isSignupCompleted()) {
            return false;
        }
        return TermsCatalog.isAgreed(user.getRole(), termsAgreementRepository.findByUserId(user.getId()));
    }
}
