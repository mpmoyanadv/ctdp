import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { AuthService } from '../../core/services/auth.service';

@Component({
  selector: 'app-fraud-report',
  standalone: true,
  imports: [CommonModule, RouterLink],
  template: `
    <div class="max-w-xl mx-auto px-4 py-14">
      <div class="bg-white rounded-2xl shadow-xl border border-slate-200 overflow-hidden">
        <div class="bg-red-700 text-white px-7 py-6">
          <p class="text-xs font-bold uppercase tracking-wider text-red-200 mb-1">Capi Instant Fraud Shield</p>
          <h1 class="text-2xl font-bold">Emergency Account Lockdown</h1>
        </div>

        <div class="p-7">
          @if (processing) {
            <div class="py-8 text-center">
              <p class="text-base font-semibold text-slate-800">Securing your account and terminating active sessions...</p>
            </div>
          } @else if (lockedSuccess) {
            <div id="fraud-locked-confirmation" class="space-y-4">
              <div class="p-4 rounded-xl bg-red-50 border border-red-300 text-red-900">
                <h2 class="font-bold text-lg text-red-800 mb-1">Account Locked &mdash; Fraud Reported</h2>
                <p class="text-sm">{{ message }}</p>
              </div>

              <div class="bg-slate-50 rounded-xl p-4 border border-slate-200 text-sm text-slate-700 space-y-2">
                <p><strong>1. Profile Locked:</strong> Login access for <strong>{{ lockedEmail }}</strong> is now blocked.</p>
                <p><strong>2. Sessions Terminated:</strong> Any active web or mobile tokens have been immediately revoked.</p>
                <p><strong>3. Verification OTP Sent:</strong> Check your email for your <strong>6-digit verification code</strong> to set a new password and restore access.</p>
              </div>

              <div class="pt-3 flex flex-wrap gap-3">
                <a [routerLink]="['/reset-password-verify']" [queryParams]="{ email: lockedEmail }"
                   class="px-5 py-2.5 rounded-lg bg-capi-blue hover:bg-capi-dark text-white text-sm font-semibold transition">
                  Enter 6-Digit OTP &amp; Reset Password
                </a>
                <a routerLink="/login"
                   class="px-5 py-2.5 rounded-lg border border-slate-300 hover:bg-slate-100 text-slate-700 text-sm font-semibold transition">
                  Back to Login
                </a>
              </div>
            </div>
          } @else {
            <div class="p-4 rounded-xl bg-amber-50 border border-amber-300 text-amber-900 text-sm mb-4">
              {{ errorMessage || 'Unable to process fraud lockdown token.' }}
            </div>
            <a routerLink="/login" class="inline-block px-4 py-2 rounded-lg bg-capi-blue text-white text-sm font-semibold">
              Return to Login
            </a>
          }
        </div>
      </div>
    </div>
  `
})
export class FraudReportComponent implements OnInit {
  processing = true;
  lockedSuccess = false;
  lockedEmail = '';
  message = '';
  errorMessage = '';

  constructor(private route: ActivatedRoute, private authService: AuthService) {}

  ngOnInit(): void {
    this.route.queryParams.subscribe((params) => {
      const token = params['token'];
      const alreadyLocked = params['locked'] === 'true';
      if (alreadyLocked) {
        this.authService.clearSessionOnly();
        this.processing = false;
        this.lockedSuccess = true;
        this.lockedEmail = params['email'] || '';
        this.message = 'Your Capi account is now locked and a 6-digit verification code has been emailed to you.';
        return;
      }
      if (!token) {
        this.processing = false;
        this.errorMessage = 'Missing security token parameter.';
        return;
      }

      this.authService.reportFraud(token).subscribe({
        next: (res) => {
          this.processing = false;
          this.lockedSuccess = res.locked;
          this.lockedEmail = res.email;
          this.message = res.message;
        },
        error: (err) => {
          this.processing = false;
          this.errorMessage = err.error?.message || 'Fraud report link is invalid or expired.';
        }
      });
    });
  }
}

