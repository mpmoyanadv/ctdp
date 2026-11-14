import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { PortalApiService } from './portal-api.service';
import { AuditLogEntry, DisputeSummary, PageResponse } from '../models/portal.models';

describe('PortalApiService', () => {
  let service: PortalApiService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()]
    });
    service = TestBed.inject(PortalApiService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('requests paginated disputes with status filter and unwraps content in getDisputes', () => {
    const sampleDispute: DisputeSummary = {
      id: 'disp-1',
      disputeReference: 'DSP-1001',
      transactionId: 'tx-1',
      merchantName: 'Checkers',
      paymentRail: 'CARD',
      status: 'LOGGED',
      terminal: false,
      category: 'UNAUTHORIZED_TRANSACTION',
      amount: 249.5,
      reason: 'Unknown charge',
      resolutionSummary: null,
      assignedDomain: 'INTERNAL_CAPI',
      assignedUserId: null,
      assignedUserName: null,
      originalDisputeId: null,
      originalDisputeReference: null,
      customer: {
        id: 'cust-1',
        fullName: 'Test Customer',
        email: 'customer@capi.test',
        saIdNumber: '9001015009087',
        phoneNumber: '+27820000001',
        accountNumber: '1000000001',
        piiMasked: false
      },
      attachmentCount: 1,
      sourceChannel: 'WEB_PORTAL',
      contactChannelMetadata: null,
      createdAt: '2026-11-14T10:00:00Z',
      resolvedAt: null
    };
    const page: PageResponse<DisputeSummary> = {
      content: [sampleDispute],
      page: 1,
      size: 25,
      totalElements: 1,
      totalPages: 1,
      first: false,
      last: true
    };

    service.getDisputes(1, 25, 'LOGGED').subscribe((items) => {
      expect(items.length).toBe(1);
      expect(items[0].disputeReference).toBe('DSP-1001');
    });

    const req = httpMock.expectOne((r) => r.url === '/api/v1/disputes');
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('page')).toBe('1');
    expect(req.request.params.get('size')).toBe('25');
    expect(req.request.params.get('status')).toBe('LOGGED');
    req.flush(page);
  });

  it('requests paginated audit logs and unwraps content in getAuditLogs', () => {
    const sampleLog: AuditLogEntry = {
      id: 1,
      actorId: 'admin-1',
      actorEmail: 'admin@capi.test',
      severity: 'INFO',
      eventType: 'DISPUTE_STATUS_CHANGED',
      entityName: 'DISPUTE',
      entityId: 'disp-1',
      ipAddress: '127.0.0.1',
      metadata: 'Updated status',
      timestamp: '2026-11-14T10:05:00Z'
    };
    const page: PageResponse<AuditLogEntry> = {
      content: [sampleLog],
      page: 0,
      size: 50,
      totalElements: 1,
      totalPages: 1,
      first: true,
      last: true
    };

    service.getAuditLogs('DISPUTE_STATUS_CHANGED', 0, 50).subscribe((logs) => {
      expect(logs).toEqual([sampleLog]);
    });

    const req = httpMock.expectOne((r) => r.url === '/api/v1/admin/audit-logs');
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('eventType')).toBe('DISPUTE_STATUS_CHANGED');
    expect(req.request.params.get('page')).toBe('0');
    expect(req.request.params.get('size')).toBe('50');
    req.flush(page);
  });
});
