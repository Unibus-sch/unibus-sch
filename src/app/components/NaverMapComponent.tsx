import { useCallback, useEffect, useRef } from "react";

interface NaverMapProps {
  center?: { lat: number; lng: number };
  zoom?: number;
  buses?: Array<{
    id: string;
    position: { lat: number; lng: number };
    heading?: number;
    label: string;
    etaLabel?: string;
    isSimulation?: boolean;
    routeAnimationMode?: 'loop' | 'ping-pong';
  }>;
  stops?: Array<{ id: string; name: string; position: { lat: number; lng: number }; type?: 'start' | 'end' | 'middle' }>;
  userLocation?: { lat: number; lng: number } | null;
  focusLocation?: { lat: number; lng: number; zoom?: number; key?: number } | null;
  fitBoundsKey?: number;
  autoFitBounds?: boolean;
  fitBoundsOptions?: { top: number; right: number; bottom: number; left: number; maxZoom?: number; zoomOffset?: number };
  fitBoundsPoints?: Array<{ lat: number; lng: number }>;
  numberedStops?: boolean;
  routePath?: [number, number][]; // [[lng, lat], ...] from Naver Directions API
  onBusClick?: (busId: string) => void;
  onLocateRequest?: () => void;
  clientId?: string;
}

declare global {
  interface Window { naver: any; }
}

const getNaverMaps = () => {
  const maps = window.naver?.maps;
  return typeof maps?.LatLng === "function" ? maps : null;
};

const DEFAULT_FIT_BOUNDS_OPTIONS = { top: 80, right: 80, bottom: 80, left: 80 };
const EMPTY_FIT_BOUNDS_POINTS: Array<{ lat: number; lng: number }> = [];

const escapeHtml = (value: string) =>
  value.replace(/[&<>"']/g, (char) => ({
    "&": "&amp;",
    "<": "&lt;",
    ">": "&gt;",
    "\"": "&quot;",
    "'": "&#39;",
  })[char] || char);

// 지도 앱에서 익숙한 핀형 차량 마커. 핀 끝이 실제 좌표를 가리키고, 작은 화살표만 진행 방향을 표시한다.
const BUS_MARKER_CONTENT = (label: string, rotation = 0, etaLabel?: string) => `
  <div style="width:104px;height:78px;display:flex;flex-direction:column;align-items:center;cursor:pointer;filter:drop-shadow(0 4px 8px rgba(15,23,42,0.28));">
    <div style="max-width:140px;margin-bottom:4px;background:white;color:#0f172a;border:1px solid rgba(15,23,42,0.12);box-shadow:0 2px 5px rgba(15,23,42,0.12);padding:4px 8px;border-radius:8px;font-size:13px;font-weight:600;line-height:18px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;font-family:sans-serif;text-align:center;">
      <div>${escapeHtml(label)}</div>
      ${etaLabel ? `<div style="margin-top:2px;color:#64748b;font-size:12px;font-weight:500;">${escapeHtml(etaLabel)} 도착 예정</div>` : ""}
    </div>
    <div style="position:relative;width:38px;height:42px;">
      <div style="position:absolute;left:50%;top:-6px;width:0;height:0;border-left:5px solid transparent;border-right:5px solid transparent;border-bottom:9px solid #ef4444;transform:translateX(-50%) rotate(${rotation}deg);transform-origin:50% 22px;transition:transform 0.25s ease;"></div>
      <svg width="38" height="42" viewBox="0 0 38 42" fill="none" xmlns="http://www.w3.org/2000/svg">
        <path d="M19 41C19 41 34 27.2 34 16.5C34 7.94 27.28 1 19 1C10.72 1 4 7.94 4 16.5C4 27.2 19 41 19 41Z" fill="#1e3b8a" stroke="white" stroke-width="3"/>
        <circle cx="19" cy="16.5" r="11.5" fill="white"/>
        <rect x="11" y="10" width="16" height="12" rx="2.5" fill="#1e3b8a"/>
        <rect x="13" y="12.5" width="5" height="4" rx="0.8" fill="white" opacity="0.95"/>
        <rect x="20" y="12.5" width="5" height="4" rx="0.8" fill="white" opacity="0.95"/>
        <path d="M12.5 18.5H25.5" stroke="white" stroke-width="1.5" stroke-linecap="round" opacity="0.8"/>
        <circle cx="14.5" cy="23" r="1.5" fill="#1e3b8a" stroke="white" stroke-width="1"/>
        <circle cx="23.5" cy="23" r="1.5" fill="#1e3b8a" stroke="white" stroke-width="1"/>
      </svg>
    </div>
  </div>
`;

