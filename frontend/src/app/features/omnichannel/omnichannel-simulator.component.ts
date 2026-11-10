import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { PortalApiService } from '../../core/services/portal-api.service';
import { AuthService } from '../../core/services/auth.service';
import {
  BankTransaction,
  DisputeCategory,
  OmnichannelSimulationResponse
} from '../../core/models/portal.models';

@Component({
  selector: 'app-omnichannel-simulator',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink],
  template: `
    <div class="max-w-6xl mx-auto px-4 sm:px-6 py-8 space-y-6">
      <!-- Header -->
      <div class="bg-white rounded-2xl shadow-sm border border-slate-200 p-6 flex flex-col md:flex-row md:items-center justify-between gap-4">
        <div>
          <span class="text-xs font-bold uppercase tracking-wider text-capi-blue">Omnichannel Banking Intake Simulator</span>
          <h1 class="text-2xl font-bold text-slate-900 mt-0.5">SMS, WhatsApp &amp; Voice Call Dispute Simulator</h1>
          <p class="text-sm text-slate-500 mt-1">
            Simulate urgent customer disputes arriving via SMS/WhatsApp alerts or Call Centre IVR (e.g. at an ATM, filling station, or spotting suspicious activity).
          </p>
        </div>
        <a [routerLink]="[auth.getHomeRouteForRole(auth.currentUser()?.role)]"
           class="px-4 py-2 rounded-lg border border-slate-300 text-slate-700 text-xs font-semibold hover:bg-slate-50 whitespace-nowrap">
          &larr; Back to Dashboard
        </a>
      </div>

      <!-- Channel Mode Tabs -->
      <div class="flex flex-wrap gap-2">
        <button
          id="tab-sms-whatsapp"
          type="button"
          (click)="activeTab = 'MESSAGING'; clearFeedback()"
          [class]="activeTab === 'MESSAGING'
            ? 'px-5 py-2.5 rounded-xl bg-capi-blue text-white text-sm font-semibold shadow-sm'
            : 'px-5 py-2.5 rounded-xl bg-white border border-slate-200 text-slate-700 hover:bg-slate-50 text-sm font-semibold'"
        >
          SMS &amp; WhatsApp Gateway
        </button>
        <button
          id="tab-voice-call"
          type="button"
          (click)="activeTab = 'VOICE'; clearFeedback()"
          [class]="activeTab === 'VOICE'
            ? 'px-5 py-2.5 rounded-xl bg-capi-blue text-white text-sm font-semibold shadow-sm'
            : 'px-5 py-2.5 rounded-xl bg-white border border-slate-200 text-slate-700 hover:bg-slate-50 text-sm font-semibold'"
        >
          Call Centre / Voice IVR Intake
        </button>
      </div>

      @if (errorMsg) {
        <div class="p-4 rounded-xl bg-red-50 border border-red-300 text-red-800 text-sm font-medium">
          {{ errorMsg }}
        </div>
      }

      @if (activeTab === 'MESSAGING') {
        <div class="grid grid-cols-1 lg:grid-cols-2 gap-6">
          <!-- Left: Inbound Gateway Form -->
          <div class="bg-white rounded-2xl shadow-sm border border-slate-200 p-6 space-y-5">
            <div class="flex items-center justify-between">
              <h2 class="text-lg font-bold text-slate-900">Inbound Mobile Message</h2>
              <div class="inline-flex rounded-lg bg-slate-100 p-1">
                <button
                  type="button"
                  (click)="smsChannel = 'SMS'"
                  [class]="smsChannel === 'SMS' ? 'px-3 py-1 rounded-md bg-white text-capi-blue shadow-xs text-xs font-bold' : 'px-3 py-1 text-xs font-semibold text-slate-600'"
                >
                  SMS
                </button>
                <button
                  type="button"
                  (click)="smsChannel = 'WHATSAPP'"
                  [class]="smsChannel === 'WHATSAPP' ? 'px-3 py-1 rounded-md bg-emerald-600 text-white shadow-xs text-xs font-bold' : 'px-3 py-1 text-xs font-semibold text-slate-600'"
                >
                  WhatsApp
                </button>
              </div>
            </div>

            <!-- Real-World Quick Scenario Chips -->
            <div>
              <p class="text-xs font-semibold uppercase tracking-wider text-slate-500 mb-2">Quick Banking Scenarios</p>
              <div class="flex flex-wrap gap-2">
                <button type="button" (click)="applySmsPreset('ATM')"
                        class="px-3 py-1.5 rounded-lg bg-amber-50 hover:bg-amber-100 border border-amber-200 text-amber-900 text-xs font-semibold transition">
                  ATM: No Cash Dispensed
                </button>
                <button type="button" (click)="applySmsPreset('FUEL')"
                        class="px-3 py-1.5 rounded-lg bg-blue-50 hover:bg-blue-100 border border-blue-200 text-capi-blue text-xs font-semibold transition">
                  Filling Station: Double Tap
                </button>
                <button type="button" (click)="applySmsPreset('PAYSHAP')"
                        class="px-3 py-1.5 rounded-lg bg-purple-50 hover:bg-purple-100 border border-purple-200 text-purple-900 text-xs font-semibold transition">
                  Notice: Unknown PayShap
                </button>
              </div>
            </div>

            <form (ngSubmit)="submitSmsSimulation()" class="space-y-4">
              <div>
                <label class="block text-xs font-semibold text-slate-700 mb-1">Registered Customer Mobile (MSISDN)</label>
                <input
                  type="text"
                  name="fromPhone"
                  [(ngModel)]="fromPhone"
                  placeholder="+27825550192"
                  class="w-full px-3.5 py-2 rounded-lg border border-slate-300 text-sm font-mono focus:ring-2 focus:ring-capi-blue outline-none"
                />
                <p class="text-[11px] text-slate-400 mt-1">Customer 1: +27825550192 &bull; Customer 2: +27834419901</p>
              </div>

              @if (transactions.length > 0) {
                <div>
                  <label class="block text-xs font-semibold text-slate-700 mb-1">Optional Linked Transaction</label>
                  <select
                    name="smsTxId"
                    [(ngModel)]="smsSelectedTxId"
                    class="w-full px-3.5 py-2 rounded-lg border border-slate-300 text-sm bg-white focus:ring-2 focus:ring-capi-blue outline-none"
                  >
                    <option [ngValue]="null">Auto-match TXN-YYYY-XXXX from message text</option>
                    @for (tx of transactions; track tx.id) {
                      <option [value]="tx.id">{{ tx.transactionReference }} — {{ tx.merchantName }} (R {{ tx.amount | number:'1.2-2' }})</option>
                    }
                  </select>
                </div>
              }

              <div>
                <label class="block text-xs font-semibold text-slate-700 mb-1">Inbound {{ smsChannel }} Message</label>
                <textarea
                  id="sms-message-input"
                  name="smsMessage"
                  rows="3"
                  required
                  [(ngModel)]="smsMessage"
                  placeholder="e.g. DISPUTE TXN-2026-9005 ATM did not dispense cash at Sandton City"
                  class="w-full px-3.5 py-2 rounded-lg border border-slate-300 text-sm focus:ring-2 focus:ring-capi-blue outline-none"
                ></textarea>
              </div>

              <div class="flex flex-wrap items-center gap-3">
                <button
                  id="send-sms-btn"
                  type="submit"
                  [disabled]="submitting"
                  class="px-5 py-2.5 rounded-lg bg-capi-blue hover:bg-capi-dark text-white text-sm font-semibold shadow transition disabled:opacity-50"
                >
                  {{ submitting ? 'Routing via Gateway...' : 'Send Inbound ' + smsChannel }}
                </button>
                @if (lastSmsIdempotencyKey) {
                  <button
                    type="button"
                    (click)="retryLastSmsForIdempotency()"
                    class="px-3.5 py-2.5 rounded-lg border border-amber-400 bg-amber-50 hover:bg-amber-100 text-amber-900 text-xs font-semibold transition"
                  >
                    Simulate Gateway Retry (Test Redis Lock)
                  </button>
                }
              </div>
            </form>
          </div>

          <!-- Right: Mobile Handset Preview -->
          <div class="bg-slate-900 rounded-3xl p-5 text-white shadow-xl border-4 border-slate-800 flex flex-col justify-between min-h-[430px]">
            <div>
              <div class="flex items-center justify-between border-b border-slate-800 pb-3 mb-4">
                <div class="flex items-center gap-2.5">
                  <span class="w-8 h-8 rounded-full flex items-center justify-center font-bold text-xs"
                        [class]="smsChannel === 'WHATSAPP' ? 'bg-emerald-600' : 'bg-capi-blue'">
                    {{ smsChannel === 'WHATSAPP' ? 'WA' : 'SMS' }}
                  </span>
                  <div>
                    <div class="text-sm font-bold">Capi Bank Alerts ({{ smsChannel }})</div>
                    <div class="text-[11px] text-slate-400">Shortcode 31200 &bull; Automated Dispute Intake</div>
                  </div>
                </div>
                <span class="px-2 py-0.5 rounded bg-emerald-900/60 text-emerald-300 text-[10px] font-mono">ONLINE</span>
              </div>

              <div class="space-y-4">
                <div class="bg-slate-800 rounded-2xl rounded-tl-none p-3.5 max-w-[88%] text-xs text-slate-200 leading-relaxed">
                  <div class="font-bold text-blue-300 mb-1">Capi Transaction Alert</div>
                  Alert: Purchase/Withdrawal at Sandton City ATM #042 or Shell Braamfontein. If unauthorized or cash not dispensed, reply <strong>DISPUTE &lt;TXN-REF&gt; &lt;reason&gt;</strong>.
                </div>

                @if (smsMessage) {
                  <div class="ml-auto rounded-2xl rounded-tr-none p-3.5 max-w-[88%] text-xs text-white leading-relaxed"
                       [class]="smsChannel === 'WHATSAPP' ? 'bg-emerald-700' : 'bg-capi-blue'">
                    <div class="text-[10px] opacity-75 mb-0.5">{{ fromPhone }} &bull; Draft/Sent</div>
                    {{ smsMessage }}
                  </div>
                }

                @if (lastResult && (lastResult.channel === 'SMS' || lastResult.channel === 'WHATSAPP')) {
                  <div id="sms-auto-reply-bubble" class="bg-slate-800 border border-emerald-500/40 rounded-2xl rounded-tl-none p-3.5 max-w-[92%] text-xs text-emerald-100 leading-relaxed space-y-2">
                    <div class="font-bold text-emerald-400">Gateway Auto-Reply Dispatched</div>
                    <p>{{ lastResult.outboundReply }}</p>
                    <div class="pt-2 border-t border-slate-700 flex items-center justify-between gap-2">
                      <span class="font-mono text-[11px] text-slate-300">Case: {{ lastResult.dispute.disputeReference }}</span>
                      <a [routerLink]="['/disputes', lastResult.dispute.id]"
                         class="px-2.5 py-1 rounded bg-emerald-600 hover:bg-emerald-500 text-white text-[11px] font-semibold">
                        Open Case
                      </a>
                    </div>
                  </div>
                }
              </div>
            </div>

            <div class="mt-4 pt-3 border-t border-slate-800 text-[11px] text-slate-400 flex items-center justify-between">
              <span>Redis Idempotency + Kafka Event Bus Active</span>
              <span>MailHog Receipt Sent</span>
            </div>
          </div>
        </div>
      } @else {
        <!-- VOICE CALL / CALL CENTRE IVR SIMULATOR -->
        <div class="grid grid-cols-1 lg:grid-cols-2 gap-6">
          <div class="bg-white rounded-2xl shadow-sm border border-slate-200 p-6 space-y-5">
            <div class="flex items-center justify-between">
              <h2 class="text-lg font-bold text-slate-900">Voice Call / Fraud Desk Intake</h2>
              <span class="px-2.5 py-1 rounded-full bg-emerald-100 text-emerald-800 text-xs font-bold">IVR Caller Verified</span>
            </div>

            <!-- Preset Voice Scenarios -->
            <div>
              <p class="text-xs font-semibold uppercase tracking-wider text-slate-500 mb-2">Call Scenario Presets</p>
              <div class="flex flex-wrap gap-2">
                <button type="button" (click)="applyVoicePreset('ATM_NO_CASH')"
                        class="px-3 py-1.5 rounded-lg bg-amber-50 hover:bg-amber-100 border border-amber-200 text-amber-900 text-xs font-semibold">
                  Stranded at ATM (No Cash)
                </button>
                <button type="button" (click)="applyVoicePreset('FILLING_STATION_DOUBLE_TAP')"
                        class="px-3 py-1.5 rounded-lg bg-blue-50 hover:bg-blue-100 border border-blue-200 text-capi-blue text-xs font-semibold">
                  Filling Station Card Issue
                </button>
                <button type="button" (click)="applyVoicePreset('SUSPICIOUS_ACTIVITY')"
                        class="px-3 py-1.5 rounded-lg bg-red-50 hover:bg-red-100 border border-red-200 text-red-800 text-xs font-semibold">
                  Suspicious Account Debit
                </button>
              </div>
            </div>

            <form (ngSubmit)="submitVoiceCall()" class="space-y-4">
              <div class="grid grid-cols-1 sm:grid-cols-2 gap-4">
                <div>
                  <label class="block text-xs font-semibold text-slate-700 mb-1">Caller CLI Number</label>
                  <input
                    type="text"
                    name="callerPhone"
                    [(ngModel)]="callerPhone"
                    class="w-full px-3.5 py-2 rounded-lg border border-slate-300 text-sm font-mono outline-none focus:ring-2 focus:ring-capi-blue"
                  />
                </div>
                <div>
                  <label class="block text-xs font-semibold text-slate-700 mb-1">Transaction Reference</label>
                  <input
                    type="text"
                    name="voiceTxRef"
                    [(ngModel)]="voiceTxRef"
                    placeholder="TXN-2026-9001"
                    class="w-full px-3.5 py-2 rounded-lg border border-slate-300 text-sm font-mono outline-none focus:ring-2 focus:ring-capi-blue"
                  />
                </div>
              </div>

              <div>
                <label class="block text-xs font-semibold text-slate-700 mb-1">Dispute Category</label>
                <select
                  name="voiceCategory"
                  [(ngModel)]="voiceCategory"
                  class="w-full px-3.5 py-2 rounded-lg border border-slate-300 text-sm bg-white outline-none focus:ring-2 focus:ring-capi-blue"
                >
                  <option value="ATM_DISPENSE_ERROR">ATM Dispense Error</option>
                  <option value="FRAUDULENT_CARD_USE">Fraudulent Card Use</option>
                  <option value="DUPLICATE_CHARGE">Duplicate Charge</option>
                  <option value="UNAUTHORIZED_TRANSACTION">Unauthorized Transaction</option>
                  <option value="PAYSHAP_WRONG_RECIPIENT">PayShap Wrong Recipient</option>
                </select>
              </div>

              <div>
                <label class="block text-xs font-semibold text-slate-700 mb-1">Customer Spoken Statement</label>
                <textarea
                  id="voice-statement-input"
                  name="voiceStatement"
                  rows="3"
                  required
                  [(ngModel)]="voiceStatement"
                  class="w-full px-3.5 py-2 rounded-lg border border-slate-300 text-sm outline-none focus:ring-2 focus:ring-capi-blue"
                ></textarea>
              </div>

              <div>
                <label class="block text-xs font-semibold text-slate-700 mb-1">Call Centre Agent Notes</label>
                <input
                  type="text"
                  name="voiceAgentNotes"
                  [(ngModel)]="voiceAgentNotes"
                  class="w-full px-3.5 py-2 rounded-lg border border-slate-300 text-sm outline-none focus:ring-2 focus:ring-capi-blue"
                />
              </div>

              <label class="flex items-center gap-2 text-xs font-semibold text-slate-700">
                <input type="checkbox" name="cardFreezeRequested" [(ngModel)]="cardFreezeRequested" class="rounded border-slate-300" />
                Customer requested precautionary temporary card hold during call
              </label>

              <button
                id="log-voice-call-btn"
                type="submit"
                [disabled]="submitting"
                class="w-full py-2.5 px-5 rounded-lg bg-capi-blue hover:bg-capi-dark text-white text-sm font-semibold shadow transition disabled:opacity-50"
              >
                {{ submitting ? 'Logging Voice Call Dispute...' : 'Complete Call &amp; Log Dispute' }}
              </button>
            </form>
          </div>

          <!-- Right: Live Call Console & Transcript Output -->
          <div class="bg-slate-900 rounded-2xl p-6 text-white shadow-xl flex flex-col justify-between space-y-5">
            <div class="space-y-4">
              <div class="flex items-center justify-between border-b border-slate-800 pb-4">
                <div>
                  <div class="text-xs uppercase tracking-wider text-blue-300 font-bold">Fraud &amp; Disputes Voice Desk</div>
                  <div class="text-lg font-bold mt-0.5">0860 10 20 43 &mdash; Call Intake</div>
                </div>
                <span class="px-3 py-1 rounded-full bg-red-600/20 border border-red-500/40 text-red-300 text-xs font-mono">
                  REC &bull; {{ callerPhone }}
                </span>
              </div>

              <div class="p-4 rounded-xl bg-slate-800/80 border border-slate-700 text-xs space-y-1.5">
                <div><strong>Scenario:</strong> {{ voiceScenario }}</div>
                <div><strong>Matched TXN Ref:</strong> {{ voiceTxRef || 'Auto-detect latest' }}</div>
                <div><strong>Precautionary Card Hold:</strong> {{ cardFreezeRequested ? 'YES (Requested)' : 'No' }}</div>
              </div>

              @if (lastResult && lastResult.channel === 'PHONE_CALL') {
                <div id="voice-call-result" class="p-4 rounded-xl bg-emerald-950/60 border border-emerald-500/50 text-xs space-y-3">
                  <div class="text-emerald-400 font-bold text-sm">Call Dispute Logged: {{ lastResult.dispute.disputeReference }}</div>
                  <p class="text-emerald-100">{{ lastResult.outboundReply }}</p>
                  <pre class="whitespace-pre-wrap font-mono text-[11px] bg-slate-950/70 p-3 rounded-lg text-slate-200 border border-slate-800">{{ lastResult.channelTranscript }}</pre>
                  <div class="pt-1">
                    <a [routerLink]="['/disputes', lastResult.dispute.id]"
                       class="inline-block px-4 py-2 rounded-lg bg-emerald-600 hover:bg-emerald-500 text-white text-xs font-semibold">
                      Open Case {{ lastResult.dispute.disputeReference }}
                    </a>
                  </div>
                </div>
              } @else {
                <div class="p-6 rounded-xl border border-dashed border-slate-700 text-center text-xs text-slate-400">
                  Select a preset (ATM / Filling Station / Suspicious Debit) and click <strong>Complete Call &amp; Log Dispute</strong> to capture the call transcript and generate a case.
                </div>
              }
            </div>

            <div class="pt-3 border-t border-slate-800 text-[11px] text-slate-400 flex items-center justify-between">
              <span>Channel: PHONE_CALL</span>
              <span>Confirmation Dispatched via Kafka &amp; MailHog</span>
            </div>
          </div>
        </div>
      }
    </div>
  `
})
export class OmnichannelSimulatorComponent implements OnInit {
  activeTab: 'MESSAGING' | 'VOICE' = 'MESSAGING';
  transactions: BankTransaction[] = [];

