import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { PortalApiService } from '../../core/services/portal-api.service';
import {
  AuditLogEntry,
  DisputeSummary,
  ProductivityReport,
  UserProfile
} from '../../core/models/portal.models';

@Component({
  selector: 'app-admin-dashboard',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink],
  template: `
    <div class="max-w-7xl mx-auto px-4 sm:px-6 py-8 space-y-6">
      <!-- Supervisor Header -->
      <div class="bg-white rounded-2xl shadow-sm border border-slate-200 p-6 flex flex-col lg:flex-row lg:items-center justify-between gap-4">
        <div>
          <span class="text-xs font-bold uppercase tracking-wider text-capi-red">Supervisor &amp; Governance Command Center</span>
          <h1 class="text-2xl font-bold text-slate-900 mt-0.5">Capi System Administration Portal</h1>
          <p class="text-sm text-slate-500 mt-1">
            Review escalated cases, delegate cross-domain disputes, audit IDOR security boundary probes, export immutable CSV logs, and monitor investigator productivity.
          </p>
        </div>

        <div class="flex flex-wrap gap-2">
          <button
            id="tab-disputes-btn"
            type="button"
            (click)="switchTab('DISPUTES')"
            [class]="tabClass('DISPUTES')"
          >
            Disputes &amp; Escalations ({{ disputes.length }})
          </button>
          <button
            id="tab-audit-btn"
            type="button"
            (click)="switchTab('AUDIT')"
            [class]="tabClass('AUDIT')"
          >
            Audit Management ({{ auditLogs.length }})
          </button>
          <button
            id="tab-productivity-btn"
            type="button"
            (click)="switchTab('PRODUCTIVITY')"
            [class]="tabClass('PRODUCTIVITY')"
          >
            Staff Productivity Report
          </button>
          <button
            id="tab-users-btn"
            type="button"
            (click)="switchTab('USERS')"
            [class]="tabClass('USERS')"
          >
            Accounts &amp; Locks
          </button>
        </div>
      </div>

      @if (bannerMessage) {
        <div class="p-4 rounded-xl bg-emerald-50 border border-emerald-300 text-emerald-900 text-sm font-medium">
          {{ bannerMessage }}
        </div>
      }

      <!-- TAB 1: DISPUTES & ESCALATIONS -->
      @if (activeTab === 'DISPUTES') {
        <div class="bg-white rounded-2xl shadow-sm border border-slate-200 overflow-hidden">
          <div class="px-6 py-4 border-b border-slate-100 flex items-center justify-between">
            <h2 class="text-lg font-bold text-slate-900">Supervisor Case Queue (Escalated &amp; All Cases)</h2>
            <button type="button" (click)="loadAll()" class="text-xs text-capi-blue font-semibold hover:underline">
              Refresh Queue
            </button>
          </div>

          <div class="overflow-x-auto">
            <table class="w-full text-sm text-left">
              <thead class="bg-slate-50 text-xs uppercase text-slate-500 border-b border-slate-200">
                <tr>
                  <th class="px-5 py-3">Reference</th>
                  <th class="px-5 py-3">Customer</th>
                  <th class="px-5 py-3">Merchant &amp; Rail</th>
                  <th class="px-5 py-3">Amount</th>
                  <th class="px-5 py-3">Domain</th>
                  <th class="px-5 py-3">Status</th>
                  <th class="px-5 py-3 text-right">Supervisor Actions</th>
                </tr>
              </thead>
              <tbody class="divide-y divide-slate-100">
                @for (d of disputes; track d.id) {
                  <tr [class]="d.status === 'ESCALATED' ? 'bg-orange-50/60 hover:bg-orange-50' : 'hover:bg-slate-50/80'">
                    <td class="px-5 py-4">
                      <div class="font-mono font-bold text-slate-900">{{ d.disputeReference }}</div>
                      @if (d.originalDisputeReference) {
                        <div class="text-xs text-indigo-700 font-semibold mt-0.5">
                          Linked to {{ d.originalDisputeReference }}
                        </div>
                      }
                    </td>
                    <td class="px-5 py-4">
                      <div class="font-medium text-slate-900">{{ d.customer.fullName }}</div>
                      <div class="text-xs text-slate-500">{{ d.customer.email }}</div>
                    </td>
                    <td class="px-5 py-4">
                      <div class="font-medium text-slate-800">{{ d.merchantName }}</div>
                      <div class="text-xs text-slate-500">{{ d.paymentRail }} &bull; {{ d.category }}</div>
                    </td>
                    <td class="px-5 py-4 font-bold text-slate-900">R {{ d.amount | number:'1.2-2' }}</td>
                    <td class="px-5 py-4 text-xs font-semibold text-slate-700">{{ d.assignedDomain }}</td>
                    <td class="px-5 py-4">
                      <span [class]="statusBadge(d.status)">{{ d.status }}</span>
                    </td>
                    <td class="px-5 py-4 text-right space-x-1.5 whitespace-nowrap">
                      @if (!d.terminal) {
                        <button
                          type="button"
                          (click)="quickReject(d)"
                          class="px-2.5 py-1.5 rounded-lg bg-red-600 hover:bg-red-700 text-white text-xs font-semibold transition"
                        >
                          Reject
                        </button>
                        <button
                          type="button"
                          (click)="quickResolve(d)"
                          class="px-2.5 py-1.5 rounded-lg bg-emerald-600 hover:bg-emerald-700 text-white text-xs font-semibold transition"
                        >
                          Resolve
                        </button>
                        <button
                          type="button"
                          (click)="assignBankserv(d)"
                          class="px-2.5 py-1.5 rounded-lg bg-purple-600 hover:bg-purple-700 text-white text-xs font-semibold transition"
                        >
                          Assign Bankserv
                        </button>
                      }
                      <a [routerLink]="['/disputes', d.id]"
                         class="px-3 py-1.5 rounded-lg bg-capi-blue hover:bg-capi-dark text-white text-xs font-semibold transition inline-block">
                        Inspect
                      </a>
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        </div>
      }

      <!-- TAB 2: AUDIT MANAGEMENT -->
      @if (activeTab === 'AUDIT') {
        <div id="audit-management-section" class="bg-white rounded-2xl shadow-sm border border-slate-200 overflow-hidden">
          <div class="px-6 py-4 border-b border-slate-100 flex flex-col sm:flex-row sm:items-center justify-between gap-3">
            <div>
              <h2 class="text-lg font-bold text-slate-900">Immutable Security &amp; Operational Audit Trail</h2>
              <p class="text-xs text-slate-500">Tracks all IDOR boundary violations (UNAUTHORIZED_ACCESS_ATTEMPT), fraud locks, and dispute state changes.</p>
            </div>
            <div class="flex items-center gap-2">
              <select
                [(ngModel)]="auditFilter"
                (change)="loadAuditLogs()"
                class="px-3 py-2 rounded-lg border border-slate-300 text-xs bg-white"
              >
                <option value="">All Event Types</option>
                <option value="UNAUTHORIZED_ACCESS_ATTEMPT">UNAUTHORIZED_ACCESS_ATTEMPT (IDOR Probes)</option>
                <option value="ACCOUNT_LOCKED_FRAUD_REPORTED">ACCOUNT_LOCKED_FRAUD_REPORTED</option>
                <option value="DISPUTE_ESCALATED">DISPUTE_ESCALATED</option>
                <option value="DISPUTE_REOPENED_LINKED">DISPUTE_REOPENED_LINKED</option>
              </select>

              <button
                id="export-audit-csv-btn"
                type="button"
                (click)="exportCsv()"
                class="px-4 py-2 rounded-lg bg-capi-blue hover:bg-capi-dark text-white text-xs font-semibold shadow transition"
              >
                Export Audit CSV
              </button>
            </div>
          </div>

          <div class="overflow-x-auto">
            <table class="w-full text-xs text-left">
              <thead class="bg-slate-50 uppercase text-slate-500 border-b border-slate-200">
                <tr>
                  <th class="px-4 py-3">ID</th>
                  <th class="px-4 py-3">Timestamp</th>
                  <th class="px-4 py-3">Severity</th>
                  <th class="px-4 py-3">Event Type</th>
                  <th class="px-4 py-3">Actor</th>
                  <th class="px-4 py-3">Entity &amp; Target ID</th>
                  <th class="px-4 py-3">IP Address</th>
                  <th class="px-4 py-3">Metadata</th>
                </tr>
              </thead>
              <tbody class="divide-y divide-slate-100">
                @for (log of auditLogs; track log.id) {
                  <tr [class]="log.eventType === 'UNAUTHORIZED_ACCESS_ATTEMPT' ? 'bg-red-50/70' : 'hover:bg-slate-50'">
                    <td class="px-4 py-3 font-mono font-bold text-slate-700">#{{ log.id }}</td>
                    <td class="px-4 py-3 text-slate-500 whitespace-nowrap">{{ log.timestamp | date:'medium' }}</td>
                    <td class="px-4 py-3">
                      <span [class]="severityBadge(log.severity)">{{ log.severity }}</span>
                    </td>
                    <td class="px-4 py-3 font-mono font-bold text-slate-900">{{ log.eventType }}</td>
                    <td class="px-4 py-3">
                      <div class="font-medium text-slate-900">{{ log.actorEmail || 'N/A' }}</div>
                      <div class="font-mono text-[11px] text-slate-500">{{ log.actorId }}</div>
                    </td>
                    <td class="px-4 py-3 font-mono text-slate-700">
                      {{ log.entityName }}: {{ log.entityId }}
                    </td>
                    <td class="px-4 py-3 font-mono font-semibold text-slate-800">{{ log.ipAddress }}</td>
                    <td class="px-4 py-3 font-mono text-[11px] text-slate-600 max-w-xs truncate" [title]="log.metadata">
                      {{ log.metadata }}
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        </div>
      }

      <!-- TAB 3: STAFF PRODUCTIVITY REPORT -->
      @if (activeTab === 'PRODUCTIVITY') {
        <div id="productivity-report-section" class="space-y-6">
          <div class="bg-white rounded-2xl shadow-sm border border-slate-200 overflow-hidden">
            <div class="px-6 py-4 border-b border-slate-100 flex items-center justify-between">
              <div>
                <h2 class="text-lg font-bold text-slate-900">Staff Investigator Productivity &amp; Turnaround Time Report</h2>
                <p class="text-xs text-slate-500">Calculated automatically from StaffWorkSession timers between IN_REVIEW claim and escalation/resolution.</p>
              </div>
              <button type="button" (click)="loadProductivity()" class="text-xs text-capi-blue font-semibold hover:underline">
                Refresh Metrics
              </button>
            </div>

            @if (productivity) {
              <div class="overflow-x-auto">
                <table class="w-full text-sm text-left">
                  <thead class="bg-slate-50 text-xs uppercase text-slate-500 border-b border-slate-200">
                    <tr>
                      <th class="px-6 py-3">Investigator</th>
                      <th class="px-6 py-3">Email</th>
                      <th class="px-6 py-3">Distinct Disputes</th>
                      <th class="px-6 py-3">Completed Sessions</th>
                      <th class="px-6 py-3">Active Sessions</th>
                      <th class="px-6 py-3">Total Time Tracked</th>
                      <th class="px-6 py-3">Avg Turnaround Time</th>
                    </tr>
                  </thead>
                  <tbody class="divide-y divide-slate-100">
                    @for (row of productivity.staffSummaries; track row.staffId) {
                      <tr class="hover:bg-slate-50">
                        <td class="px-6 py-4 font-bold text-slate-900">{{ row.staffFullName }}</td>
                        <td class="px-6 py-4 text-slate-600">{{ row.staffEmail }}</td>
                        <td class="px-6 py-4 font-semibold">{{ row.distinctDisputesHandled }}</td>
                        <td class="px-6 py-4 font-bold text-emerald-700">{{ row.completedSessions }}</td>
                        <td class="px-6 py-4 font-semibold text-blue-700">{{ row.activeSessions }}</td>
                        <td class="px-6 py-4 font-mono font-bold text-slate-900">{{ row.totalDurationSeconds }} sec</td>
                        <td class="px-6 py-4 font-mono font-bold text-capi-blue">{{ row.averageDurationSeconds }} sec / session</td>
                      </tr>
                    }
                  </tbody>
                </table>
              </div>
            }
          </div>

          @if (productivity && productivity.recentSessions.length > 0) {
            <div class="bg-white rounded-2xl shadow-sm border border-slate-200 overflow-hidden">
              <div class="px-6 py-4 border-b border-slate-100">
                <h3 class="text-base font-bold text-slate-900">Recorded StaffWorkSession Entries</h3>
              </div>
              <div class="overflow-x-auto">
                <table class="w-full text-xs text-left">
                  <thead class="bg-slate-50 uppercase text-slate-500 border-b border-slate-200">
                    <tr>
                      <th class="px-5 py-3">Session ID</th>
                      <th class="px-5 py-3">Dispute Reference</th>
                      <th class="px-5 py-3">Investigator</th>
                      <th class="px-5 py-3">Started At</th>
                      <th class="px-5 py-3">Completed At</th>
                      <th class="px-5 py-3">Duration (Seconds)</th>
                    </tr>
                  </thead>
                  <tbody class="divide-y divide-slate-100">
                    @for (s of productivity.recentSessions; track s.sessionId) {
                      <tr>
                        <td class="px-5 py-3 font-mono">#{{ s.sessionId }}</td>
                        <td class="px-5 py-3 font-mono font-bold">{{ s.disputeReference }}</td>
                        <td class="px-5 py-3">{{ s.staffEmail }}</td>
                        <td class="px-5 py-3">{{ s.startedAt | date:'medium' }}</td>
                        <td class="px-5 py-3">{{ s.completedAt ? (s.completedAt | date:'medium') : 'In Progress' }}</td>
                        <td class="px-5 py-3 font-mono font-bold text-emerald-700">
                          {{ s.totalDurationSeconds !== null ? s.totalDurationSeconds + 's' : 'Active' }}
                        </td>
                      </tr>
                    }
                  </tbody>
                </table>
              </div>
            </div>
          }
        </div>
      }

      <!-- TAB 4: ACCOUNTS & FRAUD LOCKS -->
      @if (activeTab === 'USERS') {
        <div class="bg-white rounded-2xl shadow-sm border border-slate-200 overflow-hidden">
          <div class="px-6 py-4 border-b border-slate-100">
            <h2 class="text-lg font-bold text-slate-900">Portal Users &amp; Fraud Lock Status</h2>
          </div>
          <div class="overflow-x-auto">
            <table class="w-full text-sm text-left">
              <thead class="bg-slate-50 text-xs uppercase text-slate-500 border-b border-slate-200">
                <tr>
                  <th class="px-6 py-3">Full Name</th>
                  <th class="px-6 py-3">Email</th>
                  <th class="px-6 py-3">Role &amp; Domain</th>
                  <th class="px-6 py-3">Security State</th>
                  <th class="px-6 py-3 text-right">Action</th>
                </tr>
              </thead>
              <tbody class="divide-y divide-slate-100">
                @for (u of users; track u.id) {
                  <tr>
                    <td class="px-6 py-4 font-semibold text-slate-900">{{ u.fullName }}</td>
                    <td class="px-6 py-4 text-slate-600">{{ u.email }}</td>
                    <td class="px-6 py-4 text-xs font-semibold">{{ u.role }} ({{ u.userDomain }})</td>
                    <td class="px-6 py-4">
                      @if (u.isLocked) {
                        <span class="px-2.5 py-1 rounded-full text-xs font-bold bg-red-100 text-red-800">
                          LOCKED ({{ u.lockedReason || 'Fraud Alert' }})
                        </span>
                      } @else {
                        <span class="px-2.5 py-1 rounded-full text-xs font-bold bg-emerald-100 text-emerald-800">
                          ACTIVE
                        </span>
                      }
                    </td>
                    <td class="px-6 py-4 text-right">
                      @if (u.isLocked) {
                        <button
                          type="button"
                          (click)="unlockAccount(u)"
                          class="px-3 py-1.5 rounded-lg bg-emerald-600 hover:bg-emerald-700 text-white text-xs font-semibold"
                        >
                          Unlock Account
                        </button>
                      }
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        </div>
      }
    </div>
  `
})
export class AdminDashboardComponent implements OnInit {
  activeTab: 'DISPUTES' | 'AUDIT' | 'PRODUCTIVITY' | 'USERS' = 'DISPUTES';
  disputes: DisputeSummary[] = [];
  auditLogs: AuditLogEntry[] = [];
  productivity: ProductivityReport | null = null;
  users: UserProfile[] = [];
  auditFilter = '';
  bannerMessage = '';

