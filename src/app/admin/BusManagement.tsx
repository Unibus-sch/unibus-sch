import { useCallback, useEffect, useState } from "react";
import { AlertTriangle, Bus, Check, Clock3, Pencil, Plus, Power, RefreshCw, Square, Trash2, X } from "lucide-react";
import AdminLayout from "./AdminLayout";
import { api } from "../services/api";
import type { BusRoute } from "../types";

interface ManagedDriver { id: string; name: string; email: string; }
interface ManagedBus {
  id: string;
  name: string;
  type: string;
  status: string;
  isRunning: boolean;
  licensePlate?: string | null;
  assignedDriverId?: string | null;
  assignedDriverName?: string | null;
  currentDriverName?: string | null;
  currentRoute?: { id: string; name: string } | null;
  activeTrip?: { id: string } | null;
  lastLocationAt?: string | null;
}

const typeLabel: Record<string, string> = { campus: "학내순환", commuter: "통학", commute: "통학", direct: "직행" };
function timeAgo(value?: string | null) {
  if (!value) return "GPS 수신 없음";
  const seconds = Math.max(0, Math.floor((Date.now() - new Date(value).getTime()) / 1000));
  if (seconds < 10) return "방금 전";
  if (seconds < 60) return `${seconds}초 전`;
  if (seconds < 3600) return `${Math.floor(seconds / 60)}분 전`;
  return `${Math.floor(seconds / 3600)}시간 전`;
}

