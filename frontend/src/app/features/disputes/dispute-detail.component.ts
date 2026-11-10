import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { PortalApiService } from '../../core/services/portal-api.service';
import { AuthService } from '../../core/services/auth.service';
import {
  DisputeAttachment,
  DisputeDetail,
  DisputeStatus
} from '../../core/models/portal.models';

@Component({
  selector: 'app-dispute-detail',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink],
  template: `
    <div class="max-w-6xl mx-auto px-4 sm:px-6 py-8">
      <!-- 403 FORBIDDEN IDOR BOUNDARY ALERT -->
      @if (forbiddenError) {
        <div id="forbidden-access-banner" class="bg-white rounded-2xl shadow-xl border-2 border-red-600 overflow-hidden">
          <div class="bg-red-700 text-white px-7 py-5 flex items-center justify-between">
            <div>
              <span class="text-xs font-bold uppercase tracking-wider text-red-200">Security Boundary Violation Blocked</span>
              <h1 class="text-2xl font-bold mt-0.5">403 Forbidden &mdash; Access Denied</h1>
            </div>
            <span class="px-3 py-1 rounded-full bg-red-900 text-red-100 text-xs font-mono font-bold">HTTP 403</span>
          </div>
          <div class="p-7 space-y-4">
            <p class="text-base text-slate-800 font-semibold">
              {{ errorMessage }}
            </p>
            <div class="p-4 rounded-xl bg-red-50 border border-red-200 text-xs font-mono text-red-900 space-y-1">
              <div><strong>Audit Event Recorded:</strong> UNAUTHORIZED_ACCESS_ATTEMPT</div>
              <div><strong>Severity:</strong> HIGH</div>
              <div><strong>Actor:</strong> {{ auth.currentUser()?.email }} ({{ auth.currentUser()?.id }})</div>
            </div>
            <div class="pt-2">
              <a [routerLink]="[auth.getHomeRouteForRole(auth.currentUser()?.role)]"
                 class="inline-block px-5 py-2.5 rounded-lg bg-capitec-blue text-white text-sm font-semibold">
                Return to My Dashboard
              </a>
            </div>
          </div>
        </div>
      } @else if (dispute) {
        <div class="space-y-6">
          <!-- Top Breadcrumb & Status Header -->
          <div class="bg-white rounded-2xl shadow-sm border border-slate-200 p-6">
            <div class="flex flex-col md:flex-row md:items-center justify-between gap-4">
              <div>
                <div class="flex items-center gap-2 text-xs text-slate-500 mb-1">
                  <a [routerLink]="[auth.getHomeRouteForRole(auth.currentUser()?.role)]" class="text-capitec-blue font-semibold hover:underline">
                    &larr; Back to Dashboard
                  </a>
                  <span>&bull;</span>
                  <span class="font-mono">{{ dispute.id }}</span>
                </div>
                <div class="flex flex-wrap items-center gap-3">
                  <h1 class="text-2xl font-bold text-slate-900 font-mono">{{ dispute.disputeReference }}</h1>
                  <span id="dispute-status-badge" [class]="statusBadge(dispute.status)">{{ dispute.status }}</span>
                  @if (dispute.terminal) {
                    <span class="px-2.5 py-1 rounded-full text-xs font-bold bg-slate-800 text-white">
                      TERMINAL STATE (IMMUTABLE)
                    </span>
                  }
                </div>
                <p class="text-sm text-slate-600 mt-1">
                  <strong>{{ dispute.merchantName }}</strong> &bull; Rail: <strong>{{ dispute.paymentRail }}</strong> &bull; Category: <strong>{{ dispute.category }}</strong>
                </p>
              </div>

              <div class="text-left md:text-right">
                <div class="text-xs text-slate-500">Disputed Amount</div>
                <div class="text-2xl font-extrabold text-slate-900">R {{ dispute.amount | number:'1.2-2' }}</div>
                <div class="text-xs text-slate-500 mt-0.5">Domain: <strong>{{ dispute.assignedDomain }}</strong></div>
              </div>
            </div>

            <!-- LINEAGE BANNER FOR RE-OPENED LINKED DISPUTES -->
            @if (dispute.originalDisputeReference) {
              <div id="linked-dispute-banner" class="mt-5 p-4 rounded-xl bg-indigo-50 border-2 border-indigo-400 text-indigo-950">
                <div class="flex flex-col sm:flex-row sm:items-center justify-between gap-2">
                  <div class="font-bold text-sm">
                    Linked to previous rejected dispute:
                    <a [routerLink]="['/disputes', dispute.originalDisputeId]" class="underline text-indigo-800 font-mono">
                      {{ dispute.originalDisputeReference }}
                    </a>
                  </div>
                  <span class="px-2.5 py-0.5 rounded bg-indigo-200 text-indigo-900 text-xs font-bold">
                    Lineage Verified
                  </span>
                </div>

                @if (dispute.originalDisputeLineage; as prev) {
                  <div class="mt-3 pt-3 border-t border-indigo-200 text-xs text-indigo-900 space-y-1">
                    <div><strong>Prior Status:</strong> {{ prev.status }} &bull; <strong>Original Reason:</strong> {{ prev.reason }}</div>
                    @if (prev.resolutionSummary) {
                      <div><strong>Prior Rejection Summary:</strong> {{ prev.resolutionSummary }}</div>
                    }
                    @if (prev.previousNotes.length > 0) {
                      <div class="mt-1">
                        <strong>Prior Case Notes ({{ prev.previousNotes.length }}):</strong>
                        <ul class="list-disc list-inside mt-0.5">
                          @for (pn of prev.previousNotes; track pn.id) {
                            <li>[{{ pn.authorRole }}] {{ pn.message }}</li>
                          }
                        </ul>
                      </div>
                    }
                  </div>
                }
              </div>
            }

            <!-- REJECTED -> CUSTOMER RE-OPEN / FILE LINKED DISPUTE PANEL -->
            @if (dispute.status === 'REJECTED' && auth.currentUser()?.role === 'CUSTOMER') {
              <div class="mt-5 p-5 rounded-xl bg-red-50 border-2 border-red-300">
                <div class="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
                  <div>
                    <h3 class="text-base font-bold text-red-900">This Dispute Was Rejected</h3>
                    <p class="text-xs text-red-800 mt-0.5">
                      {{ dispute.resolutionSummary || 'Your dispute was rejected. If you have new supporting evidence or an affidavit, you may file a linked dispute.' }}
                    </p>
                  </div>
                  <button
                    id="reopen-dispute-btn"
                    type="button"
                    (click)="showReopenForm = !showReopenForm"
                    class="px-4 py-2.5 rounded-lg bg-capitec-red hover:bg-red-700 text-white text-xs font-bold shadow transition whitespace-nowrap"
                  >
                    Re-open / File Linked Dispute
                  </button>
                </div>

                @if (showReopenForm) {
                  <form (ngSubmit)="submitReopenLinkedDispute()" class="mt-4 pt-4 border-t border-red-200 space-y-3">
                    <label for="reopen-reason-input" class="block text-xs font-bold uppercase text-red-900">
                      Updated Statement / New Evidence Explanation (Links to {{ dispute.disputeReference }})
                    </label>
                    <textarea
                      id="reopen-reason-input"
                      name="reopenReason"
                      rows="3"
                      required
                      [(ngModel)]="reopenReason"
                      class="w-full px-3.5 py-2 rounded-lg border border-red-300 text-sm bg-white"
                    ></textarea>
                    <div class="flex justify-end gap-2">
                      <button
                        id="confirm-reopen-submit-btn"
                        type="submit"
                        [disabled]="busy"
                        class="px-5 py-2 rounded-lg bg-capitec-blue hover:bg-capitec-dark text-white text-xs font-bold transition"
                      >
                        Submit Linked Dispute
                      </button>
                    </div>
                  </form>
                }
              </div>
            }
          </div>

          @if (actionFeedback) {
            <div class="p-4 rounded-xl bg-emerald-50 border border-emerald-300 text-emerald-900 text-sm font-medium">
              {{ actionFeedback }}
            </div>
          }

          @if (actionError) {
            <div class="p-4 rounded-xl bg-red-50 border border-red-300 text-red-800 text-sm font-medium">
              {{ actionError }}
            </div>
          }

          <div class="grid grid-cols-1 lg:grid-cols-3 gap-6">
            <!-- Left Column: Statement, Notes & Evidence -->
            <div class="lg:col-span-2 space-y-6">
              <!-- Statement of Dispute -->
              <div class="bg-white rounded-2xl shadow-sm border border-slate-200 p-6">
                <h2 class="text-base font-bold text-slate-900 mb-2">Customer Dispute Statement</h2>
                <p class="text-sm text-slate-700 leading-relaxed bg-slate-50 p-4 rounded-xl border border-slate-100">
                  {{ dispute.reason }}
                </p>
                @if (dispute.resolutionSummary) {
                  <div class="mt-4 p-3.5 rounded-xl bg-slate-100 border border-slate-200 text-xs text-slate-800">
                    <strong>Resolution / Decision Summary:</strong> {{ dispute.resolutionSummary }}
                  </div>
                }
              </div>

              <!-- Investigation Notes & Customer Communication -->
              <div class="bg-white rounded-2xl shadow-sm border border-slate-200 p-6 space-y-4">
                <div class="flex items-center justify-between">
                  <h2 class="text-base font-bold text-slate-900">Case Notes &amp; Evidence Requests ({{ dispute.notes.length }})</h2>
                  @if (dispute.terminal) {
                    <span class="text-xs font-semibold text-slate-500">Commenting disabled (Terminal State)</span>
                  }
                </div>

                @if (dispute.notes.length === 0) {
                  <p class="text-xs text-slate-500 italic">No investigation notes recorded yet.</p>
                } @else {
                  <div class="space-y-3">
                    @for (n of dispute.notes; track n.id) {
                      <div [class]="n.requiresCustomerReply ? 'p-4 rounded-xl bg-amber-50 border border-amber-300' : (n.internalOnly ? 'p-4 rounded-xl bg-slate-100 border border-slate-300' : 'p-4 rounded-xl bg-slate-50 border border-slate-200')">
                        <div class="flex items-center justify-between text-xs text-slate-500 mb-1">
                          <span class="font-bold text-slate-800">
                            {{ n.authorName }} ({{ n.authorRole }})
                          </span>
                          <span>{{ n.createdAt | date:'medium' }}</span>
                        </div>
                        <p class="text-sm text-slate-800">{{ n.message }}</p>
                        <div class="mt-2 flex gap-2">
                          @if (n.requiresCustomerReply) {
                            <span class="px-2 py-0.5 rounded bg-amber-200 text-amber-900 text-[11px] font-bold">
                              Customer Action / Reply Requested
                            </span>
                          }
                          @if (n.internalOnly) {
                            <span class="px-2 py-0.5 rounded bg-slate-700 text-white text-[11px] font-bold">
                              Internal Capitec Note
                            </span>
                          }
                        </div>
                      </div>
                    }
                  </div>
                }

                @if (!dispute.terminal) {
                  <form (ngSubmit)="submitNote()" class="pt-4 border-t border-slate-100 space-y-3">
                    <label for="note-message-input" class="block text-xs font-bold uppercase text-slate-600">
                      Add Investigation / Case Note
                    </label>
                    <textarea
                      id="note-message-input"
                      name="noteMessage"
                      rows="3"
                      required
                      [(ngModel)]="noteMessage"
                      placeholder="Write a note or request supporting documentation..."
                      class="w-full px-3.5 py-2.5 rounded-lg border border-slate-300 text-sm focus:ring-2 focus:ring-capitec-blue outline-none"
                    ></textarea>

                    @if (isStaffOrAdmin()) {
                      <div class="flex flex-wrap items-center gap-5 text-xs text-slate-700">
                        <label class="inline-flex items-center gap-2 cursor-pointer font-semibold">
                          <input
                            id="requires-customer-reply-checkbox"
                            type="checkbox"
                            name="requiresCustomerReply"
                            [(ngModel)]="requiresCustomerReply"
                            class="rounded text-capitec-blue"
                          />
                          <span>Requires Customer Reply (Sets status to PENDING_CUSTOMER_ACTION)</span>
                        </label>

                        <label class="inline-flex items-center gap-2 cursor-pointer">
                          <input
                            id="internal-only-checkbox"
                            type="checkbox"
                            name="internalOnly"
                            [(ngModel)]="internalOnly"
                            class="rounded text-capitec-blue"
                          />
                          <span>Internal Capitec Note Only</span>
                        </label>
                      </div>
                    }

                    <div class="flex justify-end">
                      <button
                        id="add-note-submit-btn"
                        type="submit"
                        [disabled]="busy"
                        class="px-4 py-2 rounded-lg bg-capitec-blue hover:bg-capitec-dark text-white text-xs font-semibold transition"
                      >
                        Post Note
                      </button>
                    </div>
                  </form>
                }
              </div>

              <!-- Supporting Attachments & SHA-256 Checksums -->
              <div class="bg-white rounded-2xl shadow-sm border border-slate-200 p-6 space-y-4">
                <div class="flex items-center justify-between">
                  <h2 class="text-base font-bold text-slate-900">
                    Evidence Attachments ({{ dispute.attachments.length }} / 5)
                  </h2>
                  <span class="text-xs text-slate-500">SHA-256 Verified</span>
                </div>

                @if (dispute.attachments.length === 0) {
                  <p class="text-xs text-slate-500 italic">No documents uploaded yet.</p>
                } @else {
                  <div class="divide-y divide-slate-100 border border-slate-200 rounded-xl overflow-hidden">
                    @for (att of dispute.attachments; track att.id) {
                      <div class="p-3.5 flex flex-col sm:flex-row sm:items-center justify-between gap-2 bg-slate-50/50">
                        <div>
                          <div class="text-sm font-semibold text-slate-900">
                            {{ att.fileName }}
                            <span class="text-xs font-normal text-slate-500">({{ (att.fileSizeBytes / 1024) | number:'1.0-1' }} KB &bull; {{ att.mimeType }})</span>
                          </div>
                          <div class="text-[11px] font-mono text-slate-500 break-all">
                            SHA-256: {{ att.checksumSha256 }}
                          </div>
                        </div>
                        <button
                          type="button"
                          (click)="downloadFile(att)"
                          class="px-3 py-1.5 rounded-lg border border-slate-300 bg-white hover:bg-slate-100 text-xs font-semibold text-slate-700 self-start sm:self-center"
                        >
                          Download
                        </button>
                      </div>
                    }
                  </div>
                }

                @if (!dispute.terminal && dispute.attachments.length < 5) {
                  <div class="pt-3 border-t border-slate-100 flex flex-col sm:flex-row sm:items-center justify-between gap-3">
                    <input
                      id="detail-upload-input"
                      type="file"
                      multiple
                      accept=".pdf,.png,.jpg,.jpeg"
                      (change)="onUploadFilesSelected($event)"
                      class="text-xs text-slate-600"
                    />
                    <button
                      type="button"
                      [disabled]="additionalFiles.length === 0 || busy"
                      (click)="uploadMoreFiles()"
                      class="px-4 py-2 rounded-lg bg-slate-800 hover:bg-slate-900 text-white text-xs font-semibold disabled:opacity-40"
                    >
                      Upload Evidence ({{ additionalFiles.length }})
                    </button>
                  </div>
                }
              </div>
            </div>

            <!-- Right Column: Customer Identity (with PII Masking) & Investigator Controls -->
            <div class="space-y-6">
              <!-- Customer Identity Card -->
              <div id="customer-pii-card" class="bg-white rounded-2xl shadow-sm border border-slate-200 p-6 space-y-3">
                <div class="flex items-center justify-between">
                  <h2 class="text-base font-bold text-slate-900">Customer Profile</h2>
                  @if (dispute.customer.piiMasked) {
                    <span id="pii-masked-badge" class="px-2.5 py-0.5 rounded-full bg-purple-100 text-purple-900 text-[11px] font-bold">
                      PII MASKED
                    </span>
                  }
                </div>

                <div class="text-sm space-y-2 pt-2 border-t border-slate-100">
                  <div class="flex justify-between">
                    <span class="text-slate-500">Name:</span>
                    <span class="font-semibold text-slate-900">{{ dispute.customer.fullName }}</span>
                  </div>
                  <div class="flex justify-between">
                    <span class="text-slate-500">SA ID:</span>
                    <span id="customer-sa-id" class="font-mono font-bold text-slate-900">ID: {{ dispute.customer.saIdNumber }}</span>
                  </div>
                  <div class="flex justify-between">
                    <span class="text-slate-500">Phone:</span>
                    <span class="font-mono text-slate-800">{{ dispute.customer.phoneNumber }}</span>
                  </div>
                  <div class="flex justify-between">
                    <span class="text-slate-500">Account:</span>
                    <span class="font-mono text-slate-800">{{ dispute.customer.accountNumber }}</span>
                  </div>
                  <div class="flex justify-between">
                    <span class="text-slate-500">Email:</span>
                    <span class="font-mono text-xs text-slate-700">{{ dispute.customer.email }}</span>
                  </div>
                </div>
              </div>

              <!-- Investigator / Supervisor / Affiliate Workflow Controls -->
              @if (!dispute.terminal && auth.currentUser()?.role !== 'CUSTOMER') {
                <div class="bg-white rounded-2xl shadow-sm border border-slate-200 p-6 space-y-4">
                  <h2 class="text-base font-bold text-slate-900">Investigation Actions</h2>

                  @if (isStaffOrAdmin()) {
                    <button
                      id="claim-dispute-btn"
                      type="button"
                      [disabled]="busy"
                      (click)="claimCase()"
                      class="w-full py-2.5 px-4 rounded-lg bg-emerald-600 hover:bg-emerald-700 text-white text-xs font-bold shadow-sm transition"
                    >
                      Claim Case (Start IN_REVIEW Work Timer)
                    </button>

                    <button
                      id="escalate-supervisor-btn"
                      type="button"
                      [disabled]="busy"
                      (click)="escalateToSupervisor()"
                      class="w-full py-2.5 px-4 rounded-lg bg-orange-600 hover:bg-orange-700 text-white text-xs font-bold shadow-sm transition"
                    >
                      Escalate to Supervisor
                    </button>

                    <div class="pt-3 border-t border-slate-100 space-y-2">
                      <label class="block text-xs font-bold uppercase text-slate-500">External Partner Delegation</label>
                      <div class="grid grid-cols-2 gap-2">
                        <button
                          id="delegate-bankserv-btn"
                          type="button"
                          (click)="delegate('PARTNER_BANKSERV')"
                          class="py-2 px-3 rounded-lg bg-purple-600 hover:bg-purple-700 text-white text-xs font-semibold transition"
                        >
                          Assign PARTNER_BANKSERV
                        </button>
                        <button
                          type="button"
                          (click)="delegate('PARTNER_MERCHANT')"
                          class="py-2 px-3 rounded-lg bg-slate-700 hover:bg-slate-800 text-white text-xs font-semibold transition"
                        >
                          Assign PARTNER_MERCHANT
                        </button>
                      </div>
                    </div>
                  }

                  <!-- Status Decision Controls -->
                  <div class="pt-3 border-t border-slate-100 space-y-3">
                    <label for="status-select" class="block text-xs font-bold uppercase text-slate-500">
                      Update Case Status / Final Decision
                    </label>
                    <select
                      id="status-select"
                      name="targetStatus"
                      [(ngModel)]="targetStatus"
                      class="w-full px-3 py-2 rounded-lg border border-slate-300 text-xs bg-white font-semibold"
                    >
                      <option value="IN_REVIEW">IN_REVIEW</option>
                      <option value="PENDING_CUSTOMER_ACTION">PENDING_CUSTOMER_ACTION</option>
                      <option value="PENDING_EXTERNAL_REVIEW">PENDING_EXTERNAL_REVIEW</option>
                      <option value="RESOLVED">RESOLVED (Terminal)</option>
                      <option value="REFUNDED">REFUNDED (Terminal)</option>
                      <option value="REJECTED">REJECTED (Terminal)</option>
                    </select>

                    <textarea
                      id="resolution-summary-input"
                      name="resolutionSummary"
                      rows="2"
                      [(ngModel)]="resolutionSummary"
                      placeholder="Decision / resolution summary..."
                      class="w-full px-3 py-2 rounded-lg border border-slate-300 text-xs"
                    ></textarea>

                    <button
                      id="apply-status-btn"
                      type="button"
                      [disabled]="busy"
                      (click)="applyStatusChange()"
                      class="w-full py-2.5 px-4 rounded-lg bg-capitec-blue hover:bg-capitec-dark text-white text-xs font-bold transition"
                    >
                      Apply Status ({{ targetStatus }})
                    </button>
                  </div>
                </div>
              }

              <!-- Customer Escalation Option -->
              @if (!dispute.terminal && auth.currentUser()?.role === 'CUSTOMER' && dispute.status !== 'ESCALATED') {
                <div class="bg-white rounded-2xl shadow-sm border border-slate-200 p-6 space-y-3">
                  <h3 class="text-sm font-bold text-slate-900">Need Supervisor Review?</h3>
                  <p class="text-xs text-slate-500">You may escalate an active dispute to a Capitec Supervisor at any time.</p>
                  <button
                    type="button"
                    (click)="escalateToSupervisor()"
                    class="w-full py-2 px-4 rounded-lg border border-orange-500 text-orange-700 hover:bg-orange-50 text-xs font-bold transition"
                  >
                    Escalate Dispute
                  </button>
                </div>
              }
            </div>
          </div>
        </div>
      }
    </div>
  `
})
export class DisputeDetailComponent implements OnInit {
  dispute: DisputeDetail | null = null;
  forbiddenError = false;
  errorMessage = '';

