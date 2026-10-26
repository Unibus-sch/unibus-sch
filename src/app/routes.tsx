import { exhibitionRootEnabled } from "./services/exhibition";
import { lazy } from "react";
import { Navigate, createBrowserRouter } from "react-router-dom";
import SplashScreen from "../imports/SplashScreen";
import { Layout } from "./components/Layout";
import { AnimatedMobileLayout } from "./components/AnimatedMobileLayout";
import { ProtectedOutlet } from "./components/ProtectedOutlet";
import { AdminOutlet } from "./components/AdminOutlet";
import { DriverOutlet } from "./components/DriverOutlet";
import { RouteErrorFallback } from "./components/RouteErrorFallback";
import {
  loadCampusShuttleWrapper,
  loadCommuterBusWrapper,
  loadHomeWrapper,
  loadLoginWrapper,
  loadNoticeWrapper,
  loadOnboardingWrapper,
  loadQrScannerWrapper,
  loadSettingsWrapper,
  loadSignUpWrapper,
} from "./routeModules";

const OnboardingWrapper = lazy(loadOnboardingWrapper);
const LoginWrapper = lazy(loadLoginWrapper);
const SignUpWrapper = lazy(loadSignUpWrapper);
const HomeWrapper = lazy(loadHomeWrapper);
const CampusShuttleWrapper = lazy(loadCampusShuttleWrapper);
const CommuterBusWrapper = lazy(loadCommuterBusWrapper);
const QrScannerWrapper = lazy(loadQrScannerWrapper);
const NoticeWrapper = lazy(loadNoticeWrapper);
const SettingsWrapper = lazy(loadSettingsWrapper);
const DriverHomeWrapper = lazy(() => import("./screens/driver/DriverHomeWrapper"));
const DriverActiveWrapper = lazy(() => import("./screens/driver/DriverActiveWrapper"));
const AdminLogin = lazy(() => import("./admin/AdminLogin"));
const AdminDashboard = lazy(() => import("./admin/AdminDashboard"));
const NoticeManagement = lazy(() => import("./admin/NoticeManagement"));
const RouteManagement = lazy(() => import("./admin/RouteManagement"));
const BusManagement = lazy(() => import("./admin/BusManagement"));
const UserManagement = lazy(() => import("./admin/UserManagement"));
const SupportManagement = lazy(() => import("./admin/SupportManagement"));

export const router = createBrowserRouter([
  // ── 모바일 앱 라우트 ──
  {
    element: <AnimatedMobileLayout />,
    errorElement: <RouteErrorFallback />,
    children: [
      // 스플래시
      {
        path: "/",
        element: exhibitionRootEnabled ? <Navigate to="/exhibition" replace /> : (
          <Layout autoNavigate={{ to: "/onboarding", delay: 2500, ifAuthenticated: "/home" }}>
            <SplashScreen />
          </Layout>
        ),
      },
      // QR exhibition flow uses separate screens; ordinary authentication stays editable.
      { path: "/exhibition", element: <OnboardingWrapper exhibition /> },
      { path: "/exhibition/signup", element: <SignUpWrapper exhibition /> },
      { path: "/exhibition/login", element: <LoginWrapper exhibition /> },
      // 인증 화면 (공개)
      { path: "/onboarding", element: <OnboardingWrapper /> },
      { path: "/login",      element: <LoginWrapper /> },
      { path: "/signup",     element: <SignUpWrapper /> },
      // 보호된 화면
      {
        element: <ProtectedOutlet />,
        children: [
          { path: "/home",            element: <HomeWrapper /> },
          { path: "/campus-shuttle",  element: <CampusShuttleWrapper /> },
          { path: "/shuttle",         element: <CampusShuttleWrapper /> },
          { path: "/commuter-bus",    element: <CommuterBusWrapper /> },
          { path: "/qr-scanner",     element: <QrScannerWrapper /> },
          { path: "/notice",         element: <NoticeWrapper /> },
          { path: "/settings",       element: <SettingsWrapper /> },
        ],
      },
    ],
  },

  // ── 기사 라우트 (별도 레이아웃, 모바일 전용) ──
  {
    element: <DriverOutlet />,
    errorElement: <RouteErrorFallback />,
    children: [
      { path: "/driver",        element: <DriverHomeWrapper /> },
      { path: "/driver/active", element: <DriverActiveWrapper /> },
    ],
  },

  // ── 관리자 라우트 (별도 레이아웃, 애니메이션 없음) ──
  { path: "/admin/login", Component: AdminLogin, errorElement: <RouteErrorFallback /> },
  {
    element: <AdminOutlet />,
    errorElement: <RouteErrorFallback />,
    children: [
      { path: "/admin/dashboard",     element: <AdminDashboard /> },
      { path: "/admin/notices",       element: <NoticeManagement /> },
      { path: "/admin/routes",        element: <RouteManagement /> },
      { path: "/admin/buses",         element: <BusManagement /> },
      { path: "/admin/demo",          element: <Navigate to="/admin/dashboard" replace /> },
      { path: "/admin/notifications", element: <Navigate to="/admin/notices" replace /> },
      { path: "/admin/support",       element: <SupportManagement /> },
      { path: "/admin/users",         element: <UserManagement /> },
    ],
  },

  // ── 404 ──
  {
    path: "*",
    Component: () => (
      <div className="flex items-center justify-center min-h-screen">
        <div className="text-center">
          <h1 className="text-4xl font-bold mb-4">404</h1>
          <p className="text-gray-600 mb-4">Page not found</p>
          <a href="/home" className="text-blue-600 underline">
            Go to Home
          </a>
        </div>
      </div>
    ),
  },
]);
