package com.itda.backend.service.insight;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.itda.backend.domain.consent.ConsentRevokeReason;
import com.itda.backend.domain.insight.InsightResult;
import com.itda.backend.domain.sharing.ReceiverType;
import com.itda.backend.domain.sharing.SharingHistory;
import com.itda.backend.repository.insight.InsightResultRepository;
import com.itda.backend.repository.sharing.SharingHistoryRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 동의가 꺼지면 이미 내준 인사이트 열람 권한을 거둔다 (#213, {@code AI/insight/CRITERIA.md} §7).
 *
 * <p>Gate 2 가 <b>열람 방식</b>인 이유가 "발송과 달리 철회하면 그 뒤로 안 보인다" 였다 (#206). 권한을
 * 만들기만 하고 거두지 않으면 발송 방식과 다를 게 없다.
 *
 * <p><b>거두는 기준이 두 가지라 섞지 않는다.</b>
 *
 * <table>
 * <tr><td>기관의 동의가 꺼졌다</td><td>그 기관이 <b>받은</b> 권한과, 그 기관이 <b>재료를 낸</b> 인사이트의
 * 기관 권한을 함께 거둔다</td></tr>
 * <tr><td>보호자 연결이 끊겼다</td><td>그 사람의 <b>보호자 권한</b>을 전부 거둔다</td></tr>
 * </table>
 *
 * <p><b>재료 기관 철회가 보호자 권한까지 거두지는 않는다.</b> 철회의 목적은 "내 아이 기록이 <i>다른
 * 기관</i>에 가는 것" 을 막는 것이지 보호자 본인이 자기 아이 이야기를 못 보게 하는 게 아니다. 보호자는
 * 철회한 당사자이기도 하다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InsightAccessRevoker {

    private final InsightResultRepository insightResultRepository;
    private final SharingHistoryRepository sharingHistoryRepository;

    /**
     * 그 기관의 동의가 꺼졌을 때 기관 열람 권한을 거둔다. <b>두 방향을 모두 본다.</b>
     *
     * <table>
     * <tr><td>받는 쪽</td><td>그 기관이 이 아이에 대해 받은 권한. 보호자가 {@code #223} 에서 켠 것이다</td></tr>
     * <tr><td>내는 쪽</td><td>그 기관 기록이 재료로 들어간 인사이트의 기관 권한. 다른 기관이 받고 있다</td></tr>
     * </table>
     *
     * <p><b>받는 쪽을 빠뜨리면 철회가 아무것도 안 한다.</b> 보호자가 켜는 권한은 재료를 내지 않은 기관에만
     * 생기므로 (§9.4, {@code GuardianShareService.shareableInstitutionIds}), 내는 쪽만 보면 정작 그 기관이
     * 들고 있는 권한은 한 건도 안 걸린다. #223 이전에는 {@code ORGANIZATION} 행 자체가 없어 드러나지 않던
     * 구멍이다.
     *
     * <p>부르는 쪽이 {@code child} 행을 잠근 상태여야 한다 (§3.3) — 잠그지 않으면 Gate 2 승인이나 보호자의
     * 기관 선택이 먼저 커밋되고 이 철회가 그 권한을 못 본다.
     */
    @Transactional
    public void revokeForInstitution(Long childId, Long organizationId, ConsentRevokeReason reason,
            LocalDateTime at) {
        List<Long> fedByThem = insightsFedBy(childId, organizationId);
        Stream<SharingHistory> onTheirMaterial = fedByThem.isEmpty() ? Stream.of()
                : sharingHistoryRepository
                        .findByInsightResultIdInAndReceiverTypeAndRevokedAtIsNull(
                                fedByThem, ReceiverType.ORGANIZATION)
                        .stream();
        Stream<SharingHistory> theirs = sharingHistoryRepository
                .findByChildIdAndReceiverTypeAndReceiverIdAndRevokedAtIsNull(
                        childId, ReceiverType.ORGANIZATION, organizationId)
                .stream();

        // 두 방향이 같은 행을 집을 일은 없지만 (재료 기관은 후보에서 빠진다) 겹쳐도 한 번만 센다.
        Map<Long, SharingHistory> revoked = Stream.concat(theirs, onTheirMaterial)
                .collect(Collectors.toMap(SharingHistory::getId, history -> history, (a, b) -> a));
        revoked.values().forEach(history -> history.revoke(reason, at));
        if (!revoked.isEmpty()) {
            log.info("insight access revoked by consent childId={} organizationId={} material={} grants={}",
                    childId, organizationId, fedByThem.size(), revoked.size());
        }
    }

    /** 연결이 끊긴 보호자의 열람 권한을 전부 거둔다. 더 이상 이 아이의 보호자가 아니다. */
    @Transactional
    public void revokeForGuardian(Long childId, Long guardianId, ConsentRevokeReason reason, LocalDateTime at) {
        List<SharingHistory> revoked = sharingHistoryRepository
                .findByChildIdAndReceiverTypeAndReceiverIdAndRevokedAtIsNull(
                        childId, ReceiverType.GUARDIAN, guardianId);
        revoked.forEach(history -> history.revoke(reason, at));
        if (!revoked.isEmpty()) {
            log.info("guardian access revoked childId={} guardianId={} grants={}",
                    childId, guardianId, revoked.size());
        }
    }

    /**
     * 그 기관 기록이 재료로 들어간 인사이트들.
     *
     * <p><b>{@code source_entry_ids} 로 찾으면 안 된다.</b> 교사가 Gate 1 에서 새로 쓴 문장은 일지가 없어
     * 거기 안 잡힌다 (#212). {@code institution_id} 는 {@code source} 와 무관하게 claim 마다 붙으므로
     * 기관 쪽이 빠짐없는 열쇠다.
     *
     * <p>ponytail: {@code source_institution_ids} 가 TEXT JSON 이라 SQL 로 못 거른다. 그 아이 인사이트를
     * 읽어 자바에서 거른다 — 아이 한 명당 주마다 몇 건이라 가볍다. 무거워지면 기관 연결 테이블을 따로 둔다.
     */
    private List<Long> insightsFedBy(Long childId, Long organizationId) {
        return insightResultRepository.findByChildIdInOrderByIdDesc(Set.of(childId)).stream()
                .filter(insight -> PrimarySource.sourceIdsOf(insight).contains(organizationId))
                .map(InsightResult::getId)
                .collect(Collectors.toList());
    }
}
