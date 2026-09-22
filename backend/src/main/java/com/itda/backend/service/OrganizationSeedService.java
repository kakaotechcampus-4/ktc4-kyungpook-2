package com.itda.backend.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.itda.backend.domain.Organization;
import com.itda.backend.domain.OrganizationType;
import com.itda.backend.repository.OrganizationRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 시연에 쓸 기관을 미리 넣어둔다. 기관 생성 API 를 만들지 않기로 했기 때문에,
 * 이 시더가 organization 테이블에 쓰는 유일한 주체다.
 *
 * <p><b>왜 data.sql 이 아니라 Java 인가.</b> 배포(application-docker.yml)는 PostgreSQL 에
 * {@code ddl-auto: update} 라 서버를 다시 띄워도 테이블과 데이터가 남는다. 매번 INSERT 하면
 * 재배포할 때마다 같은 기관이 쌓이고, 이미 그 기관에 붙어 있던 user.organization_id 와 어긋난다.
 * 멱등하게 만들려면 ON CONFLICT(PostgreSQL) / MERGE(H2) 로 문법이 갈리는데,
 * "이미 있나" 를 먼저 보는 방식은 양쪽에서 똑같이 동작한다.
 *
 * <p>행 단위로 검사한다. 전체 건수로 한 번에 판단하면, 나중에 기관을 하나 더 추가했을 때
 * 이미 시드가 들어간 배포 DB 에서는 그 행이 영영 들어가지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrganizationSeedService {

    /** 엔티티가 아니라 템플릿이다 — 매번 새 Organization 을 만들어 저장한다. */
    private record DemoOrganization(String name, OrganizationType type) {
    }

    private static final List<DemoOrganization> DEMO_ORGANIZATIONS = List.of(
            new DemoOrganization("햇살어린이집", OrganizationType.CENTER),
            new DemoOrganization("가온초등학교", OrganizationType.SCHOOL),
            new DemoOrganization("늘봄활동지원센터", OrganizationType.ACTIVITY_SUPPORT));

    private final OrganizationRepository organizationRepository;

    @Transactional
    public void seedDemoOrganizations() {
        for (DemoOrganization demo : DEMO_ORGANIZATIONS) {
            if (organizationRepository.existsByName(demo.name())) {
                continue;
            }
            Organization saved = organizationRepository.save(
                    Organization.of(demo.name(), demo.type()));
            log.info("시연용 기관 추가: id={} name={} type={}",
                    saved.getId(), saved.getName(), saved.getType());
        }
    }
}
