import { useEffect, useMemo, useRef } from "react";
import { createPortal } from "react-dom";
import { AnimatePresence, motion } from "framer-motion";
import { ExternalLink, TrainFront, X, Zap } from "lucide-react";
import {
  SINCHANG_TRAIN_TIMETABLE,
  type TrainServiceDay,
} from "../data/sinchangTrainTimetable";

interface SinchangTimetableSheetProps {
  open: boolean;
  serviceDay: TrainServiceDay;
  onServiceDayChange: (day: TrainServiceDay) => void;
  onClose: () => void;
}

const dayOptions: Array<{ key: TrainServiceDay; label: string }> = [
  { key: "weekday", label: "평일" },
  { key: "holiday", label: "토·공휴일" },
];

export default function SinchangTimetableSheet({
  open,
  serviceDay,
  onServiceDayChange,
  onClose,
}: SinchangTimetableSheetProps) {
  const scheduleScrollRef = useRef<HTMLDivElement>(null);
  const currentHourRef = useRef<HTMLDivElement>(null);
  const groupedSchedule = useMemo(() => {
    const groups = new Map<string, typeof SINCHANG_TRAIN_TIMETABLE.weekday>();
    SINCHANG_TRAIN_TIMETABLE[serviceDay].forEach((train) => {
      const hour = train.time.slice(0, 2);
      const trains = groups.get(hour) ?? [];
      trains.push(train);
      groups.set(hour, trains);
    });
    return [...groups.entries()];
  }, [serviceDay]);
  const currentHour = String(new Date().getHours()).padStart(2, "0");

  useEffect(() => {
    if (!open) return;
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") onClose();
    };
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    document.addEventListener("keydown", handleKeyDown);
    return () => {
      document.body.style.overflow = previousOverflow;
      document.removeEventListener("keydown", handleKeyDown);
    };
  }, [onClose, open]);

  useEffect(() => {
    if (!open) return;
    const frame = requestAnimationFrame(() => {
      const container = scheduleScrollRef.current;
      const target = currentHourRef.current;
      if (container && target) {
        const targetOffset = target.getBoundingClientRect().top - container.getBoundingClientRect().top;
        container.scrollTop = Math.max(0, container.scrollTop + targetOffset - 16);
      }
    });
    return () => cancelAnimationFrame(frame);
  }, [open, serviceDay]);

  return createPortal(
    <AnimatePresence>
      {open ? (
        <motion.div
          className="fixed inset-0 z-[100] flex items-end justify-center bg-black/35 px-0 backdrop-blur-[2px] sm:px-4"
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          exit={{ opacity: 0 }}
          onClick={onClose}
        >
          <motion.section
            role="dialog"
            aria-modal="true"
            aria-labelledby="sinchang-timetable-title"
            className="unibus-sheet flex max-h-[88dvh] w-full max-w-[430px] flex-col overflow-hidden rounded-t-[24px] bg-unibus-surface sm:rounded-[24px] sm:mb-4"
            initial={{ y: "100%" }}
            animate={{ y: 0 }}
            exit={{ y: "100%" }}
            transition={{ type: "spring", stiffness: 360, damping: 34 }}
            onClick={(event) => event.stopPropagation()}
          >
            <div className="flex h-8 shrink-0 items-center justify-center">
              <div className="h-1 w-10 rounded-full bg-unibus-divider" />
            </div>

            <header className="flex items-start justify-between px-5 pb-4">
              <div className="flex items-center gap-3">
                <span className="grid size-9 shrink-0 place-items-center text-unibus-muted">
                  <TrainFront className="size-5" aria-hidden="true" />
                </span>
                <div>
                  <h2 id="sinchang-timetable-title" className="text-[19px] font-semibold leading-6 text-unibus-text">
                    신창역 출발 시간표
                  </h2>
                  <p className="mt-0.5 text-[13px] font-normal text-unibus-muted">1호선 · 서울 방면</p>
                </div>
              </div>
              <button
                type="button"
                aria-label="시간표 닫기"
                onClick={onClose}
                className="grid size-10 place-items-center rounded-full bg-unibus-surface-subtle text-unibus-muted transition-colors hover:bg-unibus-divider"
              >
                <X className="size-5" aria-hidden="true" />
              </button>
            </header>

            <div className="px-5 pb-4">
              <div className="grid grid-cols-2 rounded-xl bg-unibus-surface-subtle p-1">
                {dayOptions.map((option) => (
                  <button
                    key={option.key}
                    type="button"
                    aria-pressed={serviceDay === option.key}
                    onClick={() => onServiceDayChange(option.key)}
                    className={`h-10 rounded-lg text-[13px] font-semibold transition-all ${
                      serviceDay === option.key
                        ? "bg-unibus-surface text-unibus-brand "
                        : "text-unibus-muted"
                    }`}
                  >
                    {option.label}
                  </button>
                ))}
              </div>
            </div>

            <div ref={scheduleScrollRef} className="min-h-0 flex-1 overflow-y-auto overscroll-contain border-t border-unibus-divider px-5 py-4">
              <div className="space-y-5">
                {groupedSchedule.map(([hour, trains]) => (
                  <div
                    key={hour}
                    ref={hour === currentHour ? currentHourRef : undefined}
                    className="grid scroll-mt-4 grid-cols-[42px_1fr] gap-3"
                  >
                    <p className="pt-1 text-[15px] font-bold text-unibus-brand">{hour}시</p>
                    <div className="grid grid-cols-1 gap-2 min-[380px]:grid-cols-2">
                      {trains.map((train) => (
                        <div
                          key={`${train.time}-${train.destination}`}
                          className="flex min-h-12 items-center justify-between rounded-lg border border-unibus-divider bg-unibus-surface px-3 py-2"
                        >
                          <div>
                            <p className="text-[15px] font-bold tabular-nums text-unibus-text">{train.time}</p>
                            <p className="text-[13px] font-normal text-unibus-muted">{train.destination}행</p>
                          </div>
                          {train.express ? (
                            <span className="flex items-center gap-0.5 rounded bg-unibus-surface-subtle px-1.5 py-1 text-[12px] font-medium text-unibus-muted">
                              <Zap className="size-2.5" aria-hidden="true" /> 급행
                            </span>
                          ) : null}
                        </div>
                      ))}
                    </div>
                  </div>
                ))}
              </div>
            </div>

            <footer className="border-t border-unibus-divider bg-unibus-surface-subtle px-5 pb-[calc(16px+env(safe-area-inset-bottom))] pt-3">
              <div className="flex flex-wrap items-center justify-between gap-3">
                <p className="text-[13px] font-semibold leading-4 text-unibus-muted">
                  2026년 7월 공개 시간표 기준<br />운행 전 코레일에서 다시 확인해 주세요.
                </p>
                <a
                  href="https://www.letskorail.com/"
                  target="_blank"
                  rel="noreferrer"
                  className="flex shrink-0 items-center gap-1 rounded-lg bg-unibus-surface px-3 py-2 text-[13px] font-semibold text-unibus-brand"
                >
                  코레일 확인 <ExternalLink className="size-3" aria-hidden="true" />
                </a>
              </div>
            </footer>
          </motion.section>
        </motion.div>
      ) : null}
    </AnimatePresence>,
    document.body,
  );
}