  noteMessage = '';
  requiresCustomerReply = false;
  internalOnly = false;

  targetStatus: DisputeStatus = 'REJECTED';
  resolutionSummary = '';

  showReopenForm = false;
  reopenReason = 'Submitting police affidavit and updated sworn statement for linked review.';

  additionalFiles: File[] = [];
  busy = false;
  actionFeedback = '';
  actionError = '';

  constructor(
    private route: ActivatedRoute,
    private router: Router,
    private api: PortalApiService,
    public auth: AuthService
  ) {}

  ngOnInit(): void {
    this.route.paramMap.subscribe((params) => {
      const id = params.get('id');
      if (id) {
        this.loadDispute(id);
      }
    });
  }

  loadDispute(id: string): void {
    this.forbiddenError = false;
    this.errorMessage = '';
    this.actionError = '';

    this.api.getDisputeById(id).subscribe({
      next: (d) => {
        this.dispute = d;
      },
      error: (err: HttpErrorResponse) => {
        if (err.status === 403) {
          this.forbiddenError = true;
          this.errorMessage =
            err.error?.message ||
            '403 Forbidden: Access Denied. You are not authorized to access another customer or domain dispute.';
        } else {
          this.actionError = err.error?.message || 'Unable to load dispute details.';
        }
      }
    });
  }