  // SMS / WhatsApp fields
  smsChannel: 'SMS' | 'WHATSAPP' = 'SMS';
  fromPhone = '+27825550192';
  smsSelectedTxId: string | null = null;
  smsMessage = 'DISPUTE TXN-2026-9005 Sandton City ATM did not dispense R1500 cash after debit alert.';
  lastSmsIdempotencyKey = '';

  // Voice Call fields
  callerPhone = '+27825550192';
  voiceTxRef = 'TXN-2026-9001';
  voiceScenario = 'FILLING_STATION_DOUBLE_TAP';
  voiceCategory: DisputeCategory = 'FRAUDULENT_CARD_USE';
  voiceStatement = 'Customer called from Shell Braamfontein filling station reporting card was tapped twice for R850.';
  voiceAgentNotes = 'Verified caller via OTP/IVR; advised customer to retain fuel slip.';
  cardFreezeRequested = true;

  submitting = false;
  errorMsg = '';
  lastResult: OmnichannelSimulationResponse | null = null;

  constructor(private api: PortalApiService, public auth: AuthService) {}

  ngOnInit(): void {
    if (this.auth.currentUser()?.role === 'CUSTOMER') {
      this.api.getTransactions().subscribe({
        next: (txs) => (this.transactions = txs),
        error: () => {}
      });
    }
  }

