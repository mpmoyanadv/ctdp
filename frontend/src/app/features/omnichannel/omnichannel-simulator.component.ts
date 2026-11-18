import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { PortalApiService } from '../../core/services/portal-api.service';
import { AuthService } from '../../core/services/auth.service';
import {
  BankTransaction,
  CustomerCallerVerificationResponse,
  CustomerKycLookupResponse,
  DisputeCategory,
  OmnichannelSimulationResponse
} from '../../core/models/portal.models';

@Component({
  selector: 'app-omnichannel-simulator',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink],
  template: `
    <div class="max-w-7xl mx-auto px-4 sm:px-6 py-8 space-y-6">
      <!-- Header -->
      <div class="bg-white rounded-2xl shadow-sm border border-slate-200 p-6 flex flex-col md:flex-row md:items-center justify-between gap-4">
        <div>
          <span class="text-xs font-bold uppercase tracking-wider text-capi-blue">Staff Operations &bull; Call Centre &amp; Omnichannel Desk</span>
          <h1 class="text-2xl font-bold text-slate-900 mt-0.5">Caller KYC Verification &amp; Assisted Dispute Intake</h1>
          <p class="text-sm text-slate-500 mt-1">
            Capture the calling customer's SA ID number to load their KYC &amp; credit-bureau profile, confirm identity on the call, and capture dispute details on their behalf.
          </p>
        </div>
        <a [routerLink]="[auth.getHomeRouteForRole(auth.currentUser()?.role)]"
           class="px-4 py-2 rounded-lg border border-slate-300 text-slate-700 text-xs font-semibold hover:bg-slate-50 whitespace-nowrap">
          &larr; Back to Staff Workbench
        </a>
      </div>

      <!-- STEP 1: SA ID Capture -> Load Customer Details -> Verified / Unverified -->
      @if (!verification) {
        <div id="caller-kyc-panel" class="bg-white rounded-2xl shadow-sm border-2 p-6 space-y-5"
             [class]="kycError ? 'border-red-300' : 'border-capi-blue/40'">
        <div class="flex flex-col sm:flex-row sm:items-center justify-between gap-3 border-b border-slate-100 pb-4">
          <div>
            <div class="flex items-center gap-2">
              <span class="px-2.5 py-0.5 rounded-full text-xs font-bold"
                    [class]="verification ? 'bg-emerald-100 text-emerald-800' : 'bg-blue-100 text-capi-blue'">
                STEP 1
              </span>
              <h2 class="text-lg font-bold text-slate-900">Caller Identity &amp; Credit Bureau Verification</h2>
            </div>
            <p class="text-xs text-slate-500 mt-1">
              Enter the caller's 13-digit SA ID or Foreign Passport number to unlock <strong>Load Customer Details</strong>, verify details with the caller on the line, then choose <strong>Verified</strong> or <strong>Unverified</strong>.
            </p>
          </div>
          <div class="flex flex-wrap gap-2">
            <button type="button" id="preset-kyc-thabo" (click)="applyKycPreset('THABO')"
                    class="px-3 py-1.5 rounded-lg bg-blue-50 hover:bg-blue-100 border border-blue-200 text-capi-blue text-xs font-semibold transition">
              Fill Thabo SA ID
            </button>
            <button type="button" id="preset-kyc-naledi" (click)="applyKycPreset('NALEDI')"
                    class="px-3 py-1.5 rounded-lg bg-slate-100 hover:bg-slate-200 border border-slate-300 text-slate-700 text-xs font-semibold transition">
              Fill Naledi SA ID
            </button>
            <button type="button" id="preset-kyc-tendai" (click)="applyKycPreset('TENDAI')"
                    class="px-3 py-1.5 rounded-lg bg-purple-50 hover:bg-purple-100 border border-purple-200 text-purple-800 text-xs font-semibold transition">
              Fill Tendai Passport (Foreign 1)
            </button>
            <button type="button" id="preset-kyc-chidinma" (click)="applyKycPreset('CHIDINMA')"
                    class="px-3 py-1.5 rounded-lg bg-amber-50 hover:bg-amber-100 border border-amber-200 text-amber-900 text-xs font-semibold transition">
              Fill Chidinma Passport (Foreign 2)
            </button>
            @if (loadedCustomer || verification || kycError) {
              <button type="button" (click)="resetVerification()"
                      class="px-3 py-1.5 rounded-lg border border-red-200 text-red-700 hover:bg-red-50 text-xs font-semibold transition">
                Clear Caller Session
              </button>
            }
          </div>
        </div>

        <!-- Stage 1A: Capture SA ID or Foreign Passport + Conditional Load Button -->
        <div class="flex flex-col sm:flex-row sm:items-end gap-4">
          <div class="flex-1 max-w-xl">
            <label for="kyc-sa-id-input" class="block text-xs font-semibold text-slate-700 mb-1">
              SA ID or Foreign Passport Number <span class="text-capi-red">* Required</span>
            </label>
            <input
              id="kyc-sa-id-input"
              type="text"
              name="saIdNumber"
              required
              [(ngModel)]="saIdNumber"
              (ngModelChange)="onSaIdInputChange()"
              placeholder="Enter 13-digit SA ID or Foreign Passport (e.g. 8501015800082 or ZW4829104)"
              class="w-full px-3.5 py-2.5 rounded-lg border border-slate-300 text-sm font-mono focus:ring-2 focus:ring-capi-blue outline-none"
            />
            <p class="text-[11px] text-slate-400 mt-1">
              SA IDs: Thabo (8501015800082), Naledi (9104120842087) &bull; Foreign Passports: Tendai (ZW4829104), Chidinma (NG7739201)
            </p>
          </div>

          @if (saIdNumber.trim().length > 0) {
            <div class="sm:pb-5">
              <button
                id="load-customer-details-btn"
                type="button"
                [disabled]="loadingLookup"
                (click)="loadCustomerDetails()"
                class="px-5 py-2.5 rounded-xl bg-capi-blue hover:bg-capi-dark text-white text-sm font-semibold shadow transition disabled:opacity-50 whitespace-nowrap"
              >
                {{ loadingLookup ? 'Loading Customer Details...' : 'Load Customer Details' }}
              </button>
            </div>
          }
        </div>

        @if (kycError) {
          <div id="kyc-error-banner" class="p-4 rounded-xl bg-red-50 border border-red-300 text-red-900 text-sm font-semibold">
            {{ kycError }}
          </div>
        }

        <!-- Stage 1B: Loaded Customer Profile & Verification Controls -->
        @if (loadedCustomer; as lc) {
          <div id="loaded-customer-details-panel" class="p-5 rounded-2xl bg-slate-50 border border-slate-200 space-y-4">
            <div class="flex flex-col md:flex-row md:items-center justify-between gap-2 border-b border-slate-200 pb-3">
              <div>
                <div class="flex flex-wrap items-center gap-2">
                  <span class="text-xs font-bold uppercase tracking-wider text-capi-blue">Loaded Caller KYC &amp; Credit Bureau Profile</span>
                  <span class="px-2 py-0.5 rounded-full text-[11px] font-bold"
                        [class]="isForeignPassport(lc.saIdNumber) ? 'bg-purple-100 text-purple-800 border border-purple-200' : 'bg-blue-100 text-capi-blue border border-blue-200'">
                    {{ isForeignPassport(lc.saIdNumber) ? 'Foreign National (Outside SA &bull; Passport)' : 'South African Citizen / Resident (SA ID)' }}
                  </span>
                </div>
                <div class="text-base font-bold text-slate-900 mt-0.5">
                  {{ lc.fullName }} <span class="text-xs font-normal text-slate-500">({{ lc.email }})</span>
                </div>
                <div class="text-xs text-slate-500 font-mono mt-0.5">
                  {{ isForeignPassport(lc.saIdNumber) ? 'Foreign Passport' : 'SA ID' }}: {{ lc.saIdNumber }}
                </div>
              </div>
              <span class="px-3 py-1 rounded-full text-xs font-semibold bg-amber-100 text-amber-900 border border-amber-200 self-start md:self-auto">
                Verify at least 2 details with caller on line
              </span>
            </div>

            <div class="grid grid-cols-1 md:grid-cols-2 gap-3 text-xs">
              <label class="flex items-start gap-3 p-3.5 rounded-xl bg-white border border-slate-200 cursor-pointer hover:border-capi-blue transition">
                <input type="checkbox" name="checkPhoneVerified" [(ngModel)]="checkPhoneVerified" class="mt-0.5 rounded border-slate-300" />
                <div>
                  <div class="font-bold text-slate-700">Check 1: Registered Cellphone (MSISDN)</div>
                  <div class="text-sm font-mono font-semibold text-slate-900 mt-0.5">{{ lc.phoneNumber }}</div>
                </div>
              </label>

              <label class="flex items-start gap-3 p-3.5 rounded-xl bg-white border border-slate-200 cursor-pointer hover:border-capi-blue transition">
                <input type="checkbox" name="checkAccountVerified" [(ngModel)]="checkAccountVerified" class="mt-0.5 rounded border-slate-300" />
                <div>
                  <div class="font-bold text-slate-700">Check 2: Global One Account Number</div>
                  <div class="text-sm font-mono font-semibold text-slate-900 mt-0.5">{{ lc.accountNumber }}</div>
                </div>
              </label>

              <label class="flex items-start gap-3 p-3.5 rounded-xl bg-white border border-slate-200 cursor-pointer hover:border-capi-blue transition">
                <input type="checkbox" name="checkCreditCardVerified" [(ngModel)]="checkCreditCardVerified" class="mt-0.5 rounded border-slate-300" />
                <div>
                  <div class="font-bold text-slate-700">Check 3 (Credit Bureau): Active Capi Credit Card Facility?</div>
                  <div class="text-sm font-semibold text-slate-900 mt-0.5">
                    {{ lc.hasCreditCardFacility ? 'Yes — Active Credit Card Account' : 'No — Savings / Transactional Only' }}
                  </div>
                </div>
              </label>

              <label class="flex items-start gap-3 p-3.5 rounded-xl bg-white border border-slate-200 cursor-pointer hover:border-capi-blue transition">
                <input type="checkbox" name="checkDebitOrderVerified" [(ngModel)]="checkDebitOrderVerified" class="mt-0.5 rounded border-slate-300" />
                <div>
                  <div class="font-bold text-slate-700">Check 4 (Credit Bureau / Mandate): Active DebiCheck Debit Order?</div>
                  <div class="text-sm font-semibold text-slate-900 mt-0.5">
                    {{ lc.hasActiveDebitOrderMandate ? 'Yes — Active DebiCheck Mandate' : 'No — No Active Debit Order Mandate' }}
                  </div>
                </div>
              </label>
            </div>

            <div class="flex flex-col sm:flex-row sm:items-center justify-between gap-3 pt-2 border-t border-slate-200">
              <span class="text-xs text-slate-500">
                Audit trail logs <code>CALLER_KYC_LOOKUP</code>, <code>CALLER_KYC_LOOKUP_FAILED</code>, <code>CALLER_KYC_VERIFIED</code>, and <code>CALLER_KYC_VERIFICATION_FAILED</code> (Unverified) permanently for banking investigation.
              </span>
              <div class="flex items-center gap-3">
                <button
                  id="not-verified-btn"
                  type="button"
                  [disabled]="verifying"
                  (click)="markNotVerified()"
                  class="px-5 py-2.5 rounded-xl bg-red-600 hover:bg-red-700 text-white text-sm font-semibold shadow transition disabled:opacity-50"
                >
                  Unverified
                </button>
                <button
                  id="verify-customer-btn"
                  type="button"
                  [disabled]="verifying"
                  (click)="verifyCustomer()"
                  class="px-6 py-2.5 rounded-xl bg-emerald-600 hover:bg-emerald-700 text-white text-sm font-semibold shadow transition disabled:opacity-50"
                >
                  {{ verifying ? 'Confirming Verification...' : 'Verified' }}
                </button>
              </div>
            </div>
          </div>
        }

        </div>
      }

      @if (verification; as v) {
        <div id="kyc-verified-banner" class="p-5 rounded-2xl shadow-sm bg-emerald-50 border-2 border-emerald-400 text-emerald-950 flex flex-col md:flex-row md:items-center justify-between gap-3">
          <div>
            <div class="flex flex-wrap items-center gap-2">
              <span class="text-xs font-bold uppercase tracking-wider text-emerald-700">Identity &amp; Credit Bureau Verified</span>
              <span class="px-2 py-0.5 rounded-full text-[11px] font-bold"
                    [class]="isForeignPassport(v.customer.saIdNumber) ? 'bg-purple-100 text-purple-800 border border-purple-200' : 'bg-blue-100 text-capi-blue border border-blue-200'">
                {{ isForeignPassport(v.customer.saIdNumber) ? 'Foreign National (Outside SA &bull; Passport)' : 'South African Citizen / Resident (SA ID)' }}
              </span>
            </div>
            <div class="text-base font-bold mt-0.5">
              {{ v.customer.fullName }} &mdash; {{ isForeignPassport(v.customer.saIdNumber) ? 'Foreign Passport' : 'SA ID' }} {{ v.customer.saIdNumber }} &bull; Acct {{ v.customer.accountNumber }}
            </div>
            <div class="text-xs text-emerald-800 mt-1">
              {{ v.verificationSummary }} &bull; {{ v.creditBureauProfileSummary }}
            </div>
          </div>
          <div class="flex flex-wrap items-center gap-2">
            <span class="px-3 py-1.5 rounded-lg bg-emerald-600 text-white text-xs font-bold whitespace-nowrap">
              {{ v.transactions.length }} Customer Transactions Unlocked
            </span>
            <button
              id="reset-verified-caller-btn"
              type="button"
              (click)="resetVerification()"
              class="px-3 py-1.5 rounded-lg bg-white hover:bg-red-50 border border-red-200 text-red-700 text-xs font-semibold whitespace-nowrap transition"
            >
              Verify Another Caller
            </button>
          </div>
        </div>
      }

      <!-- STEP 2 & STEP 3: Unlocked only after Caller Verification -->
      @if (verification; as verifiedData) {
        <!-- STEP 2: Verified Customer Transactions -->
        <div id="verified-customer-transactions" class="bg-white rounded-2xl shadow-sm border border-slate-200 overflow-hidden">
          <div class="px-6 py-4 border-b border-slate-100 flex flex-col sm:flex-row sm:items-center justify-between gap-2">
            <div>
              <div class="flex items-center gap-2">
                <span class="px-2.5 py-0.5 rounded-full text-xs font-bold bg-indigo-100 text-indigo-800">STEP 2</span>
                <h2 class="text-lg font-bold text-slate-900">Verified Caller Account Transactions ({{ verifiedData.customer.fullName }})</h2>
              </div>
              <p class="text-xs text-slate-500 mt-0.5">Select a transaction below to pre-fill the Dispute Capture Form, or enter custom transaction details manually below.</p>
            </div>
            <div class="flex items-center gap-3">
              <span class="text-xs font-mono text-slate-500">{{ verifiedData.transactions.length }} transactions</span>
              @if (selectedTx) {
                <button
                  type="button"
                  (click)="clearSelectedTransaction()"
                  class="px-3 py-1.5 rounded-lg border border-slate-300 text-slate-700 hover:bg-slate-50 text-xs font-semibold"
                >
                  Clear Selected TXN
                </button>
              }
            </div>
          </div>

          <div class="overflow-x-auto">
            <table class="w-full text-sm text-left">
              <thead class="bg-slate-50 text-xs uppercase text-slate-500 border-b border-slate-200">
                <tr>
                  <th class="px-5 py-3">Reference</th>
                  <th class="px-5 py-3">Date &amp; Time</th>
                  <th class="px-5 py-3">Merchant / Description</th>
                  <th class="px-5 py-3">Transaction Type</th>
                  <th class="px-5 py-3">Amount</th>
                  <th class="px-5 py-3">Status</th>
                  <th class="px-5 py-3 text-right">Assist</th>
                </tr>
              </thead>
              <tbody class="divide-y divide-slate-100">
                @for (tx of verifiedData.transactions; track tx.id) {
                  <tr [class]="selectedTx?.id === tx.id ? 'bg-blue-50/70' : 'hover:bg-slate-50 transition'">
                    <td class="px-5 py-3 font-mono text-xs font-bold text-slate-800">{{ tx.transactionReference }}</td>
                    <td class="px-5 py-3 text-xs text-slate-600">{{ tx.transactedAt | date:'medium' }}</td>
                    <td class="px-5 py-3">
                      <div class="font-semibold text-slate-900">{{ tx.merchantName }}</div>
                      <div class="text-xs text-slate-500">{{ tx.location }}</div>
                    </td>
                    <td class="px-5 py-3">
                      <span class="px-2 py-0.5 rounded bg-slate-100 text-slate-700 text-xs font-mono">{{ tx.paymentRail }}</span>
                    </td>
                    <td class="px-5 py-3 font-bold text-slate-900">R {{ tx.amount | number:'1.2-2' }}</td>
                    <td class="px-5 py-3 text-xs font-semibold text-emerald-700">{{ tx.status || 'SETTLED' }}</td>
                    <td class="px-5 py-3 text-right">
                      <button
                        type="button"
                        (click)="selectTransactionForDispute(tx)"
                        [class]="selectedTx?.id === tx.id
                          ? 'px-3 py-1.5 rounded-lg bg-capi-blue text-white text-xs font-semibold'
                          : 'px-3 py-1.5 rounded-lg bg-capi-red hover:bg-red-700 text-white text-xs font-semibold transition'"
                      >
                        {{ selectedTx?.id === tx.id ? 'Selected' : 'Log Dispute on Behalf' }}
                      </button>
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        </div>

        <!-- STEP 3: Call Centre Assisted Dispute Capture -->
        <div class="space-y-4">
          <div class="flex items-center gap-2">
            <span class="px-2.5 py-0.5 rounded-full text-xs font-bold bg-purple-100 text-purple-800">STEP 3</span>
            <h2 class="text-lg font-bold text-slate-900">Call Centre Assisted Dispute Capture</h2>
          </div>

          @if (errorMsg) {
            <div class="p-4 rounded-xl bg-red-50 border border-red-300 text-red-800 text-sm font-medium">
              {{ errorMsg }}
            </div>
          }

          <div class="grid grid-cols-1 lg:grid-cols-2 gap-6">
              <div id="capture-dispute-panel" class="bg-white rounded-2xl shadow-sm border border-slate-200 p-6 space-y-5">
                <div class="flex items-center justify-between border-b border-slate-100 pb-3">
                  <div>
                    <h2 class="text-lg font-bold text-slate-900">Capture Dispute Details (On Behalf of Customer)</h2>
                    <p class="text-xs text-slate-500">Matches online customer dispute capture with Transaction Type &amp; Location.</p>
                  </div>
                  <span class="px-2.5 py-1 rounded-full bg-emerald-100 text-emerald-800 text-xs font-bold">KYC Verified</span>
                </div>

                <form (ngSubmit)="submitVoiceCall()" class="space-y-4">
                  <div class="grid grid-cols-1 sm:grid-cols-2 gap-4">
                    <div>
                      <label class="block text-xs font-semibold text-slate-700 mb-1">Verified Caller CLI Number</label>
                      <input
                        type="text"
                        name="callerPhone"
                        [(ngModel)]="callerPhone"
                        readonly
                        class="w-full px-3.5 py-2 rounded-lg border border-slate-200 bg-slate-50 text-sm font-mono text-slate-700"
                      />
                    </div>
                    <div>
                      <label class="block text-xs font-semibold text-slate-700 mb-1">Transaction Reference (Optional for unlinked)</label>
                      <input
                        type="text"
                        name="voiceTxRef"
                        [(ngModel)]="voiceTxRef"
                        placeholder="Select row in Step 2 or leave blank"
                        class="w-full px-3.5 py-2 rounded-lg border border-slate-300 text-sm font-mono outline-none focus:ring-2 focus:ring-capi-blue"
                      />
                    </div>
                  </div>

                  <div class="grid grid-cols-1 sm:grid-cols-2 gap-4">
                    <div>
                      <label for="assisted-merchant-input" class="block text-xs font-semibold text-slate-700 mb-1">
                        Merchant / Description <span class="text-capi-red">*</span>
                      </label>
                      <input
                        id="assisted-merchant-input"
                        type="text"
                        name="voiceMerchantName"
                        required
                        [(ngModel)]="voiceMerchantName"
                        placeholder="e.g. Shell Braamfontein"
                        class="w-full px-3.5 py-2 rounded-lg border border-slate-300 text-sm outline-none focus:ring-2 focus:ring-capi-blue"
                      />
                    </div>

                    <div>
                      <label for="assisted-transaction-type-select" class="block text-xs font-semibold text-slate-700 mb-1">
                        Transaction Type <span class="text-capi-red">*</span>
                      </label>
                      <select
                        id="assisted-transaction-type-select"
                        name="voicePaymentRail"
                        [(ngModel)]="voicePaymentRail"
                        class="w-full px-3.5 py-2 rounded-lg border border-slate-300 text-sm bg-white outline-none focus:ring-2 focus:ring-capi-blue"
                      >
                        <option value="CARD_POS">Card Payment (POS / Speedpoint)</option>
                        <option value="ATM_WITHDRAWAL">ATM (Cash Withdrawal / Deposit)</option>
                        <option value="EFT">EFT (Electronic Funds Transfer)</option>
                        <option value="DEBIT_ORDER">Debit Order / DebiCheck</option>
                        <option value="PAYSHAP">PayShap Instant Payment</option>
                        <option value="ONLINE_ECOMMERCE">Online Card Payment (E-Commerce)</option>
                      </select>
                    </div>
                  </div>

                  <div class="grid grid-cols-1 sm:grid-cols-2 gap-4">
                    <div>
                      <label for="assisted-location-input" class="block text-xs font-semibold text-slate-700 mb-1">
                        Location (Optional)
                      </label>
                      <input
                        id="assisted-location-input"
                        type="text"
                        name="voiceLocation"
                        [(ngModel)]="voiceLocation"
                        placeholder="e.g. Sandton City Mall, Gauteng (Optional)"
                        class="w-full px-3.5 py-2 rounded-lg border border-slate-300 text-sm outline-none focus:ring-2 focus:ring-capi-blue"
                      />
                    </div>

                    <div>
                      <label for="assisted-amount-input" class="block text-xs font-semibold text-slate-700 mb-1">
                        Disputed Amount (ZAR) <span class="text-capi-red">*</span>
                      </label>
                      <input
                        id="assisted-amount-input"
                        type="number"
                        step="0.01"
                        min="0.01"
                        name="voiceAmount"
                        required
                        [(ngModel)]="voiceAmount"
                        [readonly]="selectedTx !== null && voiceTxRef.trim() === selectedTx.transactionReference"
                        class="w-full px-3.5 py-2 rounded-lg border border-slate-300 text-sm font-semibold outline-none focus:ring-2 focus:ring-capi-blue read-only:bg-slate-100 read-only:text-slate-700"
                      />
                    </div>
                  </div>

                  <div>
                    <label for="assisted-category-select" class="block text-xs font-semibold text-slate-700 mb-1">Dispute Category</label>
                    <select
                      id="assisted-category-select"
                      name="voiceCategory"
                      [(ngModel)]="voiceCategory"
                      class="w-full px-3.5 py-2 rounded-lg border border-slate-300 text-sm bg-white outline-none focus:ring-2 focus:ring-capi-blue"
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
                    <label for="voice-statement-input" class="block text-xs font-semibold text-slate-700 mb-1">Statement of Dispute</label>
                    <textarea
                      id="voice-statement-input"
                      name="voiceStatement"
                      rows="3"
                      required
                      [(ngModel)]="voiceStatement"
                      placeholder="Explain why the verified caller is disputing this transaction..."
                      class="w-full px-3.5 py-2 rounded-lg border border-slate-300 text-sm outline-none focus:ring-2 focus:ring-capi-blue"
                    ></textarea>
                  </div>

                  <div>
                    <label for="assisted-file-input" class="block text-xs font-semibold text-slate-700 mb-1">
                      Supporting Evidence Documents (Optional &mdash; Up to 5 files: PDF, PNG, JPG &mdash; Max 5MB each)
                    </label>
                    <input
                      id="assisted-file-input"
                      type="file"
                      multiple
                      accept=".pdf,.png,.jpg,.jpeg,application/pdf,image/png,image/jpeg"
                      (change)="onFilesSelected($event)"
                      class="block w-full text-sm text-slate-600 file:mr-4 file:py-2 file:px-4 file:rounded-lg file:border-0 file:text-xs file:font-semibold file:bg-blue-50 file:text-capi-blue hover:file:bg-blue-100"
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
                    {{ submitting ? 'Submitting Dispute & Computing SHA-256...' : 'Complete Call & Log Dispute on Behalf' }}
                  </button>
                </form>
              </div>

              <div class="bg-slate-900 rounded-2xl p-6 text-white shadow-xl flex flex-col justify-between space-y-5">
                <div class="space-y-4">
                  <div class="flex items-center justify-between border-b border-slate-800 pb-4">
                    <div>
                      <div class="text-xs uppercase tracking-wider text-blue-300 font-bold">Fraud &amp; Disputes Voice Desk</div>
                      <div class="text-lg font-bold mt-0.5">0860 10 20 43 &mdash; Assisted Intake</div>
                    </div>
                    <span class="px-3 py-1 rounded-full bg-red-600/20 border border-red-500/40 text-red-300 text-xs font-mono">
                      REC &bull; {{ verifiedData.customer.fullName }}
                    </span>
                  </div>

                  <div class="p-4 rounded-xl bg-slate-800/80 border border-slate-700 text-xs space-y-1.5">
                    <div><strong>KYC Check:</strong> {{ verifiedData.verificationSummary }}</div>
                    <div><strong>Transaction Type:</strong> {{ voicePaymentRail }}</div>
                    <div><strong>Merchant:</strong> {{ voiceMerchantName || 'Not specified' }}</div>
                    <div><strong>Location:</strong> {{ voiceLocation || 'Optional (none entered)' }}</div>
                    <div><strong>Target TXN Ref:</strong> {{ voiceTxRef || 'Unlinked / Manual Assisted Entry' }}</div>
                    <div><strong>Precautionary Card Hold:</strong> {{ cardFreezeRequested ? 'YES (Requested)' : 'No' }}</div>
                  </div>

                  @if (lastResult && lastResult.channel === 'PHONE_CALL') {
                    <div id="voice-call-result" class="p-4 rounded-xl bg-emerald-950/60 border border-emerald-500/50 text-xs space-y-3">
                      <div class="text-emerald-400 font-bold text-sm">Call Dispute Logged: {{ lastResult.dispute.disputeReference }}</div>
                      <p class="text-emerald-100">{{ lastResult.outboundReply }}</p>
                      <div class="text-emerald-200 font-mono">
                        Status: {{ lastResult.dispute.status }} &bull; Assigned: {{ lastResult.dispute.assignedUserName || 'You' }} &bull; Attachments: {{ lastResult.dispute.attachments.length }}
                      </div>
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
                      Select any transaction above or enter custom transaction details, then click <strong>Complete Call &amp; Log Dispute on Behalf</strong>.
                    </div>
                  }
                </div>

                <div class="pt-3 border-t border-slate-800 text-[11px] text-slate-400 flex items-center justify-between">
                  <span>Channel: PHONE_CALL</span>
                  <span>Auto-assigned to current Call Centre Agent</span>
                </div>
              </div>
            </div>
        </div>
      } @else {
        <div class="bg-slate-100 border border-dashed border-slate-300 rounded-2xl p-8 text-center text-sm text-slate-600">
          Capture the caller's SA ID number above, click <strong>Load Customer Details</strong>, and confirm identity (<strong>Verified</strong>) to unlock customer transactions and dispute capture.
        </div>
      }
    </div>
  `
})
export class OmnichannelSimulatorComponent {
  // Step 1: SA ID capture -> lookup -> Verified / Not Verified
  saIdNumber = '';
  loadingLookup = false;
  loadedCustomer: CustomerKycLookupResponse | null = null;
  checkPhoneVerified = true;
  checkAccountVerified = true;
  checkCreditCardVerified = true;
  checkDebitOrderVerified = true;

