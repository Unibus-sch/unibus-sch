import { useEffect, useState } from "react";
import { Bell, BusFront, Home, MapPinned, Settings } from "lucide-react";
import { useLocation, useNavigate } from "react-router";
import { useLanguage } from "../contexts/LanguageContext";
import { getUnreadNoticeCount } from "../utils/notificationPreferences";

const NAV_ITEMS = [
  { path: "/home", labelKo: "홈", labelEn: "Home", Icon: Home },
  { path: "/campus-shuttle", labelKo: "셔틀", labelEn: "Shuttle", Icon: MapPinned },
  { path: "/commuter-bus", labelKo: "통학", labelEn: "Commute", Icon: BusFront },
  { path: "/notice", labelKo: "공지", labelEn: "Notices", Icon: Bell },
  { path: "/settings", labelKo: "설정", labelEn: "Settings", Icon: Settings },
];

export default function BottomNav() {
  const navigate = useNavigate();
  const location = useLocation();
  const { t } = useLanguage();
  const [unreadNotices, setUnreadNotices] = useState(() => getUnreadNoticeCount());

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
      className="unibus-bottom-nav relative z-50 mx-auto w-full shrink-0 border-t border-unibus-divider bg-unibus-surface px-2 pt-1 pb-[max(env(safe-area-inset-bottom),6px)]"
    >
      <div className="grid grid-cols-5">
        {NAV_ITEMS.map(({ path, labelKo, labelEn, Icon }) => {
          const active = location.pathname === path || (path === "/campus-shuttle" && location.pathname === "/shuttle");
          const label = t(labelKo, labelEn);
          return (
            <button
              key={path}
              type="button"
              aria-current={active ? "page" : undefined}
              aria-label={path === "/notice" && unreadNotices > 0 ? t(`공지, 읽지 않은 공지 ${unreadNotices}개`, `Notices, ${unreadNotices} unread`) : label}
              onClick={() => navigate(path)}
              className={`flex min-h-[60px] min-w-0 flex-col items-center justify-center gap-1 rounded-xl px-0 outline-none transition-colors focus-visible:ring-2 focus-visible:ring-[var(--unibus-focus)] ${active ? "text-unibus-brand" : "text-unibus-muted hover:bg-unibus-surface-subtle"}`}
            >
              <span className={`relative grid h-7 w-10 place-items-center rounded-lg transition-colors ${active ? "bg-unibus-brand text-unibus-brand-foreground" : ""}`}>
                <Icon aria-hidden="true" className="size-[21px]" strokeWidth={active ? 2 : 1.75} />
                {path === "/notice" && unreadNotices > 0 ? (
                  <span aria-hidden="true" className="absolute right-1 top-0.5 size-1.5 rounded-full bg-unibus-brand ring-2 ring-unibus-surface" />
                ) : null}
              </span>
              <span className={`max-w-full truncate text-[12px] leading-4 ${active ? "font-semibold" : "font-medium"}`}>{label}</span>
            </button>
          );
        })}
      </div>
    </nav>
  );
}
