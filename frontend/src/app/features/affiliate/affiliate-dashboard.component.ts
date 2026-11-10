import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { PortalApiService } from '../../core/services/portal-api.service';
import { AuthService } from '../../core/services/auth.service';
import { DisputeSummary } from '../../core/models/portal.models';

@Component({
  selector: 'app-affiliate-dashboard',
  standalone: true,
  imports: [CommonModule, RouterLink],
  template: `
    <div class="max-w-7xl mx-auto px-4 sm:px-6 py-8 space-y-6">
      <div class="bg-white rounded-2xl shadow-sm border border-purple-200 p-6 flex flex-col md:flex-row md:items-center justify-between gap-4">
        <div>
          <div class="inline-flex items-center gap-2 px-3 py-1 rounded-full bg-purple-100 text-purple-900 text-xs font-bold mb-2">
            External Affiliate Portal &bull; {{ auth.currentUser()?.partnerInstitution || auth.currentUser()?.userDomain }}
          </div>
          <h1 class="text-2xl font-bold text-slate-900">Delegated Partner Dispute Queue</h1>
          <p class="text-sm text-slate-600 mt-1">
            Cross-institution view restricted strictly to cases delegated to <strong>{{ auth.currentUser()?.userDomain }}</strong>. Customer PII (SA ID, Phone, Account) is masked at the API boundary in compliance with POPIA.
          </p>
        </div>
        <div class="px-4 py-3 rounded-xl bg-amber-50 border border-amber-200 text-xs text-amber-900 font-semibold">
          POPIA PII Masking Enforced
        </div>
      </div>

      <div class="bg-white rounded-2xl shadow-sm border border-slate-200 overflow-hidden">
        @if (disputes.length === 0) {
          <div class="p-10 text-center text-sm text-slate-500">
            No disputes currently delegated to {{ auth.currentUser()?.userDomain }}.
          </div>
        } @else {
          <div class="overflow-x-auto">
            <table class="w-full text-sm text-left">
              <thead class="bg-slate-50 text-xs uppercase text-slate-500 border-b border-slate-200">
                <tr>
                  <th class="px-6 py-3">Dispute Ref</th>
                  <th class="px-6 py-3">Masked Customer Identity</th>
                  <th class="px-6 py-3">Masked Account &amp; Contact</th>
                  <th class="px-6 py-3">Rail &amp; Amount</th>
                  <th class="px-6 py-3">Status</th>
                  <th class="px-6 py-3 text-right">Action</th>
                </tr>
              </thead>
              <tbody class="divide-y divide-slate-100">
                @for (d of disputes; track d.id) {
                  <tr class="hover:bg-slate-50/80 transition">
                    <td class="px-6 py-4 font-mono font-bold text-slate-900">{{ d.disputeReference }}</td>
                    <td class="px-6 py-4">
                      <div class="font-semibold text-slate-900">{{ d.customer.fullName }}</div>
                      <div class="font-mono text-xs text-purple-800 font-semibold">ID: {{ d.customer.saIdNumber }}</div>
                    </td>
                    <td class="px-6 py-4 font-mono text-xs text-slate-600">
                      <div>Acct: {{ d.customer.accountNumber }}</div>
                      <div>Tel: {{ d.customer.phoneNumber }}</div>
                    </td>
                    <td class="px-6 py-4">
                      <div class="font-bold text-slate-900">R {{ d.amount | number:'1.2-2' }}</div>
                      <div class="text-xs text-slate-500">{{ d.merchantName }} ({{ d.paymentRail }})</div>
                    </td>
                    <td class="px-6 py-4">
                      <span class="px-2.5 py-1 rounded-full text-xs font-bold bg-purple-100 text-purple-900">
                        {{ d.status }}
                      </span>
                    </td>
                    <td class="px-6 py-4 text-right">
                      <a [routerLink]="['/disputes', d.id]"
                         class="px-3.5 py-1.5 rounded-lg bg-capitec-blue hover:bg-capitec-dark text-white text-xs font-semibold transition inline-block">
                        Inspect &amp; Resolve
                      </a>
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        }
      </div>
    </div>
  `
})
export class AffiliateDashboardComponent implements OnInit {
  disputes: DisputeSummary[] = [];

  constructor(private api: PortalApiService, public auth: AuthService) {}

  ngOnInit(): void {
    this.api.getDisputes().subscribe((ds) => (this.disputes = ds));
  }
}

