import { useState, useEffect, useCallback, useMemo } from "react";
import { useNavigate } from "react-router";
import { Bell, BusFront, ChevronRight, MapPinned, TrainFront, Zap } from "lucide-react";
import svgPaths from "../../imports/svg-odbnwpa57u";
import SinchangTimetableSheet from "../components/SinchangTimetableSheet";
import { useLanguage } from "../contexts/LanguageContext";
import {
  getTrainServiceDay,
  getUpcomingTrains,
  type TrainServiceDay,
} from "../data/sinchangTrainTimetable";
import { api } from "../services/api";
import type { Notice } from "../types";
import { simulateCampusLoop } from "../utils/campusLoopSimulation";
import { estimateStopArrivals } from "../utils/shuttleEta";

interface HomeStop {
  id: string;
  nameKo: string;
  lat: number;
  lng: number;
  order: number;
}

interface HomeBus {
  id: string;
  label: string;
  position: { lat: number; lng: number };
  speed: number;
  timestamp: string;
}

// 학내 순환 정류장 목록
const CAMPUS_STOPS = [
  { id: "rear-gate", nameKo: "후문",   lat: 36.772760, lng: 126.933816, order: 1 },
  { id: "hyang3",    nameKo: "향3",    lat: 36.768228, lng: 126.935383, order: 2 },
  { id: "hyang1",    nameKo: "향1",    lat: 36.767905, lng: 126.932505, order: 3 },
  { id: "library",   nameKo: "도서관", lat: 36.768856, lng: 126.930700, order: 4 },
  { id: "main-gate", nameKo: "정문",   lat: 36.769014, lng: 126.927978, order: 5 },
] satisfies HomeStop[];

function haversineKm(lat1: number, lng1: number, lat2: number, lng2: number) {
  const R = 6371;
  const dLat = ((lat2 - lat1) * Math.PI) / 180;
  const dLng = ((lng2 - lng1) * Math.PI) / 180;
  const a =
    Math.sin(dLat / 2) ** 2 +
    Math.cos((lat1 * Math.PI) / 180) *
      Math.cos((lat2 * Math.PI) / 180) *
      Math.sin(dLng / 2) ** 2;
  return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}

function formatRouteStops(stops: any[]): HomeStop[] {
  return stops
    .filter((stop) => Number.isFinite(Number(stop.lat)) && Number.isFinite(Number(stop.lng)))
    .map((stop, index) => ({
      id: stop.id || `stop-${index + 1}`,
      nameKo: stop.name || `정류장 ${index + 1}`,
      lat: Number(stop.lat),
      lng: Number(stop.lng),
      order: Number(stop.order) || index + 1,
    }));
}

