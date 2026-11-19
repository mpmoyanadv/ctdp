import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { PortalApiService } from '../../core/services/portal-api.service';
import { AuthService } from '../../core/services/auth.service';
import { DisputeSummary } from '../../core/models/portal.models';

@Component({
  selector: 'app-staff-dashboard',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink],
  template: `
    <div class="w-full max-w-[1600px] mx-auto px-3 sm:px-6 lg:px-8 py-4 sm:py-6 space-y-5">
      <div class="bg-white rounded-2xl shadow-sm border border-slate-200 p-4 sm:p-6 flex flex-col md:flex-row md:items-center justify-between gap-4">
        <div>
          <span class="text-xs font-bold uppercase tracking-wider text-capi-blue">Internal Capi Operations</span>
          <h1 class="text-2xl font-bold text-slate-900 mt-0.5">Dispute Investigator Workbench</h1>
          <p class="text-sm text-slate-500 mt-1">
            Work on disputes assigned to you by a Supervisor/Admin or automatically assigned during Call Centre intake.
          </p>
        </div>
        <div class="flex flex-wrap gap-2">
          @for (f of filters; track f.key) {
            <button
              type="button"
              (click)="activeFilter = f.key"
              [class]="activeFilter === f.key
                ? 'px-3.5 py-1.5 rounded-lg bg-capi-blue text-white text-xs font-semibold'
                : 'px-3.5 py-1.5 rounded-lg bg-slate-100 text-slate-700 hover:bg-slate-200 text-xs font-semibold'"
            >
              {{ f.label }}
            </button>
          }
        </div>
      </div>

      <!-- Call Centre Intake Simulator & Search Bar (Between Header and Disputes List) -->
      <div class="bg-white rounded-2xl shadow-sm border border-slate-200 p-4 flex flex-col md:flex-row md:items-center justify-between gap-4">
        <div class="flex flex-col sm:flex-row sm:items-center gap-3 flex-1">
          <div class="relative w-full md:w-96">
            <input
              id="staff-dispute-search"
              type="text"
              name="searchQuery"
              [(ngModel)]="searchQuery"
              placeholder="Search by Dispute Reference, Customer Email, or SA ID Number..."
              class="w-full px-3.5 py-2 pr-14 rounded-xl border border-slate-300 text-xs font-mono focus:ring-2 focus:ring-capi-blue outline-none"
            />
            @if (searchQuery) {
              <button
                type="button"
                (click)="searchQuery = ''"
                class="absolute right-3 top-1/2 -translate-y-1/2 text-[11px] font-semibold text-slate-500 hover:text-slate-800"
              >
                Clear
              </button>
            }
          </div>
          <span class="px-2.5 py-1 rounded-full bg-slate-100 text-slate-600 text-xs font-semibold whitespace-nowrap">
            {{ filteredDisputes().length }} of {{ disputes.length }} cases
          </span>
        </div>

        <a routerLink="/simulator"
           id="staff-open-simulator-btn"
           class="px-4 py-2.5 rounded-xl bg-capi-blue hover:bg-capi-dark text-white text-xs font-semibold shadow-sm transition whitespace-nowrap text-center">
          Call Centre Intake Simulator
        </a>
      </div>

      @if (actionBanner) {
        <div class="p-4 rounded-xl bg-emerald-50 border border-emerald-300 text-emerald-900 text-sm font-medium">
          {{ actionBanner }}
        </div>
      }

      <div class="bg-white rounded-2xl shadow-sm border border-slate-200 overflow-hidden">
        <div class="overflow-x-auto">
          <table class="w-full text-sm text-left">
            <thead class="bg-slate-50 text-xs uppercase text-slate-500 border-b border-slate-200">
              <tr>
                <th class="px-3 sm:px-4 py-3">Reference</th>
                <th class="px-3 sm:px-4 py-3">Customer</th>
                <th class="px-3 sm:px-4 py-3">Merchant &amp; Rail</th>
                <th class="px-3 sm:px-4 py-3">Amount</th>
                <th class="px-3 sm:px-4 py-3">Domain / Assignee</th>
                <th class="px-3 sm:px-4 py-3">Status</th>
                <th class="px-3 sm:px-4 py-3 text-right">Actions</th>
              </tr>
            </thead>
            <tbody class="divide-y divide-slate-100">
              @for (d of filteredDisputes(); track d.id) {
                <tr class="hover:bg-slate-50/80 transition">
                  <td class="px-3 sm:px-4 py-3">
                    <div class="font-mono font-bold text-slate-900 whitespace-nowrap">{{ d.disputeReference }}</div>
                    @if (d.originalDisputeReference) {
                      <div class="text-xs text-indigo-700 font-semibold mt-0.5">
                        Linked: {{ d.originalDisputeReference }}
                      </div>
                    }
                  </td>
                  <td class="px-3 sm:px-4 py-3">
                    <div class="font-medium text-slate-900">{{ d.customer.fullName }}</div>
                    <div class="text-xs text-slate-500">{{ d.customer.email }}</div>
                    @if (d.customer.saIdNumber) {
                      <div class="text-[11px] font-mono text-slate-400">ID: {{ d.customer.saIdNumber }}</div>
                    }
                  </td>
                  <td class="px-3 sm:px-4 py-3">
                    <div class="font-medium text-slate-800">{{ d.merchantName }}</div>
                    <div class="text-xs text-slate-500">{{ d.paymentRail }} &bull; {{ d.category }} &bull; {{ d.sourceChannel || 'WEB_PORTAL' }}</div>
                  </td>
                  <td class="px-3 sm:px-4 py-3 font-bold text-slate-900 whitespace-nowrap">R {{ d.amount | number:'1.2-2' }}</td>
                  <td class="px-3 sm:px-4 py-3 text-xs">
                    <div class="font-semibold text-slate-700">{{ d.assignedDomain }}</div>
                    @if (isAssignedToCurrentUser(d)) {
                      <span class="inline-block mt-1 px-2 py-0.5 rounded-full bg-emerald-100 text-emerald-800 text-[11px] font-bold">
                        Assigned to You
                      </span>
                    } @else if (d.assignedUserName) {
                      <span class="inline-block mt-1 px-2 py-0.5 rounded-full bg-slate-100 text-slate-700 text-[11px] font-semibold">
                        Assigned: {{ d.assignedUserName }}
                      </span>
                    } @else {
                      <span class="inline-block mt-1 px-2 py-0.5 rounded-full bg-amber-100 text-amber-800 text-[11px] font-semibold">
                        Unassigned &mdash; Awaiting Admin
                      </span>
                    }
                  </td>
                  <td class="px-3 sm:px-4 py-3">
                    <span [class]="statusBadge(d.status)">{{ d.status }}</span>
                  </td>
                  <td class="px-3 sm:px-4 py-3 text-right whitespace-nowrap">
                    @if (isAssignedToCurrentUser(d)) {
                      <a [routerLink]="['/disputes', d.id]"
                         class="px-3.5 py-1.5 rounded-lg bg-capi-blue hover:bg-capi-dark text-white text-xs font-semibold transition inline-block">
                        Work on Case
                      </a>
                    } @else {
                      <a [routerLink]="['/disputes', d.id]"
                         class="px-3.5 py-1.5 rounded-lg border border-slate-300 bg-white hover:bg-slate-50 text-slate-700 text-xs font-semibold transition inline-block">
                        View Case (Read-Only)
                      </a>
                    }
                  </td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      </div>
    </div>
  `
})
export class StaffDashboardComponent implements OnInit {
  disputes: DisputeSummary[] = [];
  activeFilter = 'ALL';
  searchQuery = '';
  actionBanner = '';