  verifying = false;
  kycError = '';
  verification: CustomerCallerVerificationResponse | null = null;
  selectedTx: BankTransaction | null = null;

  // Step 3: Call Centre Assisted intake state
  callerPhone = '';
  voiceTxRef = '';
  voiceMerchantName = '';
  voicePaymentRail = 'CARD_POS';
  voiceLocation = '';
  voiceAmount: number | null = null;
  voiceScenario = 'CALL_CENTRE_ASSISTED';
  voiceCategory: DisputeCategory = 'UNAUTHORIZED_TRANSACTION';
  voiceStatement = '';
  voiceAgentNotes = 'Verified caller identity via SA ID and security/credit-bureau checks.';
  selectedFiles: File[] = [];
  cardFreezeRequested = false;

  submitting = false;
  errorMsg = '';
  lastResult: OmnichannelSimulationResponse | null = null;

  constructor(private api: PortalApiService, public auth: AuthService) {}

  onSaIdInputChange(): void {
    this.kycError = '';
    const trimmed = this.saIdNumber.trim().toLowerCase();
    if (this.loadedCustomer && this.loadedCustomer.saIdNumber.toLowerCase() !== trimmed) {
      this.loadedCustomer = null;
    }
    if (this.verification && this.verification.customer.saIdNumber.toLowerCase() !== trimmed) {
      this.verification = null;
      this.selectedTx = null;
      this.lastResult = null;
    }
  }