  constructor(private api: PortalApiService) {}

  ngOnInit(): void {
    this.loadAll();
  }

  loadAll(): void {
    this.api.getDisputes().subscribe((d) => (this.disputes = d));
    this.loadAuditLogs();
    this.loadProductivity();
    this.api.getAdminUsers().subscribe((u) => (this.users = u));
  }

  loadAuditLogs(): void {
    this.api.getAuditLogs(this.auditFilter || undefined).subscribe((logs) => (this.auditLogs = logs));
  }

  loadProductivity(): void {
    this.api.getProductivityReport().subscribe((rep) => (this.productivity = rep));
  }

  switchTab(tab: 'DISPUTES' | 'AUDIT' | 'PRODUCTIVITY' | 'USERS'): void {
    this.activeTab = tab;
    this.bannerMessage = '';
    if (tab === 'AUDIT') this.loadAuditLogs();
    if (tab === 'PRODUCTIVITY') this.loadProductivity();
  }

  quickReject(d: DisputeSummary): void {
    this.api
      .updateStatus(d.id, 'REJECTED', 'Rejected by Supervisor: Insufficient supporting affidavit provided within SLA.')
      .subscribe((updated) => {
        this.bannerMessage = `Dispute ${updated.disputeReference} transitioned to REJECTED.`;
        this.loadAll();
      });
  }

