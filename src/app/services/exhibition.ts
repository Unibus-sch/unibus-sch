// Display values only. Authentication uses a server-issued session, never this password.
export const exhibitionAccount = {
  name: "방문객",
  studentId: "20260000",
  email: "visitor@unibus.example",
  password: "Unibus2026!",
  confirmPassword: "Unibus2026!",
};
export const exhibitionRootEnabled = import.meta.env.VITE_EXHIBITION_MODE === "true";