  isStaffOrAdmin(): boolean {
    const role = this.auth.currentUser()?.role;
    return role === 'STAFF' || role === 'ADMIN';
  }

  claimCase(): void {
    if (!this.dispute) return;
    this.busy = true;
    this.api.claimDispute(this.dispute.id).subscribe({
      next: (d) => {
        this.dispute = d;
        this.busy = false;
        this.actionFeedback = `Case ${d.disputeReference} claimed (Status: IN_REVIEW). Productivity timer started.`;
      },
      error: (err) => {
        this.busy = false;
        this.actionError = err.error?.message || 'Could not claim dispute.';
      }
    });
  }

  submitNote(): void {
    if (!this.dispute || !this.noteMessage.trim()) return;
    this.busy = true;
    this.api
      .addNote(
        this.dispute.id,
        this.noteMessage.trim(),
        this.internalOnly,
        this.requiresCustomerReply
      )
      .subscribe({
        next: (d) => {
          this.dispute = d;
          this.noteMessage = '';
          this.requiresCustomerReply = false;
          this.internalOnly = false;
          this.busy = false;
          this.actionFeedback = `Note recorded. Current status: ${d.status}.`;
        },
        error: (err) => {
          this.busy = false;
          this.actionError = err.error?.message || 'Failed to add note.';
        }
      });
  }

