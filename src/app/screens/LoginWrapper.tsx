import { useState } from "react";
import { useNavigate, Navigate } from "react-router";
import svgPaths from "../../imports/svg-9blebrmjt8";
import { useLanguage } from "../contexts/LanguageContext";
import { useAuth } from "../contexts/AuthContext";
import { api } from "../services/api";
import { kakaoService } from "../services/kakao";

export default function LoginWrapper() {
  const navigate = useNavigate();
  const { t } = useLanguage();
  const { login, isAuthenticated, isLoading } = useAuth();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);

  if (!isLoading && isAuthenticated) {
    return <Navigate to="/home" replace />;
  }

  const handleLogin = async () => {
    setError("");
    if (!email.trim()) {
      setError(t("이메일을 입력해주세요", "Please enter your email"));
      return;
    }
    if (!password.trim()) {
      setError(t("비밀번호를 입력해주세요", "Please enter your password"));
      return;
    }
    setLoading(true);
    try {
      const result = await api.login(email, password);
      login(result.token, result.user);
      if (result.user.role === 'driver') {
        navigate("/driver");
      } else {
        navigate("/home");
      }
    } catch {
      setError(t("로그인에 실패했습니다. 이메일과 비밀번호를 확인해주세요.", "Login failed. Please check your email and password."));
    } finally {
      setLoading(false);
    }
  };

  const handleKakaoLogin = async () => {
    setError("");
    setLoading(true);
    try {
      const kakaoUser = await kakaoService.login();
      const result = await api.kakaoLogin(
        kakaoUser.kakaoId,
        kakaoUser.accessToken,
        kakaoUser.email,
        kakaoUser.name,
        kakaoUser.profileImage,
      );
      login(result.token, result.user);
      navigate("/home");
    } catch {
      setError(t("카카오 로그인에 실패했습니다. 다시 시도해주세요.", "Kakao login failed. Please try again."));
    } finally {
      setLoading(false);
    }
  };

  const handleKeyPress = (e: React.KeyboardEvent) => {
    if (e.key === "Enter") handleLogin();
  };

  return (
    <div className="font-['Public_Sans'] relative flex size-full min-h-0 flex-col items-start overflow-hidden bg-unibus-surface">
      {/* Header */}
      <div data-testid="login-header" className="bg-unibus-surface relative shrink-0 w-full pt-safe">
        <div className="flex flex-row items-center justify-center size-full">
          <div className="content-stretch flex items-center justify-center pb-[8px] pt-[15px] px-[16px] relative w-full">
            <div className="flex flex-col font-semibold h-[23px] justify-center leading-[0] relative shrink-0 text-unibus-text text-[18px] text-center tracking-[-0.27px]">
              <p className="leading-[22.5px]">{t("로그인", "Login")}</p>
            </div>
          </div>
        </div>
      </div>

      {/* Main Content */}
      <div data-testid="login-scroll" className="relative min-h-0 flex-1 w-full overflow-y-auto overscroll-y-contain pb-safe animate-[routeFade_180ms_ease-out] [-webkit-overflow-scrolling:touch]">
        <div className="content-stretch flex flex-col items-start justify-between pb-[32px] pt-[28px] px-[24px] relative w-full">

          {/* Logo + Title */}
          <div className="onboard-reveal content-stretch flex flex-col items-start pb-[28px] relative shrink-0 w-full">
            <div className="relative shrink-0 w-full">
              <div className="content-stretch flex gap-[8px] items-center mb-4">
                <div className="bg-unibus-brand content-stretch flex items-center justify-center relative rounded-[12px] shrink-0 size-[40px]">
                  <div className="h-[19px] relative shrink-0 w-[16px]">
                    <svg className="absolute block size-full" fill="none" preserveAspectRatio="none" viewBox="0 0 16 19">
                      <path d={svgPaths.pdce8f20} fill="var(--unibus-brand-foreground)" />
                    </svg>
                  </div>
                </div>
                <div className="flex flex-col font-semibold justify-center leading-[0] relative shrink-0 text-unibus-brand text-[20px] tracking-[-0.5px]">
                  <p className="leading-[28px]">UNIBUS SCH</p>
                </div>
              </div>
              <div className="mb-2">
                <div className="flex flex-col font-semibold justify-center leading-[0] relative shrink-0 text-unibus-text text-[26px] tracking-[-0.7px]">
                  <p className="leading-[35px]">{t("다시 만나 반가워요", "Welcome back")}</p>
                </div>
              </div>

            </div>
          </div>

          {/* Error */}
          {error && (
            <div className="mb-4 p-3 bg-red-50 border border-red-200 rounded-lg w-full animate-[routeLift_160ms_ease-out]">
              <p className="text-red-600 text-sm">{error}</p>
            </div>
          )}

          {/* Form Fields */}
          <div className="content-stretch flex flex-col gap-[24px] items-start relative shrink-0 w-full">
            {/* Email */}
            <div className="onboard-reveal content-stretch flex flex-col items-start relative shrink-0 w-full [animation-delay:80ms]">
              <div className="content-stretch flex flex-col items-start pb-[8px] relative shrink-0 w-full">
                <div className="flex flex-col font-semibold justify-center leading-[0] not-italic relative shrink-0 text-unibus-text text-[14px] w-full">
                  <p className="leading-[21px]">{t("이메일", "Email")}</p>
                </div>
              </div>
              <div className="relative w-full">
                <input
                  type="text"
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                  onKeyPress={handleKeyPress}
                  placeholder={t("이메일을 입력하세요", "Enter your email")}
                  className="w-full h-[52px] px-4 bg-unibus-surface border border-unibus-divider rounded-[12px] text-[16px] text-unibus-text placeholder:text-unibus-muted focus:outline-none focus:border-unibus-brand focus:ring-2 focus:ring-unibus-brand/20 transition-all"
                />
              </div>
            </div>

            {/* Password */}
            <div className="onboard-reveal content-stretch flex flex-col items-start relative shrink-0 w-full [animation-delay:120ms]">
              <div className="content-stretch flex items-center justify-between pb-[8px] relative shrink-0 w-full">
                <div className="flex flex-col font-semibold justify-center leading-[0] not-italic relative shrink-0 text-unibus-text text-[14px]">
                  <p className="leading-[21px]">{t("비밀번호", "Password")}</p>
                </div>
                <button className="flex flex-col font-semibold justify-center leading-[0] not-italic relative shrink-0 text-unibus-brand text-[13px]">
                  <p className="leading-[16px]">{t("비밀번호를 잊으셨나요?", "Forgot?")}</p>
                </button>
              </div>
              <div className="relative w-full">
                <input
                  type={showPassword ? "text" : "password"}
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  onKeyPress={handleKeyPress}
                  placeholder={t("비밀번호를 입력하세요", "Enter your password")}
                  className="w-full h-[52px] px-4 bg-unibus-surface border border-unibus-divider rounded-[12px] text-[16px] text-unibus-text placeholder:text-unibus-muted focus:outline-none focus:border-unibus-brand focus:ring-2 focus:ring-unibus-brand/20 transition-all"
                />
                <button onClick={() => setShowPassword(!showPassword)} className="absolute right-4 top-1/2 -translate-y-1/2">
                  <svg className="w-5 h-5 text-unibus-muted" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                    {showPassword ? (
                      // 보일 때 → 일반 눈 모양
                      <>
                        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z" />
                        <circle cx="12" cy="12" r="3" strokeWidth={2} />
                      </>
                    ) : (
                      // 안 보일 때 → 눈에 작대기
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M13.875 18.825A10.05 10.05 0 0112 19c-4.478 0-8.268-2.943-9.543-7a9.97 9.97 0 011.563-3.029m5.858.908a3 3 0 114.243 4.243M9.878 9.878l4.242 4.242M9.88 9.88l-3.29-3.29m7.532 7.532l3.29 3.29M3 3l3.59 3.59m0 0A9.953 9.953 0 0112 5c4.478 0 8.268 2.943 9.543 7a10.025 10.025 0 01-4.132 5.411m0 0L21 21" />
                    )}
                  </svg>
                </button>
              </div>
            </div>
          </div>

          {/* Login Button */}
          <div className="onboard-reveal content-stretch flex flex-col items-start pt-[28px] relative shrink-0 w-full [animation-delay:160ms]">
            <button
              onClick={handleLogin}
              className="bg-unibus-brand content-stretch flex gap-[8px] h-[52px] items-center justify-center relative rounded-[12px] shrink-0 w-full hover:bg-unibus-brand/90 transition-colors active:scale-[0.97]"
            >
              <div className="flex flex-col font-semibold justify-center leading-[0] relative shrink-0 text-[16px] text-center text-unibus-brand-foreground">
                <p className="leading-[24px]">{loading ? t("로그인 중...", "Logging in...") : t("로그인", "Login")}</p>
              </div>
              {!loading && (
                <div className="relative shrink-0 size-[15px]">
                  <svg className="absolute block size-full" fill="none" preserveAspectRatio="none" viewBox="0 0 15 15">
                    <path d={svgPaths.p2da1f880} fill="var(--unibus-brand-foreground)" />
                  </svg>
                </div>
              )}
            </button>
          </div>

          {/* Social Login */}
          <div className="onboard-reveal content-stretch flex flex-col items-start pt-[32px] relative shrink-0 w-full [animation-delay:200ms]">
            <div className="content-stretch flex flex-col gap-[16px] items-start relative shrink-0 w-full">
              <div className="content-stretch flex items-center py-[16px] relative shrink-0 w-full">
                <div className="flex-[1_0_0] h-px min-h-px min-w-px relative border-t border-unibus-divider" />
                <div className="content-stretch flex flex-col items-start px-[16px] relative shrink-0">
                  <div className="flex flex-col font-medium justify-center leading-[0] relative shrink-0 text-unibus-muted text-[13px] tracking-[1.2px] uppercase">
                    <p className="leading-[16px]">{t("또는", "or")}</p>
                  </div>
                </div>
                <div className="flex-[1_0_0] h-px min-h-px min-w-px relative border-t border-unibus-divider" />
              </div>

              <button
                onClick={handleKakaoLogin}
                disabled={loading}
                className="bg-[#FEE500] flex gap-[8px] h-[52px] items-center justify-center w-full rounded-[12px] hover:bg-[#FDD835] transition-colors disabled:opacity-50 active:scale-[0.96]"
              >
                <svg className="w-[20px] h-[20px]" viewBox="0 0 18 18" fill="none" xmlns="http://www.w3.org/2000/svg">
                  <path d="M9 0C4.02944 0 0 3.35942 0 7.50208C0 9.97392 1.43686 12.1633 3.64018 13.4946L2.71277 17.2161C2.64485 17.4831 2.93553 17.6989 3.16895 17.5449L7.48731 14.7652C7.98476 14.8471 8.48895 14.8947 9 14.8947C13.9706 14.8947 18 11.5353 18 7.50208C18 3.35942 13.9706 0 9 0Z" fill="#381E1F"/>
                </svg>
                <div className="font-semibold text-[16px] text-[#381E1F]">
                  {t("카카오로 로그인", "Continue with Kakao")}
                </div>
              </button>
            </div>
          </div>

          {/* Sign Up Link */}
          <div className="onboard-reveal content-stretch flex flex-col items-start pt-[28px] relative shrink-0 w-full [animation-delay:240ms]">
            <div className="content-stretch flex gap-[4px] items-center justify-center relative w-full">
              <div className="flex flex-col font-normal justify-center leading-[0] relative shrink-0 text-unibus-muted text-[14px] text-center">
                <p className="leading-[20px]">{t("계정이 없으신가요?", "Don't have an account?")}</p>
              </div>
              <button
                onClick={() => navigate("/signup")}
                className="flex flex-col font-semibold justify-center leading-[0] relative shrink-0 text-unibus-text text-[14px] text-center hover:text-unibus-brand transition-colors"
              >
                <p className="leading-[20px]">{t("회원가입", "Sign Up")}</p>
              </button>
            </div>
          </div>

        </div>
      </div>
    </div>
  );
}
