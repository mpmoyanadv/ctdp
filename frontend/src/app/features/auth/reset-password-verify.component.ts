import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { AuthService } from '../../core/services/auth.service';

@Component({
  selector: 'app-reset-password-verify',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink],
  template: `
    <div class="max-w-lg mx-auto px-4 py-12">
      <div class="bg-white rounded-2xl shadow-xl border border-slate-200 overflow-hidden">
        <div class="bg-capitec-blue text-white px-7 py-6">
          <p class="text-xs uppercase tracking-wider text-blue-200 font-semibold mb-1">Identity Verification</p>
          <h1 class="text-xl font-bold">Verify 6-Digit OTP &amp; Unlock Account</h1>
        </div>

        <div class="p-7">
          @if (unlockedSuccess) {
            <div id="unlock-success-banner" class="p-4 rounded-xl bg-emerald-50 border border-emerald-300 text-emerald-900 mb-5">
              <h2 class="font-bold text-base mb-1">Account Unlocked &amp; Password Updated</h2>
              <p class="text-sm">Your profile security lock has been lifted. You may now sign in with your new password.</p>
            </div>
            <button
              type="button"
              (click)="goToLogin()"
              class="w-full py-3 rounded-lg bg-capitec-blue hover:bg-capitec-dark text-white font-semibold text-sm transition"
            >
              Proceed to Sign In
            </button>
          } @else {
            <p class="text-sm text-slate-600 mb-5">
              Enter the 6-digit verification code sent to your email after the fraud lockdown, along with a new password.
            </p>

            @if (errorMessage) {
              <div class="mb-4 p-3.5 rounded-lg bg-red-50 border border-red-200 text-red-700 text-sm">
                {{ errorMessage }}
              </div>
            }

            <form (ngSubmit)="onSubmit()" class="space-y-4">
              <div>
                <label for="reset-email" class="block text-sm font-semibold text-slate-700 mb-1">Account Email</label>
                <input
                  id="reset-email"
                  name="email"
                  type="email"
                  required
                  [(ngModel)]="email"
                  placeholder="customer@capitec.test"
                  class="w-full px-4 py-2.5 rounded-lg border border-slate-300 text-sm focus:ring-2 focus:ring-capitec-blue outline-none"
                />
              </div>

              <div>
                <label for="reset-otp" class="block text-sm font-semibold text-slate-700 mb-1">6-Digit OTP Code</label>
                <input
                  id="reset-otp"
                  name="otpCode"
                  type="text"
                  maxlength="6"
                  required
                  [(ngModel)]="otpCode"
                  placeholder="123456"
                  class="w-full px-4 py-2.5 rounded-lg border border-slate-300 text-base tracking-widest font-mono focus:ring-2 focus:ring-capitec-blue outline-none"
                />
              </div>

              <div>
                <label for="reset-new-password" class="block text-sm font-semibold text-slate-700 mb-1">New Password</label>
                <input
                  id="reset-new-password"
                  name="newPassword"
                  type="password"
                  required
                  minlength="8"
                  [(ngModel)]="newPassword"
                  placeholder="Minimum 8 characters"
                  class="w-full px-4 py-2.5 rounded-lg border border-slate-300 text-sm focus:ring-2 focus:ring-capitec-blue outline-none"
                />
              </div>

              <button
                id="reset-submit-btn"
                type="submit"
                [disabled]="submitting"
                class="w-full py-3 rounded-lg bg-capitec-blue hover:bg-capitec-dark text-white font-semibold text-sm transition disabled:opacity-50"
              >
                {{ submitting ? 'Verifying & Unlocking...' : 'Verify OTP & Restore Account Access' }}
              </button>
            </form>

            <div class="mt-5 text-center">
              <a routerLink="/login" class="text-xs text-slate-500 hover:text-capitec-blue font-medium">
                Return to Sign In
              </a>
            </div>
          }
        </div>
      </div>
    </div>
  `
})
export class ResetPasswordVerifyComponent implements OnInit {
  email = '';
  otpCode = '';
  newPassword = '';
  submitting = false;
  unlockedSuccess = false;
  errorMessage = '';

  constructor(
    private route: ActivatedRoute,
    private router: Router,
    private authService: AuthService
  ) {}

  ngOnInit(): void {
    this.route.queryParams.subscribe((params) => {
      if (params['email']) {
        this.email = params['email'];
      }
    });
  }

  onSubmit(): void {
    if (!this.email || !this.otpCode || !this.newPassword) {
      this.errorMessage = 'All fields are required.';
      return;
    }
    this.submitting = true;
    this.errorMessage = '';

    this.authService
      .resetPasswordVerify(this.email.trim(), this.otpCode.trim(), this.newPassword)
      .subscribe({
        next: () => {
          this.submitting = false;
          this.unlockedSuccess = true;
        },
        error: (err) => {
          this.submitting = false;
          this.errorMessage = err.error?.message || 'Verification failed. Please check your 6-digit OTP code.';
        }
      });
  }

  goToLogin(): void {
    this.router.navigate(['/login']);
  }
}

