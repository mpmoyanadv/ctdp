import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { PortalApiService } from '../../core/services/portal-api.service';
import {
  BankTransaction,
  DisputeCategory,
  DisputeDetail,
  DisputeSummary
} from '../../core/models/portal.models';

@Component({
  selector: 'app-customer-dashboard',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink],
  template: `
    <div class="max-w-7xl mx-auto px-4 sm:px-6 py-8 space-y-8">
      <!-- Top Welcome Banner -->
      <div class="bg-white rounded-2xl shadow-sm border border-slate-200 p-6 flex flex-col md:flex-row md:items-center md:justify-between gap-4">
        <div>
          <h1 class="text-2xl font-bold text-slate-900">Global One Retail Transactions &amp; Disputes</h1>
          <p class="text-sm text-slate-500 mt-1">
            Select any unrecognized transaction below to log a formal dispute and attach supporting PDF, PNG, or JPG evidence.
          </p>
        </div>
        <div class="flex items-center gap-3">
          <div class="px-4 py-2 rounded-xl bg-blue-50 border border-blue-100 text-right">
            <div class="text-xs text-slate-500">Active Disputes</div>
            <div class="text-lg font-bold text-capitec-blue">{{ activeDisputesCount }}</div>
          </div>
          <div class="px-4 py-2 rounded-xl bg-emerald-50 border border-emerald-100 text-right">
            <div class="text-xs text-slate-500">Historic / Closed</div>
            <div class="text-lg font-bold text-emerald-700">{{ historicDisputesCount }}</div>
          </div>
        </div>
      </div>

      @if (createdDisputeBanner; as created) {
        <div id="dispute-created-banner" class="p-5 rounded-2xl bg-emerald-50 border-2 border-emerald-400 text-emerald-950 flex flex-col sm:flex-row sm:items-center justify-between gap-4">
          <div>
            <div class="text-xs font-bold uppercase tracking-wider text-emerald-700">Dispute Successfully Logged</div>
            <div class="text-lg font-bold mt-0.5">
              Reference: {{ created.disputeReference }} &mdash; Status: <span class="px-2 py-0.5 rounded bg-emerald-200 text-emerald-900 text-xs">{{ created.status }}</span>
            </div>
            <div class="text-xs text-emerald-800 mt-1 font-mono">Case UUID: {{ created.id }} &bull; Attachments: {{ created.attachments.length }}</div>
          </div>
          <a [routerLink]="['/disputes', created.id]"
             class="px-4 py-2.5 rounded-lg bg-emerald-700 hover:bg-emerald-800 text-white text-xs font-semibold transition whitespace-nowrap">
            Open Case {{ created.disputeReference }}
          </a>
        </div>
      }

      <!-- Log Dispute Form Modal/Drawer Panel -->
      @if (selectedTx) {
        <div id="log-dispute-panel" class="bg-white rounded-2xl shadow-lg border-2 border-capitec-blue p-6">
          <div class="flex items-start justify-between mb-4 pb-4 border-b border-slate-100">
            <div>
              <span class="text-xs font-bold uppercase tracking-wider text-capitec-blue">New Transaction Dispute</span>
              <h2 class="text-xl font-bold text-slate-900 mt-0.5">
                Disputing: {{ selectedTx.merchantName }} (R {{ selectedTx.amount | number:'1.2-2' }})
              </h2>
              <p class="text-xs text-slate-500">Ref: {{ selectedTx.transactionReference }} &bull; Rail: {{ selectedTx.paymentRail }} &bull; {{ selectedTx.location }}</p>
            </div>
            <button type="button" (click)="cancelDisputeForm()" class="text-slate-400 hover:text-slate-700 text-sm font-semibold">
              Cancel &times;
            </button>
          </div>

          @if (formError) {
            <div class="mb-4 p-3.5 rounded-lg bg-red-50 border border-red-200 text-red-700 text-sm">
              {{ formError }}
            </div>
          }

          <form (ngSubmit)="submitDispute()" class="space-y-4">
            <div class="grid grid-cols-1 md:grid-cols-2 gap-4">
              <div>
                <label for="dispute-category" class="block text-sm font-semibold text-slate-700 mb-1">Dispute Category</label>
                <select
                  id="dispute-category"
                  name="category"
                  [(ngModel)]="category"
                  class="w-full px-3.5 py-2.5 rounded-lg border border-slate-300 text-sm bg-white focus:ring-2 focus:ring-capitec-blue outline-none"
                >
                  <option value="UNAUTHORIZED_TRANSACTION">Unauthorized Transaction</option>
                  <option value="FRAUDULENT_CARD_USE">Fraudulent Card Use</option>
                  <option value="PAYSHAP_WRONG_RECIPIENT">PayShap Unrecognized / Wrong Recipient</option>
                  <option value="DUPLICATE_CHARGE">Duplicate Charge</option>
                  <option value="GOODS_OR_SERVICES_NOT_RECEIVED">Goods or Services Not Received</option>
                  <option value="ATM_DISPENSE_ERROR">ATM Dispense Error</option>
                  <option value="OTHER">Other</option>
                </select>
              </div>

              <div>
                <label class="block text-sm font-semibold text-slate-700 mb-1">Disputed Amount (ZAR)</label>
                <input
                  type="text"
                  readonly
                  [value]="'R ' + (selectedTx.amount | number:'1.2-2')"
                  class="w-full px-3.5 py-2.5 rounded-lg border border-slate-200 bg-slate-100 text-slate-700 text-sm font-semibold"
                />
              </div>
            </div>

            <div>
              <label for="dispute-reason" class="block text-sm font-semibold text-slate-700 mb-1">Statement of Dispute</label>
              <textarea
                id="dispute-reason"
                name="reason"
                rows="3"
                required
                [(ngModel)]="reason"
                placeholder="Explain why you are disputing this transaction..."
                class="w-full px-3.5 py-2.5 rounded-lg border border-slate-300 text-sm focus:ring-2 focus:ring-capitec-blue outline-none"
              ></textarea>
            </div>

            <div>
              <label for="dispute-file-input" class="block text-sm font-semibold text-slate-700 mb-1">
                Supporting Evidence Documents (Up to 5 files: PDF, PNG, JPG &mdash; Max 5MB each)
              </label>
              <input
                id="dispute-file-input"
                type="file"
                multiple
                accept=".pdf,.png,.jpg,.jpeg,application/pdf,image/png,image/jpeg"
                (change)="onFilesSelected($event)"
                class="block w-full text-sm text-slate-600 file:mr-4 file:py-2 file:px-4 file:rounded-lg file:border-0 file:text-xs file:font-semibold file:bg-blue-50 file:text-capitec-blue hover:file:bg-blue-100"
              />
              @if (selectedFiles.length > 0) {
                <div class="mt-2 flex flex-wrap gap-2">
                  @for (f of selectedFiles; track f.name) {
                    <span class="px-2.5 py-1 rounded-md bg-slate-100 text-slate-700 text-xs font-medium border border-slate-200">
                      {{ f.name }} ({{ (f.size / 1024) | number:'1.0-1' }} KB)
                    </span>
                  }
                </div>
              }
            </div>

            <div class="flex items-center justify-end gap-3 pt-2">
              <button
                type="button"
                (click)="cancelDisputeForm()"
                class="px-4 py-2.5 rounded-lg border border-slate-300 text-slate-700 text-sm font-semibold hover:bg-slate-50"
              >
                Cancel
              </button>
              <button
                id="submit-dispute-btn"
                type="submit"
                [disabled]="submitting"
                class="px-6 py-2.5 rounded-lg bg-capitec-blue hover:bg-capitec-dark text-white text-sm font-semibold shadow transition disabled:opacity-50"
              >
                {{ submitting ? 'Submitting Dispute & Computing SHA-256...' : 'Submit Dispute' }}
              </button>
            </div>
          </form>
        </div>
      }

      <!-- Recent Transactions Feed -->
      <section class="bg-white rounded-2xl shadow-sm border border-slate-200 overflow-hidden">
        <div class="px-6 py-4 border-b border-slate-100 flex items-center justify-between">
          <h2 class="text-lg font-bold text-slate-900">Recent Account Transactions</h2>
          <span class="text-xs text-slate-500">{{ transactions.length }} settled items</span>
        </div>

        <div class="overflow-x-auto">
          <table class="w-full text-sm text-left">
            <thead class="bg-slate-50 text-xs uppercase text-slate-500 border-b border-slate-200">
              <tr>
                <th class="px-6 py-3">Reference</th>
                <th class="px-6 py-3">Date &amp; Time</th>
                <th class="px-6 py-3">Merchant / Description</th>
                <th class="px-6 py-3">Payment Rail</th>
                <th class="px-6 py-3">Location</th>
                <th class="px-6 py-3">Dispute Status</th>
                <th class="px-6 py-3 text-right">Amount</th>
                <th class="px-6 py-3 text-right">Action</th>
              </tr>
            </thead>
            <tbody class="divide-y divide-slate-100">
              @for (tx of transactions; track tx.id) {
                <tr class="hover:bg-slate-50/80 transition">
                  <td class="px-6 py-4 font-mono text-xs text-slate-600">{{ tx.transactionReference }}</td>
                  <td class="px-6 py-4 text-xs text-slate-500">{{ tx.transactedAt | date:'medium' }}</td>
                  <td class="px-6 py-4 font-semibold text-slate-900">{{ tx.merchantName }}</td>
                  <td class="px-6 py-4">
                    <span class="px-2.5 py-0.5 rounded-full text-xs font-medium bg-slate-100 text-slate-700">
                      {{ tx.paymentRail }}
                    </span>
                  </td>
                  <td class="px-6 py-4 text-slate-500">{{ tx.location }}</td>
                  <td class="px-6 py-4">
                    @if (getLatestDisputeForTransaction(tx.id); as txDispute) {
                      <a [routerLink]="['/disputes', txDispute.id]" class="inline-flex items-center gap-1.5 hover:underline">
                        <span [class]="statusBadgeClass(txDispute.status)">{{ txDispute.status }}</span>
                        <span class="text-xs font-mono text-slate-500">({{ txDispute.disputeReference }})</span>
                      </a>
                    } @else {
                      <span class="text-xs text-slate-400">No dispute</span>
                    }
                  </td>
                  <td class="px-6 py-4 text-right font-bold text-slate-900">R {{ tx.amount | number:'1.2-2' }}</td>
                  <td class="px-6 py-4 text-right">
                    <button
                      type="button"
                      (click)="openLogDispute(tx)"
                      class="px-3.5 py-1.5 rounded-lg bg-capitec-red hover:bg-red-700 text-white text-xs font-semibold transition"
                    >
                      Log Dispute
                    </button>
                  </td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      </section>

      <!-- Customer's Logged & Historic Disputed Transactions -->
      <section class="bg-white rounded-2xl shadow-sm border border-slate-200 overflow-hidden">
        <div class="px-6 py-4 border-b border-slate-100 flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3">
          <div>
            <h2 class="text-lg font-bold text-slate-900">My Logged &amp; Historic Disputed Transactions</h2>
            <p class="text-xs text-slate-500">Complete audit trail of active and closed transaction disputes, outcomes, and linked cases.</p>
          </div>
          <div class="flex items-center gap-2">
            <button
              type="button"
              (click)="disputeFilter = 'ALL'"
              [class]="disputeFilter === 'ALL'
                ? 'px-3 py-1.5 rounded-lg bg-capitec-blue text-white text-xs font-semibold'
                : 'px-3 py-1.5 rounded-lg bg-slate-100 text-slate-700 hover:bg-slate-200 text-xs font-semibold'"
            >
              All ({{ disputes.length }})
            </button>
            <button
              type="button"
              (click)="disputeFilter = 'ACTIVE'"
              [class]="disputeFilter === 'ACTIVE'
                ? 'px-3 py-1.5 rounded-lg bg-capitec-blue text-white text-xs font-semibold'
                : 'px-3 py-1.5 rounded-lg bg-slate-100 text-slate-700 hover:bg-slate-200 text-xs font-semibold'"
            >
              Active ({{ activeDisputesCount }})
            </button>
            <button
              type="button"
              (click)="disputeFilter = 'HISTORIC'"
              [class]="disputeFilter === 'HISTORIC'
                ? 'px-3 py-1.5 rounded-lg bg-capitec-blue text-white text-xs font-semibold'
                : 'px-3 py-1.5 rounded-lg bg-slate-100 text-slate-700 hover:bg-slate-200 text-xs font-semibold'"
            >
              Historic / Closed ({{ historicDisputesCount }})
            </button>
            <button type="button" (click)="loadData()" class="ml-2 text-xs text-capitec-blue font-semibold hover:underline">
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
                  <th class="px-6 py-3">Dispute Ref</th>
                  <th class="px-6 py-3">Merchant &amp; Rail</th>
                  <th class="px-6 py-3">Category &amp; Outcome</th>
                  <th class="px-6 py-3">Amount</th>
                  <th class="px-6 py-3">Status</th>
                  <th class="px-6 py-3">Logged / Resolved</th>
                  <th class="px-6 py-3">Evidence</th>
                  <th class="px-6 py-3 text-right">Details</th>
                </tr>
              </thead>
              <tbody class="divide-y divide-slate-100">
                @for (d of filteredDisputes; track d.id) {
                  <tr class="hover:bg-slate-50/80 transition">
                    <td class="px-6 py-4">
                      <div class="font-mono font-bold text-slate-900">{{ d.disputeReference }}</div>
                      @if (d.originalDisputeReference) {
                        <div class="text-xs text-indigo-700 font-medium mt-0.5">
                          Linked to {{ d.originalDisputeReference }}
                        </div>
                      }
                    </td>
                    <td class="px-6 py-4">
                      <div class="font-medium text-slate-800">{{ d.merchantName }}</div>
                      <div class="text-xs text-slate-500">{{ d.paymentRail }}</div>
                    </td>
                    <td class="px-6 py-4">
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
                    <td class="px-6 py-4 font-semibold text-slate-900">R {{ d.amount | number:'1.2-2' }}</td>
                    <td class="px-6 py-4">
                      <span [class]="statusBadgeClass(d.status)">{{ d.status }}</span>
                    </td>
                    <td class="px-6 py-4 text-xs text-slate-500">
                      <div>Logged: {{ d.createdAt | date:'shortDate' }}</div>
                      @if (d.resolvedAt) {
                        <div class="text-emerald-700 font-medium">Closed: {{ d.resolvedAt | date:'shortDate' }}</div>
                      }
                    </td>
                    <td class="px-6 py-4 text-xs text-slate-600">{{ d.attachmentCount }} file(s)</td>
                    <td class="px-6 py-4 text-right">
                      <a [routerLink]="['/disputes', d.id]"
                         class="px-3.5 py-1.5 rounded-lg bg-capitec-blue hover:bg-capitec-dark text-white text-xs font-semibold transition inline-block">
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
export class CustomerDashboardComponent implements OnInit {
  transactions: BankTransaction[] = [];
  disputes: DisputeSummary[] = [];
  disputeFilter: 'ALL' | 'ACTIVE' | 'HISTORIC' = 'ALL';

  selectedTx: BankTransaction | null = null;
  category: DisputeCategory = 'UNAUTHORIZED_TRANSACTION';
  reason = '';
  selectedFiles: File[] = [];
  submitting = false;
  formError = '';
  createdDisputeBanner: DisputeDetail | null = null;

  constructor(private api: PortalApiService) {}

  get activeDisputesCount(): number {
    return this.disputes.filter((d) => !d.terminal).length;
  }

  get historicDisputesCount(): number {
    return this.disputes.filter((d) => d.terminal).length;
  }

  get filteredDisputes(): DisputeSummary[] {
    if (this.disputeFilter === 'ACTIVE') {
      return this.disputes.filter((d) => !d.terminal);
    }
    if (this.disputeFilter === 'HISTORIC') {
      return this.disputes.filter((d) => d.terminal);
    }
    return this.disputes;
  }

  getLatestDisputeForTransaction(transactionId: string): DisputeSummary | undefined {
    return this.disputes.find((d) => d.transactionId === transactionId);
  }

  ngOnInit(): void {
    this.loadData();
  }

  loadData(): void {
    this.api.getTransactions().subscribe((txs) => (this.transactions = txs));
    this.api.getDisputes().subscribe((ds) => (this.disputes = ds));
  }

  openLogDispute(tx: BankTransaction): void {
    this.selectedTx = tx;
    this.category = tx.paymentRail === 'PAYSHAP' ? 'PAYSHAP_WRONG_RECIPIENT' : 'UNAUTHORIZED_TRANSACTION';
    this.reason = `Unauthorized ${tx.merchantName} transaction of R${tx.amount.toFixed(2)} not initiated by me.`;
    this.selectedFiles = [];
    this.formError = '';
  }

  cancelDisputeForm(): void {
    this.selectedTx = null;
    this.selectedFiles = [];
    this.formError = '';
  }

  onFilesSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    if (!input.files) return;
    const files = Array.from(input.files);
    if (files.length > 5) {
      this.formError = 'Maximum of 5 files can be uploaded.';
      return;
    }
    this.selectedFiles = files;
    this.formError = '';
  }

  submitDispute(): void {
    if (!this.selectedTx) return;
    if (!this.reason || this.reason.trim().length < 5) {
      this.formError = 'Please provide a clear reason (at least 5 characters).';
      return;
    }

    this.submitting = true;
    this.formError = '';

    const fd = new FormData();
    fd.append('transactionId', this.selectedTx.id);
    fd.append('merchantName', this.selectedTx.merchantName);
    fd.append('paymentRail', this.selectedTx.paymentRail);
    fd.append('category', this.category);
    fd.append('amount', String(this.selectedTx.amount));
    fd.append('reason', this.reason.trim());
    this.selectedFiles.forEach((f) => fd.append('files', f, f.name));

    this.api.createDisputeMultipart(fd).subscribe({
      next: (created) => {
        this.submitting = false;
        this.createdDisputeBanner = created;
        this.selectedTx = null;
        this.selectedFiles = [];
        this.loadData();
      },
      error: (err) => {
        this.submitting = false;
        this.formError = err.error?.message || 'Failed to submit dispute.';
      }
    });
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

