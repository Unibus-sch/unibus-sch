import { publicAnonKey, supabaseUrl } from '../../../utils/supabase/info';
import type {
  ApiResponse,
  User,
  Notice,
  BusRoute,
  SignupRequest,
  UserReport,
  ReportCategory,
  ReportStatus,
  NotificationDelivery
} from '../types';

type RouteStopInput = {
  id?: string;
  name: string;
  order: number;
  lat?: number | null;
  lng?: number | null;
  arrivalTime?: string | null;
};

type RouteShapePointInput = {
  id?: string;
  name?: string | null;
  afterStopOrder: number;
  order: number;
  lat: number;
  lng: number;
};

type RouteMutationPayload = Omit<Partial<BusRoute>, 'stops'> & {
  stops?: RouteStopInput[];
  shapePoints?: RouteShapePointInput[];
  shuttleVariant?: BusRoute['shuttleVariant'];
};

const SUPABASE_URL = supabaseUrl;
const EDGE_API_BASE_URL = `${SUPABASE_URL}/functions/v1/make-server`;
const configuredPublicApiBaseUrl = import.meta.env.VITE_PUBLIC_API_BASE_URL?.trim();
const PUBLIC_API_BASE_URL = configuredPublicApiBaseUrl
  ? configuredPublicApiBaseUrl.replace(/\/+$/, '')
  : EDGE_API_BASE_URL;
const configuredAuthApiBaseUrl = import.meta.env.VITE_AUTH_API_BASE_URL?.trim();
const AUTH_API_BASE_URL = configuredAuthApiBaseUrl
  ? configuredAuthApiBaseUrl.replace(/\/+$/, '')
  : PUBLIC_API_BASE_URL;
const configuredAdminApiBaseUrl = import.meta.env.VITE_ADMIN_API_BASE_URL?.trim();
const ADMIN_API_BASE_URL = configuredAdminApiBaseUrl
  ? configuredAdminApiBaseUrl.replace(/\/+$/, '')
  : AUTH_API_BASE_URL;
const configuredDriverApiBaseUrl = import.meta.env.VITE_DRIVER_API_BASE_URL?.trim();
const DRIVER_API_BASE_URL = configuredDriverApiBaseUrl
  ? configuredDriverApiBaseUrl.replace(/\/+$/, '')
  : AUTH_API_BASE_URL;
const isDev = import.meta.env.DEV;
const REQUEST_TIMEOUT_MS = 15_000;

class ApiClient {
  private token: string | null = null;

  constructor() {
    // Load token from localStorage
    this.token = localStorage.getItem('auth_token');
  }

  setToken(token: string | null) {
    this.token = token;
    if (token) {
      localStorage.setItem('auth_token', token);
    } else {
      localStorage.removeItem('auth_token');
    }
  }

  getToken(): string | null {
    return this.token;
  }

