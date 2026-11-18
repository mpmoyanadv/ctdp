import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { PortalApiService } from '../../core/services/portal-api.service';
import {
  AuditLogEntry,
  DisputeSummary,
  ProductivityReport,
  StaffRoleDefinition,
  UserDomain,
  UserProfile,
  UserRole
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
            Staff, Roles &amp; Accounts ({{ users.length }})
          </button>
        </div>
      </div>

      @if (bannerMessage) {
        <div class="p-4 rounded-xl bg-emerald-50 border border-emerald-300 text-emerald-900 text-sm font-medium">
          {{ bannerMessage }}
        </div>
      }

      @if (errorMessage) {
        <div class="p-4 rounded-xl bg-red-50 border border-red-300 text-red-800 text-sm font-medium">
          {{ errorMessage }}
        </div>
      }

      <!-- TAB 1: DISPUTES & ESCALATIONS -->
      @if (activeTab === 'DISPUTES') {
        <div class="bg-white rounded-2xl shadow-sm border border-slate-200 overflow-hidden">
          <div class="px-6 py-4 border-b border-slate-100 flex flex-col md:flex-row md:items-center justify-between gap-3">
            <div>
              <h2 class="text-lg font-bold text-slate-900">Supervisor Case Queue &amp; Staff Assignment</h2>
              <p class="text-xs text-slate-500">Assign open disputes to internal investigators, reassign unavailable staff, delegate externally, or manage escalations.</p>
            </div>
            <div class="flex flex-wrap items-center gap-3">
              <div class="relative w-full sm:w-80">
                <input
                  id="admin-dispute-search"
                  type="text"
                  name="disputeSearch"
                  [(ngModel)]="disputeSearch"
                  placeholder="Search Reference, Customer Email, SA ID, or Staff..."
                  class="w-full px-3 py-1.5 pr-12 rounded-lg border border-slate-300 text-xs font-mono focus:ring-2 focus:ring-capi-blue outline-none"
                />
                @if (disputeSearch) {
                  <button
                    type="button"
                    (click)="disputeSearch = ''"
                    class="absolute right-2.5 top-1/2 -translate-y-1/2 text-[11px] font-semibold text-slate-500 hover:text-slate-800"
                  >
                    Clear
                  </button>
                }
              </div>
              <button type="button" (click)="loadAll()" class="text-xs text-capi-blue font-semibold hover:underline whitespace-nowrap">
                Refresh Queue
              </button>
            </div>
          </div>

          <div class="overflow-x-auto">
            <table class="w-full text-sm text-left">
              <thead class="bg-slate-50 text-xs uppercase text-slate-500 border-b border-slate-200">
                <tr>
                  <th class="px-5 py-3">Reference</th>
                  <th class="px-5 py-3">Customer</th>
                  <th class="px-5 py-3">Merchant &amp; Rail</th>
                  <th class="px-5 py-3">Amount</th>
                  <th class="px-5 py-3">Assigned Investigator</th>
                  <th class="px-5 py-3">Status</th>
                  <th class="px-5 py-3 text-right">Supervisor Actions &amp; Reassignment</th>
                </tr>
              </thead>
              <tbody class="divide-y divide-slate-100">
                @for (d of filteredDisputes(); track d.id) {
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
                      @if (d.customer.saIdNumber) {
                        <div class="text-[11px] font-mono text-slate-400">ID: {{ d.customer.saIdNumber }}</div>
                      }
                    </td>
                    <td class="px-5 py-4">
                      <div class="font-medium text-slate-800">{{ d.merchantName }}</div>
                      <div class="text-xs text-slate-500">{{ d.paymentRail }} &bull; {{ d.category }}</div>
                    </td>
                    <td class="px-5 py-4 font-bold text-slate-900">R {{ d.amount | number:'1.2-2' }}</td>
                    <td class="px-5 py-4 text-xs">
                      <div class="font-semibold text-slate-700">{{ d.assignedDomain }}</div>
                      @if (d.assignedUserName) {
                        <span class="inline-block mt-1 px-2 py-0.5 rounded-full bg-indigo-100 text-indigo-800 text-[11px] font-bold">
                          {{ d.assignedUserName }}
                        </span>
                      } @else {
                        <span class="inline-block mt-1 px-2 py-0.5 rounded-full bg-amber-100 text-amber-800 text-[11px] font-semibold">
                          Unassigned
                        </span>
                      }
                    </td>
                    <td class="px-5 py-4">
                      <span [class]="statusBadge(d.status)">{{ d.status }}</span>
                    </td>
                    <td class="px-5 py-4 text-right whitespace-nowrap">
                      <div class="flex flex-wrap items-center justify-end gap-1.5">
                        @if (!d.terminal) {
                          <div class="inline-flex items-center gap-1 bg-slate-50 border border-slate-200 rounded-lg p-1">
                            <select
                              [name]="'assignStaff-' + d.id"
                              [(ngModel)]="selectedStaffByDispute[d.id]"
                              class="px-2 py-1 rounded border border-slate-300 text-xs bg-white text-slate-800"
                            >
                              <option value="">Select Staff...</option>
                              @for (staff of staffUsers(); track staff.id) {
                                <option [value]="staff.id">{{ staff.fullName }} ({{ staff.partnerInstitution || staff.role }} &bull; {{ staff.email }})</option>
                              }
                            </select>
                            <button
                              type="button"
                              (click)="assignToStaff(d)"
                              class="px-2.5 py-1 rounded bg-capi-blue hover:bg-capi-dark text-white text-xs font-semibold transition"
                            >
                              {{ d.assignedUserId ? 'Reassign' : 'Assign' }}
                            </button>
                          </div>
                          <button
                            type="button"
                            (click)="assignBankserv(d)"
                            class="px-2.5 py-1.5 rounded-lg bg-purple-600 hover:bg-purple-700 text-white text-xs font-semibold transition"
                          >
                            Bankserv
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
                            (click)="quickReject(d)"
                            class="px-2.5 py-1.5 rounded-lg bg-red-600 hover:bg-red-700 text-white text-xs font-semibold transition"
                          >
                            Reject
                          </button>
                        }
                        <a [routerLink]="['/disputes', d.id]"
                           class="px-3 py-1.5 rounded-lg border border-slate-300 bg-white hover:bg-slate-50 text-slate-700 text-xs font-semibold transition inline-block">
                          Inspect
                        </a>
                      </div>
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
          <div class="px-6 py-4 border-b border-slate-100 space-y-4">
            <div class="flex flex-col lg:flex-row lg:items-center justify-between gap-3">
              <div>
                <h2 class="text-lg font-bold text-slate-900">Immutable Banking Security &amp; Operational Audit Trail</h2>
                <p class="text-xs text-slate-500">
                  Tracks Call Centre KYC lookups (<code>CALLER_KYC_LOOKUP</code> / <code>CALLER_KYC_LOOKUP_FAILED</code>), Verifications (<code>CALLER_KYC_VERIFIED</code> / <code>CALLER_KYC_VERIFICATION_FAILED</code>), IDOR violations, fraud locks, and dispute actions.
                </p>
              </div>
              <div class="flex flex-wrap items-center gap-2">
                <select
                  id="audit-event-filter"
                  [(ngModel)]="auditFilter"
                  (change)="loadAuditLogs()"
                  class="px-3 py-2 rounded-lg border border-slate-300 text-xs bg-white"
                >
                  <option value="">All Event Types</option>
                  <optgroup label="Call Centre KYC &amp; Identity Verification">
                    <option value="CALLER_KYC_LOOKUP">CALLER_KYC_LOOKUP (Load Customer Details)</option>
                    <option value="CALLER_KYC_LOOKUP_FAILED">CALLER_KYC_LOOKUP_FAILED (Invalid / Missing SA ID)</option>
                    <option value="CALLER_KYC_VERIFIED">CALLER_KYC_VERIFIED (Verified)</option>
                    <option value="CALLER_KYC_VERIFICATION_FAILED">CALLER_KYC_VERIFICATION_FAILED (Unverified / Mismatch)</option>
                  </optgroup>
                  <optgroup label="Security, Fraud &amp; Access Controls">
                    <option value="UNAUTHORIZED_ACCESS_ATTEMPT">UNAUTHORIZED_ACCESS_ATTEMPT (IDOR Probes)</option>
                    <option value="ACCOUNT_LOCKED_FRAUD_REPORTED">ACCOUNT_LOCKED_FRAUD_REPORTED</option>
                    <option value="LOGIN_FAILED_BAD_CREDENTIALS">LOGIN_FAILED_BAD_CREDENTIALS</option>
                    <option value="LOGIN_FAILED_UNKNOWN_USER">LOGIN_FAILED_UNKNOWN_USER</option>
                    <option value="LOGIN_BLOCKED_LOCKED">LOGIN_BLOCKED_LOCKED</option>
                    <option value="LOGIN_SUCCESS">LOGIN_SUCCESS</option>
                    <option value="ADMIN_UNLOCKED_USER_ACCOUNT">ADMIN_UNLOCKED_USER_ACCOUNT</option>
                    <option value="AUDIT_LOG_CSV_EXPORTED">AUDIT_LOG_CSV_EXPORTED</option>
                  </optgroup>
                  <optgroup label="Dispute Operations &amp; Assignments">
                    <option value="ADMIN_DISPUTE_ASSIGNED">ADMIN_DISPUTE_ASSIGNED</option>
                    <option value="DISPUTE_CREATED">DISPUTE_CREATED</option>
                    <option value="DISPUTE_CLAIMED">DISPUTE_CLAIMED</option>
                    <option value="DISPUTE_STATUS_CHANGED">DISPUTE_STATUS_CHANGED</option>
                    <option value="DISPUTE_NOTE_ADDED">DISPUTE_NOTE_ADDED</option>
                    <option value="DISPUTE_ESCALATED">DISPUTE_ESCALATED</option>
                    <option value="DISPUTE_DELEGATED_EXTERNAL">DISPUTE_DELEGATED_EXTERNAL</option>
                    <option value="DISPUTE_REOPENED_LINKED">DISPUTE_REOPENED_LINKED</option>
                    <option value="ATTACHMENT_UPLOADED">ATTACHMENT_UPLOADED</option>
                    <option value="IDEMPOTENCY_DUPLICATE_PREVENTED">IDEMPOTENCY_DUPLICATE_PREVENTED</option>
                  </optgroup>
                </select>

                <button
                  type="button"
                  (click)="loadAuditLogs()"
                  class="px-3.5 py-2 rounded-lg border border-slate-300 bg-white hover:bg-slate-50 text-slate-700 text-xs font-semibold transition"
                >
                  Refresh
                </button>

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

            <!-- Banking Investigation Quick Filters & Search -->
            <div class="flex flex-col sm:flex-row sm:items-center justify-between gap-3 pt-1">
              <div class="flex flex-wrap gap-2">
                <button
                  type="button"
                  (click)="setAuditCategory('ALL')"
                  [class]="auditCategoryFilter === 'ALL'
                    ? 'px-3 py-1.5 rounded-lg bg-slate-900 text-white text-xs font-semibold'
                    : 'px-3 py-1.5 rounded-lg bg-slate-100 hover:bg-slate-200 text-slate-700 text-xs font-semibold'"
                >
                  All Events ({{ auditLogs.length }})
                </button>
                <button
                  id="audit-filter-suspicious-btn"
                  type="button"
                  (click)="setAuditCategory('SUSPICIOUS')"
                  [class]="auditCategoryFilter === 'SUSPICIOUS'
                    ? 'px-3 py-1.5 rounded-lg bg-red-600 text-white text-xs font-semibold'
                    : 'px-3 py-1.5 rounded-lg bg-red-50 hover:bg-red-100 border border-red-200 text-red-800 text-xs font-semibold'"
                >
                  Suspicious / Unverified &amp; Security Alerts
                </button>
                <button
                  id="audit-filter-kyc-btn"
                  type="button"
                  (click)="setAuditCategory('CALL_CENTRE_KYC')"
                  [class]="auditCategoryFilter === 'CALL_CENTRE_KYC'
                    ? 'px-3 py-1.5 rounded-lg bg-capi-blue text-white text-xs font-semibold'
                    : 'px-3 py-1.5 rounded-lg bg-blue-50 hover:bg-blue-100 border border-blue-200 text-capi-blue text-xs font-semibold'"
                >
                  Call Centre KYC &amp; Customer Lookups
                </button>
              </div>

              <div class="w-full sm:w-80">
                <input
                  id="audit-search-input"
                  type="search"
                  [(ngModel)]="auditSearch"
                  placeholder="Search SA ID, email, event, IP, or metadata..."
                  class="w-full px-3.5 py-2 rounded-lg border border-slate-300 text-xs outline-none focus:ring-2 focus:ring-capi-blue"
                />
              </div>
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
                  <th class="px-4 py-3">Investigation Metadata</th>
                </tr>
              </thead>
              <tbody class="divide-y divide-slate-100">
                @for (log of filteredAuditLogs(); track log.id) {
                  <tr [class]="auditRowClass(log)">
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
                    <td class="px-4 py-3 font-mono text-[11px] text-slate-700 max-w-md break-words whitespace-pre-wrap">
                      {{ log.metadata }}
                    </td>
                  </tr>
                } @empty {
                  <tr>
                    <td colspan="8" class="px-6 py-8 text-center text-slate-500">
                      No audit events match the selected investigation filter or search term.
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
            <div class="px-6 py-4 border-b border-slate-100 flex flex-col sm:flex-row sm:items-center justify-between gap-2">
              <div>
                <h2 class="text-lg font-bold text-slate-900">Staff Investigator Productivity &amp; Turnaround Time Report</h2>
                <p class="text-xs text-slate-500">
                  Calculated automatically from StaffWorkSession timers started on Supervisor case assignment, reassignment, or Call Centre assisted intake auto-assignment (IN_REVIEW) through resolution, refund, rejection, escalation, external partner delegation, or staff reassignment.
                </p>
              </div>
              <button type="button" (click)="loadProductivity()" class="text-xs text-capi-blue font-semibold hover:underline whitespace-nowrap">
                Refresh Metrics
              </button>
            </div>

            @if (productivity) {
              <div class="overflow-x-auto">
                <table class="w-full text-sm text-left">
                  <thead class="bg-slate-50 text-xs uppercase text-slate-500 border-b border-slate-200">
                    <tr>
                      <th class="px-5 py-3">Investigator &amp; Role</th>
                      <th class="px-5 py-3">Email</th>
                      <th class="px-5 py-3">Assigned (Active)</th>
                      <th class="px-5 py-3">Call Centre Auto-Assigned</th>
                      <th class="px-5 py-3">Resolved / Closed</th>
                      <th class="px-5 py-3">Distinct Disputes</th>
                      <th class="px-5 py-3">Work Sessions</th>
                      <th class="px-5 py-3">Total Time Tracked</th>
                      <th class="px-5 py-3">Avg Turnaround Time</th>
                    </tr>
                  </thead>
                  <tbody class="divide-y divide-slate-100">
                    @for (row of productivity.staffSummaries; track row.staffId) {
                      <tr class="hover:bg-slate-50">
                        <td class="px-5 py-4">
                          <div class="font-bold text-slate-900">{{ row.staffFullName }}</div>
                          <div class="flex flex-wrap items-center gap-1.5 mt-1">
                            <span class="px-2 py-0.5 rounded-full text-[11px] font-bold bg-blue-50 text-capi-blue border border-blue-200">
                              {{ row.staffRole || 'STAFF' }}
                            </span>
                            @if (row.staffRoleTitle) {
                              <span class="text-xs text-slate-600">{{ row.staffRoleTitle }}</span>
                            }
                          </div>
                        </td>
                        <td class="px-5 py-4 text-slate-600 text-xs font-mono">{{ row.staffEmail }}</td>
                        <td class="px-5 py-4 font-bold text-blue-700">{{ row.currentlyAssignedDisputes ?? row.activeSessions }}</td>
                        <td class="px-5 py-4 font-semibold text-indigo-700">{{ row.callCentreDisputesLogged ?? 0 }}</td>
                        <td class="px-5 py-4 font-bold text-emerald-700">{{ row.resolvedDisputesCount ?? 0 }}</td>
                        <td class="px-5 py-4 font-semibold text-slate-800">{{ row.distinctDisputesHandled }}</td>
                        <td class="px-5 py-4 text-xs">
                          <span class="font-bold text-emerald-700">{{ row.completedSessions }}</span> completed /
                          <span class="font-bold text-blue-700">{{ row.activeSessions }}</span> active
                        </td>
                        <td class="px-5 py-4 font-mono font-bold text-slate-900">{{ row.totalDurationSeconds }} sec</td>
                        <td class="px-5 py-4 font-mono font-bold text-capi-blue">{{ row.averageDurationSeconds }} sec / session</td>
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
                      <th class="px-5 py-3">Assignment Source</th>
                      <th class="px-5 py-3">Outcome / Status</th>
                      <th class="px-5 py-3">Started At</th>
                      <th class="px-5 py-3">Completed At</th>
                      <th class="px-5 py-3">Duration</th>
                    </tr>
                  </thead>
                  <tbody class="divide-y divide-slate-100">
                    @for (s of productivity.recentSessions; track s.sessionId) {
                      <tr class="hover:bg-slate-50">
                        <td class="px-5 py-3 font-mono">#{{ s.sessionId }}</td>
                        <td class="px-5 py-3 font-mono font-bold">{{ s.disputeReference }}</td>
                        <td class="px-5 py-3">
                          <div class="font-semibold text-slate-900">{{ s.staffFullName || s.staffEmail }}</div>
                          <div class="text-[11px] text-slate-500 font-mono">{{ s.staffEmail }}</div>
                        </td>
                        <td class="px-5 py-3 font-medium text-slate-700">{{ s.assignmentSource || 'Supervisor / Admin Assignment' }}</td>
                        <td class="px-5 py-3">
                          <div class="font-semibold text-slate-800">{{ s.sessionOutcome || (s.completedAt ? 'Completed' : 'Active (In Review)') }}</div>
                          @if (s.disputeStatus) {
                            <span [class]="statusBadge(s.disputeStatus)" class="inline-block mt-1 scale-90 origin-left">{{ s.disputeStatus }}</span>
                          }
                        </td>
                        <td class="px-5 py-3 whitespace-nowrap">{{ s.startedAt | date:'medium' }}</td>
                        <td class="px-5 py-3 whitespace-nowrap">{{ s.completedAt ? (s.completedAt | date:'medium') : 'In Progress' }}</td>
                        <td class="px-5 py-3 font-mono font-bold"
                            [class]="s.totalDurationSeconds !== null ? 'text-emerald-700' : 'text-blue-700'">
                          {{ s.totalDurationSeconds !== null ? s.totalDurationSeconds + 's' : ((s.elapsedDurationSeconds ?? 1) + 's (Active)') }}
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

      <!-- TAB 4: STAFF, ROLES & ACCOUNTS -->
      @if (activeTab === 'USERS') {
        <div class="space-y-6">
          <div class="grid grid-cols-1 lg:grid-cols-2 gap-6">
            <!-- Panel 1: Role Catalog & Add/Update Role -->
            <div id="admin-roles-panel" class="bg-white rounded-2xl shadow-sm border border-slate-200 p-6 space-y-5">
              <div class="flex items-center justify-between border-b border-slate-100 pb-3">
                <div>
                  <h2 class="text-lg font-bold text-slate-900">Operational Roles Catalog ({{ roles.length }})</h2>
                  <p class="text-xs text-slate-500">Define or update banking roles mapped to RBAC security roles and institutional domains.</p>
                </div>
                @if (editingRoleCode) {
                  <button type="button" (click)="cancelRoleEdit()" class="text-xs font-semibold text-red-600 hover:underline">
                    Cancel Edit
                  </button>
                }
              </div>

              <form (ngSubmit)="saveRole()" class="space-y-3 p-4 rounded-xl bg-slate-50 border border-slate-200">
                <div class="text-xs font-bold uppercase tracking-wider text-capi-blue">
                  {{ editingRoleCode ? 'Update Role: ' + editingRoleCode : 'Add New Operational Role' }}
                </div>
                <div class="grid grid-cols-1 sm:grid-cols-2 gap-3">
                  <div>
                    <label for="role-code-input" class="block text-[11px] font-semibold text-slate-700 mb-1">Role Code (Optional)</label>
                    <input
                      id="role-code-input"
                      type="text"
                      name="roleCodeInput"
                      [(ngModel)]="roleForm.roleCode"
                      [disabled]="!!editingRoleCode"
                      placeholder="Auto-derived if blank (e.g. CHARGEBACK_LEAD)"
                      class="w-full px-3 py-2 rounded-lg border border-slate-300 text-xs font-mono bg-white disabled:bg-slate-100 outline-none focus:ring-2 focus:ring-capi-blue"
                    />
                  </div>
                  <div>
                    <label for="role-display-input" class="block text-[11px] font-semibold text-slate-700 mb-1">Role Display Name <span class="text-capi-red">*</span></label>
                    <input
                      id="role-display-input"
                      type="text"
                      name="roleDisplayName"
                      required
                      [(ngModel)]="roleForm.displayName"
                      placeholder="e.g. Chargeback Specialist"
                      class="w-full px-3 py-2 rounded-lg border border-slate-300 text-xs bg-white outline-none focus:ring-2 focus:ring-capi-blue"
                    />
                  </div>
                  <div>
                    <label for="role-base-select" class="block text-[11px] font-semibold text-slate-700 mb-1">Base Security Role <span class="text-capi-red">*</span></label>
                    <select
                      id="role-base-select"
                      name="roleBaseRole"
                      [(ngModel)]="roleForm.baseRole"
                      (ngModelChange)="onRoleFormBaseRoleChange()"
                      class="w-full px-3 py-2 rounded-lg border border-slate-300 text-xs bg-white outline-none focus:ring-2 focus:ring-capi-blue"
                    >
                      <option value="STAFF">STAFF (Internal Investigator / Agent)</option>
                      <option value="ADMIN">ADMIN (Supervisor / System Admin)</option>
                      <option value="AFFILIATE">AFFILIATE (External Clearing / Merchant Partner)</option>
                    </select>
                  </div>
                  <div>
                    <label for="role-domain-select" class="block text-[11px] font-semibold text-slate-700 mb-1">Default Domain <span class="text-capi-red">*</span></label>
                    <select
                      id="role-domain-select"
                      name="roleDefaultDomain"
                      [(ngModel)]="roleForm.defaultDomain"
                      class="w-full px-3 py-2 rounded-lg border border-slate-300 text-xs bg-white outline-none focus:ring-2 focus:ring-capi-blue"
                    >
                      <option value="INTERNAL_CAPI">INTERNAL_CAPI</option>
                      <option value="PARTNER_BANKSERV">PARTNER_BANKSERV</option>
                      <option value="PARTNER_MERCHANT">PARTNER_MERCHANT</option>
                    </select>
                  </div>
                </div>
                <div>
                  <label for="role-desc-input" class="block text-[11px] font-semibold text-slate-700 mb-1">Role Description</label>
                  <input
                    id="role-desc-input"
                    type="text"
                    name="roleDescription"
                    [(ngModel)]="roleForm.description"
                    placeholder="Operational responsibilities for this role..."
                    class="w-full px-3 py-2 rounded-lg border border-slate-300 text-xs bg-white outline-none focus:ring-2 focus:ring-capi-blue"
                  />
                </div>
                <div class="flex justify-end gap-2 pt-1">
                  @if (editingRoleCode) {
                    <button type="button" (click)="cancelRoleEdit()" class="px-3 py-1.5 rounded-lg border border-slate-300 bg-white text-slate-700 text-xs font-semibold">
                      Cancel
                    </button>
                  }
                  <button
                    id="save-role-btn"
                    type="submit"
                    [disabled]="savingRole"
                    class="px-4 py-1.5 rounded-lg bg-capi-blue hover:bg-capi-dark text-white text-xs font-semibold shadow-sm transition disabled:opacity-50"
                  >
                    {{ savingRole ? 'Saving Role...' : (editingRoleCode ? 'Update Role' : 'Add Role') }}
                  </button>
                </div>
              </form>

              <div class="space-y-2 max-h-72 overflow-y-auto pr-1">
                @for (r of roles; track r.roleCode) {
                  <div class="p-3 rounded-xl border border-slate-200 bg-white flex items-start justify-between gap-3">
                    <div>
                      <div class="flex flex-wrap items-center gap-1.5">
                        <span class="font-bold text-xs text-slate-900">{{ r.displayName }}</span>
                        <span class="px-2 py-0.5 rounded bg-slate-100 text-slate-700 font-mono text-[10px] font-bold">{{ r.roleCode }}</span>
                        <span class="px-2 py-0.5 rounded bg-blue-50 text-capi-blue text-[10px] font-bold">{{ r.baseRole }} &bull; {{ r.defaultDomain }}</span>
                      </div>
                      <p class="text-[11px] text-slate-500 mt-1">{{ r.description }}</p>
                    </div>
                    <button
                      type="button"
                      (click)="startEditRole(r)"
                      class="px-2.5 py-1 rounded-lg border border-slate-300 hover:bg-slate-50 text-slate-700 text-[11px] font-semibold whitespace-nowrap"
                    >
                      Edit Role
                    </button>
                  </div>
                }
              </div>
            </div>

            <!-- Panel 2: Add / Update Staff Member & Account -->
            <div id="admin-staff-form-panel" class="bg-white rounded-2xl shadow-sm border border-slate-200 p-6 space-y-5">
              <div class="flex items-center justify-between border-b border-slate-100 pb-3">
                <div>
                  <h2 class="text-lg font-bold text-slate-900">
                    {{ editingUserId ? 'Update Staff / Portal Account' : 'Add New Staff Member' }}
                  </h2>
                  <p class="text-xs text-slate-500">
                    Provision new investigators/supervisors or update an existing user's details, role, domain, and credentials.
                  </p>
                </div>
                @if (editingUserId) {
                  <button type="button" (click)="cancelUserEdit()" class="text-xs font-semibold text-red-600 hover:underline">
                    Cancel Edit
                  </button>
                }
              </div>

              <form (ngSubmit)="saveUser()" class="space-y-3">
                <div class="grid grid-cols-1 sm:grid-cols-2 gap-3">
                  <div>
                    <label for="staff-fullname-input" class="block text-[11px] font-semibold text-slate-700 mb-1">Full Name <span class="text-capi-red">*</span></label>
                    <input
                      id="staff-fullname-input"
                      type="text"
                      name="staffFullName"
                      required
                      [(ngModel)]="userForm.fullName"
                      placeholder="e.g. Kagiso Molefe"
                      class="w-full px-3 py-2 rounded-lg border border-slate-300 text-xs outline-none focus:ring-2 focus:ring-capi-blue"
                    />
                  </div>
                  <div>
                    <label for="staff-email-input" class="block text-[11px] font-semibold text-slate-700 mb-1">Work Email <span class="text-capi-red">*</span></label>
                    <input
                      id="staff-email-input"
                      type="email"
                      name="staffEmail"
                      required
                      [(ngModel)]="userForm.email"
                      placeholder="e.g. kagiso.molefe@capi.test"
                      class="w-full px-3 py-2 rounded-lg border border-slate-300 text-xs font-mono outline-none focus:ring-2 focus:ring-capi-blue"
                    />
                  </div>
                  <div>
                    <label for="staff-phone-input" class="block text-[11px] font-semibold text-slate-700 mb-1">Phone Number</label>
                    <input
                      id="staff-phone-input"
                      type="text"
                      name="staffPhone"
                      [(ngModel)]="userForm.phoneNumber"
                      placeholder="+27219411005"
                      class="w-full px-3 py-2 rounded-lg border border-slate-300 text-xs font-mono outline-none focus:ring-2 focus:ring-capi-blue"
                    />
                  </div>
                  <div>
                    <label for="staff-role-template-select" class="block text-[11px] font-semibold text-slate-700 mb-1">Apply Role Template (Optional)</label>
                    <select
                      id="staff-role-template-select"
                      name="selectedRoleTemplate"
                      [(ngModel)]="selectedRoleTemplate"
                      (ngModelChange)="onRoleTemplateSelected()"
                      class="w-full px-3 py-2 rounded-lg border border-slate-300 text-xs bg-white outline-none focus:ring-2 focus:ring-capi-blue"
                    >
                      <option value="">-- Select Role Template --</option>
                      @for (r of roles; track r.roleCode) {
                        <option [value]="r.roleCode">{{ r.displayName }} ({{ r.baseRole }})</option>
                      }
                    </select>
                  </div>
                  <div>
                    <label for="staff-role-select" class="block text-[11px] font-semibold text-slate-700 mb-1">System RBAC Role <span class="text-capi-red">*</span></label>
                    <select
                      id="staff-role-select"
                      name="staffRole"
                      [(ngModel)]="userForm.role"
                      (ngModelChange)="onStaffRoleChange()"
                      class="w-full px-3 py-2 rounded-lg border border-slate-300 text-xs bg-white outline-none focus:ring-2 focus:ring-capi-blue"
                    >
                      <option value="STAFF">STAFF</option>
                      <option value="ADMIN">ADMIN</option>
                      <option value="AFFILIATE">AFFILIATE</option>
                      <option value="CUSTOMER">CUSTOMER</option>
                    </select>
                  </div>
                  <div>
                    <label for="staff-domain-select" class="block text-[11px] font-semibold text-slate-700 mb-1">User Domain <span class="text-capi-red">*</span></label>
                    <select
                      id="staff-domain-select"
                      name="staffDomain"
                      [(ngModel)]="userForm.userDomain"
                      class="w-full px-3 py-2 rounded-lg border border-slate-300 text-xs bg-white outline-none focus:ring-2 focus:ring-capi-blue"
                    >
                      <option value="INTERNAL_CAPI">INTERNAL_CAPI</option>
                      <option value="PARTNER_BANKSERV">PARTNER_BANKSERV</option>
                      <option value="PARTNER_MERCHANT">PARTNER_MERCHANT</option>
                      <option value="CUSTOMER">CUSTOMER</option>
                    </select>
                  </div>
                </div>

                <div class="grid grid-cols-1 sm:grid-cols-2 gap-3">
                  <div>
                    <label for="staff-title-input" class="block text-[11px] font-semibold text-slate-700 mb-1">Operational Role Title / Institution</label>
                    <input
                      id="staff-title-input"
                      type="text"
                      name="staffTitle"
                      [(ngModel)]="userForm.partnerInstitution"
                      placeholder="e.g. Senior Fraud & Chargeback Specialist"
                      class="w-full px-3 py-2 rounded-lg border border-slate-300 text-xs outline-none focus:ring-2 focus:ring-capi-blue"
                    />
                  </div>
                  <div>
                    <label for="staff-password-input" class="block text-[11px] font-semibold text-slate-700 mb-1">
                      {{ editingUserId ? 'New Password (Optional — leave blank to keep)' : 'Initial Password (Defaults to Password123!)' }}
                    </label>
                    <input
                      id="staff-password-input"
                      type="password"
                      name="staffPassword"
                      [(ngModel)]="userForm.password"
                      placeholder="Minimum 8 characters"
                      class="w-full px-3 py-2 rounded-lg border border-slate-300 text-xs outline-none focus:ring-2 focus:ring-capi-blue"
                    />
                  </div>
                </div>

                @if (editingUserId) {
                  <div class="p-3 rounded-xl bg-slate-50 border border-slate-200 flex flex-col sm:flex-row sm:items-center gap-3">
                    <label class="inline-flex items-center gap-2 text-xs font-semibold text-slate-800 cursor-pointer">
                      <input type="checkbox" name="staffIsLocked" [(ngModel)]="userForm.isLocked" class="rounded border-slate-300" />
                      Account Locked
                    </label>
                    @if (userForm.isLocked) {
                      <input
                        type="text"
                        name="staffLockedReason"
                        [(ngModel)]="userForm.lockedReason"
                        placeholder="Lock reason..."
                        class="flex-1 px-3 py-1.5 rounded-lg border border-slate-300 text-xs bg-white outline-none focus:ring-2 focus:ring-capi-blue"
                      />
                    }
                  </div>
                }

                <div class="flex justify-end gap-2 pt-2">
                  @if (editingUserId) {
                    <button type="button" (click)="cancelUserEdit()" class="px-4 py-2 rounded-lg border border-slate-300 bg-white text-slate-700 text-xs font-semibold">
                      Cancel Edit
                    </button>
                  }
                  <button
                    id="save-staff-btn"
                    type="submit"
                    [disabled]="savingUser"
                    class="px-5 py-2 rounded-lg bg-capi-blue hover:bg-capi-dark text-white text-xs font-semibold shadow-sm transition disabled:opacity-50"
                  >
                    {{ savingUser ? 'Saving...' : (editingUserId ? 'Update Staff Member' : 'Add Staff Member') }}
                  </button>
                </div>
              </form>
            </div>
          </div>

          <!-- Panel 3: Portal Users Table -->
          <div class="bg-white rounded-2xl shadow-sm border border-slate-200 overflow-hidden">
            <div class="px-6 py-4 border-b border-slate-100 flex items-center justify-between">
              <div>
                <h2 class="text-lg font-bold text-slate-900">Portal Users, Staff Roles &amp; Fraud Lock Status</h2>
                <p class="text-xs text-slate-500">Review active staff members, update assigned roles/domains, or unlock fraud-flagged accounts.</p>
              </div>
              <button type="button" (click)="loadAll()" class="text-xs text-capi-blue font-semibold hover:underline">
                Refresh Directory
              </button>
            </div>
            <div class="overflow-x-auto">
              <table class="w-full text-sm text-left">
                <thead class="bg-slate-50 text-xs uppercase text-slate-500 border-b border-slate-200">
                  <tr>
                    <th class="px-6 py-3">Full Name &amp; Contact</th>
                    <th class="px-6 py-3">Email</th>
                    <th class="px-6 py-3">Role, Title &amp; Domain</th>
                    <th class="px-6 py-3">Security State</th>
                    <th class="px-6 py-3 text-right">Actions</th>
                  </tr>
                </thead>
                <tbody class="divide-y divide-slate-100">
                  @for (u of users; track u.id) {
                    <tr class="hover:bg-slate-50/70">
                      <td class="px-6 py-4">
                        <div class="font-semibold text-slate-900">{{ u.fullName }}</div>
                        @if (u.phoneNumber) {
                          <div class="text-xs font-mono text-slate-500 mt-0.5">{{ u.phoneNumber }}</div>
                        }
                      </td>
                      <td class="px-6 py-4 text-slate-600 font-mono text-xs">{{ u.email }}</td>
                      <td class="px-6 py-4 text-xs">
                        <div class="flex flex-wrap items-center gap-1.5">
                          <span class="px-2 py-0.5 rounded-full font-bold bg-indigo-50 text-indigo-800 border border-indigo-200">{{ u.role }}</span>
                          <span class="font-mono text-slate-600">{{ u.userDomain }}</span>
                        </div>
                        @if (u.partnerInstitution) {
                          <div class="text-xs text-slate-600 mt-1 font-medium">{{ u.partnerInstitution }}</div>
                        }
                      </td>
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
                      <td class="px-6 py-4 text-right whitespace-nowrap">
                        <div class="inline-flex items-center gap-2">
                          <button
                            type="button"
                            (click)="startEditUser(u)"
                            class="px-3 py-1.5 rounded-lg border border-slate-300 bg-white hover:bg-slate-50 text-slate-700 text-xs font-semibold transition"
                          >
                            Edit / Update Role
                          </button>
                          @if (u.isLocked) {
                            <button
                              type="button"
                              (click)="unlockAccount(u)"
                              class="px-3 py-1.5 rounded-lg bg-emerald-600 hover:bg-emerald-700 text-white text-xs font-semibold"
                            >
                              Unlock Account
                            </button>
                          }
                        </div>
                      </td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
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
  roles: StaffRoleDefinition[] = [];
  selectedStaffByDispute: Record<string, string> = {};
  disputeSearch = '';
  auditSearch = '';
  auditCategoryFilter: 'ALL' | 'SUSPICIOUS' | 'CALL_CENTRE_KYC' = 'ALL';
  auditFilter = '';
  bannerMessage = '';
  errorMessage = '';

  // Role management state
  editingRoleCode: string | null = null;
  savingRole = false;
  roleForm: {
    roleCode: string;
    displayName: string;
    baseRole: UserRole;
    defaultDomain: UserDomain;
    description: string;
  } = {
    roleCode: '',
    displayName: '',
    baseRole: 'STAFF',
    defaultDomain: 'INTERNAL_CAPI',
    description: ''
  };

  // Staff/user management state
  editingUserId: string | null = null;
  savingUser = false;
  selectedRoleTemplate = '';
  userForm: {
    fullName: string;
    email: string;
    phoneNumber: string;
    role: UserRole;
    userDomain: UserDomain;
    partnerInstitution: string;
    password: string;
    isLocked: boolean;
    lockedReason: string;
  } = {
    fullName: '',
    email: '',
    phoneNumber: '',
    role: 'STAFF',
    userDomain: 'INTERNAL_CAPI',
    partnerInstitution: 'Dispute Investigator',
    password: '',
    isLocked: false,
    lockedReason: ''
  };

  constructor(private api: PortalApiService) {}

  ngOnInit(): void {
    this.loadAll();
  }

  loadAll(): void {
    this.api.getAdminUsers().subscribe((u) => {
      this.users = u;
      this.syncDefaultAssignees();
    });
    this.loadRoles();
    this.api.getDisputes().subscribe((d) => {
      this.disputes = d;
      this.syncDefaultAssignees();
    });
    this.loadAuditLogs();
    this.loadProductivity();
  }

  loadRoles(): void {
    this.api.getAdminRoles().subscribe((list) => (this.roles = list));
  }

  staffUsers(): UserProfile[] {
    return this.users.filter(
      (u) => (u.role === 'STAFF' || u.role === 'ADMIN') && u.userDomain === 'INTERNAL_CAPI' && !u.isLocked
    );
  }

  filteredDisputes(): DisputeSummary[] {
    const q = this.disputeSearch.trim().toLowerCase();
    if (!q) {
      return this.disputes;
    }
    return this.disputes.filter(
      (d) =>
        d.disputeReference.toLowerCase().includes(q) ||
        (d.originalDisputeReference?.toLowerCase().includes(q) ?? false) ||
        d.customer.email.toLowerCase().includes(q) ||
        (d.customer.saIdNumber?.toLowerCase().includes(q) ?? false) ||
        d.customer.fullName.toLowerCase().includes(q) ||
        (d.assignedUserName?.toLowerCase().includes(q) ?? false)
    );
  }

  private syncDefaultAssignees(): void {
    for (const d of this.disputes) {
      if (d.assignedUserId) {
        this.selectedStaffByDispute[d.id] = d.assignedUserId;
      } else if (!this.selectedStaffByDispute[d.id]) {
        this.selectedStaffByDispute[d.id] = '';
      }
    }
  }

  assignToStaff(d: DisputeSummary): void {
    const staffId = this.selectedStaffByDispute[d.id];
    if (!staffId) {
      this.errorMessage = `Select a staff member to assign dispute ${d.disputeReference}.`;
      return;
    }
    this.errorMessage = '';
    this.bannerMessage = '';
    this.api.adminAssignDispute(d.id, staffId, 'INTERNAL_CAPI', 'IN_REVIEW').subscribe({
      next: (updated) => {
        this.bannerMessage = `Dispute ${updated.disputeReference} assigned to ${updated.assignedUserName} (Status: ${updated.status}).`;
        this.loadAll();
      },
      error: (err) => {
        this.errorMessage = err.error?.message || 'Failed to assign dispute.';
      }
    });
  }

  loadAuditLogs(): void {
    this.api.getAuditLogs(this.auditFilter || undefined, 0, 100).subscribe((logs) => (this.auditLogs = logs));
  }

  setAuditCategory(category: 'ALL' | 'SUSPICIOUS' | 'CALL_CENTRE_KYC'): void {
    this.auditCategoryFilter = category;
  }

  filteredAuditLogs(): AuditLogEntry[] {
    const kycEvents = new Set([
      'CALLER_KYC_LOOKUP',
      'CALLER_KYC_LOOKUP_FAILED',
      'CALLER_KYC_VERIFIED',
      'CALLER_KYC_VERIFICATION_FAILED'
    ]);
    const suspiciousEvents = new Set([
      'CALLER_KYC_VERIFICATION_FAILED',
      'CALLER_KYC_LOOKUP_FAILED',
      'UNAUTHORIZED_ACCESS_ATTEMPT',
      'ACCOUNT_LOCKED_FRAUD_REPORTED',
      'LOGIN_FAILED_BAD_CREDENTIALS',
      'LOGIN_FAILED_UNKNOWN_USER',
      'LOGIN_BLOCKED_LOCKED',
      'LOGIN_RATE_LIMITED_IP',
      'OTP_VERIFICATION_FAILED'
    ]);

    let result = this.auditLogs;
    if (this.auditCategoryFilter === 'SUSPICIOUS') {
      result = result.filter(
        (l) =>
          suspiciousEvents.has(l.eventType) ||
          l.severity === 'HIGH' ||
          l.severity === 'CRITICAL' ||
          l.severity === 'WARN'
      );
    } else if (this.auditCategoryFilter === 'CALL_CENTRE_KYC') {
      result = result.filter((l) => kycEvents.has(l.eventType));
    }

    const q = this.auditSearch.trim().toLowerCase();
    if (!q) {
      return result;
    }
    return result.filter(
      (l) =>
        l.eventType.toLowerCase().includes(q) ||
        l.severity.toLowerCase().includes(q) ||
        (l.actorEmail?.toLowerCase().includes(q) ?? false) ||
        l.entityName.toLowerCase().includes(q) ||
        l.entityId.toLowerCase().includes(q) ||
        l.ipAddress.toLowerCase().includes(q) ||
        (l.metadata?.toLowerCase().includes(q) ?? false)
    );
  }

  auditRowClass(log: AuditLogEntry): string {
    if (
      log.eventType === 'CALLER_KYC_VERIFICATION_FAILED' ||
      log.eventType === 'UNAUTHORIZED_ACCESS_ATTEMPT' ||
      log.eventType === 'ACCOUNT_LOCKED_FRAUD_REPORTED' ||
      log.severity === 'HIGH' ||
      log.severity === 'CRITICAL'
    ) {
      return 'bg-red-50/75 hover:bg-red-100/60';
    }
    if (log.eventType === 'CALLER_KYC_LOOKUP_FAILED' || log.severity === 'WARN') {
      return 'bg-amber-50/75 hover:bg-amber-100/60';
    }
    if (log.eventType === 'CALLER_KYC_VERIFIED') {
      return 'bg-emerald-50/50 hover:bg-emerald-50';
    }
    return 'hover:bg-slate-50';
  }

  loadProductivity(): void {
    this.api.getProductivityReport().subscribe((rep) => (this.productivity = rep));
  }

  switchTab(tab: 'DISPUTES' | 'AUDIT' | 'PRODUCTIVITY' | 'USERS'): void {
    this.activeTab = tab;
    this.bannerMessage = '';
    this.errorMessage = '';
    if (tab === 'AUDIT') this.loadAuditLogs();
    if (tab === 'PRODUCTIVITY') this.loadProductivity();
    if (tab === 'USERS') {
      this.loadRoles();
      this.api.getAdminUsers().subscribe((u) => (this.users = u));
    }
  }

  onRoleFormBaseRoleChange(): void {
    if (this.roleForm.baseRole === 'STAFF' || this.roleForm.baseRole === 'ADMIN') {
      this.roleForm.defaultDomain = 'INTERNAL_CAPI';
    } else if (
      this.roleForm.baseRole === 'AFFILIATE' &&
      this.roleForm.defaultDomain !== 'PARTNER_BANKSERV' &&
      this.roleForm.defaultDomain !== 'PARTNER_MERCHANT'
    ) {
      this.roleForm.defaultDomain = 'PARTNER_BANKSERV';
    }
  }

  startEditRole(r: StaffRoleDefinition): void {
    this.editingRoleCode = r.roleCode;
    this.bannerMessage = '';
    this.errorMessage = '';
    this.roleForm = {
      roleCode: r.roleCode,
      displayName: r.displayName,
      baseRole: r.baseRole,
      defaultDomain: r.defaultDomain,
      description: r.description || ''
    };
  }

  cancelRoleEdit(): void {
    this.editingRoleCode = null;
    this.roleForm = {
      roleCode: '',
      displayName: '',
      baseRole: 'STAFF',
      defaultDomain: 'INTERNAL_CAPI',
      description: ''
    };
  }

  saveRole(): void {
    if (!this.roleForm.displayName.trim()) {
      this.errorMessage = 'Role display name is required.';
      return;
    }
    this.savingRole = true;
    this.bannerMessage = '';
    this.errorMessage = '';
    const payload = {
      roleCode: this.roleForm.roleCode.trim() || undefined,
      displayName: this.roleForm.displayName.trim(),
      baseRole: this.roleForm.baseRole,
      defaultDomain: this.roleForm.defaultDomain,
      description: this.roleForm.description.trim() || undefined
    };
    const request$ = this.editingRoleCode
      ? this.api.updateAdminRole(this.editingRoleCode, payload)
      : this.api.createAdminRole(payload);
    request$.subscribe({
      next: (saved) => {
        this.savingRole = false;
        this.bannerMessage = this.editingRoleCode
          ? `Updated role ${saved.displayName} (${saved.roleCode}).`
          : `Added new role ${saved.displayName} (${saved.roleCode}).`;
        this.cancelRoleEdit();
        this.loadAll();
      },
      error: (err) => {
        this.savingRole = false;
        this.errorMessage = err.error?.message || 'Failed to save role.';
      }
    });
  }

  onRoleTemplateSelected(): void {
    const tpl = this.roles.find((r) => r.roleCode === this.selectedRoleTemplate);
    if (!tpl) {
      return;
    }
    this.userForm.role = tpl.baseRole;
    this.userForm.userDomain = tpl.defaultDomain;
    this.userForm.partnerInstitution = tpl.displayName;
  }

  onStaffRoleChange(): void {
    if (this.userForm.role === 'STAFF' || this.userForm.role === 'ADMIN') {
      this.userForm.userDomain = 'INTERNAL_CAPI';
    } else if (
      this.userForm.role === 'AFFILIATE' &&
      this.userForm.userDomain !== 'PARTNER_BANKSERV' &&
      this.userForm.userDomain !== 'PARTNER_MERCHANT'
    ) {
      this.userForm.userDomain = 'PARTNER_BANKSERV';
    } else if (this.userForm.role === 'CUSTOMER') {
      this.userForm.userDomain = 'CUSTOMER';
    }
  }

  startEditUser(u: UserProfile): void {
    this.editingUserId = u.id;
    this.selectedRoleTemplate = '';
    this.bannerMessage = '';
    this.errorMessage = '';
    this.userForm = {
      fullName: u.fullName,
      email: u.email,
      phoneNumber: u.phoneNumber || '',
      role: u.role,
      userDomain: u.userDomain,
      partnerInstitution: u.partnerInstitution || '',
      password: '',
      isLocked: u.isLocked,
      lockedReason: u.lockedReason || ''
    };
    document.getElementById('admin-staff-form-panel')?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }

  cancelUserEdit(): void {
    this.editingUserId = null;
    this.selectedRoleTemplate = '';
    this.userForm = {
      fullName: '',
      email: '',
      phoneNumber: '',
      role: 'STAFF',
      userDomain: 'INTERNAL_CAPI',
      partnerInstitution: 'Dispute Investigator',
      password: '',
      isLocked: false,
      lockedReason: ''
    };
  }

  saveUser(): void {
    if (!this.userForm.fullName.trim() || !this.userForm.email.trim()) {
      this.errorMessage = 'Full name and email are required.';
      return;
    }
    this.savingUser = true;
    this.bannerMessage = '';
    this.errorMessage = '';

    if (this.editingUserId) {
      this.api
        .updateAdminUser(this.editingUserId, {
          fullName: this.userForm.fullName.trim(),
          email: this.userForm.email.trim(),
          phoneNumber: this.userForm.phoneNumber.trim() || undefined,
          role: this.userForm.role,
          userDomain: this.userForm.userDomain,
          partnerInstitution: this.userForm.partnerInstitution.trim() || undefined,
          isLocked: this.userForm.isLocked,
          lockedReason: this.userForm.lockedReason.trim() || undefined,
          newPassword: this.userForm.password.trim() || undefined
        })
        .subscribe({
          next: (updated) => {
            this.savingUser = false;
            this.bannerMessage = `Updated user ${updated.fullName} (${updated.email}) — ${updated.role} / ${updated.partnerInstitution || updated.userDomain}.`;
            this.cancelUserEdit();
            this.loadAll();
          },
          error: (err) => {
            this.savingUser = false;
            this.errorMessage = err.error?.message || 'Failed to update staff account.';
          }
        });
    } else {
      this.api
        .createAdminUser({
          fullName: this.userForm.fullName.trim(),
          email: this.userForm.email.trim(),
          phoneNumber: this.userForm.phoneNumber.trim() || undefined,
          role: this.userForm.role,
          userDomain: this.userForm.userDomain,
          partnerInstitution: this.userForm.partnerInstitution.trim() || undefined,
          password: this.userForm.password.trim() || undefined
        })
        .subscribe({
          next: (created) => {
            this.savingUser = false;
            this.bannerMessage = `Added new user ${created.fullName} (${created.email}) as ${created.role} (${created.partnerInstitution || created.userDomain}).`;
            this.cancelUserEdit();
            this.loadAll();
          },
          error: (err) => {
            this.savingUser = false;
            this.errorMessage = err.error?.message || 'Failed to create staff account.';
          }
        });
    }
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

