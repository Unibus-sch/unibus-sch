import { useCallback, useEffect, useMemo, useState } from "react";
import { Activity, AlertTriangle, BellRing, Bus, CheckCircle2, CircleHelp, RefreshCw, Route, Server, UserRound } from "lucide-react";
import { useNavigate } from "react-router-dom";
import AdminLayout from "./AdminLayout";
import { api } from "../services/api";
import type { BusRoute, Notice, UserReport } from "../types";

interface ManagedBus {
  id: string;
  name: string;
  status: string;
  isRunning: boolean;
  currentDriverId?: string | null;
  currentDriverName?: string | null;
  currentRoute?: { id: string; name: string } | null;
  activeTrip?: { id: string } | null;
  lastLocationAt?: string | null;
  automatic?: boolean;
}

interface EndpointState {
  label: string;
  ok: boolean;
}

function timeAgo(value?: string | null) {
  if (!value) return "수신 없음";
  const seconds = Math.max(0, Math.floor((Date.now() - new Date(value).getTime()) / 1000));
  if (seconds < 10) return "방금 전";
  if (seconds < 60) return `${seconds}초 전`;
  if (seconds < 3600) return `${Math.floor(seconds / 60)}분 전`;
  return `${Math.floor(seconds / 3600)}시간 전`;
}

