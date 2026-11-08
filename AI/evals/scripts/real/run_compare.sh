#!/usr/bin/env bash
# 모델 비교 실험. 스크립트는 레포에, 데이터·결과·로그는 git-ignored 폴더(evals/generated/real/)에 둔다.
#
#   bash evals/scripts/real/run_compare.sh <모델> smoke   # 사전 검증: 4월만 (.env 의 COMPARE_SMOKE_CHILD 한 명이면 2~3분)
#   bash evals/scripts/real/run_compare.sh <모델> full    # 본실행: 딥다이브 전 3 + 후 3 동시 (약 45분)
#
# <모델> = .env 의 COMPARE_<이름> 칸 이름 (luna · opus · sonnet · gemini · sol 등).
# 주소·모델·JSON 강제는 AI/.env 의 COMPARE_<이름>_URL · _MODEL · _JSON 에서 읽는다. 키는 LUNA_API_KEY 하나를 같이 쓴다.
# 결과 파일 이름 시각이 분 단위라, 같은 설정이 같은 분에 시작하면 덮어쓴다 → 본실행은 65초 간격으로 띄운다.
set -euo pipefail
cd "$(dirname "$0")/../../.."          # AI/
NAME=$1; MODE=${2:-smoke}
set -a; source .env; set +a
UP=$(echo "$NAME" | tr '[:lower:]' '[:upper:]')
url_var="COMPARE_${UP}_URL"; model_var="COMPARE_${UP}_MODEL"; json_var="COMPARE_${UP}_JSON"
export LUNA_API_URL="${!url_var:?.env 에 $url_var 가 없습니다}"
export LUNA_MODEL="${!model_var:?.env 에 $model_var 가 없습니다}"
export LUNA_JSON_MODE="${!json_var:-on}"
: "${LUNA_API_KEY:?.env 에 LUNA_API_KEY 가 없습니다}"
echo "[$NAME] 모델 $LUNA_MODEL · JSON 강제 $LUNA_JSON_MODE · $MODE"

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