const STOP_MARKER_CONTENT = (name: string, _type: 'start' | 'end' | 'middle' = 'middle') => `
  <div style="width:128px;height:58px;display:flex;flex-direction:column;align-items:center;cursor:default;filter:drop-shadow(0 3px 8px rgba(0,0,0,0.3));">
    <div style="max-width:160px;background:#1e3a8a;color:white;padding:4px 10px;border-radius:6px;font-size:13px;font-weight:600;line-height:18px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;font-family:sans-serif;letter-spacing:0;">
      ${escapeHtml(name)}
    </div>
    <div style="width:2.5px;height:10px;background:#1e3a8a;"></div>
    <div style="width:20px;height:20px;border-radius:50%;background:#1e3a8a;border:3px solid white;box-shadow:0 2px 6px rgba(30,58,138,0.5);box-sizing:border-box;"></div>
  </div>
`;

const NUMBERED_STOP_MARKER_CONTENT = (index: number) => `
  <div style="width:30px;height:38px;display:flex;flex-direction:column;align-items:center;filter:drop-shadow(0 3px 6px rgba(15,23,42,0.25));">
    <div style="width:26px;height:26px;border-radius:50%;display:flex;align-items:center;justify-content:center;background:#1e3a8a;color:white;border:2px solid white;font:800 11px sans-serif;box-sizing:border-box;">${index + 1}</div>
    <div style="width:2px;height:9px;background:#1e3a8a;"></div>
  </div>
`;

const USER_MARKER_CONTENT = () => `
  <div style="position:relative;width:28px;height:28px;display:flex;align-items:center;justify-content:center;">
    <div style="position:absolute;width:28px;height:28px;border-radius:50%;background:rgba(30,58,138,0.18);"></div>
    <div style="position:relative;width:14px;height:14px;border-radius:50%;background:#1e3a8a;border:3px solid white;box-shadow:0 2px 8px rgba(30,58,138,0.4);"></div>
  </div>
`;

const DEFAULT_REALTIME_INTERP_MS = 1_000;
const MIN_REALTIME_INTERP_MS = 250;
const MAX_REALTIME_INTERP_MS = 5_500;
// 시뮬레이션 마커는 실제 GPS 갱신과 무관하게 이 시간 동안 노선을 한 바퀴 돈다.
// 영상에서도 이동이 명확히 보이되 지도 사용 중에는 지나치게 빠르지 않은 속도다.
const SIMULATION_LOOP_MS = 120_000;
const OPEN_ROUTE_CYCLE_MS = 100_000;
const HEADING_ICON_UPDATE_MS = 180;

interface RouteSample {
  position: { lat: number; lng: number };
  heading: number;
}

interface RouteAnimationMetric {
  path: [number, number][];
  cumulative: number[];
  total: number;
  signature: string;
}

const distanceBetweenRoutePoints = (from: [number, number], to: [number, number]) => {
  const averageLatitude = ((from[1] + to[1]) / 2) * Math.PI / 180;
  const x = (to[0] - from[0]) * Math.cos(averageLatitude);
  const y = to[1] - from[1];
  return Math.hypot(x, y);
};

const createRouteAnimationMetric = (
  routePath: [number, number][],
  closeRoute: boolean,
): RouteAnimationMetric | null => {
  if (routePath.length < 2) return null;
  const path = [...routePath];
  const first = path[0];
  const last = path[path.length - 1];
  if (closeRoute && distanceBetweenRoutePoints(first, last) > 0.00008) path.push(first);

  const cumulative = [0];
  for (let index = 1; index < path.length; index += 1) {
    cumulative.push(cumulative[index - 1] + distanceBetweenRoutePoints(path[index - 1], path[index]));
  }
  const total = cumulative[cumulative.length - 1];
  if (!Number.isFinite(total) || total <= 0) return null;
  return {
    path,
    cumulative,
    total,
    signature: `${closeRoute ? 'loop' : 'open'}:${path.length}:${path[0].join(',')}:${path[path.length - 1].join(',')}`,
  };
};

const sampleRouteAnimation = (
  metric: RouteAnimationMetric,
  distance: number,
  loop: boolean,
): RouteSample => {
  const normalized = loop
    ? ((distance % metric.total) + metric.total) % metric.total
    : Math.max(0, Math.min(metric.total, distance));
  let index = metric.cumulative.findIndex((value) => value >= normalized);
  if (index <= 0) index = 1;
  const segmentStart = metric.cumulative[index - 1];
  const segmentLength = metric.cumulative[index] - segmentStart || 1;
  const ratio = (normalized - segmentStart) / segmentLength;
  const [fromLng, fromLat] = metric.path[index - 1];
  const [toLng, toLat] = metric.path[index];
  return {
    position: {
      lat: fromLat + (toLat - fromLat) * ratio,
      lng: fromLng + (toLng - fromLng) * ratio,
    },
    heading: (Math.atan2(toLng - fromLng, toLat - fromLat) * 180 / Math.PI + 360) % 360,
  };
};

