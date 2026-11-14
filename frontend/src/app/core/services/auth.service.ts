import { Injectable, signal, computed } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Router } from '@angular/router';
import { Observable, tap } from 'rxjs';
import { AuthResponse, FraudReportPreviewResponse, FraudReportResponse, UserProfile } from '../models/portal.models';

const TOKEN_KEY = 'capi_portal_token';
const USER_KEY = 'capi_portal_user';

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly userSignal = signal<UserProfile | null>(this.loadStoredUser());

  readonly currentUser = computed(() => this.userSignal());
  readonly isAuthenticated = computed(() => !!this.userSignal() && !!this.getToken());

  constructor(private http: HttpClient, private router: Router) {}

  login(email: string, password: string): Observable<AuthResponse> {
    return this.http.post<AuthResponse>('/api/v1/auth/login', { email, password }).pipe(
      tap((res) => {
        localStorage.setItem(TOKEN_KEY, res.accessToken);
        localStorage.setItem(USER_KEY, JSON.stringify(res.user));
        this.userSignal.set(res.user);
      })
    );
  }

  previewFraudReport(token: string): Observable<FraudReportPreviewResponse> {
    return this.http.get<FraudReportPreviewResponse>(
      `/api/v1/auth/report-fraud/preview?token=${encodeURIComponent(token)}`
    );
  }

  reportFraud(token: string): Observable<FraudReportResponse> {
    this.clearSessionOnly();
    return this.http.post<FraudReportResponse>(
      `/api/v1/auth/report-fraud?token=${encodeURIComponent(token)}`,
      {}
    );
  }

  resetPasswordVerify(email: string, otpCode: string, newPassword: string): Observable<UserProfile> {
    return this.http.post<UserProfile>('/api/v1/auth/reset-password-verify', {
      email,
      otpCode,
      newPassword
    });
  }

  getToken(): string | null {
    return localStorage.getItem(TOKEN_KEY);
  }

  clearSessionOnly(): void {
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(USER_KEY);
    this.userSignal.set(null);
  }

  logout(): void {
    this.clearSessionOnly();
    this.router.navigate(['/login']);
  }

  getHomeRouteForRole(role?: string): string {
    switch (role) {
      case 'CUSTOMER':
        return '/customer';
      case 'STAFF':
        return '/staff';
      case 'AFFILIATE':
        return '/affiliate';
      case 'ADMIN':
        return '/admin';
      default:
        return '/login';
    }
  }

  private loadStoredUser(): UserProfile | null {
    try {
      const raw = localStorage.getItem(USER_KEY);
      return raw ? (JSON.parse(raw) as UserProfile) : null;
    } catch {
      return null;
    }
  }
}