  private async request<T>(
    endpoint: string,
    options: RequestInit = {},
    baseUrl = EDGE_API_BASE_URL,
    includeSupabaseAuthorization = true,
    includeSessionToken = true,
  ): Promise<T> {
    const headers: Record<string, string> = {
      'Content-Type': 'application/json',
    };

    if (includeSupabaseAuthorization) {
      headers['Authorization'] = `Bearer ${publicAnonKey}`;
    }

    const optionHeaders = new Headers(options.headers);
    optionHeaders.forEach((value, key) => {
      headers[key] = value;
    });

    if (includeSessionToken && this.token) {
      headers['X-Auth-Token'] = this.token;
    }

    const url = `${baseUrl}${endpoint}`;
    if (isDev) console.log(`[API] ${options.method || 'GET'} ${url}`);

    const requestController = new AbortController();
    const upstreamSignal = options.signal;
    const abortFromUpstream = () => requestController.abort(upstreamSignal?.reason);

    if (upstreamSignal?.aborted) {
      abortFromUpstream();
    } else {
      upstreamSignal?.addEventListener('abort', abortFromUpstream, { once: true });
    }

    const requestMethod = (options.method ?? 'GET').toUpperCase();
    const shouldTimeout = requestMethod === 'GET' || requestMethod === 'HEAD';
    const timeoutId = shouldTimeout
      ? window.setTimeout(() => {
          requestController.abort(new DOMException('Request timed out', 'TimeoutError'));
        }, REQUEST_TIMEOUT_MS)
      : undefined;

    try {
      const response = await fetch(url, {
        ...options,
        headers,
        signal: requestController.signal,
      });

      // Handle non-JSON responses
      const contentType = response.headers.get('content-type');
      if (!contentType || !contentType.includes('application/json')) {
        if (isDev) {
          console.error('[API] Non-JSON response received:', {
            status: response.status,
            statusText: response.statusText,
            contentType
          });
        }
        throw new Error(`Server returned ${response.status}: ${response.statusText}`);
      }

      const data = await response.json();

      if (!response.ok) {
        if (isDev) {
          console.error('[API] Request failed:', {
            endpoint,
            status: response.status,
            error: data.error || data.message
          });
        }

        if (response.status === 401) {
          this.setToken(null);
          localStorage.removeItem('user');
          window.dispatchEvent(new Event('auth:expired'));
        }

        throw new Error(data.error || data.message || `HTTP ${response.status}`);
      }

      if (isDev) console.log(`[API] Success:`, { endpoint, success: data.success });
      return data;
    } catch (error) {
      const requestError = requestController.signal.reason instanceof DOMException &&
          requestController.signal.reason.name === 'TimeoutError'
        ? new Error('요청 시간이 초과되었습니다. 네트워크 연결을 확인해 주세요.')
        : error;

      if (isDev) {
        console.error('[API] Request failed:', {
          endpoint,
          error: requestError instanceof Error ? requestError.message : String(requestError)
        });
      }
      throw requestError;
    } finally {
      if (timeoutId !== undefined) window.clearTimeout(timeoutId);
      upstreamSignal?.removeEventListener('abort', abortFromUpstream);
    }
  }

  private publicRequest<T>(endpoint: string): Promise<T> {
    return this.request<T>(endpoint, {}, PUBLIC_API_BASE_URL, false, false);
  }

  private authRequest<T>(endpoint: string, options: RequestInit, includeSessionToken = false): Promise<T> {
    return this.request<T>(endpoint, options, AUTH_API_BASE_URL, false, includeSessionToken);
  }

  private adminRequest<T>(endpoint: string, options: RequestInit = {}): Promise<T> {
    return this.request<T>(endpoint, options, ADMIN_API_BASE_URL, false, true);
  }

  private driverRequest<T>(endpoint: string, options: RequestInit = {}): Promise<T> {
    return this.request<T>(endpoint, options, DRIVER_API_BASE_URL, false, true);
  }

  // ============ AUTH ENDPOINTS ============

  async signup(data: SignupRequest): Promise<{ user: User }> {
    const response = await this.authRequest<ApiResponse<{ user: User }>>('/auth/signup', {
      method: 'POST',
      body: JSON.stringify(data),
    });
    
    if (response.success && response.data) {
      return response.data;
    }
    throw new Error(response.error || 'Signup failed');
  }

  async login(email: string, password: string): Promise<{ token: string; user: User }> {
    const data = await this.authRequest<ApiResponse>('/auth/login', {
      method: 'POST',
      body: JSON.stringify({ email, password }),
    });
    
    if (data.success && data.token) {
      this.setToken(data.token);
      return { token: data.token, user: data.user! };
    }
    throw new Error(data.error || 'Login failed');
  }

  async kakaoLogin(kakaoId: string, accessToken: string, email?: string, name?: string, profileImage?: string): Promise<{ token: string; user: User }> {
    const data = await this.authRequest<ApiResponse>('/auth/kakao', {
      method: 'POST',
      body: JSON.stringify({ kakaoId, accessToken, email, name, profileImage }),
    });
    
    if (data.success && data.token) {
      this.setToken(data.token);
      return { token: data.token, user: data.user! };
    }
    throw new Error(data.error || 'Kakao login failed');
  }