export default function BusManagement() {
  const [buses, setBuses] = useState<ManagedBus[]>([]);
  const [drivers, setDrivers] = useState<ManagedDriver[]>([]);
  const [routes, setRoutes] = useState<BusRoute[]>([]);
  const [loading, setLoading] = useState(true);
  const [actionId, setActionId] = useState<string | null>(null);
  const [error, setError] = useState("");
  const [editingId, setEditingId] = useState<string | null>(null);
  const [editingName, setEditingName] = useState("");
  const [form, setForm] = useState({ name: "", type: "campus", licensePlate: "", capacity: "", routeId: "" });
  const [creating, setCreating] = useState(false);

  const loadAll = useCallback(async () => {
    setLoading(true);
    setError("");
    try {
      const [busData, userData, routeData] = await Promise.all([api.getManagedBuses(), api.getUsers(), api.getRoutes()]);
      setBuses(busData);
      setDrivers(userData.filter((user: any) => user.role === "driver"));
      setRoutes(routeData.filter((route) => route.isActive));
    } catch (err) {
      setError(err instanceof Error ? err.message : "운행 정보를 불러오지 못했습니다.");
    } finally {
      setLoading(false);
    }
  }, []);

  const refreshBuses = useCallback(async () => {
    try { setBuses(await api.getManagedBuses()); } catch { /* keep the last confirmed state */ }
  }, []);

  useEffect(() => { void loadAll(); }, [loadAll]);
  useEffect(() => {
    const interval = window.setInterval(() => void refreshBuses(), 10000);
    return () => window.clearInterval(interval);
  }, [refreshBuses]);

  const mutateBus = async (busId: string, action: () => Promise<unknown>, failureMessage: string) => {
    setActionId(busId);
    setError("");
    try { await action(); await refreshBuses(); }
    catch (err) { setError(`${failureMessage}: ${err instanceof Error ? err.message : "알 수 없는 오류"}`); }
    finally { setActionId(null); }
  };

  const createBus = async (event: React.FormEvent) => {
    event.preventDefault();
    if (!form.name.trim()) { setError("버스 이름을 입력해 주세요."); return; }
    setCreating(true);
    setError("");
    try {
      await api.createBus({
        name: form.name.trim(), type: form.type,
        licensePlate: form.licensePlate.trim() || undefined,
        capacity: form.capacity ? Number(form.capacity) : undefined,
        routeId: form.routeId || undefined,
      });
      setForm({ name: "", type: "campus", licensePlate: "", capacity: "", routeId: "" });
      await refreshBuses();
    } catch (err) { setError(err instanceof Error ? err.message : "버스 등록에 실패했습니다."); }
    finally { setCreating(false); }
  };

  const rename = (bus: ManagedBus) => {
    const name = editingName.trim();
    if (!name) return;
    void mutateBus(bus.id, () => api.updateBus(bus.id, { name }), "이름 변경 실패").then(() => { setEditingId(null); setEditingName(""); });
  };

  return (
    <AdminLayout>
      <div className="p-4 sm:p-6 lg:p-8">
        <div className="mb-6 flex flex-col gap-4 sm:flex-row sm:items-end sm:justify-between">
          <div><h1 className="text-[26px] font-bold text-[#0f172a] sm:text-[32px]">운행 관리</h1><p className="mt-1 text-[15px] text-[#64748b]">기사·노선 배정, GPS 상태, 강제 종료를 한 곳에서 처리합니다.</p></div>
          <button type="button" onClick={() => void loadAll()} disabled={loading} className="flex items-center justify-center gap-2 rounded-lg border border-[#cbd5e1] bg-white px-4 py-2.5 text-sm font-semibold text-[#475569] disabled:opacity-50"><RefreshCw className={`h-4 w-4 ${loading ? "animate-spin" : ""}`} />새로고침</button>
        </div>

        {error ? <div role="alert" className="mb-5 flex items-center gap-2 rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700"><AlertTriangle className="h-4 w-4 shrink-0" />{error}</div> : null}

        <section className="mb-6 rounded-xl border border-gray-100 bg-white p-4 shadow-sm sm:p-6">
          <div className="mb-4 flex items-center justify-between"><div><h2 className="text-lg font-bold text-[#0f172a]">실시간 차량</h2><p className="mt-1 text-xs text-[#94a3b8]">10초마다 자동 갱신 · GPS 15초 이상 지연 시 주의</p></div><span className="rounded-full bg-slate-100 px-3 py-1 text-xs font-bold text-[#475569]">운행 {buses.filter((bus) => bus.isRunning).length} / {buses.length}</span></div>
          {loading && buses.length === 0 ? <p className="py-12 text-center text-sm text-[#94a3b8]">차량 상태 확인 중...</p> : buses.length === 0 ? <p className="py-12 text-center text-sm text-[#94a3b8]">등록된 버스가 없습니다.</p> : <div className="space-y-3">{buses.map((bus) => {
            const gpsStale = bus.isRunning && (!bus.lastLocationAt || Date.now() - new Date(bus.lastLocationAt).getTime() > 15000);
            const stateMismatch = bus.isRunning !== Boolean(bus.activeTrip);
            return <article key={bus.id} className={`rounded-xl border p-4 ${gpsStale || stateMismatch ? "border-red-200 bg-red-50/30" : bus.isRunning ? "border-green-200 bg-green-50/20" : "border-gray-200"}`}>
              <div className="flex flex-col gap-4 xl:flex-row xl:items-center">
                <div className="flex min-w-0 flex-1 items-start gap-3"><span className={`grid h-10 w-10 shrink-0 place-items-center rounded-lg ${bus.isRunning ? "bg-green-600" : "bg-[#1e3b8a]"}`}><Bus className="h-5 w-5 text-white" /></span><div className="min-w-0">
                  <div className="flex flex-wrap items-center gap-2">{editingId === bus.id ? <><input aria-label="버스 이름" autoFocus value={editingName} onChange={(event) => setEditingName(event.target.value)} onKeyDown={(event) => { if (event.key === "Enter") rename(bus); if (event.key === "Escape") setEditingId(null); }} className="h-8 rounded-lg border border-[#1e3b8a] px-2 text-sm font-bold" /><button type="button" aria-label="이름 저장" onClick={() => rename(bus)}><Check className="h-4 w-4 text-green-700" /></button><button type="button" aria-label="이름 수정 취소" onClick={() => setEditingId(null)}><X className="h-4 w-4 text-[#64748b]" /></button></> : <><h3 className="text-sm font-bold text-[#0f172a]">{bus.name}</h3><button type="button" aria-label={`${bus.name} 이름 수정`} onClick={() => { setEditingId(bus.id); setEditingName(bus.name); }}><Pencil className="h-3.5 w-3.5 text-[#94a3b8]" /></button></>}
                    <span className={`rounded-full px-2 py-0.5 text-[10px] font-bold ${bus.isRunning ? "bg-green-100 text-green-700" : "bg-slate-100 text-slate-500"}`}>{bus.isRunning ? "운행 중" : "대기"}</span><span className="rounded-full bg-blue-50 px-2 py-0.5 text-[10px] font-bold text-blue-700">{typeLabel[bus.type] || bus.type}</span>{stateMismatch ? <span className="rounded-full bg-red-100 px-2 py-0.5 text-[10px] font-bold text-red-700">기록 불일치</span> : null}
                  </div><p className={`mt-1 flex items-center gap-1 text-xs ${gpsStale ? "font-bold text-red-600" : "text-[#64748b]"}`}><Clock3 className="h-3.5 w-3.5" />{timeAgo(bus.lastLocationAt)}{bus.licensePlate ? ` · ${bus.licensePlate}` : ""}</p>
                </div></div>
                <div className="grid gap-2 sm:grid-cols-2 xl:flex xl:items-center">
                  <label className="text-[11px] font-semibold text-[#64748b]">기사<select aria-label={`${bus.name} 담당 기사`} value={bus.assignedDriverId || ""} onChange={(event) => void mutateBus(bus.id, () => api.updateBus(bus.id, { assignedDriverId: event.target.value || null }), "기사 배정 실패")} disabled={actionId === bus.id} className="mt-1 block h-9 w-full min-w-[145px] rounded-lg border border-[#cbd5e1] bg-white px-2 text-xs text-[#0f172a]"><option value="">공용 배차</option>{drivers.map((driver) => <option key={driver.id} value={driver.id}>{driver.name}</option>)}</select></label>
                  <label className="text-[11px] font-semibold text-[#64748b]">노선<select aria-label={`${bus.name} 운행 노선`} value={bus.currentRoute?.id || ""} onChange={(event) => void mutateBus(bus.id, () => api.updateBus(bus.id, { currentRouteId: event.target.value || null }), "노선 배정 실패")} disabled={actionId === bus.id || bus.isRunning} className="mt-1 block h-9 w-full min-w-[170px] rounded-lg border border-[#cbd5e1] bg-white px-2 text-xs text-[#0f172a] disabled:bg-slate-100"><option value="">노선 미지정</option>{routes.map((route) => <option key={route.id} value={route.id}>{route.name}</option>)}</select></label>
                  <button type="button" onClick={() => void mutateBus(bus.id, () => api.updateBus(bus.id, { status: bus.status === "active" ? "inactive" : "active" }), "상태 변경 실패")} disabled={actionId === bus.id || bus.isRunning} className="mt-auto flex h-9 items-center justify-center gap-1.5 rounded-lg border border-gray-200 px-3 text-xs font-bold text-[#475569] disabled:opacity-40"><Power className="h-3.5 w-3.5" />{bus.status === "active" ? "비활성화" : "활성화"}</button>
                  {bus.isRunning || stateMismatch ? <button type="button" onClick={() => { if (window.confirm(`${bus.name} 운행을 강제 종료하고 활성 운행 기록도 정리할까요?`)) void mutateBus(bus.id, () => api.forceStopBus(bus.id), "강제 종료 실패"); }} disabled={actionId === bus.id} className="mt-auto flex h-9 items-center justify-center gap-1.5 rounded-lg border border-red-200 px-3 text-xs font-bold text-red-600 disabled:opacity-40"><Square className="h-3.5 w-3.5" />강제 종료</button> : <button type="button" aria-label={`${bus.name} 삭제`} onClick={() => { if (window.confirm(`${bus.name}을 삭제할까요?`)) void mutateBus(bus.id, () => api.deleteBus(bus.id), "삭제 실패"); }} disabled={actionId === bus.id || bus.status === "active"} className="mt-auto grid h-9 place-items-center rounded-lg border border-gray-200 text-[#94a3b8] hover:text-red-600 disabled:opacity-30"><Trash2 className="h-4 w-4" /></button>}
                </div>
              </div>
            </article>;
          })}</div>}
        </section>

        <section className="rounded-xl border border-gray-100 bg-white p-4 shadow-sm sm:p-6"><h2 className="text-lg font-bold text-[#0f172a]">버스 등록</h2><p className="mb-5 mt-1 text-xs text-[#94a3b8]">새 차량을 등록하면서 기본 노선을 함께 지정할 수 있습니다.</p><form onSubmit={createBus} className="grid gap-4 md:grid-cols-2 xl:grid-cols-5">
          <label className="text-xs font-bold text-[#475569]">이름 *<input value={form.name} onChange={(event) => setForm((current) => ({ ...current, name: event.target.value }))} placeholder="학내순환 1호차" className="mt-1.5 h-11 w-full rounded-lg border border-[#cbd5e1] px-3 text-sm" /></label>
          <label className="text-xs font-bold text-[#475569]">유형<select value={form.type} onChange={(event) => setForm((current) => ({ ...current, type: event.target.value }))} className="mt-1.5 h-11 w-full rounded-lg border border-[#cbd5e1] bg-white px-3 text-sm"><option value="campus">학내순환</option><option value="commuter">통학버스</option></select></label>
          <label className="text-xs font-bold text-[#475569]">기본 노선<select value={form.routeId} onChange={(event) => setForm((current) => ({ ...current, routeId: event.target.value }))} className="mt-1.5 h-11 w-full rounded-lg border border-[#cbd5e1] bg-white px-3 text-sm"><option value="">미지정</option>{routes.map((route) => <option key={route.id} value={route.id}>{route.name}</option>)}</select></label>
          <label className="text-xs font-bold text-[#475569]">차량 번호<input value={form.licensePlate} onChange={(event) => setForm((current) => ({ ...current, licensePlate: event.target.value }))} placeholder="충남 70 가 1234" className="mt-1.5 h-11 w-full rounded-lg border border-[#cbd5e1] px-3 text-sm" /></label>
          <label className="text-xs font-bold text-[#475569]">정원<input type="number" min="1" value={form.capacity} onChange={(event) => setForm((current) => ({ ...current, capacity: event.target.value }))} placeholder="45" className="mt-1.5 h-11 w-full rounded-lg border border-[#cbd5e1] px-3 text-sm" /></label>
          <button type="submit" disabled={creating} className="flex h-11 items-center justify-center gap-2 rounded-lg bg-[#1e3b8a] px-5 text-sm font-bold text-white disabled:opacity-50 md:col-span-2 xl:col-span-5 xl:w-fit"><Plus className="h-4 w-4" />{creating ? "등록 중..." : "버스 등록"}</button>
        </form></section>
      </div>
    </AdminLayout>
  );
}