export default function HomeWrapper() {
  const navigate = useNavigate();
  const { t } = useLanguage();
  const [isRefreshing, setIsRefreshing] = useState(false);
  const [locationStatus, setLocationStatus] = useState<"checking" | "ready" | "unavailable">("checking");
  const [userLocation, setUserLocation] = useState<{ lat: number; lng: number } | null>(null);
  const [activeBuses, setActiveBuses] = useState<HomeBus[]>([]);
  const [routePath, setRoutePath] = useState<[number, number][]>([]);
  const [campusStops, setCampusStops] = useState<HomeStop[]>(CAMPUS_STOPS);
  const [notices, setNotices] = useState<Notice[]>([]);
  const [timetableOpen, setTimetableOpen] = useState(false);
  const [timetableDay, setTimetableDay] = useState<TrainServiceDay>(() => getTrainServiceDay());
  const [clockTick, setClockTick] = useState(() => Date.now());

  // 사용자 GPS 위치 → 가장 가까운 정류장 계산
  useEffect(() => {
    if (!navigator.geolocation) {
      setLocationStatus("unavailable");
      return;
    }
    navigator.geolocation.getCurrentPosition(
      (pos) => {
        setUserLocation({ lat: pos.coords.latitude, lng: pos.coords.longitude });
        setLocationStatus("ready");
      },
      () => setLocationStatus("unavailable"),
      { enableHighAccuracy: false, maximumAge: 60_000, timeout: 5_000 },
    );
  }, []);

  useEffect(() => {
    api.getCampusRoutePath()
      .then((routeDetail) => {
        setRoutePath(routeDetail.path || []);
        const savedStops = formatRouteStops(routeDetail.stops || []);
        if (savedStops.length > 0) setCampusStops(savedStops);
      })
      .catch((error) => console.warn("홈 노선 정보 불러오기 실패:", error));
  }, []);

  useEffect(() => {
    api.getNotices()
      .then(setNotices)
      .catch((error) => console.warn("홈 공지사항 불러오기 실패:", error));
  }, []);

  useEffect(() => {
    const timer = window.setInterval(() => setClockTick(Date.now()), 15_000);
    return () => window.clearInterval(timer);
  }, []);

  // 활성 버스 위치 fetch
  const fetchBuses = useCallback(async () => {
    setIsRefreshing(true);
    try {
      const [allBuses, locations] = await Promise.all([
        api.getBuses(),
        api.getBusLocations(),
      ]);
      const activeById = new Map(
        allBuses
          .filter((bus: any) => bus.type === "campus" && bus.status === "active" && bus.isRunning)
          .map((bus: any) => [bus.id, bus]),
      );
      const buses = locations
        .filter((location: any) => {
          if (!activeById.has(location.busId)) return false;
          const updatedAt = new Date(location.timestamp).getTime();
          return Number.isFinite(updatedAt) && Date.now() - updatedAt <= 45_000;
        })
        .map((location: any) => ({
          id: location.busId,
          label: activeById.get(location.busId)?.name || "학내순환",
          position: { lat: Number(location.lat), lng: Number(location.lng) },
          speed: Number(location.speed) || 0,
          timestamp: location.timestamp,
        }));
      setActiveBuses(buses);
    } catch {
      // 실패 시 유지
    } finally {
      setIsRefreshing(false);
    }
  }, []);

  useEffect(() => {
    fetchBuses();
    const interval = setInterval(fetchBuses, 30000);
    return () => clearInterval(interval);
  }, [fetchBuses]);

  const nearestStop = useMemo(() => {
    if (!userLocation || campusStops.length === 0) return null;
    return campusStops.reduce((nearest, stop) => (
      haversineKm(userLocation.lat, userLocation.lng, stop.lat, stop.lng)
        < haversineKm(userLocation.lat, userLocation.lng, nearest.lat, nearest.lng) ? stop : nearest
    ));
  }, [campusStops, userLocation]);
  const nearestStopLabel = nearestStop?.nameKo
    ?? (locationStatus === "checking" ? t("위치 확인 중", "Locating...") : t("위치 권한 필요", "Location unavailable"));
  const simulation = useMemo(
    () => simulateCampusLoop(
      routePath,
      campusStops.map((stop) => ({
        id: stop.id,
        name: stop.nameKo,
        lat: stop.lat,
        lng: stop.lng,
        order: stop.order,
      })),
      clockTick,
      10,
    ),
    [campusStops, clockTick, routePath],
  );
  const displayBuses = activeBuses.length > 0 ? activeBuses : simulation.buses;
  const targetStop = nearestStop ?? campusStops[0];
  const arrivalEstimates = useMemo(
    () => estimateStopArrivals(
      routePath,
      campusStops.map((stop) => ({ id: stop.id, order: stop.order, lat: stop.lat, lng: stop.lng })),
      displayBuses,
      { loop: true, fallbackSpeedMps: 6.2, nowMs: clockTick },
    ),
    [campusStops, clockTick, displayBuses, routePath],
  );
  const nextArrival = targetStop ? arrivalEstimates.get(targetStop.id)?.minutes ?? null : null;
  const busActive = displayBuses.length > 0;
  const automaticTrainDay = getTrainServiceDay(new Date(clockTick));
  const upcomingTrains = useMemo(
    () => getUpcomingTrains(new Date(clockTick), automaticTrainDay, 3),
    [automaticTrainDay, clockTick],
  );
  const importantNotices = useMemo(() => notices
    .filter((notice) => notice.category === "route")
    .slice()
    .sort((a, b) => {
      const rank = (notice: Notice) => (notice.isPinned ? 2 : 0) + (notice.priority === "high" ? 1 : 0);
      return rank(b) - rank(a) || new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime();
    })
    .slice(0, 2), [notices]);

  const openTimetable = useCallback(() => {
    setTimetableDay(automaticTrainDay);
    setTimetableOpen(true);
  }, [automaticTrainDay]);

  return (
    <div className="bg-background content-stretch flex flex-col items-start relative size-full">
      <div className="relative flex h-full w-full shrink-0 flex-col items-start overflow-y-auto overscroll-y-contain bg-unibus-surface pb-6 scrollbar-hide [-webkit-overflow-scrolling:touch]">

        {/* Header – sticky, no entrance animation */}
        <div className="sticky top-0 z-30 w-full pt-safe">
          <div className="backdrop-blur-[6px] bg-unibus-surface/95 flex flex-row items-center w-full">
            <div className="content-stretch flex items-center justify-between pb-[12px] pt-[16px] px-[20px] relative w-full">
              <div className="content-stretch flex flex-col items-start relative shrink-0">
                <div className="font-['Public_Sans'] flex flex-col font-bold justify-center leading-[0] relative shrink-0 text-unibus-text text-[21px]">
                  <p className="leading-[32px]">UNIBUS SCH</p>
                </div>
              </div>
              <button
                onClick={() => navigate("/notice")}
                aria-label={t("공지사항 보기", "View notices")}
                className="unibus-pressable bg-unibus-surface-subtle content-stretch flex items-center justify-center relative rounded-[9999px] shrink-0 size-[40px] text-unibus-text hover:bg-unibus-divider"
              >
                <div className="h-[20px] relative shrink-0 w-[16px]">
                  <svg className="absolute block size-full" fill="none" preserveAspectRatio="none" viewBox="0 0 16 20">
                    <path d={svgPaths.p164b49c0} fill="currentColor" />
                  </svg>
                </div>
              </button>
            </div>
          </div>
        </div>

        {/* ── Content ─────────────────────── */}
        <div
          key="home-content"
          className="w-full animate-[routeFade_180ms_ease-out]"
        >

              <section className="unibus-section-reveal w-full px-5 py-3">
                <button
                  type="button"
                  aria-label={t("셔틀버스 운행 현황 보기", "View shuttle service status")}
                  onClick={() => navigate("/campus-shuttle")}
                  className="font-['Public_Sans'] unibus-feature-card unibus-pressable w-full p-5 text-left focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--unibus-focus)]"
                >
                  <div className="flex items-center justify-between gap-3">
                    <span className="text-[13px] font-medium text-white/80">{locationStatus === "ready" ? t("가까운 정류장", "Nearest stop") : t("셔틀 운행", "Shuttle service")}</span>
                    <span className="flex items-center gap-1 text-[13px] font-medium text-white/90">
                      {t("지도 보기", "View map")} <ChevronRight className="size-4" aria-hidden="true" />
                    </span>
                  </div>
                  <p className="mt-2 text-[24px] font-semibold leading-8 text-white">{locationStatus === "ready" ? nearestStopLabel : t("학내순환", "Campus shuttle")}</p>
                  <div className="mt-3 flex items-end justify-between gap-3 pt-4">
                    <div>
                      {isRefreshing && routePath.length === 0 ? (
                        <p className="text-[18px] font-medium text-white">{t("운행 확인 중", "Checking service")}</p>
                      ) : busActive ? (
                        <p className="flex flex-wrap items-baseline gap-1.5 text-white">
                          <span className="text-[32px] font-semibold leading-10 tabular-nums">{nextArrival ? `${nextArrival}분` : t("운행 중", "In service")}</span>
                          {nextArrival ? <span className="text-[15px] font-medium">{t("후 도착", "to arrive")}</span> : null}
                        </p>
                      ) : (
                        <p className="text-[18px] font-medium text-white">{t("현재 운행 없음", "No current service")}</p>
                      )}
                    </div>
                    <span className="unibus-feature-bus grid size-12 shrink-0 place-items-center rounded-full"><BusFront className="size-6 text-white/90" strokeWidth={1.5} aria-hidden="true" /></span>
                  </div>
                </button>
              </section>

              <div className="unibus-section-reveal unibus-section-delay-1 grid w-full grid-cols-2 gap-3 px-5 pb-2">
                {[
                  { path: "/campus-shuttle", label: t("셔틀 지도", "Shuttle map"), icon: MapPinned },
                  { path: "/commuter-bus", label: t("통학 노선", "Commuter routes"), icon: BusFront },
                ].map((action) => (
                  <button key={action.path} type="button" onClick={() => navigate(action.path)}
                    className="unibus-shortcut unibus-pressable flex min-h-[56px] items-center justify-center gap-2 rounded-2xl px-2 text-[14px] font-medium text-unibus-text focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--unibus-focus)]">
                    <span className="grid size-8 shrink-0 place-items-center rounded-lg bg-unibus-brand-soft"><action.icon className="size-[18px] text-unibus-brand" strokeWidth={1.75} aria-hidden="true" /></span>
                    {action.label}
                  </button>
                ))}
              </div>

              {/* Sinchang timetable */}
              <section className="unibus-section-reveal unibus-section-delay-2 w-full px-5 py-3">
                <div className="mb-3 flex items-end justify-between">
                  <div>
                    <h2 className="text-[18px] font-semibold leading-7 text-unibus-text">신창역 전철</h2>
                    <p className="text-[13px] font-medium text-unibus-muted">1호선 · 서울 방면</p>
                  </div>
                  <span className="rounded-md bg-unibus-surface-subtle px-2 py-1 text-[13px] font-medium text-unibus-muted">
                    {automaticTrainDay === "weekday" ? "평일" : "토·공휴일"}
                  </span>
                </div>

                <button
                  type="button"
                  onClick={openTimetable}
                  aria-label="신창역 전체 전철 시간표 보기"
                  className="unibus-pressable w-full overflow-hidden rounded-xl border border-unibus-divider bg-unibus-surface text-left hover:border-[#c9d6ea] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-unibus-brand/40"
                >
                  <div className="border-b border-unibus-divider px-4 py-3">
                    <div className="flex items-center justify-between gap-2">
                      <span className="flex items-center gap-2 text-[14px] font-semibold text-unibus-text">
                        <TrainFront className="size-[18px] shrink-0 text-unibus-muted" aria-hidden="true" /> 다음 출발
                      </span>
                      <span className="flex shrink-0 items-center gap-1 whitespace-nowrap text-[13px] font-medium text-unibus-brand">
                        시간표 <ChevronRight className="size-4" aria-hidden="true" />
                      </span>
                    </div>
                  </div>

                  <div className="divide-y divide-unibus-divider p-2">
                    {upcomingTrains.map((train, index) => (
                      <div key={`${train.time}-${train.destination}-${train.dayOffset}`} className={`flex min-h-[56px] items-center gap-2 px-2 ${index === 0 ? "unibus-next-departure" : ""}`}>
                        <p className={`w-[62px] shrink-0 tabular-nums text-[20px] font-semibold ${index === 0 ? "text-unibus-brand" : "text-unibus-text"}`}>
                          {train.time}
                        </p>
                        <div className="min-w-0 flex-1">
                          <div className="flex flex-wrap items-center gap-1.5">
                            <p className="truncate text-[14px] font-medium text-unibus-text">{train.destination}행</p>
                            {train.express ? (
                              <span className="flex items-center gap-0.5 rounded bg-unibus-surface-subtle px-1.5 py-0.5 text-[12px] font-medium text-unibus-muted">
                                <Zap className="size-2" aria-hidden="true" /> 급행
                              </span>
                            ) : null}
                          </div>
                        </div>
                        <p className="shrink-0 text-[13px] font-medium text-unibus-muted">
                          {train.dayOffset > 0
                            ? "내일"
                            : train.minutesUntil <= 1 ? "곧 출발" : `${train.minutesUntil}분 후`}
                        </p>
                      </div>
                    ))}
                  </div>
                </button>
              </section>

              {/* Important notices */}
              <section className="unibus-section-reveal unibus-section-delay-3 mb-5 w-full px-5 py-3">
                <div className="mb-3 flex items-center justify-between">
                  <h2 className="text-[18px] font-semibold leading-7 text-unibus-text">운행 공지</h2>
                  <button
                    type="button"
                    onClick={() => navigate("/notice")}
                    className="unibus-pressable flex items-center gap-0.5 rounded-lg px-2 py-1 text-[13px] font-medium text-unibus-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--unibus-focus)]"
                  >
                    전체보기 <ChevronRight className="size-4" aria-hidden="true" />
                  </button>
                </div>

                <div className="overflow-hidden rounded-xl border border-unibus-divider bg-unibus-surface">
                  {importantNotices.length > 0 ? importantNotices.map((notice, index) => (
                    <button
                      key={notice.id}
                      type="button"
                      onClick={() => navigate("/notice")}
                      className={`unibus-pressable flex min-h-[66px] w-full items-center gap-3 px-4 py-3 text-left hover:bg-unibus-surface-subtle focus-visible:z-10 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--unibus-focus)] ${index > 0 ? "border-t border-unibus-divider" : ""}`}
                    >
                      <span className="grid size-8 shrink-0 place-items-center rounded-lg bg-unibus-brand-soft text-unibus-brand">
                        <Bell className="size-4" aria-hidden="true" />
                      </span>
                      <span className="min-w-0 flex-1">
                        <span className="block line-clamp-2 break-keep text-[14px] font-medium leading-5 text-unibus-text">{notice.title}</span>
                        <span className="block text-[13px] font-medium text-unibus-muted">
                          {new Date(notice.createdAt).toLocaleDateString("ko-KR", { month: "short", day: "numeric" })}
                        </span>
                      </span>
                      <ChevronRight className="size-4 shrink-0 text-unibus-muted" aria-hidden="true" />
                    </button>
                  )) : (
                    <button
                      type="button"
                      onClick={() => navigate("/notice")}
                      className="unibus-pressable flex min-h-[66px] w-full items-center gap-3 px-4 py-3 text-left focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--unibus-focus)]"
                    >
                      <span className="grid size-8 shrink-0 place-items-center rounded-lg bg-unibus-surface-subtle text-unibus-muted">
                        <Bell className="size-4" aria-hidden="true" />
                      </span>
                      <span className="min-w-0 flex-1">
                        <span className="block text-[14px] font-medium text-unibus-text">새로운 운행 공지가 없습니다</span>
                      </span>
                      <ChevronRight className="size-4 shrink-0 text-unibus-muted" aria-hidden="true" />
                    </button>
                  )}
                </div>
              </section>
        </div>
        {/* ── End content ────────────────────────── */}

      </div>

      <SinchangTimetableSheet
        open={timetableOpen}
        serviceDay={timetableDay}
        onServiceDayChange={setTimetableDay}
        onClose={() => setTimetableOpen(false)}
      />
    </div>
  );
}
