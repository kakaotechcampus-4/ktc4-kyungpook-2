"use client";

import { useState } from "react";
import { Card, EvidenceChip, Note } from "@/components/ui";
import { CHAT_EXAMPLES } from "@/lib/mock/data";
import type { ChatTurn, Child } from "@/lib/types";

export function ChatClient({ childList }: { childList: Child[] }) {
  const [childId, setChildId] = useState(childList[0]?.id ?? "");
  const [question, setQuestion] = useState("");
  const [turns, setTurns] = useState<ChatTurn[]>([CHAT_EXAMPLES[0]]);

  const ask = (q: string) => {
    const hit = CHAT_EXAMPLES.find((t) => t.question === q);
    setTurns((prev) => [...prev, hit ?? { question: q, answer: null, sources: [] }]);
    setQuestion("");
  };

  return (
    <>
      <div className="mb-5 flex flex-wrap items-center gap-2">
        <span className="text-[15px] font-semibold">아이 선택</span>
        {childList.map((c) => (
          <button
            key={c.id}
            onClick={() => setChildId(c.id)}
            className={`tap rounded-full border px-4 text-[15px] font-medium ${
              childId === c.id
                ? "border-accent bg-accentsoft text-accentink"
                : "border-line2 text-ink2 hover:bg-surface2"
            }`}
          >
            {c.name}
          </button>
        ))}
      </div>

      <div className="flex flex-col gap-4">
        {turns.map((t, i) => (
          <div key={i} className="flex flex-col gap-2">
            <p className="self-end rounded-lg rounded-br-none bg-accent px-4 py-2.5 text-[16px] text-white">
              {t.question}
            </p>

            {t.answer ? (
              <Card className="self-start">
                <p className="mb-3 text-[16px] leading-7">{t.answer}</p>
                <ul className="flex flex-wrap gap-2">
                  {t.sources.map((s) => (
                    <li key={s.date + s.label}>
                      <EvidenceChip
                        date={s.date}
                        label={s.label}
                        href={`/children/${childId}`}
                      />
                    </li>
                  ))}
                </ul>
              </Card>
            ) : (
              <div className="self-start rounded border border-line2 bg-surface2 px-4 py-3">
                <p className="text-[16px] leading-7 text-ink2">
                  <span aria-hidden className="mr-1.5 font-semibold text-muted">
                    ⓘ
                  </span>
                  확인된 기록에는 없어요.
                </p>
                <p className="mt-1 text-[14px] text-muted">
                  관련된 승인 기록이 아직 없습니다.
                </p>
              </div>
            )}
          </div>
        ))}
      </div>

      <form
        onSubmit={(e) => {
          e.preventDefault();
          if (question.trim()) ask(question.trim());
        }}
        className="mt-6 flex gap-2"
      >
        <input
          value={question}
          onChange={(e) => setQuestion(e.target.value)}
          placeholder="예) 최근에 식사 거부가 줄었나요?"
          className="tap min-w-0 flex-1 rounded border border-line2 px-3 text-[16px] outline-none focus:border-accent"
        />
        <button className="tap rounded bg-accent px-5 text-[16px] font-semibold text-white hover:bg-accentink">
          보내기
        </button>
      </form>

      <div className="mt-3 flex flex-wrap gap-2">
        {CHAT_EXAMPLES.map((t) => (
          <button
            key={t.question}
            onClick={() => ask(t.question)}
            className="rounded-full border border-line2 px-3 py-1.5 text-[14px] text-ink2 hover:bg-surface2"
          >
            {t.question}
          </button>
        ))}
      </div>

      <div className="mt-5">
        <Note>
          근거가 없는 질문에는 추측하지 않고 “확인된 기록에는 없어요”라고 답합니다. 검증 전
          원본 기록은 검색 대상에서 제외됩니다.
        </Note>
      </div>
    </>
  );
}
