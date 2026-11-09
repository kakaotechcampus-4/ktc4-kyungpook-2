#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../../.."
NAME=$1; MODE=${2:-smoke}
set -a; source .env; set +a
UP=$(echo "$NAME" | tr '[:lower:]' '[:upper:]')
url_var="COMPARE_${UP}_URL"; model_var="COMPARE_${UP}_MODEL"; json_var="COMPARE_${UP}_JSON"
URL="${!url_var:?.env 에 $url_var 가 없습니다}"; MODEL="${!model_var:?.env 에 $model_var 가 없습니다}"; JSON="${!json_var:-on}"
for AGENT in MATCHING VALIDATION SUMMARY INSIGHT; do
  export "${AGENT}_API_URL=$URL" "${AGENT}_MODEL=$MODEL" "${AGENT}_JSON_MODE=$JSON"
done
: "${API_KEY:?.env 에 API_KEY 가 없습니다}"
echo "[$NAME] 모델 $MODEL · JSON 강제 $JSON · $MODE"

LOGDIR=evals/generated/real/runs/logs_${NAME}_$(date +%m%d_%H%M); mkdir -p "$LOGDIR"
if [ "$MODE" = smoke ]; then
  if python -u evals/scripts/real/run_real.py --months 04 --school synthetic_school_dev.json ${COMPARE_SMOKE_CHILD:+--child "$COMPARE_SMOKE_CHILD"} > "$LOGDIR/smoke.log" 2>&1; then
    echo "[$NAME] 사전 검증 통과"; grep -h "^요약 \|^인사이트 \|약한 인사이트\|호출 {" "$LOGDIR/smoke.log" || true
  else
    echo "[$NAME] ❌ 사전 검증 실패 — 마지막 줄:"; tail -5 "$LOGDIR/smoke.log"
  fi
  grep -h "LlmUnavailable\|HTTP 4\|400" "$LOGDIR/smoke.log" | head -3 || true
  exit 0
fi

i=0
for mode in off off off refine refine refine; do
  i=$((i+1))
  python -u evals/scripts/real/run_real.py --months 04,05,06 --school synthetic_school_dev.json --weak-mode $mode \
    > "$LOGDIR/run${i}_${mode}.log" 2>&1 &
  echo "[$NAME] $i/6 시작 ($mode)"
  [ $i -lt 6 ] && sleep 65
done
echo "[$NAME] 6개 모두 띄움 · 로그 $LOGDIR"
wait
echo "[$NAME] 끝"
grep -h "정답표\|^요약 \|약한 인사이트" "$LOGDIR"/*.log || true