  filters = [
    { key: 'ALL', label: 'All Cases' },
    { key: 'MY_ASSIGNED', label: 'My Assigned Cases' },
    { key: 'LOGGED', label: 'Open (LOGGED)' },
    { key: 'IN_REVIEW', label: 'In Review' },
    { key: 'PENDING_CUSTOMER_ACTION', label: 'Pending Customer' },
    { key: 'ESCALATED', label: 'Escalated' }
  ];

  constructor(private api: PortalApiService, public auth: AuthService) {}

  ngOnInit(): void {
    this.loadDisputes();
  }

  loadDisputes(): void {
    this.api.getDisputes().subscribe((list) => (this.disputes = list));
  }

  isAssignedToCurrentUser(d: DisputeSummary): boolean {
    const uid = this.auth.currentUser()?.id;
    return !!uid && d.assignedUserId === uid;
  }

  filteredDisputes(): DisputeSummary[] {
    let list: DisputeSummary[];
    if (this.activeFilter === 'ALL') {
      list = this.disputes;
    } else if (this.activeFilter === 'MY_ASSIGNED') {
      list = this.disputes.filter((d) => this.isAssignedToCurrentUser(d));
    } else {
      list = this.disputes.filter((d) => d.status === this.activeFilter);
    }

    const q = this.searchQuery.trim().toLowerCase();
    if (!q) {
      return list;
    }

    return list.filter(
      (d) =>
        d.disputeReference.toLowerCase().includes(q) ||
        (d.originalDisputeReference?.toLowerCase().includes(q) ?? false) ||
        d.customer.email.toLowerCase().includes(q) ||
        (d.customer.saIdNumber?.toLowerCase().includes(q) ?? false) ||
        d.customer.fullName.toLowerCase().includes(q)
    );
  }

  statusBadge(status: string): string {
    const base = 'px-2.5 py-1 rounded-full text-xs font-bold ';
    switch (status) {
      case 'LOGGED':
        return base + 'bg-blue-100 text-blue-800';
      case 'IN_REVIEW':
        return base + 'bg-indigo-100 text-indigo-800';
      case 'PENDING_CUSTOMER_ACTION':
        return base + 'bg-amber-100 text-amber-800';
      case 'PENDING_EXTERNAL_REVIEW':
        return base + 'bg-purple-100 text-purple-800';
      case 'ESCALATED':
        return base + 'bg-orange-100 text-orange-900';
      case 'RESOLVED':
      case 'REFUNDED':
        return base + 'bg-emerald-100 text-emerald-800';
      case 'REJECTED':
        return base + 'bg-red-100 text-red-800';
      default:
        return base + 'bg-slate-100 text-slate-800';
    }
  }
}