const stableBusPhase = (busId: string) => {
  const numericSlot = Number(busId.match(/(\d+)$/)?.[1]);
  if (Number.isFinite(numericSlot) && numericSlot > 0) {
    return ((numericSlot - 1) % 3) / 3;
  }
  let hash = 0;
  for (let index = 0; index < busId.length; index += 1) {
    hash = (hash * 31 + busId.charCodeAt(index)) >>> 0;
  }
  return (hash % 10_000) / 10_000;
};

export default function NaverMapComponent({
  center = { lat: 36.7694, lng: 126.9322 },
  zoom = 16,
  buses = [],
  stops = [],
  userLocation = null,
  focusLocation = null,
  fitBoundsKey = 0,
  autoFitBounds = false,
  fitBoundsOptions = DEFAULT_FIT_BOUNDS_OPTIONS,
  fitBoundsPoints = EMPTY_FIT_BOUNDS_POINTS,
  numberedStops = false,
  routePath = [],
  onBusClick,
  onLocateRequest,
  clientId = import.meta.env.VITE_NAVER_CLIENT_ID || "YOUR_NAVER_CLIENT_ID",
}: NaverMapProps) {
  const mapRef = useRef<HTMLDivElement>(null);
  const mapInstance = useRef<any>(null);
  const busMarkersRef = useRef<any[]>([]);
  const stopMarkersRef = useRef<any[]>([]);
  const userMarkerRef = useRef<any>(null);
  const polylineRef = useRef<any>(null);
  const scriptLoadedRef = useRef<boolean>(false);
  const fitBoundsFrameRef = useRef<number | null>(null);
  const routeAnimationMetricRef = useRef<RouteAnimationMetric | null>(null);
  const routeAnimationSourceSignatureRef = useRef('');

  const busesRef = useRef(buses);
  busesRef.current = buses;
  const stopsRef = useRef(stops);
  stopsRef.current = stops;
  const onBusClickRef = useRef(onBusClick);
  onBusClickRef.current = onBusClick;
  const userLocationRef = useRef(userLocation);
  userLocationRef.current = userLocation;
  const routePathRef = useRef(routePath);
  routePathRef.current = routePath;
  const autoFitBoundsRef = useRef(autoFitBounds);
  autoFitBoundsRef.current = autoFitBounds;
  const fitBoundsOptionsRef = useRef(fitBoundsOptions);
  fitBoundsOptionsRef.current = fitBoundsOptions;
  const fitBoundsPointsRef = useRef(fitBoundsPoints);
  fitBoundsPointsRef.current = fitBoundsPoints;
  const onLocateRequestRef = useRef(onLocateRequest);
  onLocateRequestRef.current = onLocateRequest;
  const initialCenterRef = useRef(center);
  const initialZoomRef = useRef(zoom);

  const fitMapToContent = useCallback(() => {
    const maps = getNaverMaps();
    if (!mapInstance.current || !maps) return;

    const bounds = new maps.LatLngBounds();
    let pointCount = 0;
    stopsRef.current.forEach((stop) => {
      if (!Number.isFinite(stop.position.lat) || !Number.isFinite(stop.position.lng)) return;
      bounds.extend(new maps.LatLng(stop.position.lat, stop.position.lng));
      pointCount += 1;
    });
    routePathRef.current.forEach(([lng, lat]) => {
      if (!Number.isFinite(lat) || !Number.isFinite(lng)) return;
      bounds.extend(new maps.LatLng(lat, lng));
      pointCount += 1;
    });
    fitBoundsPointsRef.current.forEach(({ lat, lng }) => {
      if (!Number.isFinite(lat) || !Number.isFinite(lng)) return;
      bounds.extend(new maps.LatLng(lat, lng));
      pointCount += 1;
    });
    if (pointCount === 0) return;

    const { zoomOffset = 0, ...naverFitOptions } = fitBoundsOptionsRef.current;
    mapInstance.current.fitBounds(bounds, naverFitOptions);
    if (zoomOffset !== 0) {
      const currentZoom = mapInstance.current.getZoom();
      const nextZoom = currentZoom + zoomOffset;
      mapInstance.current.setZoom(
        naverFitOptions.maxZoom == null ? nextZoom : Math.min(naverFitOptions.maxZoom, nextZoom),
      );
    }
  }, []);

  const requestFitMapToContent = useCallback(() => {
    if (fitBoundsFrameRef.current !== null) {
      cancelAnimationFrame(fitBoundsFrameRef.current);
    }
    fitBoundsFrameRef.current = requestAnimationFrame(() => {
      fitBoundsFrameRef.current = null;
      fitMapToContent();
    });
  }, [fitMapToContent]);

  // ── snap-to-segment: GPS 좌표를 경로 선분 위 최근접 점으로 스냅 ──
  const snapToSegment = useCallback((
    px: number, py: number,
    ax: number, ay: number,
    bx: number, by: number
  ): { x: number; y: number; t: number; dist: number } => {
    const dx = bx - ax, dy = by - ay;
    const lenSq = dx * dx + dy * dy;
    if (lenSq === 0) {
      return { x: ax, y: ay, t: 0, dist: (px - ax) ** 2 + (py - ay) ** 2 };
    }
    const t = Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / lenSq));
    const cx = ax + t * dx, cy = ay + t * dy;
    return { x: cx, y: cy, t, dist: (px - cx) ** 2 + (py - cy) ** 2 };
  }, []);

  const updateBusMarkerIcon = useCallback((
    marker: any,
    label: string,
    heading: number,
    etaLabel?: string,
    force = false,
  ) => {
    const nextEtaLabel = etaLabel ?? '';
    const previousHeading = marker.__heading ?? heading;
    const headingDelta = ((heading - previousHeading) % 360 + 540) % 360 - 180;
    const labelChanged = marker.__label !== label || marker.__etaLabel !== nextEtaLabel;
    if (!force && Math.abs(headingDelta) <= 5 && !labelChanged) return;
    try {
      marker.setIcon({
        content: BUS_MARKER_CONTENT(label, Math.round(heading), nextEtaLabel),
        size: new window.naver.maps.Size(104, 78),
        anchor: new window.naver.maps.Point(52, 78),
      });
    } catch (_) {}
    marker.__label = label;
    marker.__etaLabel = nextEtaLabel;
    marker.__heading = heading;
  }, []);

  const startSimulatedMarkerAnimation = useCallback((
    marker: any,
    bus: NonNullable<NaverMapProps['buses']>[number],
  ) => {
    let metric = routeAnimationMetricRef.current;
    const currentRoute = routePathRef.current;
    const animationMode = bus.routeAnimationMode ?? 'loop';
    const loop = animationMode === 'loop';
    const sourceSignature = currentRoute.length > 1
      ? `${animationMode}:${currentRoute.length}:${currentRoute[0].join(',')}:${currentRoute[currentRoute.length - 1].join(',')}`
      : '';
    if (!metric || routeAnimationSourceSignatureRef.current !== sourceSignature) {
      metric = createRouteAnimationMetric(currentRoute, loop);
      routeAnimationMetricRef.current = metric;
      routeAnimationSourceSignatureRef.current = sourceSignature;
    }
    if (!metric) return false;

    updateBusMarkerIcon(marker, bus.label, marker.__heading ?? bus.heading ?? 0, bus.etaLabel);
    marker.__isSimulation = true;

    if (marker.__simRafId && marker.__simRouteSignature === metric.signature) {
      return true;
    }
    if (marker.__animRafId) {
      cancelAnimationFrame(marker.__animRafId);
      marker.__animRafId = null;
    }
    if (marker.__simRafId) cancelAnimationFrame(marker.__simRafId);

    marker.__simRouteSignature = metric.signature;
    marker.__lastHeadingIconAt = 0;
    const phaseOffset = stableBusPhase(bus.id);
    const cycleDuration = loop ? SIMULATION_LOOP_MS : OPEN_ROUTE_CYCLE_MS;

    const tick = (now: number) => {
      const cycleProgress = ((Date.now() / cycleDuration) + phaseOffset) % 1;
      const forward = loop || cycleProgress < 0.5;
      const routeProgress = loop
        ? cycleProgress
        : forward ? cycleProgress * 2 : (1 - cycleProgress) * 2;
      const sample = sampleRouteAnimation(metric!, routeProgress * metric!.total, loop);
      const heading = forward ? sample.heading : (sample.heading + 180) % 360;
      try {
        marker.setPosition(new window.naver.maps.LatLng(sample.position.lat, sample.position.lng));
      } catch (_) {}

      if (now - marker.__lastHeadingIconAt >= HEADING_ICON_UPDATE_MS) {
        updateBusMarkerIcon(marker, marker.__label ?? bus.label, heading, marker.__etaLabel, false);
        marker.__lastHeadingIconAt = now;
      }
      marker.__simRafId = requestAnimationFrame(tick);
    };

    // 첫 페인트 전에 올바른 노선 위치로 옮겨 새로고침 시 날아오는 현상을 막는다.
    tick(performance.now());
    return true;
  }, [updateBusMarkerIcon]);

  // 실제 GPS 마커는 최근 수신 간격만큼 선형 보간해 정지-출발 느낌을 줄인다.
  const animateMarker = useCallback((
    marker: any,
    fromLat: number, fromLng: number,
    toLat: number, toLng: number, toHeading: number,
    label: string, etaLabel: string | undefined,
    durationMs: number,
  ) => {
    updateBusMarkerIcon(marker, label, toHeading, etaLabel);

    // 위치가 같더라도 예상 도착 정보는 위에서 갱신한다.
    if (fromLat === toLat && fromLng === toLng) return;

    // [변경] 이전 RAF 취소 (중복 실행 방지)
    if (marker.__animRafId) {
      cancelAnimationFrame(marker.__animRafId);
      marker.__animRafId = null;
    }

    const route = routePathRef.current;
    let waypoints: { lat: number; lng: number }[] = [];

    if (route.length > 1) {
      const searchFrom: number = marker.__routeIdx ?? 0;

      // snap-to-segment: 목적지를 경로 선분 위로 스냅
      let bestDist = Infinity;
      let bestIdx = searchFrom;
      let bestSnap = { x: toLng, y: toLat };

      for (let i = 0; i < route.length - 1; i++) {
        const [aLng, aLat] = route[i];
        const [bLng, bLat] = route[i + 1] ?? route[i];
        const snap = snapToSegment(toLng, toLat, aLng, aLat, bLng, bLat);
        if (snap.dist < bestDist) {
          bestDist = snap.dist;
          bestIdx = snap.t >= 0.5 ? i + 1 : i;
          bestSnap = { x: snap.x, y: snap.y };
        }
      }

      if (bestIdx !== searchFrom) {
        const forwardSteps = (bestIdx - searchFrom + route.length) % route.length;
        const backwardSteps = (searchFrom - bestIdx + route.length) % route.length;
        const routeWaypoints = forwardSteps <= backwardSteps
          ? [
              ...route.slice(searchFrom, bestIdx).map(([lng, lat]) => ({ lat, lng })),
              ...(bestIdx < searchFrom ? route.slice(searchFrom).map(([lng, lat]) => ({ lat, lng })) : []),
              ...(bestIdx < searchFrom ? route.slice(0, bestIdx).map(([lng, lat]) => ({ lat, lng })) : []),
            ]
          : route
              .slice(bestIdx + 1, searchFrom + 1)
              .reverse()
              .map(([lng, lat]) => ({ lat, lng }));

        waypoints = [
          { lat: fromLat, lng: fromLng },
          ...routeWaypoints,
          { lat: bestSnap.y, lng: bestSnap.x },
        ];
        marker.__routeIdx = bestIdx;
      }
    }

    // 경로 없거나 스냅 실패 → 직선
    if (waypoints.length < 2) {
      waypoints = [{ lat: fromLat, lng: fromLng }, { lat: toLat, lng: toLng }];
    }

    const segCount = waypoints.length - 1;
    const startTime = performance.now(); // [변경] performance.now() 기반

    // [변경] requestAnimationFrame 루프
    const tick = (now: number) => {
      const elapsed = now - startTime;
      const rawT = Math.min(elapsed / durationMs, 1);
      const t = rawT;

      const segIdx = Math.min(Math.floor(t * segCount), segCount - 1);
      const segT = t * segCount - segIdx;
      const from = waypoints[segIdx];
      const to   = waypoints[segIdx + 1] ?? waypoints[segIdx];

      const lat = from.lat + (to.lat - from.lat) * segT;
      const lng = from.lng + (to.lng - from.lng) * segT;

      try {
        marker.setPosition(new window.naver.maps.LatLng(lat, lng));
      } catch (_) {}

      if (rawT < 1) {
        marker.__animRafId = requestAnimationFrame(tick);
      } else {
        marker.__animRafId = null;
      }
    };

    marker.__animRafId = requestAnimationFrame(tick);
  }, [snapToSegment, updateBusMarkerIcon]);

  const updateBusMarkers = useCallback(() => {
    const maps = getNaverMaps();
    if (!mapInstance.current || !maps) return;

    const currentBuses = busesRef.current;
    const existingMap = new Map<string, any>(
      busMarkersRef.current.map(m => [m.__busId, m])
    );
    const newMarkers: any[] = [];

    currentBuses.forEach(bus => {
      const existing = existingMap.get(bus.id);
      if (existing) {
        if (bus.isSimulation && startSimulatedMarkerAnimation(existing, bus)) {
          existingMap.delete(bus.id);
          newMarkers.push(existing);
          return;
        }

        if (existing.__simRafId) {
          cancelAnimationFrame(existing.__simRafId);
          existing.__simRafId = null;
          existing.__simRouteSignature = null;
        }
        existing.__isSimulation = false;
        // [변경] in-flight 이어받기: 현재 마커 위치를 새 출발점으로 사용
        const pos = existing.getPosition();
        const fromLat = pos.lat();
        const fromLng = pos.lng();
        const now = performance.now();
        const lastTargetAt = existing.__lastTargetAt ?? (now - DEFAULT_REALTIME_INTERP_MS);
        const durationMs = Math.max(
          MIN_REALTIME_INTERP_MS,
          Math.min(MAX_REALTIME_INTERP_MS, (now - lastTargetAt) * 1.05),
        );
        existing.__lastTargetAt = now;

        animateMarker(
          existing,
          fromLat, fromLng,
          bus.position.lat, bus.position.lng, bus.heading ?? 0,
          bus.label, bus.etaLabel,
          durationMs,
        );
        existing.__heading = bus.heading ?? 0;
        existingMap.delete(bus.id);
        newMarkers.push(existing);
      } else {
        // 새 마커 생성
        try {
          const marker = new maps.Marker({
            position: new maps.LatLng(bus.position.lat, bus.position.lng),
            map: mapInstance.current,
            icon: {
              content: BUS_MARKER_CONTENT(bus.label, bus.heading ?? 0, bus.etaLabel),
              size: new maps.Size(104, 78),
              anchor: new maps.Point(52, 78),
            },
            zIndex: 20,
          });
          marker.__busId = bus.id;
          marker.__label = bus.label;
          marker.__etaLabel = bus.etaLabel ?? '';
          marker.__heading = bus.heading ?? 0;
          marker.__routeIdx = 0;
          marker.__animRafId = null;
          marker.__simRafId = null;
          marker.__simRouteSignature = null;
          marker.__lastTargetAt = performance.now();
          maps.Event.addListener(marker, 'click', () => {
            onBusClickRef.current?.(bus.id);
          });
          if (bus.isSimulation) startSimulatedMarkerAnimation(marker, bus);
          newMarkers.push(marker);
        } catch (e) { console.error("버스 마커 오류:", e); }
      }
    });

    // [변경] 없어진 버스: RAF 취소 후 마커 제거
    existingMap.forEach(m => {
      if (m.__animRafId) {
        cancelAnimationFrame(m.__animRafId);
        m.__animRafId = null;
      }
      if (m.__simRafId) {
        cancelAnimationFrame(m.__simRafId);
        m.__simRafId = null;
      }
      try { m.setMap(null); } catch (_) {}
    });
    busMarkersRef.current = newMarkers;
  }, [animateMarker, startSimulatedMarkerAnimation]);

  const updateStopMarkers = useCallback(() => {
    const maps = getNaverMaps();
    if (!mapInstance.current || !maps) return;
    stopMarkersRef.current.forEach(m => { try { m.setMap(null); } catch (_) {} });
    stopMarkersRef.current = [];

    stopsRef.current.forEach((stop, index) => {
      try {
        const markerContent = numberedStops
          ? NUMBERED_STOP_MARKER_CONTENT(index)
          : STOP_MARKER_CONTENT(stop.name, stop.type ?? 'middle');
        const marker = new maps.Marker({
          position: new maps.LatLng(stop.position.lat, stop.position.lng),
          map: mapInstance.current,
          icon: {
            content: markerContent,
            size: numberedStops ? new maps.Size(30, 38) : new maps.Size(128, 58),
            anchor: numberedStops ? new maps.Point(15, 38) : new maps.Point(64, 43),
          },
          zIndex: 10,
        });
        stopMarkersRef.current.push(marker);
      } catch (e) { console.error("정류장 마커 오류:", e); }
    });
  }, [numberedStops]);

  const updateUserMarker = useCallback((loc: { lat: number; lng: number } | null) => {
    const maps = getNaverMaps();
    if (!mapInstance.current || !maps) return;
    if (userMarkerRef.current) {
      try { userMarkerRef.current.setMap(null); } catch (_) {}
      userMarkerRef.current = null;
    }
    if (!loc) return;
    try {
      userMarkerRef.current = new maps.Marker({
        position: new maps.LatLng(loc.lat, loc.lng),
        map: mapInstance.current,
        icon: {
          content: USER_MARKER_CONTENT(),
          size: new maps.Size(28, 28),
          anchor: new maps.Point(14, 14),
        },
        zIndex: 30,
      });
    } catch (e) { console.error("사용자 마커 오류:", e); }
  }, []);

  const updatePolyline = useCallback((path: [number, number][]) => {
    const maps = getNaverMaps();
    if (!mapInstance.current || !maps) return;
    if (polylineRef.current) {
      try { polylineRef.current.setMap(null); } catch (_) {}
      polylineRef.current = null;
    }
    if (!path || path.length === 0) return;
    try {
      const latLngPath = path.map(([lng, lat]) => new maps.LatLng(lat, lng));
      polylineRef.current = new maps.Polyline({
        path: latLngPath,
        strokeColor: '#1e3a8a',
        strokeWeight: 5,
        strokeOpacity: 0.75,
        strokeStyle: 'solid',
        map: mapInstance.current,
        zIndex: 5,
      });
    } catch (e) { console.error("폴리라인 오류:", e); }
  }, []);

  const updateBusMarkersRef = useRef(updateBusMarkers);
  const updateStopMarkersRef = useRef(updateStopMarkers);
  const updatePolylineRef = useRef(updatePolyline);

  useEffect(() => {
    updateBusMarkersRef.current = updateBusMarkers;
  }, [updateBusMarkers]);

  useEffect(() => {
    updateStopMarkersRef.current = updateStopMarkers;
  }, [updateStopMarkers]);

  useEffect(() => {
    updatePolylineRef.current = updatePolyline;
  }, [updatePolyline]);

  // 지도 초기화
  useEffect(() => {
    let disposed = false;
    let retryTimer: number | null = null;
    let retryCount = 0;
    const initializeMap = () => {
      if (disposed || !mapRef.current || mapInstance.current) return;
      const maps = window.naver?.maps;
      if (typeof maps?.Map !== "function" || typeof maps?.LatLng !== "function") {
        if (retryCount < 80) {
          retryCount += 1;
          retryTimer = window.setTimeout(initializeMap, 100);
        } else {
          console.error("네이버 지도 SDK 초기화 시간이 초과되었습니다.");
        }
        return;
      }
      try {
        mapInstance.current = new maps.Map(mapRef.current, {
          center: new maps.LatLng(initialCenterRef.current.lat, initialCenterRef.current.lng),
          zoom: initialZoomRef.current,
          draggable: true,
          pinchZoom: true,
          scrollWheel: true,
          keyboardShortcuts: true,
          disableDoubleClickZoom: false,
          disableDoubleTapZoom: false,
          disableTwoFingerTapZoom: false,
          zoomControl: false,
          mapTypeControl: false,
          scaleControl: false,
          logoControl: false,
          mapDataControl: false,
        });
        maps.Event.addListener(mapInstance.current, 'idle', () => {
          updateBusMarkersRef.current();
          updateStopMarkersRef.current();
          if (routePathRef.current?.length) updatePolylineRef.current(routePathRef.current);
        });
        if (autoFitBoundsRef.current) requestFitMapToContent();
      } catch (e) { console.error("네이버 지도 초기화 오류:", e); }
    };

    if (typeof window.naver?.maps?.Map === "function" && typeof window.naver?.maps?.LatLng === "function") {
      initializeMap();
    } else if (!scriptLoadedRef.current) {
      scriptLoadedRef.current = true;

      const existingScript = document.querySelector<HTMLScriptElement>("script[data-naver-map-sdk='true']");
      if (existingScript) {
        existingScript.addEventListener("load", initializeMap, { once: true });
        initializeMap();
      } else {
        const script = document.createElement("script");
        script.src = `https://oapi.map.naver.com/openapi/v3/maps.js?ncpKeyId=${clientId}&submodules=geocoder`;
        script.async = true;
        script.dataset.naverMapSdk = "true";
        script.onload = initializeMap;
        script.onerror = () => console.error("네이버 지도 API 로드 실패. Client ID를 확인하세요.");
        document.head.appendChild(script);
      }
    }

    // [변경] cleanup: 모든 RAF 취소
    return () => {
      disposed = true;
      if (retryTimer !== null) window.clearTimeout(retryTimer);
      if (fitBoundsFrameRef.current !== null) {
        cancelAnimationFrame(fitBoundsFrameRef.current);
        fitBoundsFrameRef.current = null;
      }
      busMarkersRef.current.forEach(m => {
        if (m.__animRafId) {
          cancelAnimationFrame(m.__animRafId);
          m.__animRafId = null;
        }
        if (m.__simRafId) {
          cancelAnimationFrame(m.__simRafId);
          m.__simRafId = null;
        }
        try { m.setMap(null); } catch (_) {}
      });
      stopMarkersRef.current.forEach(m => { try { m.setMap(null); } catch (_) {} });
      if (polylineRef.current) { try { polylineRef.current.setMap(null); } catch (_) {} }
      mapInstance.current = null;
    };
  }, [clientId, requestFitMapToContent]);

  useEffect(() => {
    const maps = getNaverMaps();
    if (!mapInstance.current || !maps) return;
    mapInstance.current.setCenter(new maps.LatLng(center.lat, center.lng));
    mapInstance.current.setZoom(zoom);
  }, [center.lat, center.lng, zoom]);

  useEffect(() => { updateBusMarkers(); }, [buses, updateBusMarkers]);
  useEffect(() => {
    updateStopMarkers();
    if (autoFitBoundsRef.current) requestFitMapToContent();
  }, [stops, updateStopMarkers, requestFitMapToContent]);
  useEffect(() => { updateUserMarker(userLocation ?? null); }, [userLocation, updateUserMarker]);
  useEffect(() => {
    routeAnimationMetricRef.current = null;
    routeAnimationSourceSignatureRef.current = '';
    updatePolyline(routePath);
    // 경로 로드 후 정류장 마커를 경로 위에 스냅해서 다시 그림
    updateStopMarkers();
    updateBusMarkers();
    if (autoFitBoundsRef.current) requestFitMapToContent();
  }, [routePath, updatePolyline, updateStopMarkers, updateBusMarkers, requestFitMapToContent]);

  useEffect(() => {
    if (autoFitBoundsRef.current) requestFitMapToContent();
  }, [fitBoundsPoints, requestFitMapToContent]);

  useEffect(() => {
    const maps = getNaverMaps();
    if (!focusLocation || !mapInstance.current || !maps) return;
    mapInstance.current.setCenter(new maps.LatLng(focusLocation.lat, focusLocation.lng));
    mapInstance.current.setZoom(focusLocation.zoom ?? 18);
  }, [focusLocation]);

  useEffect(() => {
    if (!fitBoundsKey) return;
    requestFitMapToContent();
  }, [fitBoundsKey, requestFitMapToContent]);

  const handleZoomIn  = () => { mapInstance.current?.setZoom(mapInstance.current.getZoom() + 1); };
  const handleZoomOut = () => { mapInstance.current?.setZoom(mapInstance.current.getZoom() - 1); };
  const handleLocate  = () => {
    const maps = getNaverMaps();
    if (!mapInstance.current || !maps) return;
    if (!userLocationRef.current) {
      onLocateRequestRef.current?.();
    }
    const loc = userLocationRef.current ?? center;
    mapInstance.current.setCenter(new maps.LatLng(loc.lat, loc.lng));
    mapInstance.current.setZoom(18);
  };

  return (
    <>
      <div ref={mapRef} className="absolute inset-0 w-full h-full z-0" style={{ background: '#e2e8f0' }} />

      {/* 지도 컨트롤 버튼 */}
      <div className="absolute content-stretch flex flex-col gap-[8px] items-start right-[16px] top-[108px] z-20">
        <button
          onClick={handleZoomIn}
          aria-label="지도 확대"
          className="bg-unibus-surface text-unibus-text content-stretch flex items-center justify-center p-px relative rounded-[12px] shrink-0 size-[40px] border border-unibus-divider hover:bg-gray-50 active:scale-95 transition-all"
        >
          <div className="relative shrink-0 size-[14px]">
            <svg className="absolute block size-full" fill="none" preserveAspectRatio="none" viewBox="0 0 14 14">
              <path d="M14 8H8V14H6V8H0V6H6V0H8V6H14V8Z" fill="currentColor" />
            </svg>
          </div>
        </button>

        <button
          onClick={handleZoomOut}
          aria-label="지도 축소"
          className="bg-unibus-surface text-unibus-text content-stretch flex items-center justify-center p-px relative rounded-[12px] shrink-0 size-[40px] border border-unibus-divider hover:bg-gray-50 active:scale-95 transition-all"
        >
          <div className="h-[2px] relative shrink-0 w-[14px]">
            <svg className="absolute block size-full" fill="none" preserveAspectRatio="none" viewBox="0 0 14 2">
              <path d="M0 2V0H14V2H0V2" fill="currentColor" />
            </svg>
          </div>
        </button>

        <div className="pt-[8px]">
          <button
            onClick={handleLocate}
            aria-label="내 위치 보기"
            className="bg-unibus-surface text-unibus-text content-stretch flex items-center justify-center p-px relative rounded-[12px] shrink-0 size-[40px] border border-unibus-divider hover:bg-unibus-surface-subtle active:scale-95 transition-all"
          >
            <div className="relative shrink-0 size-[21.9px]">
              <svg className="absolute block size-full" fill="none" preserveAspectRatio="none" viewBox="0 0 21.9 21.9">
                <path d="M10.95 10.95C12.1546 10.95 13.31 10.4705 14.1539 9.62661C14.9978 8.78271 15.4773 7.62728 15.4773 6.42273C15.4773 5.21817 14.9978 4.06274 14.1539 3.21884C13.31 2.37495 12.1546 1.89545 10.95 1.89545C9.74546 1.89545 8.59003 2.37495 7.74613 3.21884C6.90224 4.06274 6.42273 5.21817 6.42273 6.42273C6.42273 7.62728 6.90224 8.78271 7.74613 9.62661C8.59003 10.4705 9.74546 10.95 10.95 10.95ZM10.95 0C12.6572 0 14.2944 0.677588 15.5034 1.88656C16.7124 3.09554 17.39 4.73271 17.39 6.43991C17.39 12.2698 10.95 21.9 10.95 21.9C10.95 21.9 4.51 12.2698 4.51 6.43991C4.51 4.73271 5.18759 3.09554 6.39656 1.88656C7.60554 0.677588 9.24271 0 10.95 0Z" fill="currentColor" />
              </svg>
            </div>
          </button>
        </div>
      </div>
    </>
  );
}