  async logout(): Promise<void> {
    try {
      await this.authRequest('/auth/logout', {
        method: 'POST',
      }, true);
    } finally {
      this.setToken(null);
      localStorage.removeItem('user');
    }
  }

  // ============ NOTICE ENDPOINTS ============

  async getNotices(): Promise<Notice[]> {
    const response = await this.publicRequest<ApiResponse<Notice[]>>('/notices');
    
    if (response.success && response.data) {
      return response.data;
    }
    throw new Error(response.error || 'Failed to fetch notices');
  }

  async getNotice(id: string): Promise<Notice> {
    const response = await this.publicRequest<ApiResponse<Notice>>(`/notices/${id}`);
    
    if (response.success && response.data) {
      return response.data;
    }
    throw new Error(response.error || 'Failed to fetch notice');
  }

  async createNotice(notice: Partial<Notice>): Promise<Notice> {
    const response = await this.adminRequest<ApiResponse<Notice>>('/notices', {
      method: 'POST',
      body: JSON.stringify(notice),
    });
    
    if (response.success && response.data) {
      return response.data;
    }
    throw new Error(response.error || 'Failed to create notice');
  }

  async updateNotice(id: string, updates: Partial<Notice>): Promise<Notice> {
    const response = await this.adminRequest<ApiResponse<Notice>>(`/notices/${id}`, {
      method: 'PUT',
      body: JSON.stringify(updates),
    });
    
    if (response.success && response.data) {
      return response.data;
    }
    throw new Error(response.error || 'Failed to update notice');
  }

  async deleteNotice(id: string): Promise<void> {
    const response = await this.adminRequest<ApiResponse>(`/notices/${id}`, {
      method: 'DELETE',
    });
    
    if (!response.success) {
      throw new Error(response.error || 'Failed to delete notice');
    }
  }

  async uploadNoticeImage(file: File): Promise<string> {
    const form = new FormData();
    form.append('file', file);

    const headers: Record<string, string> = {};

    if (this.token) {
      headers['X-Auth-Token'] = this.token;
    }

    const res = await fetch(`${ADMIN_API_BASE_URL}/notices/images`, {
      method: 'POST',
      headers,
      body: form,
    });

    if (!res.ok) {
      const err = await res.json().catch(() => ({}));
      throw new Error(err.error || '이미지 업로드 실패');
    }

    const data = await res.json();
    if (data.success && data.data?.url) {
      return data.data.url;
    }
    throw new Error(data.error || '이미지 업로드 실패');
  }

  // ============ NOTIFICATION ENDPOINTS ============

  async getVapidPublicKey(): Promise<string> {
    const response = await this.request<ApiResponse<{ publicKey: string }>>(
      '/notifications/vapid-public-key', {}, AUTH_API_BASE_URL, false, false,
    );
    if (response.success && response.data?.publicKey) return response.data.publicKey;
    throw new Error(response.error || 'Failed to fetch push public key');
  }

  async subscribePush(subscription: PushSubscriptionJSON): Promise<void> {
    const response = await this.authRequest<ApiResponse>('/notifications/subscribe', {
      method: 'POST',
      body: JSON.stringify({ subscription }),
    }, true);
    if (!response.success) throw new Error(response.error || 'Failed to subscribe push');
  }

  async unsubscribePush(endpoint: string): Promise<void> {
    const response = await this.authRequest<ApiResponse>('/notifications/unsubscribe', {
      method: 'POST',
      body: JSON.stringify({ endpoint }),
    }, true);
    if (!response.success) throw new Error(response.error || 'Failed to unsubscribe push');
  }