  clearFeedback(): void {
    this.errorMsg = '';
  }

  applySmsPreset(preset: 'ATM' | 'FUEL' | 'PAYSHAP'): void {
    this.errorMsg = '';
    this.smsSelectedTxId = null;
    if (preset === 'ATM') {
      this.smsMessage = 'DISPUTE TXN-2026-9005 Sandton City ATM #042 debited R1500 but no cash was dispensed.';
    } else if (preset === 'FUEL') {
      this.smsMessage = 'DISPUTE TXN-2026-9001 Shell Braamfontein filling station charged my card twice R850.';
    } else {
      this.smsMessage = 'STOP FRAUD TXN-2026-9003 Just noticed unrecognized PayShap transfer of R1200 on my statement.';
    }
  }

  applyVoicePreset(preset: 'ATM_NO_CASH' | 'FILLING_STATION_DOUBLE_TAP' | 'SUSPICIOUS_ACTIVITY'): void {
    this.errorMsg = '';
    this.voiceScenario = preset;
    if (preset === 'ATM_NO_CASH') {
      this.voiceTxRef = 'TXN-2026-9005';
      this.voiceCategory = 'ATM_DISPENSE_ERROR';
      this.voiceStatement = 'Standing at Sandton City ATM #042; received debit SMS for R1,500.00 but ATM shutter did not open.';
      this.voiceAgentNotes = 'Logged ATM cash retract dispute; advised 48-hour ATM cash count reconciliation.';
      this.cardFreezeRequested = false;
    } else if (preset === 'FILLING_STATION_DOUBLE_TAP') {
      this.voiceTxRef = 'TXN-2026-9001';
      this.voiceCategory = 'FRAUDULENT_CARD_USE';
      this.voiceStatement = 'At Shell Braamfontein filling station; card was taken out of sight and R850.00 debited unexpectedly.';
      this.voiceAgentNotes = 'High-risk card-present fuel merchant alert; customer requested immediate precautionary card hold.';
      this.cardFreezeRequested = true;
    } else {
      this.voiceTxRef = 'TXN-2026-9002';
      this.voiceCategory = 'UNAUTHORIZED_TRANSACTION';
      this.voiceStatement = 'Just noticed an unauthorized Checkers Sixty60 online charge of R450.00 while checking my SMS notifications.';
      this.voiceAgentNotes = 'Confirmed customer never placed delivery order; placed precautionary hold and logged case.';
      this.cardFreezeRequested = true;
    }
  }