  escalateToSupervisor(): void {
    if (!this.dispute) return;
    this.busy = true;
    this.api
      .escalateDispute(this.dispute.id, 'Escalated to Supervisor for high-priority review.')
      .subscribe({
        next: (d) => {
          this.dispute = d;
          this.busy = false;
          this.actionFeedback = `Dispute ${d.disputeReference} escalated to Supervisor (Status: ESCALATED).`;
        },
        error: (err) => {
          this.busy = false;
          this.actionError = err.error?.message || 'Escalation failed.';
        }
      });
  }

  delegate(domain: 'PARTNER_BANKSERV' | 'PARTNER_MERCHANT'): void {
    if (!this.dispute) return;
    this.busy = true;
    this.api
      .delegateDispute(
        this.dispute.id,
        domain,
        `Delegated to external partner domain ${domain} for settlement verification.`
      )
      .subscribe({
        next: (d) => {
          this.dispute = d;
          this.busy = false;
          this.actionFeedback = `Dispute delegated to ${domain} (Status: ${d.status}).`;
        },
        error: (err) => {
          this.busy = false;
          this.actionError = err.error?.message || 'Delegation failed.';
        }
      });
  }

  applyStatusChange(): void {
    if (!this.dispute) return;
    this.busy = true;
    const summary =
      this.resolutionSummary.trim() ||
      `Status updated to ${this.targetStatus} by ${this.auth.currentUser()?.email}`;
    this.api.updateStatus(this.dispute.id, this.targetStatus, summary).subscribe({
      next: (d) => {
        this.dispute = d;
        this.busy = false;
        this.actionFeedback = `Dispute status updated to ${d.status}.`;
      },
      error: (err) => {
        this.busy = false;
        this.actionError = err.error?.message || 'Status update failed.';
      }
    });
  }

