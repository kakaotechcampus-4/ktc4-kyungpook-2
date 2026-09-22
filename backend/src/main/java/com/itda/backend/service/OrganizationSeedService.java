package com.itda.backend.service;

import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

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

    /**
     * 행마다 따로 저장한다. 하나를 트랜잭션으로 묶으면 한 건이 실패할 때 나머지까지 되돌아간다.
     */
    public void seedDemoOrganizations() {
        for (DemoOrganization demo : DEMO_ORGANIZATIONS) {
            seedOne(demo);
        }
    }

    private void seedOne(DemoOrganization demo) {
        if (organizationRepository.existsByName(demo.name())) {
            return;
        }
        try {
            Organization saved = organizationRepository.save(
                    Organization.of(demo.name(), demo.type()));
            log.info("시연용 기관 추가: id={} name={} type={}",
                    saved.getId(), saved.getName(), saved.getType());
        } catch (DataIntegrityViolationException e) {
            /*
             * "있나 보고 없으면 넣는다" 사이에 다른 인스턴스가 먼저 넣은 경우.
             * uk_organization_name 이 막아주므로 중복은 생기지 않았고, 우리가 하려던 일은
             * 이미 이루어졌다. 여기서 예외가 그대로 올라가면 ApplicationRunner 가 실패해
             * 서버가 아예 뜨지 않는다 — 배포가 겹치는 순간 새 인스턴스가 죽는다.
             */
            log.info("시연용 기관 {} 은 이미 있습니다. 건너뜁니다.", demo.name());
        }
    }
}
