import { useState, useEffect, useMemo, useRef } from "react";
import { AnimatePresence, motion, useReducedMotion } from "framer-motion";
import { useNavigate } from "react-router";
import RouteMapModal from "../components/RouteMapModal";
import { useLanguage } from "../contexts/LanguageContext";
import { api } from "../services/api";
import { parseDurationMinutes, simulateCommuterBus } from "../utils/commuterSimulation";

interface RouteBusInfo {
  position: { lat: number; lng: number };
  etaMins: number;
  heading?: number;
  isSimulation?: boolean;
}



function openPayco() {
  const ua = navigator.userAgent;
  window.location.href = "payco://";
  setTimeout(() => {
    if (/iPhone|iPad/i.test(ua)) {
      window.location.href = "https://apps.apple.com/kr/app/payco/id924292361";
    } else {
      window.location.href = "https://play.google.com/store/apps/details?id=com.nhnent.payapp";
    }
  }, 1500);
}

export default function CommuterBusWrapper() {
  const navigate = useNavigate();
  const { t } = useLanguage();
  const reduceMotion = useReducedMotion();
  const [selectedRegion, setSelectedRegion] = useState<string>("to-school");
  const [expandedRoute, setExpandedRoute] = useState<string | null>(null);
  const [routes, setRoutes] = useState<any[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [routeModalId, setRouteModalId] = useState<string | null>(null);
  const [liveRouteBusMap, setLiveRouteBusMap] = useState<Record<string, RouteBusInfo>>({});
  const [simulationTick, setSimulationTick] = useState(() => Date.now());
  const liveIntervalRef = useRef<ReturnType<typeof setInterval> | null>(null);

  useEffect(() => {
    const fetchRoutes = async () => {
      try {
        setLoading(true);
        setError(null);
        const allRoutes = await api.getRoutes();
        const commuterRoutes = allRoutes.filter((r: any) => r.type === "commuter");
        setRoutes(commuterRoutes);
      } catch (e: any) {
        setError(e.message || "노선 정보를 불러오지 못했습니다.");
      } finally {
        setLoading(false);
      }
    };
    fetchRoutes();
  }, []);

  useEffect(() => {
    if (routes.length === 0) {
      setLiveRouteBusMap({});
      return;
    }

    const DEST: Record<string, { lat: number; lng: number }> = {
      "서울": { lat: 37.497, lng: 127.047 },
      "인천": { lat: 37.456, lng: 126.705 },
    };
    const haversineKm = (a: { lat: number; lng: number }, b: { lat: number; lng: number }) => {
      const R = 6371, toRad = (d: number) => (d * Math.PI) / 180;
      const dLat = toRad(b.lat - a.lat), dLng = toRad(b.lng - a.lng);
      const x = Math.sin(dLat / 2) ** 2 + Math.cos(toRad(a.lat)) * Math.cos(toRad(b.lat)) * Math.sin(dLng / 2) ** 2;
      return R * 2 * Math.atan2(Math.sqrt(x), Math.sqrt(1 - x));
    };

    const fetchLive = async () => {
      if (document.hidden) return;
      try {
        const [buses, locations] = await Promise.all([api.getBuses(), api.getBusLocations()]);
        const commuterBuses = buses.filter((b: any) => b.type === "commuter" && b.status === "active");
        const locMap = new Map(locations.map((l: any) => [l.busId, l]));

        const newRouteBusMap: Record<string, { position: { lat: number; lng: number }; etaMins: number }> = {};
        commuterBuses.forEach((b: any) => {
          const routeId = b.currentRoute?.id;
          if (!routeId) return;
          const loc = locMap.get(b.id);
          if (!loc) return;
          const pos = { lat: loc.lat, lng: loc.lng };
          const routeObj = routes.find((r) => r.id === routeId);
          const region = routeObj?.region ?? "";
          const dest = DEST[region] ?? { lat: 37.5, lng: 127.0 };
          const km = haversineKm(pos, dest);
          const etaMins = Math.round((km / 60) * 60);
          newRouteBusMap[routeId] = { position: pos, etaMins };
        });

        setLiveRouteBusMap(newRouteBusMap);
      } catch {
        // 실패 시 조용히 무시
      }
    };
    const handleVisibilityChange = () => {
      if (!document.hidden) fetchLive();
    };

    fetchLive();
    document.addEventListener("visibilitychange", handleVisibilityChange);
    liveIntervalRef.current = setInterval(fetchLive, 15000);
    return () => {
      document.removeEventListener("visibilitychange", handleVisibilityChange);
      if (liveIntervalRef.current) clearInterval(liveIntervalRef.current);
    };
  }, [routes]);

  useEffect(() => {
    const timer = window.setInterval(() => setSimulationTick(Date.now()), 15_000);
    return () => window.clearInterval(timer);
  }, []);

  const routeBusMap = useMemo(() => {
    const result: Record<string, RouteBusInfo> = { ...liveRouteBusMap };
    routes.forEach((route) => {
      if (result[route.id] || !route.isActive) return;
      const stopPath = (route.stops || [])
        .filter((stop: any) => Number.isFinite(Number(stop.lng)) && Number.isFinite(Number(stop.lat)))
        .sort((left: any, right: any) => left.order - right.order)
        .map((stop: any) => [Number(stop.lng), Number(stop.lat)] as [number, number]);
      const fallbackDestination: [number, number] = route.region === "인천"
        ? [126.705, 37.456]
        : [127.047, 37.497];
      const path = stopPath.length >= 2
        ? stopPath
        : [[126.927978, 36.769014] as [number, number], fallbackDestination];
      const simulation = simulateCommuterBus(route.id, path, simulationTick, parseDurationMinutes(route.duration));
      if (simulation) result[route.id] = simulation;
    });
    return result;
  }, [liveRouteBusMap, routes, simulationTick]);

  // 유니크 지역 목록 (region 필드 기반)
  const regions = ["to-school", "from-school", ...Array.from(new Set(routes.map((r) => r.region).filter(Boolean)))];

  const filteredRoutes =
    selectedRegion === "to-school"
      ? routes.filter((r) => r.name?.includes("[출발]"))
      : selectedRegion === "from-school"
      ? routes.filter((r) => r.name?.includes("[도착]"))
      : routes.filter((r) => r.region === selectedRegion);

  return (
    <div className="font-['Public_Sans'] bg-background content-stretch flex flex-col items-center relative size-full">
      <div
        className="relative flex h-full w-full max-w-[430px] flex-col items-start overflow-y-auto overscroll-y-contain bg-unibus-surface pb-6 scrollbar-hide [-webkit-overflow-scrolling:touch]"
      >
        {/* Header */}
        <div className="sticky top-0 z-30 w-full pt-safe">
          <div className="backdrop-blur-[6px] bg-unibus-surface/95 flex items-center justify-between pb-[12px] pt-[16px] px-[16px] w-full">
            <button
              type="button"
              aria-label={t("홈으로 돌아가기", "Back to home")}
              onClick={() => navigate("/home")}
              className="flex size-[40px] items-center justify-center rounded-full transition-all hover:bg-gray-100 active:scale-95 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-unibus-brand focus-visible:ring-offset-2 motion-reduce:transform-none motion-reduce:transition-none"
            >
              <svg className="w-3 h-5 text-unibus-text" fill="none" viewBox="0 0 12 20" stroke="currentColor" strokeWidth="2">
                <path d="M11 1L1 10L11 19" />
              </svg>
            </button>

            <div className="flex flex-col items-center">
              <p className="font-bold text-unibus-text text-[18px] leading-[22.5px]">
                {t("통학버스", "Commuter Bus")}
              </p>

            </div>

            <div className="w-[40px]" />
          </div>

          {/* Region Filter */}
          <div
            role="group"
            aria-label={t("통학버스 지역 필터", "Commuter bus region filter")}
            className="flex gap-2 overflow-x-auto border-b border-unibus-divider px-[16px] py-[12px] scrollbar-hide"
          >
            {regions.map((region) => (
              <motion.button
                key={region}
                type="button"
                aria-pressed={selectedRegion === region}
                onClick={() => setSelectedRegion(region)}
                whileHover={reduceMotion ? undefined : { y: -1 }}
                whileTap={reduceMotion ? undefined : { scale: 0.96 }}
                transition={{ type: "spring", stiffness: 420, damping: 30 }}
                className={`unibus-filter relative isolate overflow-hidden rounded-xl px-4 py-2 text-[13px] font-semibold whitespace-nowrap focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-unibus-brand focus-visible:ring-offset-2 ${
                  selectedRegion === region
                    ? "text-unibus-brand-foreground"
                    : "bg-unibus-surface-subtle text-unibus-muted hover:bg-unibus-divider"
                }`}
              >
                {selectedRegion === region ? (
                  <motion.span
                    layoutId="commuter-region-indicator"
                    className="absolute inset-0 -z-10 rounded-xl bg-unibus-brand"
                    transition={reduceMotion ? { duration: 0 } : { type: "spring", stiffness: 430, damping: 34 }}
                  />
                ) : null}
                <span className="relative z-10">
                  {region === "to-school"
                    ? t("등교", "To School")
                    : region === "from-school"
                    ? t("하교", "From School")
                    : region}
                </span>
              </motion.button>
            ))}
          </div>
        </div>

        {/* Content */}
        <div className="flex-1 w-full px-[16px] py-[16px] space-y-3">
          {/* Loading */}
          {loading && (
            <div className="flex flex-col items-center justify-center py-16 gap-3">
              <div className="h-8 w-8 rounded-full border-2 border-unibus-brand border-t-transparent animate-spin motion-reduce:animate-none" />
              <p className="text-unibus-muted text-[14px]">
                {t("노선 불러오는 중...", "Loading routes...")}
              </p>
            </div>
          )}

          {/* Error */}
          {!loading && error && (
            <div className="flex flex-col items-center justify-center py-12 gap-3">
              <div className="bg-red-50 rounded-full p-4">
                <svg className="w-8 h-8 text-red-400" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 9v2m0 4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
                </svg>
              </div>
              <p className="font-bold text-unibus-text text-[15px]">
                {t("불러오기 실패", "Failed to load")}
              </p>
              <p className="text-unibus-muted text-[13px] text-center">{error}</p>
              <button
                onClick={() => window.location.reload()}
                className="mt-2 rounded-lg bg-unibus-brand px-5 py-2 text-[13px] font-semibold text-unibus-brand-foreground transition-transform active:scale-[0.97] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-unibus-brand focus-visible:ring-offset-2 motion-reduce:transform-none motion-reduce:transition-none"
              >
                {t("다시 시도", "Retry")}
              </button>
            </div>
          )}

          {/* Routes */}
          <AnimatePresence initial={false} mode="wait">
            {!loading && !error ? (
              <motion.div
                key={selectedRegion}
                initial={reduceMotion ? { opacity: 1 } : { opacity: 0, x: 6 }}
                animate={{ opacity: 1, x: 0 }}
                exit={reduceMotion ? { opacity: 0 } : { opacity: 0, x: -6 }}
                transition={reduceMotion ? { duration: 0 } : { duration: 0.22, ease: [0.22, 1, 0.36, 1] }}
                className="space-y-3"
              >
              {filteredRoutes.map((route) => {
              const stopNames: string[] =
                route.stops?.map((s: any) => s.name) || [];
              const color = "var(--unibus-brand)";
              const isExpanded = expandedRoute === route.id;
              const liveInfo = routeBusMap[route.id];

              return (
                <motion.article
                  key={route.id}
                  layout={!reduceMotion}
                  initial={reduceMotion ? false : { opacity: 0, y: 8 }}
                  animate={{ opacity: 1, y: 0 }}
                  exit={reduceMotion ? undefined : { opacity: 0, y: -6 }}
                  whileHover={reduceMotion ? undefined : { y: -2 }}
                  transition={reduceMotion ? { duration: 0 } : { type: "spring", stiffness: 360, damping: 30 }}
                  className={`overflow-hidden rounded-[16px] border bg-unibus-surface  transition-[border-color,box-shadow] ${
                    isExpanded ? "border-unibus-brand/30 " : "border-unibus-divider"
                  }`}
                >
                  <motion.button
                    type="button"
                    id={`route-toggle-${route.id}`}
                    aria-expanded={isExpanded}
                    aria-controls={`route-details-${route.id}`}
                    onClick={() =>
                      setExpandedRoute(isExpanded ? null : route.id)
                    }
                    whileTap={reduceMotion ? undefined : { scale: 0.992 }}
                    className="w-full p-[16px] text-left focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-unibus-brand"
                  >
                    <div className="flex items-start gap-3">
                      <div
                        className="rounded-[12px] size-[40px] bg-unibus-brand-soft flex items-center justify-center shrink-0"
                      >
                        <svg className="w-5 h-5 text-unibus-brand" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                          <path
                            strokeLinecap="round"
                            strokeLinejoin="round"
                            strokeWidth={2}
                            d="M8 7h12m0 0l-4-4m4 4l-4 4m0 6H4m0 0l4 4m-4-4l4-4"
                          />
                        </svg>
                      </div>

                      <div className="min-w-0 flex-1">
                        <div className="flex items-center gap-2 mb-1 flex-wrap">
                          <h3 className="font-bold text-unibus-text text-[16px] leading-[24px]">
                            {route.name?.replace(/\[(출발|도착)\]\s*/g, "")}
                          </h3>
                          {liveInfo ? (
                            <span className="flex items-center gap-1 bg-unibus-brand-soft text-unibus-brand px-2 py-1 rounded-[4px] font-bold text-[13px]">
                              <span className="inline-block h-1.5 w-1.5 rounded-full bg-unibus-brand animate-pulse motion-reduce:animate-none" />
                              {liveInfo.isSimulation ? t("시연 운행", "Demo Run") : t("운행 중", "In Service")}
                            </span>
                          ) : !route.isActive ? (
                            <span className="bg-unibus-surface-subtle text-unibus-muted px-2 py-1 rounded-[4px] font-bold text-[13px]">
                              {t("운행 중단", "Suspended")}
                            </span>
                          ) : null}
                        </div>
                        {route.schedule && (
                          <p className="font-medium text-unibus-text text-[15px] leading-[22px]">
                            {route.schedule}
                          </p>
                        )}

                        {liveInfo && (
                          <div className="flex items-center gap-1 mb-1">
                            <svg className="w-3 h-3 text-unibus-brand" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z" />
                            </svg>
                            <span className="font-bold text-unibus-brand text-[13px]">
                              {t(`도착 예상 ${liveInfo.etaMins}분`, `ETA ${liveInfo.etaMins} min`)}
                            </span>
                          </div>
                        )}

                        {isExpanded ? <div className="flex items-center gap-4 text-unibus-muted text-[13px] mb-2">
                          {route.duration && (
                            <div className="flex items-center gap-1">
                              <svg className="w-3 h-3" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z" />
                              </svg>
                              <span>{route.duration}</span>
                            </div>
                          )}
                          {route.fare && (
                            <div className="flex items-center gap-1">
                              <svg className="w-3 h-3" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 8c-1.657 0-3 .895-3 2s1.343 2 3 2 3 .895 3 2-1.343 2-3 2m0-8c1.11 0 2.08.402 2.599 1M12 8V7m0 1v8m0 0v1m0-1c-1.11 0-2.08-.402-2.599-1M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
                              </svg>
                              <span>{route.fare}</span>
                            </div>
                          )}
                        </div> : null}

                        {isExpanded && route.description && (
                          <p className="text-unibus-muted text-[13px] leading-[18px] mt-1">
                            {route.description}
                          </p>
                        )}
                      </div>

                      <svg
                        className={`mt-2 h-5 w-5 shrink-0 text-unibus-muted transition-transform duration-300 motion-reduce:transition-none ${
                          isExpanded ? "rotate-180" : ""
                        }`}
                        fill="none"
                        viewBox="0 0 24 24"
                        stroke="currentColor"
                      >
                        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 9l-7 7-7-7" />
                      </svg>
                    </div>
                  </motion.button>

                  <AnimatePresence initial={false}>
                    {isExpanded ? (
                    <motion.div
                      id={`route-details-${route.id}`}
                      role="region"
                      aria-labelledby={`route-toggle-${route.id}`}
                      initial={reduceMotion ? { opacity: 1 } : { height: 0, opacity: 0 }}
                      animate={{ height: "auto", opacity: 1 }}
                      exit={reduceMotion ? { opacity: 0 } : { height: 0, opacity: 0 }}
                      transition={reduceMotion ? { duration: 0 } : { duration: 0.32, ease: [0.22, 1, 0.36, 1] }}
                      className="overflow-hidden border-t border-unibus-divider px-[16px] pb-[16px]"
                    >
                      <div className="pt-[16px]">
                        <h4 className="font-bold text-unibus-text text-[14px] mb-3">
                          {t("정류장 목록", "Route Stops")}
                        </h4>
                        {stopNames.length > 0 ? (
                          <div className="space-y-2">
                            {stopNames.map((stop, index) => (
                              <motion.div
                                key={`${route.id}-${stop}-${index}`}
                                initial={reduceMotion ? false : { opacity: 0, x: -5 }}
                                animate={{ opacity: 1, x: 0 }}
                                transition={reduceMotion ? { duration: 0 } : { delay: Math.min(index * 0.025, 0.18), duration: 0.22 }}
                                className="flex items-center gap-3"
                              >
                                <div className="relative flex flex-col items-center">
                                  <div
                                    className="rounded-full size-[24px] flex items-center justify-center font-bold text-[13px] z-10 text-unibus-brand-foreground"
                                    style={{
                                      backgroundColor:
                                        index === 0
                                          ? color
                                          : index === stopNames.length - 1
                                          ? "#1e3a8a"
                                          : "#cbd5e1",
                                    }}
                                  >
                                    {index + 1}
                                  </div>
                                  {index < stopNames.length - 1 && (
                                    <div className="w-[2px] h-[24px] bg-unibus-divider absolute top-[24px]" />
                                  )}
                                </div>
                                <div className="flex-1 py-1">
                                  <p className="text-[14px] leading-[20px] font-semibold text-unibus-text">
                                    {stop}
                                  </p>
                                </div>
                              </motion.div>
                            ))}
                          </div>
                        ) : (
                          <p className="text-unibus-muted text-[13px] font-['Public_Sans']">
                            {t("정류장 정보 없음", "No stop info")}
                          </p>
                        )}

                        <div className="flex gap-2 mt-4">
                          <button
                            type="button"
                            onClick={(e) => {
                              e.stopPropagation();
                              setRouteModalId(route.id);
                            }}
                            className="flex h-[44px] flex-1 items-center justify-center gap-2 rounded-[12px] border border-unibus-brand text-[14px] font-bold text-unibus-brand transition-all hover:bg-unibus-brand-soft active:scale-[0.98] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-unibus-brand focus-visible:ring-offset-2 motion-reduce:transform-none motion-reduce:transition-none"
                          >
                            <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 20l-5.447-2.724A1 1 0 013 16.382V5.618a1 1 0 011.447-.894L9 7m0 13l6-3m-6 3V7m6 10l4.553 2.276A1 1 0 0021 18.382V7.618a1 1 0 00-.553-.894L15 4m0 13V4m0 0L9 7" />
                            </svg>
                            {t("노선 지도", "Route map")}
                          </button>
                          <button
                            type="button"
                            onClick={route.isActive ? openPayco : undefined}
                            className={`h-[48px] flex-1 rounded-[12px] text-[14px] font-bold text-unibus-brand-foreground  transition-all active:scale-[0.98] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-unibus-brand focus-visible:ring-offset-2 motion-reduce:transform-none motion-reduce:transition-none ${
                              !route.isActive ? "opacity-50 cursor-not-allowed" : ""
                            }`}
                            style={{
                              background: route.isActive
                                ? "var(--unibus-brand)"
                                : "var(--unibus-text-muted)",
                            }}
                            disabled={!route.isActive}
                          >
                            {route.isActive
                              ? t("PAYCO 예약", "Book via PAYCO")
                              : t("운행 중단", "Suspended")}
                          </button>
                        </div>
                      </div>
                    </motion.div>
                    ) : null}
                  </AnimatePresence>
                </motion.article>
              );
              })}

              {/* Empty state */}
              {filteredRoutes.length === 0 ? (
            <motion.div
              initial={reduceMotion ? false : { opacity: 0, y: 6 }}
              animate={{ opacity: 1, y: 0 }}
              className="flex flex-col items-center justify-center py-12"
            >
              <div className="bg-unibus-surface-subtle rounded-full p-6 mb-4">
                <svg className="w-12 h-12 text-unibus-muted" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    strokeWidth={2}
                    d="M9 20l-5.447-2.724A1 1 0 013 16.382V5.618a1 1 0 011.447-.894L9 7m0 13l6-3m-6 3V7m6 10l4.553 2.276A1 1 0 0021 18.382V7.618a1 1 0 00-.553-.894L15 4m0 13V4m0 0L9 7"
                  />
                </svg>
              </div>
              <p className="font-bold text-unibus-text text-[16px] mb-1">
                {t("노선을 찾을 수 없습니다", "No routes found")}
              </p>
              <p className="font-normal text-unibus-muted text-[14px] text-center">
                {selectedRegion === "to-school"
                  ? t("등교 노선이 없습니다", "No to-school routes")
                  : selectedRegion === "from-school"
                  ? t("하교 노선이 없습니다", "No from-school routes")
                  : t(`${selectedRegion} 지역 노선이 없습니다`, `No routes in ${selectedRegion}`)}
              </p>
              <button type="button" onClick={() => setSelectedRegion(selectedRegion === "to-school" ? "from-school" : "to-school")}
                className="mt-5 min-h-11 rounded-xl border border-unibus-divider px-5 text-[14px] font-medium text-unibus-brand focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--unibus-focus)]">
                {selectedRegion === "to-school" ? t("하교 노선 보기", "View return routes") : t("등교 노선 보기", "View to-school routes")}
              </button>
            </motion.div>
              ) : null}
              </motion.div>
            ) : null}
          </AnimatePresence>
        </div>
      </div>

      {/* Route Map Modal */}
      {routeModalId && (() => {
        const modal = routes.find((r) => r.id === routeModalId);
        if (!modal) return null;
        return (
          <RouteMapModal
            route={modal}
            bus={routeBusMap[modal.id]}
            onClose={() => setRouteModalId(null)}
          />
        );
      })()}
    </div>
  );
}