  submitReopenLinkedDispute(): void {
    if (!this.dispute || !this.reopenReason.trim()) return;
    this.busy = true;
    this.api
      .reopenDispute(this.dispute.id, this.reopenReason.trim(), this.dispute.category)
      .subscribe({
        next: (newLinkedDispute) => {
          this.busy = false;
          this.showReopenForm = false;
          this.router.navigate(['/disputes', newLinkedDispute.id]);
        },
        error: (err) => {
          this.busy = false;
          this.actionError = err.error?.message || 'Failed to file linked dispute.';
        }
      });
  }

  onUploadFilesSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    if (!input.files) return;
    this.additionalFiles = Array.from(input.files);
  }

  uploadMoreFiles(): void {
    if (!this.dispute || this.additionalFiles.length === 0) return;
    this.busy = true;
    this.api.uploadAttachments(this.dispute.id, this.additionalFiles).subscribe({
      next: () => {
        this.additionalFiles = [];
        this.busy = false;
        this.actionFeedback = 'Additional evidence uploaded and SHA-256 verified.';
        if (this.dispute) {
          this.loadDispute(this.dispute.id);
        }
      },
      error: (err) => {
        this.busy = false;
        this.actionError = err.error?.message || 'File upload failed.';
      }
    });
  }

  downloadFile(att: DisputeAttachment): void {
    if (!this.dispute) return;
    this.api.downloadAttachment(this.dispute.id, att.id).subscribe((blob) => {
      const url = window.URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = att.fileName;
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      window.URL.revokeObjectURL(url);
    });
  }

  statusBadge(status: string): string {
    const base = 'px-3 py-1 rounded-full text-xs font-bold ';
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
}

