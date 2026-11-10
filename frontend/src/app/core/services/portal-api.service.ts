import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import {
  AuditLogEntry,
  BankTransaction,
  DisputeCategory,
  DisputeDetail,
  DisputeStatus,
  DisputeSummary,
  DisputeAttachment,
  OmnichannelSimulationResponse,
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

  getDisputes(): Observable<DisputeSummary[]> {
    return this.http.get<DisputeSummary[]>('/api/v1/disputes');
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

  getAuditLogs(eventType?: string): Observable<AuditLogEntry[]> {
    const query = eventType ? `?eventType=${encodeURIComponent(eventType)}` : '';
    return this.http.get<AuditLogEntry[]>(`/api/v1/admin/audit-logs${query}`);
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

