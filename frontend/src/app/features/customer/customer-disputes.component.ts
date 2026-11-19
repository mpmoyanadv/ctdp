import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { PortalApiService } from '../../core/services/portal-api.service';
import { DisputeSummary } from '../../core/models/portal.models';

@Component({
  selector: 'app-customer-disputes',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink],
  template: `
    <div class="w-full max-w-[1600px] mx-auto px-3 sm:px-6 lg:px-8 py-4 sm:py-6 space-y-5">
      <!-- Page Header -->
      <div class="bg-white rounded-2xl shadow-sm border border-slate-200 p-4 sm:p-6 flex flex-col md:flex-row md:items-center md:justify-between gap-4">
        <div>
          <span class="text-xs font-bold uppercase tracking-wider text-capi-blue">Dispute Case History &amp; Outcomes</span>
          <h1 class="text-2xl font-bold text-slate-900 mt-0.5">My Logged &amp; Historic Disputed Transactions</h1>
          <p class="text-sm text-slate-500 mt-1">
            Complete audit trail of active and closed transaction disputes, outcomes, evidence counts, and linked re-opened cases.
          </p>
        </div>
        <div class="flex flex-wrap items-center gap-3">
          <div class="px-4 py-2 rounded-xl bg-blue-50 border border-blue-100 text-right">
            <div class="text-xs text-slate-500">Active Disputes</div>
            <div class="text-lg font-bold text-capi-blue">{{ activeDisputesCount }}</div>
          </div>
          <div class="px-4 py-2 rounded-xl bg-emerald-50 border border-emerald-100 text-right">
            <div class="text-xs text-slate-500">Historic / Closed</div>
            <div class="text-lg font-bold text-emerald-700">{{ historicDisputesCount }}</div>
          </div>
          <a routerLink="/customer"
             id="back-to-transactions-link"
             class="px-4 py-2.5 rounded-xl border border-slate-300 text-slate-700 hover:bg-slate-50 text-xs font-semibold transition whitespace-nowrap">
            &larr; Back to Account Transactions
          </a>
        </div>
      </div>

      <!-- Disputes Table Card -->
      <section class="bg-white rounded-2xl shadow-sm border border-slate-200 overflow-hidden">
        <div class="px-4 sm:px-6 py-4 border-b border-slate-100 flex flex-col lg:flex-row lg:items-center lg:justify-between gap-3">
          <div class="flex flex-col sm:flex-row sm:items-center gap-3 flex-1">
            <div class="relative w-full sm:w-80">
              <input
                id="dispute-ref-search"
                type="text"
                name="searchQuery"
                [(ngModel)]="searchQuery"
                placeholder="Search by Dispute Reference (e.g. DSP-2026-10042)..."
                class="w-full px-3.5 py-2 pr-14 rounded-lg border border-slate-300 text-xs font-mono focus:ring-2 focus:ring-capi-blue outline-none"
              />
              @if (searchQuery) {
                <button
                  type="button"
                  (click)="searchQuery = ''"
                  class="absolute right-2.5 top-1/2 -translate-y-1/2 text-[11px] font-semibold text-slate-500 hover:text-slate-800"
                >
                  Clear
                </button>
              }
            </div>
            <div class="text-xs font-semibold text-slate-500 whitespace-nowrap">
              Showing {{ filteredDisputes.length }} of {{ disputes.length }} disputes
            </div>
          </div>
          <div class="flex flex-wrap items-center gap-2">
            <button
              id="filter-all-btn"
              type="button"
              (click)="disputeFilter = 'ALL'"
              [class]="disputeFilter === 'ALL'
                ? 'px-3 py-1.5 rounded-lg bg-capi-blue text-white text-xs font-semibold'
                : 'px-3 py-1.5 rounded-lg bg-slate-100 text-slate-700 hover:bg-slate-200 text-xs font-semibold'"
            >
              All ({{ disputes.length }})
            </button>
            <button
              id="filter-active-btn"
              type="button"
              (click)="disputeFilter = 'ACTIVE'"
              [class]="disputeFilter === 'ACTIVE'
                ? 'px-3 py-1.5 rounded-lg bg-capi-blue text-white text-xs font-semibold'
                : 'px-3 py-1.5 rounded-lg bg-slate-100 text-slate-700 hover:bg-slate-200 text-xs font-semibold'"
            >
              Active ({{ activeDisputesCount }})
            </button>
            <button
              id="filter-historic-btn"
              type="button"
              (click)="disputeFilter = 'HISTORIC'"
              [class]="disputeFilter === 'HISTORIC'
                ? 'px-3 py-1.5 rounded-lg bg-capi-blue text-white text-xs font-semibold'
                : 'px-3 py-1.5 rounded-lg bg-slate-100 text-slate-700 hover:bg-slate-200 text-xs font-semibold'"
            >
              Historic / Closed ({{ historicDisputesCount }})
            </button>
            <button type="button" (click)="loadDisputes()" class="ml-2 text-xs text-capi-blue font-semibold hover:underline">
              Refresh
            </button>
          </div>
        </div>

        @if (filteredDisputes.length === 0) {
          <div class="p-8 text-center text-sm text-slate-500">No disputed transactions match the selected filter.</div>
        } @else {
          <div class="overflow-x-auto">
            <table class="w-full text-sm text-left">
              <thead class="bg-slate-50 text-xs uppercase text-slate-500 border-b border-slate-200">
                <tr>
                  <th class="px-3 sm:px-4 py-3">Dispute Ref</th>
                  <th class="px-3 sm:px-4 py-3">Merchant &amp; Rail</th>
                  <th class="px-3 sm:px-4 py-3">Category &amp; Outcome</th>
                  <th class="px-3 sm:px-4 py-3">Amount</th>
                  <th class="px-3 sm:px-4 py-3">Status</th>
                  <th class="px-3 sm:px-4 py-3">Logged / Resolved</th>
                  <th class="px-3 sm:px-4 py-3">Evidence</th>
                  <th class="px-3 sm:px-4 py-3 text-right">Details</th>
                </tr>
              </thead>
              <tbody class="divide-y divide-slate-100">
                @for (d of filteredDisputes; track d.id) {
                  <tr class="hover:bg-slate-50/80 transition">
                    <td class="px-3 sm:px-4 py-3">
                      <div class="font-mono font-bold text-slate-900 whitespace-nowrap">{{ d.disputeReference }}</div>
                      @if (d.originalDisputeReference) {
                        <div class="text-xs text-indigo-700 font-medium mt-0.5">
                          Linked to {{ d.originalDisputeReference }}
                        </div>
                      }
                    </td>
                    <td class="px-3 sm:px-4 py-3">
                      <div class="font-medium text-slate-800">{{ d.merchantName }}</div>
                      <div class="text-xs text-slate-500">{{ d.paymentRail }} &bull; {{ d.sourceChannel || 'WEB_PORTAL' }}</div>
                    </td>
                    <td class="px-3 sm:px-4 py-3">
                      <div class="text-xs font-semibold text-slate-700">{{ d.category }}</div>
                      @if (d.resolutionSummary) {
                        <div class="text-xs text-slate-500 mt-0.5 max-w-xs truncate" [title]="d.resolutionSummary">
                          Outcome: {{ d.resolutionSummary }}
                        </div>
                      } @else {
                        <div class="text-xs text-slate-400 mt-0.5 max-w-xs truncate" [title]="d.reason">
                          {{ d.reason }}
                        </div>
                      }
                    </td>
                    <td class="px-3 sm:px-4 py-3 font-semibold text-slate-900 whitespace-nowrap">R {{ d.amount | number:'1.2-2' }}</td>
                    <td class="px-3 sm:px-4 py-3">
                      <span [class]="statusBadgeClass(d.status)">{{ d.status }}</span>
                    </td>
                    <td class="px-3 sm:px-4 py-3 text-xs text-slate-500 whitespace-nowrap">
                      <div>Logged: {{ d.createdAt | date:'shortDate' }}</div>
                      @if (d.resolvedAt) {
                        <div class="text-emerald-700 font-medium">Closed: {{ d.resolvedAt | date:'shortDate' }}</div>
                      }
                    </td>
                    <td class="px-3 sm:px-4 py-3 text-xs text-slate-600 whitespace-nowrap">{{ d.attachmentCount }} file(s)</td>
                    <td class="px-3 sm:px-4 py-3 text-right">
                      <a [routerLink]="['/disputes', d.id]"
                         class="px-3.5 py-1.5 rounded-lg bg-capi-blue hover:bg-capi-dark text-white text-xs font-semibold transition inline-block whitespace-nowrap">
                        View Case
                      </a>
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        }
      </section>
    </div>
  `
})
export class CustomerDisputesComponent implements OnInit {
  disputes: DisputeSummary[] = [];
  disputeFilter: 'ALL' | 'ACTIVE' | 'HISTORIC' = 'ALL';
  searchQuery = '';

