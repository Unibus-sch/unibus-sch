import { useEffect, useState } from "react";
import { useLocation, useNavigate } from "react-router";
import svgPaths from "../../imports/svg-l5s7zp6z8c";
import { useLanguage } from "../contexts/LanguageContext";
import { getUnreadNoticeCount } from "../utils/notificationPreferences";

type NavIconName = "home" | "shuttle" | "notice" | "profile";

const NAV_ITEMS = [
  { path: "/home", labelKo: "홈", labelEn: "Home", icon: "home" },
  { path: "/campus-shuttle", labelKo: "셔틀버스", labelEn: "Shuttle", icon: "shuttle" },
  { path: "/commuter-bus", labelKo: "통학버스", labelEn: "Commuter", icon: "shuttle" },
  { path: "/notice", labelKo: "공지", labelEn: "Notice", icon: "notice" },
  { path: "/settings", labelKo: "프로필", labelEn: "Profile", icon: "profile" },
] satisfies Array<{
  path: string;
  labelKo: string;
  labelEn: string;
  icon: NavIconName;
}>;

function NavIcon({ name, active }: { name: NavIconName; active: boolean }) {
  const fill = active ? "var(--unibus-brand)" : "var(--unibus-text-muted)";

  if (name === "home") {
    return (
      <svg aria-hidden="true" className="h-[20px] w-[18px]" fill="none" viewBox="0 0 17.3333 19.5">
        <path d={svgPaths.p39defd40} fill={fill} />
      </svg>
    );
  }

  if (name === "shuttle") {
    return (
      <svg aria-hidden="true" className="h-[21px] w-[18px]" fill="none" viewBox="0 0 17.3333 20.5833">
        <path d={svgPaths.p5662500} fill={fill} />
      </svg>
    );
  }

  if (name === "notice") {
    return (
      <svg aria-hidden="true" className="h-[22px] w-[18px]" fill="none" viewBox="0 0 17.3333 21.6667">
        <path d={svgPaths.p3827a538} fill={fill} />
      </svg>
    );
  }

  return (
    <svg aria-hidden="true" className="size-[18px]" fill="none" viewBox="0 0 17.3333 17.3333">
      <path d={svgPaths.p1c6e17c0} fill={fill} />
    </svg>
  );
}

export default function BottomNav() {
  const navigate = useNavigate();
  const location = useLocation();
  const { t } = useLanguage();
  const [unreadNotices, setUnreadNotices] = useState(() => getUnreadNoticeCount());

  const isActive = (path: string) =>
    location.pathname === path || (path === "/campus-shuttle" && location.pathname === "/shuttle");
  const activeIndex = NAV_ITEMS.findIndex((item) => isActive(item.path));

  useEffect(() => {
    const handleUnread = (event: Event) => {
      const customEvent = event as CustomEvent<{ count: number }>;
      setUnreadNotices(customEvent.detail?.count ?? getUnreadNoticeCount());
    };
    window.addEventListener("unibus:notification-unread", handleUnread);
    return () => window.removeEventListener("unibus:notification-unread", handleUnread);
  }, []);

  return (
    <nav
      aria-label={t("주요 화면", "Primary")}
      className="unibus-bottom-nav relative z-50 mx-auto w-full shrink-0 border-t border-[var(--unibus-divider)] bg-white/90 px-2 pt-2 pb-[max(env(safe-area-inset-bottom),6px)] shadow-[0_-10px_30px_rgba(15,23,42,0.06)] backdrop-blur-xl"
    >
      <div className="relative grid grid-cols-5 gap-1">
        {activeIndex >= 0 ? (
          <span
            aria-hidden="true"
            className="pointer-events-none absolute inset-y-0 left-0 z-0 rounded-2xl border border-[var(--unibus-brand-border)] bg-[var(--unibus-brand-soft)] shadow-[var(--unibus-shadow-soft)] transition-transform duration-200 ease-[var(--unibus-ease-out)] motion-reduce:transition-none"
            style={{
              transform: `translate3d(calc(${activeIndex * 100}% + ${activeIndex * 0.25}rem), 0, 0)`,
              width: "calc((100% - 1rem) / 5)",
            }}
          />
        ) : null}

        {NAV_ITEMS.map((item) => {
          const active = isActive(item.path);
          const label = t(item.labelKo, item.labelEn);

          return (
            <button
              key={item.path}
              type="button"
              aria-current={active ? "page" : undefined}
              aria-label={label}
              onClick={() => navigate(item.path)}
              className="group relative z-10 flex min-h-[56px] min-w-0 flex-col items-center justify-center gap-1 overflow-hidden rounded-2xl px-1 outline-none transition-colors focus-visible:ring-2 focus-visible:ring-[var(--unibus-focus)] focus-visible:ring-offset-1 active:bg-[var(--unibus-brand-soft)]"
            >
              <span
                className={`relative grid h-6 place-items-center transition-transform duration-200 ease-[var(--unibus-ease-out)] motion-reduce:transform-none motion-reduce:transition-none ${
                  active ? "-translate-y-0.5 scale-[1.08]" : "translate-y-0 scale-100"
                }`}
              >
                <NavIcon name={item.icon} active={active} />
                {item.path === "/notice" && unreadNotices > 0 && !active ? (
                  <span
                    aria-hidden="true"
                    className="unibus-nav-dot absolute -right-1 -top-0.5 size-2 rounded-full bg-[#ef4444] ring-2 ring-[var(--unibus-surface)]"
                  />
                ) : null}
              </span>

              <span
                className={`max-w-full truncate text-[10px] leading-4 transition-[color,font-weight] duration-200 ${
                  active ? "font-extrabold text-[var(--unibus-brand)]" : "font-semibold text-[var(--unibus-text-muted)]"
                }`}
              >
                {label}
              </span>
            </button>
          );
        })}
      </div>
    </nav>
  );
}
