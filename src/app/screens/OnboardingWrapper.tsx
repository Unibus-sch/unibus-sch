import { BusFront, ChevronRight, MapPinned, Bell } from "lucide-react";
import { useNavigate } from "react-router";
import { useLanguage } from "../contexts/LanguageContext";

export default function OnboardingWrapper() {
  const navigate = useNavigate();
  const { t } = useLanguage();
  return (
    <div className="font-['Public_Sans'] size-full overflow-y-auto overscroll-y-contain bg-background [-webkit-overflow-scrolling:touch]">
      <div className="mx-auto flex min-h-full max-w-[430px] flex-col px-6 pb-6 pt-safe">
        <header className="flex items-center justify-between py-4">
          <span className="flex items-center gap-2 text-[17px] font-semibold tracking-tight text-unibus-text">
            <BusFront className="size-6 text-unibus-brand" strokeWidth={1.75} aria-hidden="true" /> UNIBUS
          </span>
          <button type="button" onClick={() => navigate("/login")} className="min-h-11 rounded-lg px-2 text-[14px] font-medium text-unibus-brand focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--unibus-focus)]">
            {t("로그인", "Log in")}
          </button>
        </header>
        <main className="flex flex-1 flex-col justify-center py-4">
          <p className="text-[13px] font-medium text-unibus-muted">{t("순천향대학교 버스", "Soonchunhyang University")}</p>
          <h1 className="mt-3 text-[32px] font-semibold leading-[1.3] tracking-[-0.8px] text-unibus-text">
            {t("기다림은 짧게,", "Less waiting,")}<br />{t("이동은 가볍게.", "easier journeys.")}
          </h1>
          <p className="mt-3 text-[15px] leading-6 text-unibus-muted">{t("버스 위치와 도착 시간을 한눈에.", "Bus locations and arrivals at a glance.")}</p>
          <button type="button" onClick={() => navigate("/signup")} className="unibus-primary-button mt-6 w-full">
            {t("시작하기", "Get started")} <ChevronRight className="size-[18px]" aria-hidden="true" />
          </button>
          <div className="unibus-card unibus-route-preview mt-8 px-4 py-5">
            <div className="flex items-center justify-between text-[13px]">
              <span className="font-medium text-unibus-text">{t("학내순환", "Campus shuttle")}</span>
              <span className="text-unibus-muted">{t("정류장 안내", "Stops")}</span>
            </div>
            <div className="relative mt-6 grid grid-cols-5">
              <span aria-hidden="true" className="absolute left-[10%] right-[10%] top-2.5 border-t border-unibus-brand-border" />
              {[t("후문", "Rear"), t("향3", "Hyang 3"), t("향1", "Hyang 1"), t("도서관", "Library"), t("정문", "Main")].map((stop, index) => (
                <div key={stop} className="relative flex min-w-0 flex-col items-center gap-2">
                  <span aria-hidden="true" className={`grid size-5 place-items-center rounded-full ${index === 0 ? "bg-unibus-brand text-unibus-brand-foreground" : "border border-unibus-brand-border bg-unibus-surface"}`}>
                    {index === 0 ? <BusFront className="size-3" /> : <span className="size-1 rounded-full bg-unibus-muted" />}
                  </span>
                  <span className="text-center text-[12px] font-medium text-unibus-muted">{stop}</span>
                </div>
              ))}
            </div>
          </div>
          <div className="mt-5 flex flex-wrap items-center justify-center gap-x-5 gap-y-2 text-[13px] text-unibus-muted">
            <span className="flex items-center gap-1.5"><MapPinned className="size-4" aria-hidden="true" />{t("셔틀 위치", "Shuttle map")}</span>
            <span className="flex items-center gap-1.5"><BusFront className="size-4" aria-hidden="true" />{t("통학 노선", "Commuter routes")}</span>
            <span className="flex items-center gap-1.5"><Bell className="size-4" aria-hidden="true" />{t("운행 공지", "Notices")}</span>
          </div>
        </main>
      </div>
    </div>
  );
}