  isForeignPassport(idNumber: string | null | undefined): boolean {
    const val = (idNumber || '').trim();
    return /[A-Za-z]/.test(val) || val.length !== 13;
  }

  applyKycPreset(customer: 'THABO' | 'NALEDI' | 'TENDAI' | 'CHIDINMA'): void {
    this.resetVerification();
    switch (customer) {
      case 'THABO':
        this.saIdNumber = '8501015800082';
        break;
      case 'NALEDI':
        this.saIdNumber = '9104120842087';
        break;
      case 'TENDAI':
        this.saIdNumber = 'ZW4829104';
        break;
      case 'CHIDINMA':
        this.saIdNumber = 'NG7739201';
        break;
    }
  }

  loadCustomerDetails(): void {
    const trimmed = this.saIdNumber.trim();
    if (!trimmed) return;
    this.loadingLookup = true;
    this.kycError = '';
    this.verification = null;
    this.selectedTx = null;
    this.lastResult = null;
    this.api.lookupCustomerBySaId(trimmed).subscribe({
      next: (profile) => {
        this.loadingLookup = false;
        this.loadedCustomer = profile;
        this.checkPhoneVerified = true;
        this.checkAccountVerified = true;
        this.checkCreditCardVerified = true;
        this.checkDebitOrderVerified = true;
      },
      error: (err) => {
        this.loadingLookup = false;
        this.loadedCustomer = null;
        this.kycError = err.error?.message || 'No customer found for that SA ID number.';
      }
    });
  }

