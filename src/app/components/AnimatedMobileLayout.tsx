import { Suspense, useEffect } from "react";
import { useLocation, Outlet } from "react-router-dom";
import BottomNav from "./BottomNav";

const AUTH_ROUTES = new Set(["/", "/onboarding", "/login", "/signup"]);
const TAB_ROUTES = new Set(["/home", "/campus-shuttle", "/shuttle", "/commuter-bus", "/notice", "/settings"]);

function getTransitionClass(pathname: string) {
  if (TAB_ROUTES.has(pathname)) return "route-transition route-transition-tab";
  if (AUTH_ROUTES.has(pathname)) return "route-transition route-transition-auth";
  return "route-transition route-transition-default";
}

function RouteLoadingFallback({ isAuthRoute }: { isAuthRoute: boolean }) {
  return (
    <div
      aria-busy="true"
      aria-live="polite"
      className="size-full overflow-hidden bg-background text-foreground"
      role="status"
    >
      <div aria-hidden="true" className="flex size-full flex-col bg-unibus-surface">
        <div className="flex h-16 items-center justify-between border-b border-unibus-divider px-5">
          <div className="size-9 animate-pulse rounded-full bg-[#e8edf5] motion-reduce:animate-none dark:bg-[#2c2c2e]" />
          <div className="h-5 w-24 animate-pulse rounded-md bg-[#e8edf5] motion-reduce:animate-none dark:bg-[#2c2c2e]" />
          <div className="size-9 animate-pulse rounded-full bg-[#e8edf5] motion-reduce:animate-none dark:bg-[#2c2c2e]" />
        </div>
        <div className={`space-y-4 p-5 ${isAuthRoute ? "pt-12" : "pb-24"}`}>
          <div className="h-7 w-2/3 animate-pulse rounded-md bg-[#e8edf5] motion-reduce:animate-none dark:bg-[#2c2c2e]" />
          <div className="h-4 w-5/6 animate-pulse rounded-md bg-[#e8edf5] motion-reduce:animate-none dark:bg-[#2c2c2e]" />
          <div className="mt-7 h-32 w-full animate-pulse rounded-2xl bg-[var(--unibus-brand-soft)] motion-reduce:animate-none" />
          <div className="h-16 w-full animate-pulse rounded-2xl bg-[#e8edf5] motion-reduce:animate-none dark:bg-[#2c2c2e]" />
          <div className="h-16 w-full animate-pulse rounded-2xl bg-[#e8edf5] motion-reduce:animate-none dark:bg-[#2c2c2e]" />
        </div>
      </div>
      <span className="sr-only">요청한 화면을 불러오는 중입니다.</span>
    </div>
  );
}

export function AnimatedMobileLayout() {
  const location = useLocation();
  const showBottomNav = TAB_ROUTES.has(location.pathname);
  const isSplash = location.pathname === "/";
  const transitionClass = getTransitionClass(location.pathname);

  useEffect(() => {
    const root = document.documentElement;
    root.classList.add("unibus-mobile-scroll-lock");
    root.classList.toggle("unibus-splash-active", isSplash);

    return () => {
      root.classList.remove("unibus-mobile-scroll-lock");
      root.classList.remove("unibus-splash-active");
    };
  }, [isSplash]);

  return (
    <div
      className={`unibus-mobile-viewport flex h-dvh w-full items-center justify-center overflow-hidden p-0 transition-colors duration-200 motion-reduce:transition-none md:h-screen md:bg-gradient-to-br md:from-blue-50 md:to-slate-100 md:p-4 md:dark:from-slate-950 md:dark:to-black ${
        isSplash ? "bg-[#1e3a8a]" : "bg-background"
      }`}
    >
      <div
        className={`relative flex h-full min-h-0 w-full max-w-none flex-col overflow-hidden transition-colors duration-200 motion-reduce:transition-none md:h-[844px] md:max-h-full md:max-w-[430px] md:rounded-2xl md:shadow-2xl ${
          isSplash ? "bg-[#1e3a8a]" : "bg-background"
        }`}
      >
        <div key={location.pathname} data-testid="app-content" className={`relative min-h-0 w-full flex-1 overflow-hidden ${transitionClass}`}>
          <Suspense fallback={<RouteLoadingFallback isAuthRoute={AUTH_ROUTES.has(location.pathname)} />}>
            <Outlet />
          </Suspense>
        </div>
        {showBottomNav ? <BottomNav /> : null}
      </div>
    </div>
  );
}
