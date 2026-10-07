import { useMemo, useState, useEffect, useRef } from "react";
import { createPortal } from "react-dom";
import NaverMapComponent from "./NaverMapComponent";
import { useLanguage } from "../contexts/LanguageContext";
import { api } from "../services/api";

interface RouteMapModalProps {
  route: any;
  onClose: () => void;
  bus?: {
    position: { lat: number; lng: number };
    etaMins: number;
    heading?: number;
    isSimulation?: boolean;
  };
}

export default function RouteMapModal({ route, onClose, bus }: RouteMapModalProps) {
  const { t } = useLanguage();

  const rawStops: any[] = route.stops || [];

  const [mapStops, setMapStops] = useState<Array<{ id: string; name: string; position: { lat: number; lng: number } }>>([]);
  const [routePath, setRoutePath] = useState<[number, number][]>([]);
  const [loading, setLoading] = useState(true);
  const [noLocation, setNoLocation] = useState(false);

  useEffect(() => {
    setLoading(true);
    setMapStops([]);
    setRoutePath([]);
    setNoLocation(false);

    api.getRoutePath(route.id)
      .then(({ stops, path }) => {
        const withCoords = stops.filter((s) => s.lat != null && s.lng != null);
        if (withCoords.length === 0) {
          setNoLocation(true);
        } else {
          setMapStops(
            withCoords.map((s, i) => ({
              id: s.id,
              name: s.name,
              position: { lat: s.lat!, lng: s.lng! },
              type: i === 0 ? 'start' : i === withCoords.length - 1 ? 'end' : 'middle',
            } as any))
          );
          setRoutePath(path);
        }
      })
      .catch(() => setNoLocation(true))
      .finally(() => setLoading(false));
  }, [route.id]);

  const center = useMemo(() => {
    if (mapStops.length === 0) return { lat: 36.7694, lng: 126.9322 };
    return {
      lat: mapStops.reduce((s, m) => s + m.position.lat, 0) / mapStops.length,
      lng: mapStops.reduce((s, m) => s + m.position.lng, 0) / mapStops.length,
    };
  }, [mapStops]);

  const closeButtonRef = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    const previousFocus = document.activeElement as HTMLElement | null;
    closeButtonRef.current?.focus();
    return () => previousFocus?.focus();
  }, []);
  useEffect(() => {
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") onClose();
    };
    document.addEventListener("keydown", handleKeyDown);
    return () => document.removeEventListener("keydown", handleKeyDown);
  }, [onClose]);

  return createPortal(
    <div role="dialog" aria-modal="true" aria-labelledby="route-map-title" className="font-['Public_Sans'] fixed inset-0 z-[100] flex items-end justify-center">
      <div className="absolute inset-0 bg-black/50 backdrop-blur-[2px]" onClick={onClose} />
      <div
        className="unibus-sheet relative bg-unibus-surface rounded-t-[24px] w-full max-w-[430px] flex flex-col"
        style={{ height: "90dvh" }}
        onClick={(e) => e.stopPropagation()}
      >
        {/* 헤더 */}
        <div className="flex items-center gap-3 px-[20px] pt-[20px] pb-[14px] border-b border-unibus-divider shrink-0">
          <div
            className="rounded-[10px] size-[40px] flex items-center justify-center shrink-0"
            style={{ backgroundColor: "var(--unibus-brand)" }}
          >
            <svg className="w-5 h-5 text-unibus-brand-foreground" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 20l-5.447-2.724A1 1 0 013 16.382V5.618a1 1 0 011.447-.894L9 7m0 13l6-3m-6 3V7m6 10l4.553 2.276A1 1 0 0021 18.382V7.618a1 1 0 00-.553-.894L15 4m0 13V4m0 0L9 7" />
            </svg>
          </div>
          <div className="flex-1">
            <p id="route-map-title" className="font-semibold text-unibus-text text-[16px] leading-6">
              {route.name?.replace(/\[(출발|도착)\]\s*/g, "")}
            </p>
            <p className="text-unibus-muted text-[13px]">
              {loading
                ? t("위치 불러오는 중...", "Loading locations...")
                : `${t("전체 정류장", "All Stops")} · ${rawStops.length}${t("개", "")}`}
            </p>
          </div>
          <button
            ref={closeButtonRef}
            type="button"
            aria-label={t("노선 지도 닫기", "Close route map")}
            onClick={onClose}
            className="size-[44px] rounded-full bg-unibus-surface-subtle flex items-center justify-center hover:bg-unibus-divider active:scale-95 transition-all shrink-0"
          >
            <svg className="w-4 h-4 text-unibus-muted" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
            </svg>
          </button>
        </div>

        {/* 지도 영역 */}
        <div className="relative flex-1 overflow-hidden bg-unibus-surface-subtle">
          {loading ? (
            <div className="absolute inset-0 flex flex-col items-center justify-center gap-3">
              <div className="w-8 h-8 border-2 border-unibus-brand border-t-transparent rounded-full animate-spin" />
              <p className="text-unibus-muted text-[13px]">
                {t("정류장 위치 검색 중...", "Finding stop locations...")}
              </p>
            </div>
          ) : noLocation ? (
            <div className="absolute inset-0 flex flex-col items-center justify-center gap-2">
              <svg className="w-10 h-10 text-[#cbd5e1]" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M9 20l-5.447-2.724A1 1 0 013 16.382V5.618a1 1 0 011.447-.894L9 7m0 13l6-3m-6 3V7m6 10l4.553 2.276A1 1 0 0021 18.382V7.618a1 1 0 00-.553-.894L15 4m0 13V4m0 0L9 7" />
              </svg>
              <p className="text-unibus-muted text-[13px]">
                {t("정류장 위치를 찾지 못했습니다", "Could not find stop locations")}
              </p>
            </div>
          ) : (
            <NaverMapComponent
              center={center}
              zoom={12}
              stops={mapStops}
              routePath={routePath}
              buses={bus ? [{
                id: `commuter-${route.id}`,
                label: route.name,
                position: bus.position,
                heading: bus.heading,
                etaLabel: `${bus.etaMins}분`,
                isSimulation: routePath.length > 1,
                routeAnimationMode: 'ping-pong',
              }] : []}
              fitBoundsKey={1}
            />
          )}

        </div>

        {/* 정류장 목록 */}
        <div className="border-t border-unibus-divider bg-unibus-surface shrink-0" style={{ maxHeight: "30vh" }}>
          <div className="px-[20px] pt-[12px] pb-[4px]">
            <p className="font-bold text-unibus-text text-[13px]">
              {t("정류장 순서", "Stop Order")}
            </p>
          </div>
          <div className="overflow-y-auto px-[20px] pb-[20px]" style={{ maxHeight: "calc(30vh - 40px)" }}>
            {rawStops.length > 0 ? (
              <div>
                {rawStops.map((stop: any, index: number) => {
                  const isFirst = index === 0;
                  const isLast = index === rawStops.length - 1;
                  const dotColor = isFirst || isLast ? "var(--unibus-brand)" : "var(--unibus-text-muted)";
                  return (
                    <div key={index} className="flex gap-3">
                      <div className="flex flex-col items-center">
                        <div
                          className="rounded-full size-[24px] flex items-center justify-center font-bold text-[13px] text-unibus-brand-foreground shrink-0 z-10"
                          style={{ backgroundColor: dotColor }}
                        >
                          {index + 1}
                        </div>
                        {!isLast && <div className="w-[2px] flex-1 min-h-[16px] bg-unibus-divider" />}
                      </div>
                      <div className={`flex-1 py-[2px] ${!isLast ? "pb-[10px]" : ""}`}>
                        <p
                          className="font-semibold text-[13px]"
                          style={{ color: isFirst || isLast ? "var(--unibus-brand)" : "var(--unibus-text-strong)" }}
                        >
                          {stop.name}
                        </p>
                        {isFirst && (
                          <p className="text-[13px] text-unibus-muted">
                            {t("출발", "Departure")}
                          </p>
                        )}
                        {isLast && (
                          <p className="text-[13px] text-unibus-brand">
                            {t("도착", "Arrival")}
                          </p>
                        )}
                      </div>
                    </div>
                  );
                })}
              </div>
            ) : (
              <p className="text-unibus-muted text-[13px] py-4">
                {t("정류장 정보가 없습니다", "No stop information")}
              </p>
            )}
          </div>
        </div>
      </div>
    </div>,
    document.body,
  );
}