  async sendNotification(data: { title: string; message: string; target: string }): Promise<{ notice: Notice; push: { attempted: number; sent: number; failed: number } }> {
    const response = await this.adminRequest<ApiResponse<{ notice: Notice; push: { attempted: number; sent: number; failed: number } }>>('/notifications/send', {
      method: 'POST',
      body: JSON.stringify(data),
    });
    if (response.success && response.data) return response.data;
    throw new Error(response.error || 'Failed to send notification');
  }

  async sendNoticePush(noticeId: string, target: NotificationDelivery['target']): Promise<{ attempted: number; sent: number; failed: number }> {
    const response = await this.adminRequest<ApiResponse<{ attempted: number; sent: number; failed: number }>>('/notifications/send-existing', {
      method: 'POST',
      body: JSON.stringify({ noticeId, target }),
    });
    if (response.success && response.data) return response.data;
    throw new Error(response.error || 'Failed to send notice push');
  }

  async getNotificationHistory(): Promise<NotificationDelivery[]> {
    const response = await this.adminRequest<ApiResponse<NotificationDelivery[]>>('/notifications/history');
    if (response.success && response.data) return response.data;
    throw new Error(response.error || 'Failed to fetch notification history');
  }

  // ============ ROUTE ENDPOINTS ============

  async getRoutes(): Promise<BusRoute[]> {
    const response = await this.publicRequest<ApiResponse<BusRoute[]>>('/routes');
    if (response.success && response.data) {
      return response.data;
    }
    throw new Error(response.error || 'Failed to fetch routes');
  }

  async getRoute(id: string): Promise<BusRoute> {
    const response = await this.publicRequest<ApiResponse<BusRoute>>(`/routes/${id}`);
    if (response.success && response.data) {
      return response.data;
    }
    throw new Error(response.error || 'Failed to fetch route');
  }

  async getRoutePath(id: string): Promise<{ stops: Array<{ id: string; name: string; order: number; lat: number | null; lng: number | null }>; shapePoints?: Array<{ id: string; name?: string | null; afterStopOrder: number; order: number; lat: number; lng: number }>; path: [number, number][] }> {
    const response = await this.publicRequest<ApiResponse<any>>(`/routes/${id}/path`);
    if (response.success && response.data) {
      return response.data;
    }
    throw new Error(response.error || 'Failed to fetch route path');
  }

  async previewRoutePath(
    id: string,
    data: {
      stops: Array<{ id?: string; name: string; order: number; lat: number | null; lng: number | null }>;
      shapePoints: Array<{ id?: string; name?: string | null; afterStopOrder: number; order: number; lat: number; lng: number }>;
    }
  ): Promise<{ path: [number, number][] }> {
    const response = await this.adminRequest<ApiResponse<{ path: [number, number][] }>>(`/routes/${id}/path/preview`, {
      method: 'POST',
      body: JSON.stringify(data),
    });
    if (response.success && response.data) {
      return response.data;
    }
    throw new Error(response.error || 'Failed to preview route path');
  }

  async createRoute(route: RouteMutationPayload): Promise<BusRoute> {
    const response = await this.adminRequest<ApiResponse<BusRoute>>('/routes', {
      method: 'POST',
      body: JSON.stringify(route),
    });
    if (response.success && response.data) {
      return response.data;
    }
    throw new Error(response.error || 'Failed to create route');
  }

  async updateRoute(id: string, updates: RouteMutationPayload): Promise<BusRoute> {
    const response = await this.adminRequest<ApiResponse<BusRoute>>(`/routes/${id}`, {
      method: 'PUT',
      body: JSON.stringify(updates),
    });
    if (response.success && response.data) {
      return response.data;
    }
    throw new Error(response.error || 'Failed to update route');
  }

  async deleteRoute(id: string): Promise<void> {
    const response = await this.adminRequest<ApiResponse>(`/routes/${id}`, {
      method: 'DELETE',
    });
    if (!response.success) {
      throw new Error(response.error || 'Failed to delete route');
    }
  }

  // ============ BUS ENDPOINTS ============

