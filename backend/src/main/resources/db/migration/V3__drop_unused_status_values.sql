-- 코드에서 쓰지 않는 상태 값을 허용 값 검사 제약에서 뺀다.
-- journal_entry.status: CONSENT_BLOCKED · REUPLOAD_REQUESTED (이 값으로 바꾸는 코드가 없다)
-- raw_record.status: REVIEW · BLOCKED · FAILED (참조가 없다, 늘 PENDING)
-- 지우는 값을 가진 행이 남아 있으면 ADD CONSTRAINT 가 실패한다. 배포 전에 운영 DB 에서 값별 개수를 확인한다.

ALTER TABLE public.journal_entry DROP CONSTRAINT IF EXISTS journal_entry_status_check;
ALTER TABLE public.journal_entry ADD CONSTRAINT journal_entry_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'MATCHING'::character varying, 'MATCHED'::character varying, 'MATCH_REVIEW'::character varying, 'EXCLUDED'::character varying, 'VALIDATING'::character varying, 'VALIDATED'::character varying, 'VALIDATION_BLOCKED'::character varying, 'VALIDATION_HELD'::character varying, 'SUMMARIZING'::character varying, 'GATE1_PENDING'::character varying, 'GATE1_HELD'::character varying, 'COMPLETED'::character varying, 'SUMMARY_FAILED'::character varying, 'SYSTEM_ERROR'::character varying, 'FAILED'::character varying])::text[])));

ALTER TABLE public.raw_record DROP CONSTRAINT IF EXISTS raw_record_status_check;
ALTER TABLE public.raw_record ADD CONSTRAINT raw_record_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying])::text[])));