  quickResolve(d: DisputeSummary): void {
    this.api
      .updateStatus(d.id, 'RESOLVED', 'Resolved by Supervisor after investigation review.')
      .subscribe((updated) => {
        this.bannerMessage = `Dispute ${updated.disputeReference} transitioned to RESOLVED.`;
        this.loadAll();
      });
  }

  assignBankserv(d: DisputeSummary): void {
    this.api
      .delegateDispute(d.id, 'PARTNER_BANKSERV', 'Assigned by Supervisor to BankservAfrica external investigators.')
      .subscribe((updated) => {
        this.bannerMessage = `Dispute ${updated.disputeReference} assigned to PARTNER_BANKSERV.`;
        this.loadAll();
      });
  }

  exportCsv(): void {
    this.api.exportAuditCsv().subscribe((blob) => {
      const url = window.URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = 'capi-audit-logs.csv';
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      window.URL.revokeObjectURL(url);
      this.bannerMessage = 'Exported immutable audit log CSV (capi-audit-logs.csv).';
      this.loadAuditLogs();
    });
  }

  unlockAccount(u: UserProfile): void {
    this.api.unlockUser(u.id).subscribe(() => {
      this.bannerMessage = `Unlocked account ${u.email}.`;
      this.api.getAdminUsers().subscribe((users) => (this.users = users));
    });
  }

  tabClass(tab: string): string {
    return this.activeTab === tab
      ? 'px-4 py-2 rounded-lg bg-capi-blue text-white text-xs font-semibold shadow-sm'
      : 'px-4 py-2 rounded-lg bg-slate-100 text-slate-700 hover:bg-slate-200 text-xs font-semibold';
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
        return base + 'bg-orange-200 text-orange-950';
      case 'RESOLVED':
      case 'REFUNDED':
        return base + 'bg-emerald-100 text-emerald-800';
      case 'REJECTED':
        return base + 'bg-red-100 text-red-800';
      default:
        return base + 'bg-slate-100 text-slate-800';
    }
  }

  severityBadge(severity: string): string {
    const base = 'px-2 py-0.5 rounded font-bold text-[11px] ';
    if (severity === 'CRITICAL' || severity === 'HIGH') {
      return base + 'bg-red-600 text-white';
    }
    if (severity === 'WARN') {
      return base + 'bg-amber-200 text-amber-900';
    }
    return base + 'bg-slate-200 text-slate-700';
  }
}