  async getBuses(): Promise<any[]> {
    const response = await this.publicRequest<ApiResponse<any[]>>('/buses');
    if (response.success && response.data) {
      return response.data;
    }
    throw new Error(response.error || 'Failed to fetch buses');
  }

  async getBus(id: string): Promise<any> {
    const response = await this.publicRequest<ApiResponse<any>>(`/buses/${id}`);
    if (response.success && response.data) {
      return response.data;
    }
    throw new Error(response.error || 'Failed to fetch bus');
  }

  async createBus(bus: { name: string; type: string; capacity?: number; licensePlate?: string; routeId?: string }): Promise<any> {
    const response = await this.adminRequest<ApiResponse<any>>('/buses', {
      method: 'POST',
      body: JSON.stringify(bus),
    });
    if (response.success && response.data) {
      return response.data;
    }
    throw new Error(response.error || 'Failed to create bus');
  }

  async updateBus(id: string, updates: Partial<{ name: string; type: string; capacity: number; licensePlate: string; status: string; currentRouteId: string | null; assignedDriverId: string | null; isRunning: boolean }>): Promise<any> {
    const response = await this.adminRequest<ApiResponse<any>>(`/buses/${id}`, {
      method: 'PUT',
      body: JSON.stringify(updates),
    });
    if (response.success && response.data) {
      return response.data;
    }
    throw new Error(response.error || 'Failed to update bus');
  }

  async deleteBus(id: string): Promise<void> {
    const response = await this.adminRequest<ApiResponse>(`/buses/${id}`, {
      method: 'DELETE',
    });
    if (!response.success) {
      throw new Error(response.error || 'Failed to delete bus');
    }
  }

  async getBusLocations(): Promise<Array<{ busId: string; lat: number; lng: number; speed: number; heading: number; timestamp: string }>> {
    const response = await this.publicRequest<ApiResponse<any[]>>('/buses/locations/latest');
    if (response.success && response.data) {
      return response.data;
    }
    throw new Error(response.error || 'Failed to fetch bus locations');
  }

  async getManagedBuses(): Promise<any[]> {
    const response = await this.adminRequest<ApiResponse<any[]>>('/buses');
    if (response.success && response.data) {
      return response.data;
    }
    throw new Error(response.error || 'Failed to fetch buses');
  }

  async updateBusLocation(busId: string, location: { lat: number; lng: number; speed?: number; heading?: number }): Promise<void> {
    const response = await this.request<ApiResponse>(`/buses/${busId}/location`, {
      method: 'POST',
      body: JSON.stringify(location),
    });
    if (!response.success) {
      throw new Error(response.error || 'Failed to update bus location');
    }
  }

  async forceStopBus(busId: string, reason = '관리자 강제 종료'): Promise<void> {
    const response = await this.adminRequest<ApiResponse>(`/buses/${busId}/force-stop`, {
      method: 'POST',
      body: JSON.stringify({ reason }),
    });
    if (!response.success) throw new Error(response.error || 'Failed to force-stop bus');
  }

  // ============ USER REPORT ENDPOINTS ============

  async createReport(data: { category: ReportCategory; title: string; details: string; relatedBusId?: string; relatedRouteId?: string }): Promise<UserReport> {
    const response = await this.request<ApiResponse<UserReport>>('/reports', {
      method: 'POST',
      body: JSON.stringify(data),
    });
    if (response.success && response.data) return response.data;
    throw new Error(response.error || 'Failed to create report');
  }

  async getReports(status?: ReportStatus): Promise<UserReport[]> {
    const query = status ? `?status=${encodeURIComponent(status)}` : '';
    const response = await this.adminRequest<ApiResponse<UserReport[]>>(`/reports${query}`);
    if (response.success && response.data) return response.data;
    throw new Error(response.error || 'Failed to fetch reports');
  }

  async updateReport(id: string, updates: Partial<{ status: ReportStatus; adminNote: string }>): Promise<UserReport> {
    const response = await this.adminRequest<ApiResponse<UserReport>>(`/reports/${id}`, {
      method: 'PUT',
      body: JSON.stringify(updates),
    });
    if (response.success && response.data) return response.data;
    throw new Error(response.error || 'Failed to update report');
  }