  verifyCustomer(): void {
    if (!this.loadedCustomer) return;
    this.verifying = true;
    this.kycError = '';
    this.errorMsg = '';
    this.lastResult = null;

    this.api.verifyCustomerCaller({
      saIdNumber: this.loadedCustomer.saIdNumber,
      phoneNumber: this.checkPhoneVerified ? this.loadedCustomer.phoneNumber : undefined,
      accountNumber: this.checkAccountVerified ? this.loadedCustomer.accountNumber : undefined,
      hasCreditCardFacility: this.checkCreditCardVerified ? this.loadedCustomer.hasCreditCardFacility : null,
      hasActiveDebitOrderMandate: this.checkDebitOrderVerified ? this.loadedCustomer.hasActiveDebitOrderMandate : null
    }).subscribe({
      next: (res) => {
        this.verifying = false;
        this.loadedCustomer = null;
        this.verification = res;
        this.callerPhone = res.customer.phoneNumber;
        if (res.transactions.length > 0) {
          this.selectTransactionForDispute(res.transactions[0]);
        }
      },
      error: (err) => {
        this.verifying = false;
        this.verification = null;
        this.selectedTx = null;
        this.kycError = err.error?.message || 'Caller verification failed.';
      }
    });
  }

  markNotVerified(): void {
    const saId = this.loadedCustomer?.saIdNumber || this.saIdNumber.trim();
    if (!saId) return;
    this.verifying = true;
    this.verification = null;
    this.selectedTx = null;
    this.lastResult = null;
    this.api.markCallerNotVerified(saId, 'Caller failed identity verification during telephone call').subscribe({
      next: () => {
        this.verifying = false;
        this.kycError = 'Caller Unverified: Customer failed telephone identity verification. Account transactions and dispute logging remain locked.';
      },
      error: (err) => {
        this.verifying = false;
        this.kycError = err.error?.message || 'Caller marked as Unverified. Account transactions remain locked.';
      }
    });
  }

