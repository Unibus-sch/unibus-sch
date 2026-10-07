import { useState } from "react";
import { useNavigate } from "react-router";
import svgPaths from "../../imports/svg-9blebrmjt8";
import { useLanguage } from "../contexts/LanguageContext";
import { useAuth } from "../contexts/AuthContext";
import { api } from "../services/api";

export default function SignUpWrapper() {
  const navigate = useNavigate();
  const { t } = useLanguage();
  const { login } = useAuth();
  const [formData, setFormData] = useState({
    name: "",
    studentId: "",
    email: "",
    password: "",
    confirmPassword: "",
  });
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [loading, setLoading] = useState(false);

  const validateForm = () => {
    const newErrors: Record<string, string> = {};

    if (!formData.name.trim()) {
      newErrors.name = t("이름을 입력해주세요", "Name is required");
    }

    if (!formData.email.trim()) {
      newErrors.email = t("이메일을 입력해주세요", "Email is required");
    } else if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(formData.email)) {
      newErrors.email = t("올바른 이메일 형식이 아닙니다", "Invalid email format");
    }

    if (!formData.password) {
      newErrors.password = t("비밀번호를 입력해주세요", "Password is required");
    } else if (formData.password.length < 8) {
      newErrors.password = t("비밀번호는 최소 8자 이상이어야 합니다", "Password must be at least 8 characters");
    }

    if (formData.password !== formData.confirmPassword) {
      newErrors.confirmPassword = t("비밀번호가 일치하지 않습니다", "Passwords do not match");
    }

    setErrors(newErrors);
    return Object.keys(newErrors).length === 0;
  };

  const handleSubmit = async () => {
    if (!validateForm()) return;

    setLoading(true);
    try {
      // Fixed: Pass object instead of individual parameters
      await api.signup({
        email: formData.email,
        password: formData.password,
        name: formData.name,
        studentId: formData.studentId || undefined
      });

      // Auto login after signup — AuthContext에 반영
      const loginResult = await api.login(formData.email, formData.password);
      login(loginResult.token, loginResult.user);
      navigate("/home");
    } catch {
      setErrors({
        email: t("회원가입에 실패했습니다. 이미 존재하는 이메일일 수 있습니다.", "Signup failed. Email may already exist.")
      });
    } finally {
      setLoading(false);
    }
  };

  const handleChange = (field: string, value: string) => {
    setFormData({ ...formData, [field]: value });
    // Clear error for this field when user starts typing
    if (errors[field]) {
      setErrors({ ...errors, [field]: "" });
    }
  };

  return (
    <div className="font-['Public_Sans'] relative flex size-full flex-col items-start overflow-y-auto overscroll-y-contain bg-unibus-surface [-webkit-overflow-scrolling:touch]">
      {/* Header */}
      <div className="sticky top-0 z-30 w-full pt-safe">
        <div className="backdrop-blur-[6px] bg-unibus-surface/95 flex items-center justify-between pb-[12px] pt-[16px] px-[16px] border-b border-unibus-divider">
          <button
            type="button"
            aria-label={t("로그인으로 돌아가기", "Back to login")}
            onClick={() => navigate("/login")}
            className="flex items-center justify-center size-[40px] hover:bg-gray-100 rounded-full active:scale-95 transition-all"
          >
            <svg className="w-3 h-5" fill="none" viewBox="0 0 12 20" stroke="currentColor" strokeWidth="2">
              <path d="M11 1L1 10L11 19" />
            </svg>
          </button>

          <div className="flex flex-col font-semibold h-[23px] justify-center leading-[0] text-unibus-text text-[18px] text-center tracking-[-0.27px]">
            <p className="leading-[22.5px]">회원가입</p>
          </div>

          <div className="w-[40px]" />
        </div>
      </div>

      <div className="flex-1 w-full px-[24px] py-[32px]">
        {/* Logo and Title */}
        <div className="mb-8">
          <div className="flex gap-[8px] items-center mb-4">
            <div className="bg-unibus-brand content-stretch flex items-center justify-center relative rounded-[12px] shrink-0 size-[40px]">
              <div className="h-[19px] relative shrink-0 w-[16px]">
                <svg className="absolute block size-full" fill="none" preserveAspectRatio="none" viewBox="0 0 16 19">
                  <path d={svgPaths.pdce8f20} fill="var(--unibus-brand-foreground)" />
                </svg>
              </div>
            </div>
            <div className="font-semibold text-unibus-brand text-[20px] tracking-[-0.5px] leading-[28px]">UNIBUS SCH</div>
          </div>

          <h1 className="font-semibold text-unibus-text text-[28px] tracking-[-0.7px] leading-[35px] mb-2">
            계정 만들기
          </h1>

        </div>

        {/* Form */}
        <div className="space-y-4 mb-6">
          <div>
            <label className="font-semibold text-unibus-text text-[14px] leading-[21px] block mb-2">
              이름
            </label>
            <input
              type="text"
              value={formData.name}
              onChange={(e) => handleChange("name", e.target.value)}
              placeholder="이름을 입력하세요"
              className={`w-full h-[52px] px-4 bg-unibus-surface border rounded-[12px] text-[16px] text-unibus-text placeholder:text-unibus-muted focus:outline-none focus:ring-2 transition-all ${
                errors.name
                  ? "border-red-500 focus:border-red-500 focus:ring-red-500/20"
                  : "border-unibus-divider focus:border-unibus-brand focus:ring-unibus-brand/20"
              }`}
            />
            {errors.name && (
              <p className="mt-1 text-red-500 text-sm font-['Public_Sans']">{errors.name}</p>
            )}
          </div>

          <div>
            <label className="font-semibold text-unibus-text text-[14px] leading-[21px] block mb-2">
              학번
            </label>
            <input
              type="text"
              value={formData.studentId}
              onChange={(e) => handleChange("studentId", e.target.value)}
              placeholder="학번을 입력하세요"
              className={`w-full h-[52px] px-4 bg-unibus-surface border rounded-[12px] text-[16px] text-unibus-text placeholder:text-unibus-muted focus:outline-none focus:ring-2 transition-all ${
                errors.studentId
                  ? "border-red-500 focus:border-red-500 focus:ring-red-500/20"
                  : "border-unibus-divider focus:border-unibus-brand focus:ring-unibus-brand/20"
              }`}
            />
            {errors.studentId && (
              <p className="mt-1 text-red-500 text-sm font-['Public_Sans']">{errors.studentId}</p>
            )}
          </div>

          <div>
            <label className="font-semibold text-unibus-text text-[14px] leading-[21px] block mb-2">
              이메일
            </label>
            <input
              type="email"
              value={formData.email}
              onChange={(e) => handleChange("email", e.target.value)}
              placeholder="student@sch.ac.kr"
              className={`w-full h-[52px] px-4 bg-unibus-surface border rounded-[12px] text-[16px] text-unibus-text placeholder:text-unibus-muted focus:outline-none focus:ring-2 transition-all ${
                errors.email
                  ? "border-red-500 focus:border-red-500 focus:ring-red-500/20"
                  : "border-unibus-divider focus:border-unibus-brand focus:ring-unibus-brand/20"
              }`}
            />
            {errors.email && (
              <p className="mt-1 text-red-500 text-sm font-['Public_Sans']">{errors.email}</p>
            )}
          </div>

          <div>
            <label className="font-semibold text-unibus-text text-[14px] leading-[21px] block mb-2">
              비밀번호
            </label>
            <input
              type="password"
              value={formData.password}
              onChange={(e) => handleChange("password", e.target.value)}
              placeholder="비밀번호를 입력하세요"
              className={`w-full h-[52px] px-4 bg-unibus-surface border rounded-[12px] text-[16px] text-unibus-text placeholder:text-unibus-muted focus:outline-none focus:ring-2 transition-all ${
                errors.password
                  ? "border-red-500 focus:border-red-500 focus:ring-red-500/20"
                  : "border-unibus-divider focus:border-unibus-brand focus:ring-unibus-brand/20"
              }`}
            />
            {errors.password && (
              <p className="mt-1 text-red-500 text-sm font-['Public_Sans']">{errors.password}</p>
            )}
          </div>

          <div>
            <label className="font-semibold text-unibus-text text-[14px] leading-[21px] block mb-2">
              비밀번호 확인
            </label>
            <input
              type="password"
              value={formData.confirmPassword}
              onChange={(e) => handleChange("confirmPassword", e.target.value)}
              placeholder="비밀번호를 다시 입력하세요"
              className={`w-full h-[52px] px-4 bg-unibus-surface border rounded-[12px] text-[16px] text-unibus-text placeholder:text-unibus-muted focus:outline-none focus:ring-2 transition-all ${
                errors.confirmPassword
                  ? "border-red-500 focus:border-red-500 focus:ring-red-500/20"
                  : "border-unibus-divider focus:border-unibus-brand focus:ring-unibus-brand/20"
              }`}
            />
            {errors.confirmPassword && (
              <p className="mt-1 text-red-500 text-sm font-['Public_Sans']">{errors.confirmPassword}</p>
            )}
          </div>
        </div>

        {/* Submit Button */}
        <button
          onClick={handleSubmit}
          className="w-full bg-unibus-brand h-[52px] rounded-[12px] font-semibold text-unibus-brand-foreground text-[16px] hover:bg-unibus-brand/90 active:scale-[0.98] transition-all mb-4"
        >
          {loading ? "처리 중..." : "회원가입"}
        </button>

        {/* Login Link */}
        <div className="flex gap-1 items-center justify-center">
          <p className="font-normal text-unibus-muted text-[14px] leading-[20px]">
            이미 계정이 있으신가요?
          </p>
          <button
            onClick={() => navigate("/login")}
            className="font-semibold text-unibus-text text-[14px] leading-[20px] hover:text-unibus-brand transition-colors"
          >
            로그인
          </button>
        </div>
      </div>
    </div>
  );
}
