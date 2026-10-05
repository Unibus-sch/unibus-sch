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
    <div className="bg-[#f6f6f8] content-stretch flex flex-col items-start relative size-full">
      <div className="relative flex h-full w-full shrink-0 flex-col items-start overflow-y-auto overscroll-y-contain bg-white pb-6 shadow-[0px_25px_50px_-12px_rgba(0,0,0,0.25)] scrollbar-hide [-webkit-overflow-scrolling:touch]">

        {/* Header – sticky, no entrance animation */}
        <div className="sticky top-0 z-30 w-full pt-safe">
          <div className="backdrop-blur-[6px] bg-[rgba(255,255,255,0.9)] flex flex-row items-center w-full">
            <div className="content-stretch flex items-center justify-between pb-[12px] pt-[16px] px-[24px] relative w-full">
              <div className="content-stretch flex flex-col items-start relative shrink-0">
                <div className="flex flex-col font-['Public_Sans'] font-medium justify-center leading-[0] relative shrink-0 text-[#64748b] text-[12px] tracking-[0.6px] uppercase">
                  <p className="leading-[16px]"></p>
                </div>
                <div className="flex flex-col font-['Public_Sans'] font-bold justify-center leading-[0] relative shrink-0 text-[#0f172a] text-[24px]">
                  <p className="leading-[32px]">UNIBUS SCH</p>
                </div>
              </div>
              <button
                onClick={() => navigate("/notice")}
                aria-label={t("공지사항 보기", "View notices")}
                className="unibus-pressable bg-[#f1f5f9] content-stretch flex items-center justify-center relative rounded-[9999px] shrink-0 size-[40px] text-[#0f172a] hover:bg-[#e2e8f0]"
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

              {/* Nearest Stop Card */}
              <div className="unibus-section-reveal relative shrink-0 w-full">
                <div className="content-stretch flex flex-col items-start px-[24px] py-[16px] relative w-full">
                  <button
                    type="button"
                    aria-label={t("셔틀버스 운행 현황 보기", "View shuttle service status")}
                    className="unibus-pressable group home-accent-gradient bg-[#1e3a8a] relative rounded-[16px] shadow-[0px_10px_15px_-3px_rgba(0,0,0,0.1),0px_4px_6px_-4px_rgba(0,0,0,0.1)] shrink-0 w-full overflow-hidden cursor-pointer text-left focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--unibus-focus)] focus-visible:ring-offset-2"
                    onClick={() => navigate("/campus-shuttle")}
                  >
                    <div className="content-stretch flex flex-col items-start p-[24px] relative w-full">
                      <div className="home-accent-circle absolute bg-[rgba(255,255,255,0.1)] right-[-16px] rounded-[9999px] size-[128px] top-[-16px]" />
                      <div className="home-accent-circle absolute bg-[rgba(255,255,255,0.05)] bottom-[-32px] left-[-32px] rounded-[9999px] size-[128px]" />

                      <div className="content-stretch flex flex-col gap-[4px] items-start relative shrink-0 w-full z-10">
                        <div className="content-stretch flex gap-[8px] items-center opacity-90 relative shrink-0 w-full">
                          <div className="h-[11.667px] relative shrink-0 w-[9.333px]">
                            <svg className="absolute block size-full" fill="none" preserveAspectRatio="none" viewBox="0 0 9.33333 11.6667">
                              <path d={svgPaths.p3d8f00c0} fill="white" />
                            </svg>
                          </div>
                          <div className="flex flex-col font-['Public_Sans'] font-medium justify-center leading-[0] text-[12px] text-white tracking-[1.2px] uppercase">
                            <p className="leading-[16px]">{t("가장 가까운 정류장", "Nearest Stop")}</p>
                          </div>
                        </div>

                        <div className="flex flex-col font-['Public_Sans'] font-bold justify-center leading-[0] text-[20px] text-white w-full">
                          <p className="leading-[28px]">{nearestStopLabel}</p>
                        </div>

                        <div className="content-stretch flex items-end justify-between pt-[12px] relative shrink-0 w-full">
                          <div className="content-stretch flex flex-col items-start relative shrink-0">
                            <div className="flex flex-col font-['Public_Sans'] font-normal justify-center leading-[0] text-[14px] text-white opacity-80">
                              <p className="leading-[20px]">{t("운행 현황", "Service Status")}</p>
                            </div>
                            <div className="flex items-center gap-2 mt-1">
                              {isRefreshing && routePath.length === 0 ? (
                                <span className="font-['Public_Sans'] font-bold text-[16px] text-white/70">{t("확인 중...", "Checking...")}</span>
                              ) : busActive ? (
                                <>
                                  <div className="w-2 h-2 rounded-full bg-[#22c55e] animate-pulse" />
                                  <div className="flex items-baseline gap-1">
                                    <span className="font-['Public_Sans'] font-black text-[20px] text-white leading-[28px]">
                                      {nextArrival ? `${nextArrival}분` : t("운행 중", "In Service")}
                                    </span>
                                    {nextArrival && (
                                      <span className="font-['Public_Sans'] font-bold text-[14px] text-white/80">
                                        {t("후 도착", "to arrive")}
                                      </span>
                                    )}
                                  </div>
                                </>
                              ) : (
                                <>
                                  <div className="w-2 h-2 rounded-full bg-white/40" />
                                  <span className="font-['Public_Sans'] font-black text-[20px] text-white/70 leading-[28px]">{t("운행 없음", "No Service")}</span>
                                </>
                              )}
                            </div>
                          </div>

                          <span
                            aria-hidden="true"
                            className="content-stretch flex items-center justify-center p-[4px] relative rounded-[9999px] shrink-0 size-[48px] border-4 border-[rgba(255,255,255,0.2)] transition-colors group-hover:border-[rgba(255,255,255,0.4)]"
                          >
                            <div className="h-[22.167px] relative shrink-0 w-[18.667px]">
                              <svg className="absolute block size-full" fill="none" preserveAspectRatio="none" viewBox="0 0 18.6667 22.1667">
                                <path d={svgPaths.p5416200} fill="white" />
                              </svg>
                            </div>
                          </span>
                        </div>
                      </div>
                    </div>
                  </button>
                </div>
              </div>

              {/* Compact navigation */}
              <div className="unibus-section-reveal unibus-section-delay-1 w-full px-6 py-3">
                <div className="grid grid-cols-3 gap-2">
                  {[
                    { path: "/campus-shuttle", label: t("셔틀", "Shuttle"), icon: MapPinned },
                    { path: "/commuter-bus", label: t("통학", "Commuter"), icon: BusFront },
                    { path: "/notice", label: t("공지", "Notices"), icon: Bell },
                  ].map((action) => (
                    <button
                      key={action.path}
                      type="button"
                      onClick={() => navigate(action.path)}
                      className="unibus-pressable flex h-[76px] flex-col items-center justify-center gap-2 rounded-xl border border-[#e2e8f0] bg-white text-[#1e3a8a] shadow-sm hover:border-[#c9d6ea] hover:bg-[#f8fafc] hover:shadow-[0_8px_22px_rgba(30,58,138,0.09)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#1e3a8a]/40"
                    >
                      <action.icon className="size-5" strokeWidth={2.2} aria-hidden="true" />
                      <span className="text-[12px] font-extrabold">{action.label}</span>
                    </button>
                  ))}
                </div>
              </div>

              {/* Sinchang timetable */}
              <section className="unibus-section-reveal unibus-section-delay-2 w-full px-6 py-4">
                <div className="mb-3 flex items-end justify-between">
                  <div>
                    <h2 className="text-[18px] font-extrabold leading-7 text-[#0f172a]">신창역 전철</h2>
                    <p className="text-[11px] font-semibold text-[#64748b]">1호선 · 서울 방면</p>
                  </div>
                  <span className="rounded-md bg-[#f1f5f9] px-2 py-1 text-[10px] font-extrabold text-[#64748b]">
                    {automaticTrainDay === "weekday" ? "평일" : "토·공휴일"}
                  </span>
                </div>

                <button
                  type="button"
                  onClick={openTimetable}
                  aria-label="신창역 전체 전철 시간표 보기"
                  className="unibus-pressable w-full overflow-hidden rounded-xl border border-[#dbe4f5] bg-white text-left shadow-[0_4px_18px_rgba(30,58,138,0.08)] hover:border-[#c9d6ea] hover:shadow-[0_10px_26px_rgba(30,58,138,0.12)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#1e3a8a]/40"
                >
                  <div className="flex items-center justify-between bg-[#eef3ff] px-4 py-3">
                    <div className="flex items-center gap-3">
                      <span className="grid size-9 place-items-center rounded-full bg-[#1e3a8a] text-white">
                        <TrainFront className="size-4" aria-hidden="true" />
                      </span>
                      <div>
                        <p className="text-[13px] font-extrabold text-[#0f172a]">다음 출발</p>
                        <p className="text-[10px] font-semibold text-[#64748b]">후문 셔틀은 전철 출발 10분 전</p>
                      </div>
                    </div>
                    <span className="flex items-center gap-1 text-[11px] font-extrabold text-[#1e3a8a]">
                      전체 시간표 <ChevronRight className="size-4" aria-hidden="true" />
                    </span>
                  </div>

                  <div className="divide-y divide-[#f1f5f9] px-4">
                    {upcomingTrains.map((train, index) => (
                      <div key={`${train.time}-${train.destination}-${train.dayOffset}`} className="flex min-h-[58px] items-center gap-3">
                        <p className={`w-[54px] tabular-nums text-[18px] font-black ${index === 0 ? "text-[#1e3a8a]" : "text-[#0f172a]"}`}>
                          {train.time}
                        </p>
                        <div className="min-w-0 flex-1">
                          <div className="flex items-center gap-1.5">
                            <p className="truncate text-[12px] font-bold text-[#334155]">{train.destination}행</p>
                            {train.express ? (
                              <span className="flex items-center gap-0.5 rounded bg-[#1e3a8a] px-1.5 py-0.5 text-[8px] font-black text-white">
                                <Zap className="size-2" aria-hidden="true" /> 급행
                              </span>
                            ) : null}
                          </div>
                          <p className="text-[10px] font-semibold text-[#94a3b8]">
                            {train.dayOffset > 0 ? "내일 첫차" : index === 0 ? "가장 빠른 전철" : "이후 출발"}
                          </p>
                        </div>
                        <p className="shrink-0 text-[11px] font-extrabold text-[#64748b]">
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
              <section className="unibus-section-reveal unibus-section-delay-3 mb-5 w-full px-6 py-4">
                <div className="mb-3 flex items-center justify-between">
                  <h2 className="text-[18px] font-extrabold leading-7 text-[#0f172a]">운행 공지</h2>
                  <button
                    type="button"
                    onClick={() => navigate("/notice")}
                    className="unibus-pressable flex items-center gap-0.5 rounded-lg px-2 py-1 text-[11px] font-extrabold text-[#64748b] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--unibus-focus)]"
                  >
                    전체보기 <ChevronRight className="size-4" aria-hidden="true" />
                  </button>
                </div>

                <div className="overflow-hidden rounded-xl border border-[#e2e8f0] bg-white">
                  {importantNotices.length > 0 ? importantNotices.map((notice, index) => (
                    <button
                      key={notice.id}
                      type="button"
                      onClick={() => navigate("/notice")}
                      className={`unibus-pressable flex min-h-[66px] w-full items-center gap-3 px-4 text-left hover:bg-unibus-surface-subtle focus-visible:z-10 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--unibus-focus)] ${index > 0 ? "border-t border-[#f1f5f9]" : ""}`}
                    >
                      <span className="grid size-8 shrink-0 place-items-center rounded-lg bg-[#eef3ff] text-[#1e3a8a]">
                        <Bell className="size-4" aria-hidden="true" />
                      </span>
                      <span className="min-w-0 flex-1">
                        <span className="block truncate text-[13px] font-extrabold text-[#0f172a]">{notice.title}</span>
                        <span className="block text-[10px] font-semibold text-[#94a3b8]">
                          {new Date(notice.createdAt).toLocaleDateString("ko-KR", { month: "short", day: "numeric" })}
                        </span>
                      </span>
                      <ChevronRight className="size-4 shrink-0 text-[#94a3b8]" aria-hidden="true" />
                    </button>
                  )) : (
                    <button
                      type="button"
                      onClick={() => navigate("/notice")}
                      className="unibus-pressable flex min-h-[66px] w-full items-center gap-3 px-4 text-left focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--unibus-focus)]"
                    >
                      <span className="grid size-8 shrink-0 place-items-center rounded-lg bg-[#f1f5f9] text-[#64748b]">
                        <Bell className="size-4" aria-hidden="true" />
                      </span>
                      <span className="min-w-0 flex-1">
                        <span className="block text-[13px] font-extrabold text-[#0f172a]">새로운 운행 공지가 없습니다</span>
                        <span className="block text-[10px] font-semibold text-[#94a3b8]">공지사항에서 전체 내용을 확인하세요</span>
                      </span>
                      <ChevronRight className="size-4 shrink-0 text-[#94a3b8]" aria-hidden="true" />
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
