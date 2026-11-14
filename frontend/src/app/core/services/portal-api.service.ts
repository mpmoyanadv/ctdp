import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { map, Observable } from 'rxjs';
import {
  AuditLogEntry,
  BankTransaction,
  DisputeCategory,
  DisputeDetail,
  DisputeStatus,
  DisputeSummary,
  DisputeAttachment,
  OmnichannelSimulationResponse,
  PageResponse,
  ProductivityReport,
  SmsWebhookSimulationRequest,
  UserDomain,
  UserProfile,
  VoiceCallSimulationRequest
} from '../models/portal.models';

@Injectable({ providedIn: 'root' })
export class PortalApiService {
  constructor(private http: HttpClient) {}

  getTransactions(): Observable<BankTransaction[]> {
    return this.http.get<BankTransaction[]>('/api/v1/transactions');
  }

  getDisputesPage(page = 0, size = 50, status?: DisputeStatus): Observable<PageResponse<DisputeSummary>> {
    let params = new HttpParams().set('page', page).set('size', size);
    if (status) {
      params = params.set('status', status);
    }
    return this.http.get<PageResponse<DisputeSummary>>('/api/v1/disputes', { params });
  }

  getDisputes(page = 0, size = 50, status?: DisputeStatus): Observable<DisputeSummary[]> {
    return this.getDisputesPage(page, size, status).pipe(map((res) => res.content));
  }

  getDisputeById(id: string): Observable<DisputeDetail> {
    return this.http.get<DisputeDetail>(`/api/v1/disputes/${id}`);
  }

  createDisputeMultipart(formData: FormData): Observable<DisputeDetail> {
    return this.http.post<DisputeDetail>('/api/v1/disputes', formData);
  }

  uploadAttachments(disputeId: string, files: File[]): Observable<DisputeAttachment[]> {
    const fd = new FormData();
    files.forEach((f) => fd.append('files', f, f.name));
    return this.http.post<DisputeAttachment[]>(`/api/v1/disputes/${disputeId}/attachments`, fd);
  }

  downloadAttachment(disputeId: string, attachmentId: string): Observable<Blob> {
    return this.http.get(`/api/v1/disputes/${disputeId}/attachments/${attachmentId}`, {
      responseType: 'blob'
    });
  }

  claimDispute(disputeId: string): Observable<DisputeDetail> {
    return this.http.post<DisputeDetail>(`/api/v1/disputes/${disputeId}/claim`, {});
  }

  addNote(
    disputeId: string,
    message: string,
    internalOnly: boolean,
    requiresCustomerReply: boolean
  ): Observable<DisputeDetail> {
    return this.http.post<DisputeDetail>(`/api/v1/disputes/${disputeId}/notes`, {
      message,
      internalOnly,
      requiresCustomerReply
    });
  }

  escalateDispute(disputeId: string, reason?: string): Observable<DisputeDetail> {
    return this.http.post<DisputeDetail>(`/api/v1/disputes/${disputeId}/escalate`, {
      reason: reason || 'Escalated to Supervisor Queue'
    });
  }

  delegateDispute(disputeId: string, partnerDomain: UserDomain, note: string): Observable<DisputeDetail> {
    return this.http.post<DisputeDetail>(`/api/v1/disputes/${disputeId}/delegate`, {
      partnerDomain,
      note
    });
  }

  updateStatus(
    disputeId: string,
    status: DisputeStatus,
    resolutionSummary: string
  ): Observable<DisputeDetail> {
    return this.http.patch<DisputeDetail>(`/api/v1/disputes/${disputeId}/status`, {
      status,
      resolutionSummary
    });
  }

  reopenDispute(
    disputeId: string,
    reason: string,
    category?: DisputeCategory
  ): Observable<DisputeDetail> {
    return this.http.post<DisputeDetail>(`/api/v1/disputes/${disputeId}/reopen`, {
      reason,
      category
    });
  }

  // Admin endpoints
  getProductivityReport(): Observable<ProductivityReport> {
    return this.http.get<ProductivityReport>('/api/v1/admin/reports/productivity');
  }

  getAuditLogsPage(eventType?: string, page = 0, size = 100): Observable<PageResponse<AuditLogEntry>> {
    let params = new HttpParams().set('page', page).set('size', size);
    if (eventType) {
      params = params.set('eventType', eventType);
    }
    return this.http.get<PageResponse<AuditLogEntry>>('/api/v1/admin/audit-logs', { params });
  }

  getAuditLogs(eventType?: string, page = 0, size = 100): Observable<AuditLogEntry[]> {
    return this.getAuditLogsPage(eventType, page, size).pipe(map((res) => res.content));
  }

  exportAuditCsv(): Observable<Blob> {
    return this.http.get('/api/v1/admin/audit-logs/export', {
      responseType: 'blob'
    });
  }

  getAdminUsers(): Observable<UserProfile[]> {
    return this.http.get<UserProfile[]>('/api/v1/admin/users');
  }

  unlockUser(userId: string): Observable<UserProfile> {
    return this.http.post<UserProfile>(`/api/v1/admin/users/${userId}/unlock`, {});
  }

  adminAssignDispute(
    disputeId: string,
    assigneeUserId: string | null,
    assignedDomain: UserDomain,
    status?: DisputeStatus
  ): Observable<DisputeDetail> {
    return this.http.post<DisputeDetail>(`/api/v1/admin/disputes/${disputeId}/assign`, {
      assigneeUserId,
      assignedDomain,
      status
    });
  }

  simulateSmsWebhook(payload: SmsWebhookSimulationRequest): Observable<OmnichannelSimulationResponse> {
    return this.http.post<OmnichannelSimulationResponse>('/api/v1/omnichannel/sms-webhook', payload);
  }

  simulateVoiceCall(payload: VoiceCallSimulationRequest): Observable<OmnichannelSimulationResponse> {
    return this.http.post<OmnichannelSimulationResponse>('/api/v1/omnichannel/voice-call', payload);
  }
}

