import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { AuthService } from '../../core/services/auth.service';

@Component({
  selector: 'app-login',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink],
  template: `
    <div class="min-h-[calc(100vh-56px)] flex items-center justify-center px-3 sm:px-6 py-4 sm:py-8">
      <div class="max-w-4xl w-full grid grid-cols-1 lg:grid-cols-5 bg-white rounded-2xl shadow-xl border border-slate-200 overflow-hidden">
        <!-- Brand Left Panel -->
        <div class="lg:col-span-2 bg-gradient-to-br from-capi-blue via-capi-light to-capi-dark text-white p-5 sm:p-7 lg:p-8 flex flex-col justify-between">
          <div>
            <div class="inline-flex items-center gap-2 bg-white/15 px-3 py-1 rounded-full text-xs font-semibold uppercase tracking-wider mb-6">
              Global One Digital Security
            </div>
            <h1 class="text-2xl font-bold leading-tight mb-3">
              Cap Trans Dsp Ptl
            </h1>
            <p class="text-blue-100 text-sm leading-relaxed">
              Log unauthorized card or PayShap disputes, upload cryptographic SHA-256 evidence, and track real-time investigation progress.
            </p>
          </div>

          <div class="mt-6 lg:mt-8 pt-5 border-t border-white/20">
            <p class="text-xs uppercase tracking-wider text-blue-200 font-semibold mb-2">Demo Accounts (Quick Fill)</p>
            <div class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-1 gap-1.5 text-xs">
              <button type="button" (click)="quickFill('customer@capi.test')" class="w-full text-left px-2.5 py-1.5 rounded bg-white/10 hover:bg-white/20 transition truncate">
                Customer 1: customer&#64;capi.test
              </button>
              <button type="button" (click)="quickFill('customer2@capi.test')" class="w-full text-left px-2.5 py-1.5 rounded bg-white/10 hover:bg-white/20 transition truncate">
                Customer 2: customer2&#64;capi.test
              </button>
              <button type="button" (click)="quickFill('staff@capi.test')" class="w-full text-left px-2.5 py-1.5 rounded bg-white/10 hover:bg-white/20 transition truncate">
                Internal Staff 1: staff&#64;capi.test
              </button>
              <button type="button" (click)="quickFill('staff2@capi.test')" class="w-full text-left px-2.5 py-1.5 rounded bg-white/10 hover:bg-white/20 transition truncate">
                Internal Staff 2: staff2&#64;capi.test
              </button>
              <button type="button" (click)="quickFill('admin@capi.test')" class="w-full text-left px-2.5 py-1.5 rounded bg-white/10 hover:bg-white/20 transition truncate">
                System Admin: admin&#64;capi.test
              </button>
            </div>
          </div>
        </div>

        <!-- Sign-In Form -->
        <div class="lg:col-span-3 p-5 sm:p-7 lg:p-8 flex flex-col justify-center">
          <h2 class="text-2xl font-bold text-slate-900 mb-1">Sign In to Your Profile</h2>
          <p class="text-sm text-slate-500 mb-6">Enter your banking or investigator credentials below.</p>

          @if (sessionExpiredNotice) {
            <div class="mb-5 p-3.5 rounded-lg bg-amber-50 border border-amber-300 text-amber-900 text-sm">
              Your previous session ended or was invalidated due to a security update. Please sign in again.
            </div>
          }

          @if (accountLockedError) {
            <div id="account-locked-banner" class="mb-5 p-4 rounded-xl bg-red-50 border-2 border-red-500 text-red-900">
              <div class="flex items-center gap-2 font-bold text-base text-red-800 mb-1">
                <span>Account Locked</span>
              </div>
              <p class="text-sm text-red-700 mb-3">{{ errorMessage }}</p>
              <a [routerLink]="['/reset-password-verify']" [queryParams]="{ email: email }"
                 class="inline-flex items-center px-4 py-2 rounded-lg bg-red-700 text-white text-xs font-semibold hover:bg-red-800 transition">
                Verify 6-Digit OTP &amp; Unlock Account
              </a>
            </div>
          } @else if (errorMessage) {
            <div class="mb-5 p-3.5 rounded-lg bg-red-50 border border-red-200 text-red-700 text-sm font-medium">
              {{ errorMessage }}
            </div>
          }

          <form (ngSubmit)="onSubmit()" class="space-y-4">
            <div>
              <label for="login-email" class="block text-sm font-semibold text-slate-700 mb-1">Email Address</label>
              <input
                id="login-email"
                name="email"
                type="email"
                required
                [(ngModel)]="email"
                placeholder="customer@capi.test"
                class="w-full px-4 py-2.5 rounded-lg border border-slate-300 focus:ring-2 focus:ring-capi-blue focus:border-capi-blue outline-none text-sm"
              />
            </div>

            <div>
              <label for="login-password" class="block text-sm font-semibold text-slate-700 mb-1">Password</label>
              <input
                id="login-password"
                name="password"
                type="password"
                required
                [(ngModel)]="password"
                placeholder="Enter your password"
                class="w-full px-4 py-2.5 rounded-lg border border-slate-300 focus:ring-2 focus:ring-capi-blue focus:border-capi-blue outline-none text-sm"
              />
            </div>

            <button
              id="login-submit-btn"
              type="submit"
              [disabled]="loading"
              class="w-full py-3 px-4 rounded-lg bg-capi-blue hover:bg-capi-dark text-white font-semibold text-sm shadow transition disabled:opacity-50"
            >
              {{ loading ? 'Authenticating...' : 'Sign In Securely' }}
            </button>
          </form>

          <div class="mt-6 pt-5 border-t border-slate-100 flex flex-col sm:flex-row sm:items-center justify-between gap-1.5 text-xs text-slate-500">
            <span>Account locked after fraud alert?</span>
            <a routerLink="/reset-password-verify" class="text-capi-blue font-semibold hover:underline">
              Verify 6-Digit OTP &amp; Reset Password
            </a>
          </div>
        </div>
      </div>
    </div>
  `
})
export class LoginComponent {
  email = '';
  password = '';
  loading = false;
  errorMessage = '';
  accountLockedError = false;
  sessionExpiredNotice = false;

  constructor(
    private authService: AuthService,
    private router: Router,
    private route: ActivatedRoute
  ) {
    if (this.authService.isAuthenticated()) {
      const role = this.authService.currentUser()?.role;
      this.router.navigate([this.authService.getHomeRouteForRole(role)]);
    }
    this.route.queryParams.subscribe((params) => {
      this.sessionExpiredNotice = params['expired'] === '1';
    });
  }

  quickFill(demoEmail: string): void {
    this.email = demoEmail;
    this.password = 'Password123!';
    this.errorMessage = '';
    this.accountLockedError = false;
  }

  onSubmit(): void {
    if (!this.email || !this.password) {
      this.errorMessage = 'Please enter both email and password.';
      return;
    }
    this.loading = true;
    this.errorMessage = '';
    this.accountLockedError = false;

    this.authService.login(this.email.trim(), this.password).subscribe({
      next: (res) => {
        this.loading = false;
        this.router.navigate([this.authService.getHomeRouteForRole(res.user.role)]);
      },
      error: (err: HttpErrorResponse) => {
        this.loading = false;
        const serverMsg = err.error?.message || 'Sign-in failed. Please verify your credentials.';
        this.errorMessage = serverMsg;
        if (err.status === 423 || err.error?.code === 'ACCOUNT_LOCKED' || serverMsg.toLowerCase().includes('locked')) {
          this.accountLockedError = true;
        }
      }
    });
  }
}

