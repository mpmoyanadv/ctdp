import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router, RouterLink } from '@angular/router';
import { PortalApiService } from '../../core/services/portal-api.service';
import { DisputeSummary } from '../../core/models/portal.models';

@Component({
  selector: 'app-staff-dashboard',
  standalone: true,
  imports: [CommonModule, RouterLink],
  template: `
    <div class="max-w-7xl mx-auto px-4 sm:px-6 py-8 space-y-6">
      <div class="bg-white rounded-2xl shadow-sm border border-slate-200 p-6 flex flex-col md:flex-row md:items-center justify-between gap-4">
        <div>
          <span class="text-xs font-bold uppercase tracking-wider text-capi-blue">Internal Capi Operations</span>
          <h1 class="text-2xl font-bold text-slate-900 mt-0.5">Dispute Investigator Workbench</h1>
          <p class="text-sm text-slate-500 mt-1">
            Claim open cases to start productivity session tracking, request customer affidavits, delegate PayShap cases to BankservAfrica, or escalate to Supervisor.
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
                <th class="px-5 py-3">Reference</th>
                <th class="px-5 py-3">Customer</th>
                <th class="px-5 py-3">Merchant &amp; Rail</th>
                <th class="px-5 py-3">Amount</th>
                <th class="px-5 py-3">Domain / Assignee</th>
                <th class="px-5 py-3">Status</th>
                <th class="px-5 py-3 text-right">Actions</th>
              </tr>
            </thead>
            <tbody class="divide-y divide-slate-100">
              @for (d of filteredDisputes(); track d.id) {
                <tr class="hover:bg-slate-50/80 transition">
                  <td class="px-5 py-4">
                    <div class="font-mono font-bold text-slate-900">{{ d.disputeReference }}</div>
                    @if (d.originalDisputeReference) {
                      <div class="text-xs text-indigo-700 font-semibold mt-0.5">
                        Linked: {{ d.originalDisputeReference }}
                      </div>
                    }
                  </td>
                  <td class="px-5 py-4">
                    <div class="font-medium text-slate-900">{{ d.customer.fullName }}</div>
                    <div class="text-xs text-slate-500">{{ d.customer.email }}</div>
                  </td>
                  <td class="px-5 py-4">
                    <div class="font-medium text-slate-800">{{ d.merchantName }}</div>
                    <div class="text-xs text-slate-500">{{ d.paymentRail }} &bull; {{ d.category }} &bull; {{ d.sourceChannel || 'WEB_PORTAL' }}</div>
                  </td>
                  <td class="px-5 py-4 font-bold text-slate-900">R {{ d.amount | number:'1.2-2' }}</td>
                  <td class="px-5 py-4 text-xs">
                    <div class="font-semibold text-slate-700">{{ d.assignedDomain }}</div>
                    <div class="text-slate-500">{{ d.assignedUserName || 'Unassigned' }}</div>
                  </td>
                  <td class="px-5 py-4">
                    <span [class]="statusBadge(d.status)">{{ d.status }}</span>
                  </td>
                  <td class="px-5 py-4 text-right space-x-2 whitespace-nowrap">
                    @if (!d.terminal && d.status === 'LOGGED') {
                      <button
                        type="button"
                        (click)="claimAndOpen(d)"
                        class="px-3 py-1.5 rounded-lg bg-emerald-600 hover:bg-emerald-700 text-white text-xs font-semibold transition"
                      >
                        Claim Case
                      </button>
                    }
                    @if (!d.terminal && d.assignedDomain === 'INTERNAL_CAPI' && d.paymentRail === 'PAYSHAP') {
                      <button
                        type="button"
                        (click)="delegateToBankserv(d)"
                        class="px-3 py-1.5 rounded-lg bg-purple-600 hover:bg-purple-700 text-white text-xs font-semibold transition"
                      >
                        Assign PARTNER_BANKSERV
                      </button>
                    }
                    <a [routerLink]="['/disputes', d.id]"
                       class="px-3 py-1.5 rounded-lg bg-capi-blue hover:bg-capi-dark text-white text-xs font-semibold transition inline-block">
                      Open Case
                    </a>
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
  actionBanner = '';

  filters = [
    { key: 'ALL', label: 'All Cases' },
    { key: 'LOGGED', label: 'Open (LOGGED)' },
    { key: 'IN_REVIEW', label: 'In Review' },
    { key: 'PENDING_CUSTOMER_ACTION', label: 'Pending Customer' },
    { key: 'PENDING_EXTERNAL_REVIEW', label: 'External Partner' },
    { key: 'ESCALATED', label: 'Escalated' }
  ];

  constructor(private api: PortalApiService, private router: Router) {}

  ngOnInit(): void {
    this.loadDisputes();
  }

  loadDisputes(): void {
    this.api.getDisputes().subscribe((list) => (this.disputes = list));
  }

  filteredDisputes(): DisputeSummary[] {
    if (this.activeFilter === 'ALL') return this.disputes;
    return this.disputes.filter((d) => d.status === this.activeFilter);
  }

  claimAndOpen(d: DisputeSummary): void {
    this.api.claimDispute(d.id).subscribe(() => {
      this.router.navigate(['/disputes', d.id]);
    });
  }

  delegateToBankserv(d: DisputeSummary): void {
    this.api
      .delegateDispute(d.id, 'PARTNER_BANKSERV', 'Delegated PayShap dispute to BankservAfrica clearing rail')
      .subscribe((updated) => {
        this.actionBanner = `Dispute ${updated.disputeReference} delegated to PARTNER_BANKSERV (${updated.status}).`;
        this.loadDisputes();
      });
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

