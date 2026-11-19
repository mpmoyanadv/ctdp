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
      <header [class]="headerClass()">
        <div class="w-full max-w-[1600px] mx-auto px-3 sm:px-6 lg:px-8 min-h-14 py-2 flex flex-wrap items-center justify-between gap-2">
          <div class="flex items-center gap-2 sm:gap-3 min-w-0">
            <a [routerLink]="homeLink()" class="flex items-center gap-2 font-bold text-base sm:text-lg tracking-tight whitespace-nowrap">
              <span class="inline-flex items-center justify-center w-8 h-8 rounded-lg bg-capi-red text-white font-extrabold text-sm shrink-0">C</span>
              <span>Cap Trans Dsp Ptl</span>
            </a>
            @if (auth.currentUser(); as user) {
              <span [class]="roleBadgeClass()">
                {{ user.role }} &bull; {{ user.userDomain }}
              </span>
            }
          </div>

          @if (auth.currentUser(); as user) {
            <div class="flex flex-wrap items-center gap-1.5 sm:gap-3">
              <div class="text-right hidden lg:block">
                <div class="text-sm font-semibold leading-none">{{ user.fullName }}</div>
                <div [class]="emailTextClass()">{{ user.email }}</div>
              </div>
              @if (user.role === 'CUSTOMER') {
                <a id="nav-customer-disputes-link"
                   routerLink="/customer/disputes"
                   [class]="navLinkClass()">
                  My Disputed Transactions
                </a>
              }
              <a [routerLink]="homeLink()" [class]="navLinkClass()">
                Dashboard
              </a>
              <button
                id="logout-btn"
                type="button"
                (click)="auth.logout()"
                class="px-2.5 sm:px-3 py-1.5 rounded-lg bg-capi-red hover:bg-red-700 text-white text-xs font-semibold transition whitespace-nowrap"
              >
                Sign Out
              </button>
            </div>
          }
        </div>
      </header>

      <main class="flex-1 w-full overflow-x-hidden">
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

  headerClass(): string {
    const role = this.auth.currentUser()?.role;
    if (role === 'ADMIN') {
      return 'bg-gradient-to-r from-slate-300 via-gray-200 to-slate-300 text-slate-900 border-b border-slate-400 shadow-md transition-colors';
    }
    if (role === 'STAFF') {
      return 'bg-black text-white shadow-md transition-colors';
    }
    return 'bg-capi-blue text-white shadow-md transition-colors';
  }

  roleBadgeClass(): string {
    const role = this.auth.currentUser()?.role;
    if (role === 'ADMIN') {
      return 'hidden md:inline-block px-2.5 py-0.5 rounded-full text-xs font-semibold bg-slate-900/10 text-slate-800 border border-slate-400 whitespace-nowrap';
    }
    if (role === 'STAFF') {
      return 'hidden md:inline-block px-2.5 py-0.5 rounded-full text-xs font-semibold bg-white/15 text-slate-200 whitespace-nowrap';
    }
    return 'hidden md:inline-block px-2.5 py-0.5 rounded-full text-xs font-semibold bg-white/15 text-blue-50 whitespace-nowrap';
  }

  emailTextClass(): string {
    const role = this.auth.currentUser()?.role;
    if (role === 'ADMIN') {
      return 'text-xs text-slate-700 mt-0.5';
    }
    if (role === 'STAFF') {
      return 'text-xs text-slate-300 mt-0.5';
    }
    return 'text-xs text-blue-200 mt-0.5';
  }

  navLinkClass(): string {
    const role = this.auth.currentUser()?.role;
    if (role === 'ADMIN') {
      return 'px-2.5 sm:px-3 py-1.5 rounded-lg bg-slate-900/10 hover:bg-slate-900/20 text-slate-900 text-xs font-semibold transition whitespace-nowrap';
    }
    return 'px-2.5 sm:px-3 py-1.5 rounded-lg bg-white/10 hover:bg-white/20 text-xs font-semibold transition whitespace-nowrap';
  }
}