  constructor(private api: PortalApiService, private route: ActivatedRoute) {}

  get activeDisputesCount(): number {
    return this.disputes.filter((d) => !d.terminal).length;
  }

  get historicDisputesCount(): number {
    return this.disputes.filter((d) => d.terminal).length;
  }

  get filteredDisputes(): DisputeSummary[] {
    let list = this.disputes;
    if (this.disputeFilter === 'ACTIVE') {
      list = list.filter((d) => !d.terminal);
    } else if (this.disputeFilter === 'HISTORIC') {
      list = list.filter((d) => d.terminal);
    }

    const q = this.searchQuery.trim().toLowerCase();
    if (!q) {
      return list;
    }

    return list.filter(
      (d) =>
        d.disputeReference.toLowerCase().includes(q) ||
        (d.originalDisputeReference?.toLowerCase().includes(q) ?? false) ||
        d.merchantName.toLowerCase().includes(q)
    );
  }

  ngOnInit(): void {
    this.route.queryParamMap.subscribe((params) => {
      const f = params.get('filter')?.toUpperCase();
      if (f === 'ACTIVE' || f === 'HISTORIC' || f === 'ALL') {
        this.disputeFilter = f;
      }
    });
    this.loadDisputes();
  }

  loadDisputes(): void {
    this.api.getDisputes().subscribe((ds) => (this.disputes = ds));
  }

  statusBadgeClass(status: string): string {
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
