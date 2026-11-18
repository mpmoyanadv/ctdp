import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink, RouterOutlet } from '@angular/router';
import { AuthService } from './core/services/auth.service';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [CommonModule, RouterOutlet, RouterLink],
  template: `
    <div class="min-h-screen flex flex-col bg-slate-50">
      <header class="bg-capi-blue text-white shadow-md">
        <div class="max-w-7xl mx-auto px-4 sm:px-6 h-16 flex items-center justify-between">
          <div class="flex items-center gap-3">
            <a [routerLink]="homeLink()" class="flex items-center gap-2.5 font-bold text-lg tracking-tight">
              <span class="inline-flex items-center justify-center w-8 h-8 rounded-lg bg-capi-red text-white font-extrabold text-sm">C</span>
              <span>Cap Trans Dsp Ptl</span>
            </a>
            @if (auth.currentUser(); as user) {
              <span class="hidden sm:inline-block px-2.5 py-0.5 rounded-full text-xs font-semibold bg-white/15 text-blue-50">
                {{ user.role }} &bull; {{ user.userDomain }}
              </span>
            }
          </div>

          @if (auth.currentUser(); as user) {
            <div class="flex items-center gap-4">
              <div class="text-right hidden md:block">
                <div class="text-sm font-semibold leading-none">{{ user.fullName }}</div>
                <div class="text-xs text-blue-200 mt-0.5">{{ user.email }}</div>
              </div>
              @if (user.role === 'CUSTOMER') {
                <a id="nav-customer-disputes-link"
                   routerLink="/customer/disputes"
                   class="px-3 py-1.5 rounded-lg bg-white/10 hover:bg-white/20 text-xs font-semibold transition">
                  My Disputed Transactions
                </a>
              }
              <a [routerLink]="homeLink()" class="px-3 py-1.5 rounded-lg bg-white/10 hover:bg-white/20 text-xs font-semibold transition">
                Dashboard
              </a>
              <button
                id="logout-btn"
                type="button"
                (click)="auth.logout()"
                class="px-3.5 py-1.5 rounded-lg bg-capi-red hover:bg-red-700 text-white text-xs font-semibold transition"
              >
                Sign Out
              </button>
            </div>
          }
        </div>
      </header>

      <main class="flex-1">
        <router-outlet></router-outlet>
      </main>
    </div>
  `
})
export class AppComponent {
  constructor(public auth: AuthService) {}

  homeLink(): string {
    return this.auth.getHomeRouteForRole(this.auth.currentUser()?.role);
  }
}