  submitSmsSimulation(): void {
    this.lastSmsIdempotencyKey = 'sim-' + this.smsChannel.toLowerCase() + '-' + Date.now();
    this.sendSmsRequest(this.lastSmsIdempotencyKey);
  }

  retryLastSmsForIdempotency(): void {
    if (!this.lastSmsIdempotencyKey) return;
    this.sendSmsRequest(this.lastSmsIdempotencyKey);
  }

  private sendSmsRequest(idempotencyKey: string): void {
    this.submitting = true;
    this.errorMsg = '';
    this.api.simulateSmsWebhook({
      channel: this.smsChannel,
      fromPhoneNumber: this.fromPhone.trim(),
      transactionId: this.smsSelectedTxId,
      messageBody: this.smsMessage.trim(),
      idempotencyKey
    }).subscribe({
      next: (res) => {
        this.submitting = false;
        this.lastResult = res;
      },
      error: (err) => {
        this.submitting = false;
        this.errorMsg = err.error?.message || 'Failed to simulate inbound message.';
      }
    });
  }

  submitVoiceCall(): void {
    this.submitting = true;
    this.errorMsg = '';
    this.api.simulateVoiceCall({
      callerPhoneNumber: this.callerPhone.trim(),
      transactionReference: this.voiceTxRef.trim() || null,
      scenario: this.voiceScenario,
      category: this.voiceCategory,
      callerStatement: this.voiceStatement.trim(),
      agentNotes: this.voiceAgentNotes.trim(),
      cardFreezeRequested: this.cardFreezeRequested,
      idempotencyKey: 'sim-call-' + Date.now()
    }).subscribe({
      next: (res) => {
        this.submitting = false;
        this.lastResult = res;
      },
      error: (err) => {
        this.submitting = false;
        this.errorMsg = err.error?.message || 'Failed to log simulated voice call dispute.';
      }
    });
  }
}