  resetVerification(): void {
    this.loadedCustomer = null;
    this.verification = null;
    this.selectedTx = null;
    this.lastResult = null;
    this.kycError = '';
    this.errorMsg = '';
    this.selectedFiles = [];
  }

  selectTransactionForDispute(tx: BankTransaction): void {
    this.selectedTx = tx;
    this.voiceTxRef = tx.transactionReference;
    this.voiceMerchantName = tx.merchantName;
    this.voicePaymentRail = tx.paymentRail;
    this.voiceLocation = tx.location || '';
    this.voiceAmount = tx.amount;
    this.voiceStatement = `Caller verified on call; disputation requested for ${tx.merchantName} (${tx.transactionReference}, R${tx.amount.toFixed(2)}).`;
    if (tx.paymentRail === 'ATM_WITHDRAWAL') {
      this.voiceCategory = 'ATM_DISPENSE_ERROR';
    } else if (tx.paymentRail === 'PAYSHAP') {
      this.voiceCategory = 'PAYSHAP_WRONG_RECIPIENT';
    } else if (tx.paymentRail === 'DEBIT_ORDER') {
      this.voiceCategory = 'DUPLICATE_CHARGE';
    } else {
      this.voiceCategory = 'UNAUTHORIZED_TRANSACTION';
    }
  }

