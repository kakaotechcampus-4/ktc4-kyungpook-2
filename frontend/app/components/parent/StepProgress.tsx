export function StepProgress({ step, total }: { step: number; total: number }) {
  return (
    <div className="mb-1">
      <p className="mb-2 text-[12px] font-bold tracking-[0.15em] text-muted">
        STEP {step} / {total}
      </p>
      <div className="flex gap-1.5">
        {Array.from({ length: total }, (_, i) => (
          <span
            key={i}
            className={`h-1 flex-1 rounded-full ${i < step ? "bg-accent" : "bg-surface2"}`}
          />
        ))}
      </div>
    </div>
  );
}
