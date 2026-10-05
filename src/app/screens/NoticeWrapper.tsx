import { useEffect, useMemo, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { useNavigate } from "react-router";
import { AnimatePresence, motion, useReducedMotion } from "framer-motion";
import { useLanguage } from "../contexts/LanguageContext";
import { api } from "../services/api";
import type { Notice } from "../types";
import { NoticeSkeleton } from "../components/SkeletonLoaders";
import {
  clearUnreadNoticeCount,
  getUnreadNoticeCount,
  setLastNoticeSeenAt,
} from "../utils/notificationPreferences";

type NoticeCategoryFilter = "all" | Notice["category"];

const CATEGORY_FILTERS = [
  { id: "all", labelKo: "전체", labelEn: "All", activeClass: "bg-[#1e3a8a]" },
  { id: "route", labelKo: "운행정보", labelEn: "Route", activeClass: "bg-[#3b82f6]" },
  { id: "general", labelKo: "일반", labelEn: "General", activeClass: "bg-[#10b981]" },
  { id: "system", labelKo: "시스템", labelEn: "System", activeClass: "bg-[#f59e0b]" },
  { id: "lost", labelKo: "분실물", labelEn: "Lost & Found", activeClass: "bg-[#f97316]" },
] satisfies Array<{
  id: NoticeCategoryFilter;
  labelKo: string;
  labelEn: string;
  activeClass: string;
}>;

const CATEGORY_STYLES: Record<Notice["category"], string> = {
  route: "bg-[#3b82f6] text-white",
  system: "bg-[#f59e0b] text-white",
  general: "bg-[#10b981] text-white",
  lost: "bg-[#f97316] text-white",
};

const listContainer = {
  hidden: { opacity: 0 },
  visible: { opacity: 1, transition: { staggerChildren: 0.055, delayChildren: 0.04 } },
};

const listItem = {
  hidden: { opacity: 0, y: 12, scale: 0.992 },
  visible: {
    opacity: 1,
    y: 0,
    scale: 1,
    transition: { type: "spring" as const, stiffness: 380, damping: 30 },
  },
};

export default function NoticeWrapper() {
  const navigate = useNavigate();
  const { t, language } = useLanguage();
  const reduceMotion = useReducedMotion();
  const initialUnreadCount = useRef(getUnreadNoticeCount());
  const lightboxCloseButtonRef = useRef<HTMLButtonElement>(null);
  const [selectedCategory, setSelectedCategory] = useState<NoticeCategoryFilter>("all");
  const [expandedNotice, setExpandedNotice] = useState<string | null>(null);
  const [lightboxImage, setLightboxImage] = useState<string | null>(null);
  const [unreadNoticeIds, setUnreadNoticeIds] = useState<Set<string>>(() => new Set());
  const [notices, setNotices] = useState<Notice[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    void loadNotices();
    clearUnreadNoticeCount();
  }, []);

  useEffect(() => {
    if (!lightboxImage) return;

    const previouslyFocused = document.activeElement instanceof HTMLElement
      ? document.activeElement
      : null;
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") setLightboxImage(null);
    };

    window.addEventListener("keydown", handleKeyDown);
    lightboxCloseButtonRef.current?.focus();

    return () => {
      window.removeEventListener("keydown", handleKeyDown);
      previouslyFocused?.focus();
    };
  }, [lightboxImage]);

  async function loadNotices() {
    try {
      setLoading(true);
      setError(null);
      const data = await api.getNotices();
      setNotices(data);

      if (initialUnreadCount.current > 0) {
        setUnreadNoticeIds(
          new Set(data.slice(0, initialUnreadCount.current).map((notice) => notice.id)),
        );
        initialUnreadCount.current = 0;
      }

      if (data[0]?.createdAt) setLastNoticeSeenAt(data[0].createdAt);
    } catch (err) {
      console.error("Failed to load notices:", err);
      setError(err instanceof Error ? err.message : "공지사항을 불러오지 못했습니다");
    } finally {
      setLoading(false);
    }
  }

  const filteredNotices = useMemo(
    () => selectedCategory === "all"
      ? notices
      : notices.filter((notice) => notice.category === selectedCategory),
    [notices, selectedCategory],
  );

  const getCategoryLabel = (category: Notice["category"]) => {
    const labels: Record<Notice["category"], { ko: string; en: string }> = {
      route: { ko: "운행정보", en: "Route" },
      system: { ko: "시스템", en: "System" },
      general: { ko: "일반", en: "General" },
      lost: { ko: "분실물", en: "Lost & Found" },
    };
    return language === "ko" ? labels[category].ko : labels[category].en;
  };

  const formatDate = (dateString: string) => {
    const date = new Date(dateString);
    return date.toLocaleDateString(language === "ko" ? "ko-KR" : "en-US", {
      year: "numeric",
      month: "short",
      day: "numeric",
    });
  };

  const isNew = (dateString: string) => {
    const date = new Date(dateString);
    const now = new Date();
    const diffInDays = (now.getTime() - date.getTime()) / (1000 * 60 * 60 * 24);
    return diffInDays < 3;
  };

  const selectCategory = (category: NoticeCategoryFilter) => {
    setSelectedCategory(category);
    setExpandedNotice(null);
  };

  const toggleNotice = (noticeId: string) => {
    setExpandedNotice((current) => {
      const next = current === noticeId ? null : noticeId;

      if (next) {
        setUnreadNoticeIds((currentUnread) => {
          if (!currentUnread.has(noticeId)) return currentUnread;
          const nextUnread = new Set(currentUnread);
          nextUnread.delete(noticeId);
          return nextUnread;
        });
      }

      return next;
    });
  };

  return (
    <div className="bg-[#f6f6f8] content-stretch flex flex-col items-start relative size-full">
      <div className="relative flex h-full w-full shrink-0 flex-col items-start overflow-hidden bg-white">
        <div className="sticky top-0 z-30 w-full pt-safe">
          <div className="flex w-full items-center justify-between bg-[rgba(255,255,255,0.9)] px-[16px] pb-[12px] pt-[16px] backdrop-blur-[10px]">
            <motion.button
              type="button"
              onClick={() => navigate("/home")}
              whileTap={reduceMotion ? undefined : { scale: 0.92 }}
              aria-label={t("홈으로 돌아가기", "Back to home")}
              className="flex size-[40px] items-center justify-center rounded-full outline-none transition-colors hover:bg-gray-100 focus-visible:ring-2 focus-visible:ring-[#1e3a8a]/50 focus-visible:ring-offset-2"
            >
              <svg aria-hidden="true" className="h-5 w-3 text-[#0f172a]" fill="none" viewBox="0 0 12 20" stroke="currentColor" strokeWidth="2">
                <path d="M11 1L1 10L11 19" />
              </svg>
            </motion.button>

            <div className="flex flex-col items-center">
              <p className="font-['Public_Sans'] text-[18px] font-bold leading-[22.5px] text-[#0f172a]">
                {t("공지사항", "Notice")}
              </p>
              <p className="font-['Public_Sans'] text-[10px] font-bold uppercase leading-[15px] tracking-[1px] text-[#1e3a8a]">
                {t("업데이트 및 공지", "Updates & Announcements")}
              </p>
            </div>

            <div className="w-[40px]" />
          </div>

          <div
            className="flex gap-2 overflow-x-auto border-b border-[#f1f5f9] px-[16px] py-[12px] scrollbar-hide"
            role="group"
            aria-label={t("공지 카테고리", "Notice category")}
          >
            {CATEGORY_FILTERS.map((filter) => {
              const selected = selectedCategory === filter.id;
              return (
                <motion.button
                  key={filter.id}
                  type="button"
                  aria-pressed={selected}
                  onClick={() => selectCategory(filter.id)}
                  whileTap={reduceMotion ? undefined : { scale: 0.96 }}
                  className={`relative isolate whitespace-nowrap rounded-full px-4 py-2 font-['Public_Sans'] text-[12px] font-semibold outline-none transition-colors focus-visible:ring-2 focus-visible:ring-[#1e3a8a]/50 focus-visible:ring-offset-2 ${
                    selected ? "text-white" : "bg-[#f1f5f9] text-[#64748b] hover:bg-[#e2e8f0]"
                  }`}
                >
                  {selected ? (
                    <motion.span
                      layoutId={reduceMotion ? undefined : "notice-category-pill"}
                      aria-hidden="true"
                      className={`absolute inset-0 -z-10 rounded-full shadow-[0_5px_14px_rgba(15,23,42,0.14)] ${filter.activeClass}`}
                      transition={{ type: "spring", stiffness: 520, damping: 38, mass: 0.75 }}
                    />
                  ) : null}
                  {t(filter.labelKo, filter.labelEn)}
                </motion.button>
              );
            })}
          </div>
        </div>

        <div className="min-h-0 w-full flex-1 space-y-3 overflow-y-auto overscroll-y-contain px-[16px] py-[16px] scrollbar-hide [-webkit-overflow-scrolling:touch]">
          <p className="sr-only" aria-live="polite">
            {t(
              `공지 ${filteredNotices.length}개가 표시됩니다.`,
              `${filteredNotices.length} notices shown.`,
            )}
          </p>

          {loading ? (
            <motion.div
              key="notice-skeleton"
              initial={reduceMotion ? false : { opacity: 0 }}
              animate={{ opacity: 1 }}
              exit={{ opacity: 0 }}
              transition={{ duration: reduceMotion ? 0 : 0.15 }}
            >
              <NoticeSkeleton />
            </motion.div>
          ) : error ? (
            <motion.div
              initial={reduceMotion ? false : { opacity: 0, y: 8 }}
              animate={{ opacity: 1, y: 0 }}
              className="flex flex-col items-center justify-center py-12 text-center"
              role="alert"
            >
              <div className="mb-4 rounded-full bg-[#fee2e2] p-6">
                <svg aria-hidden="true" className="h-12 w-12 text-[#ef4444]" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 9v2m0 4h.01M12 3a9 9 0 100 18A9 9 0 0012 3z" />
                </svg>
              </div>
              <p className="mb-1 font-['Public_Sans'] text-[16px] font-bold text-[#0f172a]">
                {t("불러오기 실패", "Failed to load")}
              </p>
              <p className="mb-4 font-['Public_Sans'] text-[14px] font-normal text-[#94a3b8]">{error}</p>
              <motion.button
                type="button"
                onClick={() => void loadNotices()}
                whileTap={reduceMotion ? undefined : { scale: 0.96 }}
                className="rounded-full bg-[#1e3a8a] px-6 py-2 font-['Public_Sans'] text-[14px] font-semibold text-white outline-none transition-colors hover:bg-[#1e40af] focus-visible:ring-2 focus-visible:ring-[#1e3a8a]/50 focus-visible:ring-offset-2"
              >
                {t("다시 시도", "Retry")}
              </motion.button>
            </motion.div>
          ) : (
            <AnimatePresence mode="wait" initial={false}>
              {filteredNotices.length > 0 ? (
                <motion.div
                  key={selectedCategory}
                  variants={listContainer}
                  initial={reduceMotion ? false : "hidden"}
                  animate="visible"
                  exit={reduceMotion ? undefined : { opacity: 0, y: -4 }}
                  className="space-y-3"
                >
                  {filteredNotices.map((notice) => {
                    const expanded = expandedNotice === notice.id;
                    const unread = unreadNoticeIds.has(notice.id);
                    const triggerId = `notice-trigger-${notice.id}`;
                    const panelId = `notice-panel-${notice.id}`;

                    return (
                      <motion.article
                        key={notice.id}
                        variants={listItem}
                        layout={reduceMotion ? false : "position"}
                        className={`overflow-hidden rounded-[16px] border bg-white shadow-[0_1px_2px_rgba(0,0,0,0.05)] transition-[border-color,box-shadow] hover:shadow-[0_8px_24px_rgba(15,23,42,0.08)] ${
                          unread ? "border-[#bfdbfe]" : "border-[#e2e8f0]"
                        }`}
                      >
                        <motion.button
                          id={triggerId}
                          type="button"
                          aria-expanded={expanded}
                          aria-controls={panelId}
                          onClick={() => toggleNotice(notice.id)}
                          whileTap={reduceMotion ? undefined : { scale: 0.992 }}
                          className="w-full p-[16px] text-left outline-none transition-colors hover:bg-unibus-surface-subtle focus-visible:bg-unibus-surface-subtle focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-[var(--unibus-focus)]"
                        >
                          <span className="flex items-start justify-between gap-3">
                            <span className="min-w-0 flex-1">
                              <span className="mb-1.5 flex flex-wrap items-center gap-2">
                                <span className={`rounded-[5px] px-2 py-1 font-['Public_Sans'] text-[10px] font-bold uppercase ${CATEGORY_STYLES[notice.category]}`}>
                                  {getCategoryLabel(notice.category)}
                                </span>
                                {isNew(notice.createdAt) ? (
                                  <span className="rounded-[5px] bg-[#ef4444] px-2 py-1 font-['Public_Sans'] text-[10px] font-bold uppercase text-white">
                                    NEW
                                  </span>
                                ) : null}
                                <AnimatePresence initial={false}>
                                  {unread ? (
                                    <motion.span
                                      initial={reduceMotion ? false : { opacity: 0, scale: 0.6 }}
                                      animate={{ opacity: 1, scale: 1 }}
                                      exit={{ opacity: 0, scale: 0.6 }}
                                      className="inline-flex items-center gap-1.5 text-[10px] font-bold text-[#1e3a8a]"
                                    >
                                      <span aria-hidden="true" className="size-1.5 rounded-full bg-[#3b82f6]" />
                                      {t("읽지 않음", "Unread")}
                                    </motion.span>
                                  ) : null}
                                </AnimatePresence>
                              </span>
                              <span className="mb-1 block font-['Public_Sans'] text-[16px] font-bold leading-[24px] text-[#0f172a]">
                                {notice.title}
                              </span>
                              <span className="block font-['Public_Sans'] text-[12px] font-normal leading-[16px] text-[#94a3b8]">
                                {formatDate(notice.createdAt)}
                              </span>
                            </span>
                            <motion.span
                              aria-hidden="true"
                              animate={{ rotate: expanded ? 180 : 0, y: expanded ? 1 : 0 }}
                              transition={reduceMotion ? { duration: 0 } : { type: "spring", stiffness: 440, damping: 32 }}
                              className="mt-1 grid size-8 shrink-0 place-items-center rounded-full bg-[#f1f5f9] text-[#64748b]"
                            >
                              <svg className="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 9l-7 7-7-7" />
                              </svg>
                            </motion.span>
                          </span>
                        </motion.button>

                        <AnimatePresence initial={false}>
                          {expanded ? (
                            <motion.div
                              id={panelId}
                              role="region"
                              aria-labelledby={triggerId}
                              initial={reduceMotion ? false : { opacity: 0, height: 0 }}
                              animate={{ opacity: 1, height: "auto" }}
                              exit={{ opacity: 0, height: 0 }}
                              transition={reduceMotion
                                ? { duration: 0 }
                                : {
                                    height: { duration: 0.24, ease: [0.22, 1, 0.36, 1] },
                                    opacity: { duration: 0.18, delay: 0.035 },
                                  }}
                              className="overflow-hidden"
                            >
                              <div className="mx-[16px] border-t border-[#f1f5f9] pb-[16px] pt-[14px]">
                                <p className="whitespace-pre-line font-['Public_Sans'] text-[15px] font-medium leading-[26px] text-[#0f172a]">
                                  {notice.content}
                                </p>
                                {notice.imageUrls && notice.imageUrls.length > 0 ? (
                                  <div className="mt-3 grid grid-cols-2 gap-2">
                                    {notice.imageUrls.map((url, index) => (
                                      <motion.button
                                        key={url}
                                        type="button"
                                        onClick={() => setLightboxImage(url)}
                                        whileTap={reduceMotion ? undefined : { scale: 0.97 }}
                                        className="overflow-hidden rounded-lg border border-[#e2e8f0] outline-none focus-visible:ring-2 focus-visible:ring-[#1e3a8a]/50 focus-visible:ring-offset-2"
                                      >
                                        <img
                                          src={url}
                                          alt={t(`공지 이미지 ${index + 1}`, `Notice image ${index + 1}`)}
                                          loading="lazy"
                                          className="aspect-[4/3] w-full object-cover transition-transform duration-300 hover:scale-[1.025]"
                                        />
                                      </motion.button>
                                    ))}
                                  </div>
                                ) : null}
                                {notice.contentBelow ? (
                                  <p className="mt-3 whitespace-pre-line font-['Public_Sans'] text-[14px] font-normal leading-[22px] text-[#475569]">
                                    {notice.contentBelow}
                                  </p>
                                ) : null}
                              </div>
                            </motion.div>
                          ) : null}
                        </AnimatePresence>
                      </motion.article>
                    );
                  })}
                </motion.div>
              ) : (
                <motion.div
                  key={`empty-${selectedCategory}`}
                  initial={reduceMotion ? false : { opacity: 0, y: 8 }}
                  animate={{ opacity: 1, y: 0 }}
                  className="flex flex-col items-center justify-center py-12 text-center"
                >
                  <div className="mb-4 rounded-full bg-[#f1f5f9] p-6">
                    <svg aria-hidden="true" className="h-12 w-12 text-[#94a3b8]" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M20 13V6a2 2 0 00-2-2H6a2 2 0 00-2 2v7m16 0v5a2 2 0 01-2 2H6a2 2 0 01-2-2v-5m16 0h-2.586a1 1 0 00-.707.293l-2.414 2.414a1 1 0 01-.707.293h-3.172a1 1 0 01-.707-.293l-2.414-2.414A1 1 0 006.586 13H4" />
                    </svg>
                  </div>
                  <p className="mb-1 font-['Public_Sans'] text-[16px] font-bold text-[#0f172a]">
                    {t("공지사항 없음", "No notices")}
                  </p>
                  <p className="font-['Public_Sans'] text-[14px] font-normal text-[#94a3b8]">
                    {t("현재 공지사항이 없습니다", "No notices at the moment")}
                  </p>
                </motion.div>
              )}
            </AnimatePresence>
          )}
        </div>
      </div>

      {createPortal(<AnimatePresence initial={false}>
        {lightboxImage ? (
          <motion.div
            role="dialog"
            aria-modal="true"
            aria-label={t("공지 이미지 확대 보기", "Enlarged notice image")}
            initial={reduceMotion ? false : { opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            transition={{ duration: reduceMotion ? 0 : 0.18 }}
            className="fixed inset-0 z-[100] flex items-center justify-center bg-black/90 p-4 backdrop-blur-sm"
            onClick={() => setLightboxImage(null)}
          >
            <motion.img
              initial={reduceMotion ? false : { opacity: 0, scale: 0.92 }}
              animate={{ opacity: 1, scale: 1 }}
              exit={{ opacity: 0, scale: 0.94 }}
              transition={reduceMotion ? { duration: 0 } : { type: "spring", stiffness: 360, damping: 30 }}
              src={lightboxImage}
              alt={t("확대된 공지 이미지", "Enlarged notice image")}
              className="max-h-full max-w-full rounded-lg object-contain shadow-2xl"
              onClick={(event) => event.stopPropagation()}
            />
            <motion.button
              ref={lightboxCloseButtonRef}
              type="button"
              aria-label={t("이미지 닫기", "Close image")}
              onClick={() => setLightboxImage(null)}
              whileTap={reduceMotion ? undefined : { scale: 0.92 }}
              className="absolute right-4 top-[max(env(safe-area-inset-top),16px)] grid size-11 place-items-center rounded-full bg-black/55 text-white outline-none backdrop-blur-md transition-colors hover:bg-black/75 focus-visible:ring-2 focus-visible:ring-white focus-visible:ring-offset-2 focus-visible:ring-offset-black"
            >
              <svg aria-hidden="true" className="h-6 w-6" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
              </svg>
            </motion.button>
          </motion.div>
        ) : null}
      </AnimatePresence>, document.body)}
    </div>
  );
}
