-- enum 컬럼의 허용 값 검사 제약을 엔티티와 같게 맞춘다.
-- ddl-auto: update 는 이 제약을 처음 만든 뒤 고치지 않아, 운영 DB 에는 값이 빠진 제약이 남거나 제약이 아예 없었다.
-- 빠진 값: journal_entry.failure_reason 의 CONTENT_TOO_LONG, failure_stage 의 MATCHING, validation_result.verdict 의 FAILED.
-- 지우고 다시 만들므로 V1 으로 새로 만든 DB 에서도 그대로 돈다.

ALTER TABLE public.human_review DROP CONSTRAINT IF EXISTS human_review_decision_check;
ALTER TABLE public.human_review ADD CONSTRAINT human_review_decision_check CHECK (((decision)::text = ANY ((ARRAY['APPROVED'::character varying, 'CORRECTED'::character varying, 'HOLD'::character varying, 'REMOVED'::character varying, 'REOPENED'::character varying, 'CONFIRMED'::character varying, 'REVERTED'::character varying])::text[])));

ALTER TABLE public.journal_entry DROP CONSTRAINT IF EXISTS journal_entry_failure_reason_check;
ALTER TABLE public.journal_entry ADD CONSTRAINT journal_entry_failure_reason_check CHECK (((failure_reason)::text = ANY ((ARRAY['REQUEST_REJECTED'::character varying, 'RESPONSE_UNREADABLE'::character varying, 'EVIDENCE_MISMATCH'::character varying, 'AGENT_UNAVAILABLE'::character varying, 'CONTENT_TOO_LONG'::character varying, 'INTERNAL_ERROR'::character varying])::text[])));

ALTER TABLE public.journal_entry DROP CONSTRAINT IF EXISTS journal_entry_failure_stage_check;
ALTER TABLE public.journal_entry ADD CONSTRAINT journal_entry_failure_stage_check CHECK (((failure_stage)::text = ANY ((ARRAY['MATCHING'::character varying, 'VALIDATION'::character varying, 'SUMMARY'::character varying])::text[])));

ALTER TABLE public.journal_entry DROP CONSTRAINT IF EXISTS journal_entry_status_check;
ALTER TABLE public.journal_entry ADD CONSTRAINT journal_entry_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'MATCHING'::character varying, 'MATCHED'::character varying, 'MATCH_REVIEW'::character varying, 'EXCLUDED'::character varying, 'CONSENT_BLOCKED'::character varying, 'VALIDATING'::character varying, 'VALIDATED'::character varying, 'VALIDATION_BLOCKED'::character varying, 'REUPLOAD_REQUESTED'::character varying, 'VALIDATION_HELD'::character varying, 'SUMMARIZING'::character varying, 'GATE1_PENDING'::character varying, 'GATE1_HELD'::character varying, 'COMPLETED'::character varying, 'SUMMARY_FAILED'::character varying, 'SYSTEM_ERROR'::character varying, 'FAILED'::character varying])::text[])));

ALTER TABLE public.summary_result DROP CONSTRAINT IF EXISTS summary_result_status_check;
ALTER TABLE public.summary_result ADD CONSTRAINT summary_result_status_check CHECK (((status)::text = ANY ((ARRAY['GENERATED'::character varying, 'GATE1_PENDING'::character varying, 'APPROVED'::character varying, 'CONFIRMED'::character varying, 'REVERTED'::character varying, 'HOLD'::character varying, 'REMOVED'::character varying])::text[])));

ALTER TABLE public.terms_agreement DROP CONSTRAINT IF EXISTS terms_agreement_term_key_check;
ALTER TABLE public.terms_agreement ADD CONSTRAINT terms_agreement_term_key_check CHECK (((term_key)::text = ANY ((ARRAY['TOS'::character varying, 'PRIVACY'::character varying, 'ORG_TOS'::character varying, 'ORG_PRIVACY'::character varying, 'ORG_CHILD_DATA'::character varying])::text[])));

ALTER TABLE public.validation_result DROP CONSTRAINT IF EXISTS validation_result_verdict_check;
ALTER TABLE public.validation_result ADD CONSTRAINT validation_result_verdict_check CHECK (((verdict)::text = ANY ((ARRAY['PASS'::character varying, 'REVIEW'::character varying, 'BLOCK'::character varying, 'FAILED'::character varying])::text[])));