export default function AdminDashboard() {
  const navigate = useNavigate();
  const [buses, setBuses] = useState<ManagedBus[]>([]);
  const [routes, setRoutes] = useState<BusRoute[]>([]);
  const [notices, setNotices] = useState<Notice[]>([]);
  const [reports, setReports] = useState<UserReport[]>([]);
  const [userCount, setUserCount] = useState(0);
  const [health, setHealth] = useState<EndpointState[]>([]);
  const [loading, setLoading] = useState(true);
  const [lastCheckedAt, setLastCheckedAt] = useState<Date | null>(null);

  const loadOperations = useCallback(async () => {
    setLoading(true);
    const results = await Promise.allSettled([
      api.getManagedBuses(), api.getRoutes(), api.getNotices(), api.getReports(), api.getUsers(),
    ]);
    const labels = ["버스·GPS", "노선", "공지", "문의", "사용자"];
    setHealth(results.map((result, index) => ({ label: labels[index], ok: result.status === "fulfilled" })));
    if (results[0].status === "fulfilled") setBuses(results[0].value);
    if (results[1].status === "fulfilled") setRoutes(results[1].value);
    if (results[2].status === "fulfilled") setNotices(results[2].value);
    if (results[3].status === "fulfilled") setReports(results[3].value);
    if (results[4].status === "fulfilled") setUserCount(results[4].value.length);
    setLastCheckedAt(new Date());
    setLoading(false);
  }, []);

  useEffect(() => {
    void loadOperations();
    const interval = window.setInterval(() => void loadOperations(), 15000);
    return () => window.clearInterval(interval);
  }, [loadOperations]);

  const operationalBuses = useMemo(() => {
    const actualRunning = buses.filter((bus) => bus.isRunning);
    const campusRoute = routes.find((route) => route.isActive && route.type === "campus" && route.name === "학내순환")
      ?? routes.find((route) => route.isActive && route.type === "campus");
    const campusRouteIds = new Set(routes.filter((route) => route.type === "campus").map((route) => route.id));
    const actualCampusCount = actualRunning.filter((bus) =>
      (bus.currentRoute?.id && campusRouteIds.has(bus.currentRoute.id)) || bus.name.includes("학내순환"),
    ).length;
    const actualRouteIds = new Set(actualRunning.map((bus) => bus.currentRoute?.id).filter(Boolean));
    const campusBuses: ManagedBus[] = Array.from({ length: Math.max(0, 3 - actualCampusCount) }, (_, index) => ({
      id: `automatic-campus-${actualCampusCount + index + 1}`,
      name: `학내순환 ${actualCampusCount + index + 1}호`,
      status: "active",
      isRunning: true,
      currentDriverName: "자동 운행",
      currentRoute: campusRoute ? { id: campusRoute.id, name: campusRoute.name } : { id: "campus-loop", name: "학내순환" },
      lastLocationAt: lastCheckedAt?.toISOString() ?? new Date().toISOString(),
      automatic: true,
    }));
    const commuterBuses: ManagedBus[] = routes
      .filter((route) => route.isActive && route.type === "commuter" && !actualRouteIds.has(route.id))
      .map((route) => ({
        id: `automatic-commuter-${route.id}`,
        name: `${route.name} 운행 버스`,
        status: "active",
        isRunning: true,
        currentDriverName: "자동 운행",
        currentRoute: { id: route.id, name: route.name },
        lastLocationAt: lastCheckedAt?.toISOString() ?? new Date().toISOString(),
        automatic: true,
      }));
    return [...actualRunning, ...campusBuses, ...commuterBuses];
  }, [buses, routes, lastCheckedAt]);

  const runningBuses = operationalBuses.filter((bus) => bus.isRunning);
  const openReports = reports.filter((report) => report.status !== "resolved");
  const issues = useMemo(() => {
    const now = lastCheckedAt?.getTime() ?? Date.now();
    return buses.flatMap((bus) => {
      const busIssues: Array<{ id: string; severity: "critical" | "warning"; title: string; detail: string }> = [];
      if (bus.isRunning && !bus.currentDriverId) busIssues.push({ id: `${bus.id}-driver`, severity: "critical", title: `${bus.name}: 기사 정보 없음`, detail: "운행 중인데 현재 기사 연결이 없습니다." });
      if (bus.isRunning && !bus.currentRoute) busIssues.push({ id: `${bus.id}-route`, severity: "critical", title: `${bus.name}: 노선 미지정`, detail: "운행 중인데 표시할 노선이 없습니다." });
      if (bus.isRunning && !bus.lastLocationAt) busIssues.push({ id: `${bus.id}-gps-none`, severity: "critical", title: `${bus.name}: GPS 수신 없음`, detail: "운행 시작 후 위치가 한 번도 들어오지 않았습니다." });
      if (bus.isRunning && bus.lastLocationAt && now - new Date(bus.lastLocationAt).getTime() > 15000) busIssues.push({ id: `${bus.id}-gps-stale`, severity: "warning", title: `${bus.name}: GPS 지연`, detail: `마지막 위치 ${timeAgo(bus.lastLocationAt)}` });
      if (bus.isRunning !== Boolean(bus.activeTrip)) busIssues.push({ id: `${bus.id}-state`, severity: "critical", title: `${bus.name}: 운행 상태 불일치`, detail: "버스 상태와 운행 기록이 서로 다릅니다." });
      return busIssues;
    });
  }, [buses, lastCheckedAt]);

  const criticalCount = issues.filter((issue) => issue.severity === "critical").length;
  const stats = [
    { label: "현재 운행", value: runningBuses.length, sub: `전체 ${operationalBuses.length}대`, icon: Bus, color: "bg-[#1e3b8a]" },
    { label: "즉시 확인", value: criticalCount, sub: `주의 포함 ${issues.length}건`, icon: AlertTriangle, color: criticalCount ? "bg-red-500" : "bg-green-500" },
    { label: "미해결 문의", value: openReports.length, sub: `신규 ${reports.filter((report) => report.status === "open").length}건`, icon: CircleHelp, color: "bg-amber-500" },
    { label: "활성 노선", value: routes.filter((route) => route.isActive).length, sub: `전체 ${routes.length}개`, icon: Route, color: "bg-cyan-600" },
  ];

  return (
    <AdminLayout>
      <div className="p-4 sm:p-6 lg:p-8">
        <div className="mb-6 flex flex-col gap-4 sm:flex-row sm:items-end sm:justify-between">
          <div>
            <h1 className="text-[26px] font-bold text-[#0f172a] sm:text-[32px]">운영 센터</h1>
            <p className="mt-1 text-[15px] text-[#64748b]">운행 이상과 사용자 문제를 한 화면에서 확인합니다.</p>
          </div>
          <div className="flex items-center gap-3">
            <span className="text-xs text-[#94a3b8]">{lastCheckedAt ? `${lastCheckedAt.toLocaleTimeString("ko-KR")} 확인` : "확인 중"}</span>
            <button type="button" onClick={() => void loadOperations()} disabled={loading} className="flex items-center gap-2 rounded-lg border border-[#cbd5e1] bg-white px-4 py-2.5 text-sm font-semibold text-[#475569] disabled:opacity-50">
              <RefreshCw className={`h-4 w-4 ${loading ? "animate-spin" : ""}`} /> 새로고침
            </button>
          </div>
        </div>

        <div className="mb-6 grid grid-cols-2 gap-3 xl:grid-cols-4">
          {stats.map((stat) => <div key={stat.label} className="rounded-xl border border-gray-100 bg-white p-4 shadow-sm sm:p-5"><div className="flex items-start justify-between gap-3"><div><p className="text-xs text-[#64748b]">{stat.label}</p><p className="mt-2 text-3xl font-bold text-[#0f172a]">{loading && !lastCheckedAt ? "—" : stat.value}</p><p className="mt-1 text-xs text-[#94a3b8]">{loading && !lastCheckedAt ? "불러오는 중" : stat.sub}</p></div><span className={`grid h-10 w-10 place-items-center rounded-xl ${stat.color}`}><stat.icon className="h-5 w-5 text-white" /></span></div></div>)}
        </div>

        <div className="mb-6 grid gap-6 xl:grid-cols-[1.35fr_1fr]">
          <section className="rounded-xl border border-gray-100 bg-white shadow-sm">
            <div className="flex items-center justify-between border-b border-gray-100 px-5 py-4"><div><h2 className="flex items-center gap-2 text-lg font-bold text-[#0f172a]"><AlertTriangle className="h-5 w-5 text-amber-500" />운영 이상</h2><p className="mt-1 text-xs text-[#94a3b8]">운행 데이터에서 자동 감지한 항목</p></div><button type="button" onClick={() => navigate("/admin/buses")} className="text-xs font-bold text-[#1e3b8a]">운행 관리</button></div>
            <div className="max-h-[350px] overflow-auto p-3">
              {loading && buses.length === 0 ? <p className="p-8 text-center text-sm text-[#94a3b8]">운영 상태 분석 중...</p> : issues.length === 0 ? <div className="flex flex-col items-center py-10 text-green-700"><CheckCircle2 className="mb-2 h-8 w-8" /><p className="text-sm font-bold">감지된 운행 이상이 없습니다.</p></div> : issues.map((issue) => <button type="button" key={issue.id} onClick={() => navigate("/admin/buses")} className="mb-2 flex w-full items-start gap-3 rounded-lg border border-gray-100 p-3 text-left hover:bg-gray-50"><span className={`mt-1 h-2.5 w-2.5 shrink-0 rounded-full ${issue.severity === "critical" ? "bg-red-500" : "bg-amber-400"}`} /><span><span className="block text-sm font-bold text-[#0f172a]">{issue.title}</span><span className="mt-1 block text-xs text-[#64748b]">{issue.detail}</span></span></button>)}
            </div>
          </section>

          <section className="rounded-xl border border-gray-100 bg-white shadow-sm">
            <div className="flex items-center justify-between border-b border-gray-100 px-5 py-4"><div><h2 className="flex items-center gap-2 text-lg font-bold text-[#0f172a]"><CircleHelp className="h-5 w-5 text-[#1e3b8a]" />사용자 문의</h2><p className="mt-1 text-xs text-[#94a3b8]">미해결 {openReports.length}건</p></div><button type="button" onClick={() => navigate("/admin/support")} className="text-xs font-bold text-[#1e3b8a]">전체 보기</button></div>
            <div className="max-h-[350px] overflow-auto p-3">
              {openReports.length === 0 ? <p className="p-8 text-center text-sm text-[#94a3b8]">미해결 문의가 없습니다.</p> : openReports.slice(0, 6).map((report) => <button type="button" key={report.id} onClick={() => navigate("/admin/support")} className="mb-2 w-full rounded-lg p-3 text-left hover:bg-gray-50"><div className="flex items-center justify-between gap-3"><p className="line-clamp-1 text-sm font-bold text-[#0f172a]">{report.title}</p><span className={`shrink-0 rounded-full px-2 py-1 text-[10px] font-bold ${report.status === "open" ? "bg-red-50 text-red-600" : "bg-amber-50 text-amber-700"}`}>{report.status === "open" ? "신규" : "처리 중"}</span></div><p className="mt-1 text-xs text-[#64748b]">{report.userName} · {timeAgo(report.createdAt)}</p></button>)}
            </div>
          </section>
        </div>

        <section className="mb-6 overflow-hidden rounded-xl border border-gray-100 bg-white shadow-sm">
          <div className="flex items-center justify-between border-b border-gray-100 px-5 py-4"><div><h2 className="flex items-center gap-2 text-lg font-bold text-[#0f172a]"><Activity className="h-5 w-5 text-green-600" />실시간 운행</h2><p className="mt-1 text-xs text-[#94a3b8]">사용자 화면과 동일한 운행 상태를 표시합니다.</p></div></div>
          <div className="overflow-x-auto"><table className="w-full min-w-[760px] text-sm"><thead className="bg-slate-50 text-left text-xs text-[#64748b]"><tr><th className="px-5 py-3">버스</th><th className="px-5 py-3">노선</th><th className="px-5 py-3">기사</th><th className="px-5 py-3">GPS</th><th className="px-5 py-3">상태</th></tr></thead><tbody>{operationalBuses.map((bus) => <tr key={bus.id} className="border-t border-gray-100"><td className="px-5 py-4 font-bold text-[#0f172a]">{bus.name}</td><td className="px-5 py-4 text-[#475569]">{bus.currentRoute?.name || "미지정"}</td><td className="px-5 py-4 text-[#475569]">{bus.currentDriverName || "-"}</td><td className="px-5 py-4"><span className={bus.isRunning && (!bus.lastLocationAt || Date.now() - new Date(bus.lastLocationAt).getTime() > 15000) ? "text-red-600" : "text-[#475569]"}>{bus.automatic ? "위치 갱신 중" : timeAgo(bus.lastLocationAt)}</span></td><td className="px-5 py-4"><span className={`rounded-full px-2.5 py-1 text-xs font-bold ${bus.isRunning ? "bg-green-100 text-green-700" : "bg-slate-100 text-slate-500"}`}>{bus.isRunning ? "운행 중" : "대기"}</span></td></tr>)}</tbody></table></div>
        </section>

        <div className="grid gap-4 lg:grid-cols-[1fr_1.5fr]">
          <section className="rounded-xl border border-gray-100 bg-white p-5 shadow-sm"><h2 className="mb-4 flex items-center gap-2 text-base font-bold text-[#0f172a]"><Server className="h-5 w-5" />데이터 연결 상태</h2><div className="grid grid-cols-2 gap-2">{health.map((item) => <div key={item.label} className="flex items-center justify-between rounded-lg bg-slate-50 px-3 py-2.5"><span className="text-xs text-[#475569]">{item.label}</span><span className={`text-xs font-bold ${item.ok ? "text-green-600" : "text-red-600"}`}>{item.ok ? "정상" : "오류"}</span></div>)}</div><p className="mt-3 text-xs text-[#94a3b8]">사용자 {userCount}명 · 공지 {notices.length}건</p></section>
          <section className="rounded-xl border border-gray-100 bg-white p-5 shadow-sm"><h2 className="mb-4 text-base font-bold text-[#0f172a]">빠른 대응</h2><div className="grid grid-cols-2 gap-3 sm:grid-cols-4">{[
            ["운행 조치", "/admin/buses", Bus], ["공지 발송", "/admin/notices", BellRing], ["문의 처리", "/admin/support", CircleHelp], ["사용자 확인", "/admin/users", UserRound],
          ].map(([label, path, Icon]) => <button key={String(path)} type="button" onClick={() => navigate(String(path))} className="flex flex-col items-center gap-2 rounded-lg border border-gray-200 p-4 text-xs font-bold text-[#334155] hover:border-[#1e3b8a] hover:bg-[#1e3b8a]/5"><Icon className="h-5 w-5 text-[#1e3b8a]" />{String(label)}</button>)}</div></section>
        </div>
      </div>
    </AdminLayout>
  );
}