  clearSelectedTransaction(): void {
    this.selectedTx = null;
    this.voiceTxRef = '';
    this.voiceMerchantName = '';
    this.voiceLocation = '';
    this.voiceAmount = null;
    this.voiceStatement = '';
  }

  onFilesSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    if (!input.files) return;
    const files = Array.from(input.files);
    if (files.length > 5) {
      this.errorMsg = 'Maximum of 5 supporting evidence files can be uploaded.';
      return;
    }
    this.selectedFiles = files;
    this.errorMsg = '';
  }

  submitVoiceCall(): void {
    const trimmedRef = this.voiceTxRef.trim();
    const matchedTx = this.selectedTx && this.selectedTx.transactionReference === trimmedRef ? this.selectedTx : null;
    if (!matchedTx && (!this.voiceMerchantName.trim() || !this.voiceAmount || this.voiceAmount <= 0)) {
      this.errorMsg = 'Please select a transaction from Step 2 or enter a valid Merchant Description and Disputed Amount.';
      return;
    }
    if (!this.voiceStatement || this.voiceStatement.trim().length < 5) {
      this.errorMsg = 'Please provide a clear statement of dispute (at least 5 characters).';
      return;
    }

    this.submitting = true;
    this.errorMsg = '';
    this.api.simulateVoiceCall({
      callerPhoneNumber: this.callerPhone.trim(),
      transactionId: matchedTx ? matchedTx.id : null,
      transactionReference: trimmedRef || null,
      merchantName: this.voiceMerchantName.trim() || undefined,
      paymentRail: this.voicePaymentRail,
      location: this.voiceLocation.trim() || undefined,
      amount: this.voiceAmount ?? undefined,
      scenario: this.voiceScenario,
      category: this.voiceCategory,
      callerStatement: this.voiceStatement.trim(),
      agentNotes: this.voiceAgentNotes.trim(),
      cardFreezeRequested: this.cardFreezeRequested,
      idempotencyKey: 'sim-call-' + Date.now()
    }).subscribe({
      next: (res) => {
        if (this.selectedFiles.length > 0) {
          this.api.uploadAttachments(res.dispute.id, this.selectedFiles).subscribe({
            next: (uploaded) => {
              this.submitting = false;
              res.dispute.attachments = uploaded;
              this.selectedFiles = [];
              this.lastResult = res;
            },
            error: () => {
              this.submitting = false;
              this.lastResult = res;
            }
          });
        } else {
          this.submitting = false;
          this.lastResult = res;
        }
      },
      error: (err) => {
        this.submitting = false;
        this.errorMsg = err.error?.message || 'Failed to log assisted dispute.';
      }
    });
  }
}