  // ============ USER MANAGEMENT ENDPOINTS (Admin) ============

  async getUsers(): Promise<any[]> {
    const response = await this.adminRequest<ApiResponse<any[]>>('/users');
    if (response.success && response.data) return response.data;
    throw new Error(response.error || 'Failed to fetch users');
  }

  async updateUser(userId: string, updates: Partial<{ name: string }>): Promise<any> {
    const response = await this.adminRequest<ApiResponse<any>>(`/users/${userId}`, {
      method: 'PUT',
      body: JSON.stringify(updates),
    });
    if (response.success && response.data) return response.data;
    throw new Error(response.error || 'Failed to update user');
  }

  async updateUserRole(userId: string, role: 'user' | 'admin' | 'driver'): Promise<void> {
    const response = await this.adminRequest<ApiResponse>(`/users/${userId}/role`, {
      method: 'PUT',
      body: JSON.stringify({ role }),
    });
    if (!response.success) throw new Error(response.error || 'Failed to update role');
  }

  // ============ DRIVER ENDPOINTS ============

  async getDriverBuses(): Promise<any[]> {
    const response = await this.driverRequest<ApiResponse<any[]>>('/driver/buses');
    if (response.success && response.data) return response.data;
    throw new Error(response.error || 'Failed to fetch buses');
  }

  async driverStart(busId: string): Promise<any> {
    const response = await this.driverRequest<ApiResponse<any>>('/driver/start', {
      method: 'POST',
      body: JSON.stringify({ busId }),
    });
    if (response.success && response.data) return response.data;
    throw new Error(response.error || 'Failed to start driving');
  }

  async driverSendLocation(lat: number, lng: number, speed: number, heading: number): Promise<void> {
    const response = await this.driverRequest<ApiResponse>('/driver/location', {
      method: 'POST',
      body: JSON.stringify({ lat, lng, speed, heading }),
    });
    if (!response.success) throw new Error(response.error || 'Failed to send location');
  }

  async driverStop(): Promise<void> {
    const response = await this.driverRequest<ApiResponse>('/driver/stop', {
      method: 'POST',
    });
    if (!response.success) throw new Error(response.error || 'Failed to stop driving');
  }

  async driverUpdateProgress(stopOrder: number): Promise<any> {
    const response = await this.driverRequest<ApiResponse<any>>('/driver/progress', {
      method: 'PUT',
      body: JSON.stringify({ stopOrder }),
    });
    if (response.success && response.data) return response.data;
    throw new Error(response.error || 'Failed to update trip progress');
  }

  async driverAdvancePhase(): Promise<any> {
    const response = await this.driverRequest<ApiResponse<any>>('/driver/phase', {
      method: 'PUT',
    });
    if (response.success && response.data) return response.data;
    throw new Error(response.error || 'Failed to update service phase');
  }

  async getDriverStatus(): Promise<{ activeBus: any | null }> {
    const response = await this.driverRequest<ApiResponse<{ activeBus: any | null }>>('/driver/status');
    if (response.success && response.data) return response.data;
    return { activeBus: null };
  }

  // ============ CAMPUS ENDPOINTS ============

  async getCampusRoutePath(): Promise<{ path: [number, number][]; stops: any[] }> {
    const response = await this.request<ApiResponse<{ path: [number, number][]; stops: any[] }>>('/campus/path');
    if (response.success && response.data) return response.data;
    throw new Error(response.error || 'Failed to fetch campus route path');
  }

  // ============ UTILITY ============

  isAuthenticated(): boolean {
    return !!this.token;
  }

  getCurrentUser(): User | null {
    const userStr = localStorage.getItem('user');
    if (userStr) {
      try {
        return JSON.parse(userStr);
      } catch {
        return null;
      }
    }
    return null;
  }
}

export const api = new ApiClient();
