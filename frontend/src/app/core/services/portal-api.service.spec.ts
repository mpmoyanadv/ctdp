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

  it('fetches customer transactions from /api/v1/transactions', () => {
    service.getTransactions().subscribe((txs) => {
      expect(txs.length).toBe(1);
      expect(txs[0].transactionReference).toBe('TXN-2026-9001');
    });

    const req = httpMock.expectOne('/api/v1/transactions');
    expect(req.request.method).toBe('GET');
    req.flush([
      {
        id: 'tx-1',
        transactionReference: 'TXN-2026-9001',
        merchantName: 'Shell Braamfontein',
        paymentRail: 'CARD_POS',
        amount: 850,
        currency: 'ZAR',
        cardLastFour: '4821',
        location: 'Braamfontein',
        transactedAt: '2026-11-14T08:00:00Z'
      }
    ]);
  });

  it('handles dispute detail fetch, multipart creation, and attachment upload/download', () => {
    service.getDisputeById('disp-1').subscribe((d) => expect(d.id).toBe('disp-1'));
    const detailReq = httpMock.expectOne('/api/v1/disputes/disp-1');
    expect(detailReq.request.method).toBe('GET');
    detailReq.flush({ id: 'disp-1' });

    const fd = new FormData();
    fd.append('merchantName', 'Checkers');
    service.createDisputeMultipart(fd).subscribe((d) => expect(d.id).toBe('disp-2'));
    const createReq = httpMock.expectOne('/api/v1/disputes');
    expect(createReq.request.method).toBe('POST');
    createReq.flush({ id: 'disp-2' });

    const file = new File(['%PDF-1.4'], 'receipt.pdf', { type: 'application/pdf' });
    service.uploadAttachments('disp-1', [file]).subscribe((atts) => expect(atts.length).toBe(1));
    const uploadReq = httpMock.expectOne('/api/v1/disputes/disp-1/attachments');
    expect(uploadReq.request.method).toBe('POST');
    uploadReq.flush([{ id: 'att-1' }]);

    service.downloadAttachment('disp-1', 'att-1').subscribe((blob) => expect(blob.size).toBe(3));
    const downloadReq = httpMock.expectOne('/api/v1/disputes/disp-1/attachments/att-1');
    expect(downloadReq.request.method).toBe('GET');
    expect(downloadReq.request.responseType).toBe('blob');
    downloadReq.flush(new Blob(['pdf']));
  });

  it('invokes claim, note, and escalate dispute workflow endpoints', () => {
    service.claimDispute('disp-1').subscribe((d) => expect(d.status).toBe('IN_REVIEW'));
    const claimReq = httpMock.expectOne('/api/v1/disputes/disp-1/claim');
    expect(claimReq.request.method).toBe('POST');
    claimReq.flush({ id: 'disp-1', status: 'IN_REVIEW' });

    service.addNote('disp-1', 'Need statement', false, true).subscribe((d) => expect(d.status).toBe('PENDING_CUSTOMER_ACTION'));
    const noteReq = httpMock.expectOne('/api/v1/disputes/disp-1/notes');
    expect(noteReq.request.method).toBe('POST');
    expect(noteReq.request.body).toEqual({
      message: 'Need statement',
      internalOnly: false,
      requiresCustomerReply: true
    });
    noteReq.flush({ id: 'disp-1', status: 'PENDING_CUSTOMER_ACTION' });

    service.escalateDispute('disp-1').subscribe((d) => expect(d.status).toBe('ESCALATED'));
    const escReq = httpMock.expectOne('/api/v1/disputes/disp-1/escalate');
    expect(escReq.request.method).toBe('POST');
    expect(escReq.request.body).toEqual({ reason: 'Escalated to Supervisor Queue' });
    escReq.flush({ id: 'disp-1', status: 'ESCALATED' });
  });

  it('invokes delegate, status update, and reopen endpoints', () => {
    service.delegateDispute('disp-1', 'PARTNER_BANKSERV', 'Clearing trace').subscribe((d) =>
      expect(d.assignedDomain).toBe('PARTNER_BANKSERV')
    );
    const delReq = httpMock.expectOne('/api/v1/disputes/disp-1/delegate');
    expect(delReq.request.method).toBe('POST');
    expect(delReq.request.body).toEqual({ partnerDomain: 'PARTNER_BANKSERV', note: 'Clearing trace' });
    delReq.flush({ id: 'disp-1', assignedDomain: 'PARTNER_BANKSERV' });

    service.updateStatus('disp-1', 'REFUNDED', 'Approved reversal').subscribe((d) =>
      expect(d.status).toBe('REFUNDED')
    );
    const statusReq = httpMock.expectOne('/api/v1/disputes/disp-1/status');
    expect(statusReq.request.method).toBe('PATCH');
    expect(statusReq.request.body).toEqual({ status: 'REFUNDED', resolutionSummary: 'Approved reversal' });
    statusReq.flush({ id: 'disp-1', status: 'REFUNDED' });

    service.reopenDispute('disp-1', 'New bank statement', 'DUPLICATE_CHARGE').subscribe((d) =>
      expect(d.status).toBe('LOGGED')
    );
    const reopenReq = httpMock.expectOne('/api/v1/disputes/disp-1/reopen');
    expect(reopenReq.request.method).toBe('POST');
    expect(reopenReq.request.body).toEqual({ reason: 'New bank statement', category: 'DUPLICATE_CHARGE' });
    reopenReq.flush({ id: 'disp-2', status: 'LOGGED' });
  });

  it('invokes admin productivity, CSV export, users, unlock, and assign endpoints', () => {
    service.getProductivityReport().subscribe((r) => expect(r.staffSummaries.length).toBe(0));
    const prodReq = httpMock.expectOne('/api/v1/admin/reports/productivity');
    expect(prodReq.request.method).toBe('GET');
    prodReq.flush({ staffSummaries: [], recentSessions: [] });

    service.exportAuditCsv().subscribe((blob) => expect(blob.size).toBe(3));
    const csvReq = httpMock.expectOne('/api/v1/admin/audit-logs/export');
    expect(csvReq.request.method).toBe('GET');
    expect(csvReq.request.responseType).toBe('blob');
    csvReq.flush(new Blob(['csv']));

    service.getAdminUsers().subscribe((users) => expect(users.length).toBe(1));
    const usersReq = httpMock.expectOne('/api/v1/admin/users');
    expect(usersReq.request.method).toBe('GET');
    usersReq.flush([{ id: 'u-1', email: 'customer@capi.test' }]);

    service.unlockUser('u-1').subscribe((u) => expect(u.isLocked).toBeFalse());
    const unlockReq = httpMock.expectOne('/api/v1/admin/users/u-1/unlock');
    expect(unlockReq.request.method).toBe('POST');
    unlockReq.flush({ id: 'u-1', isLocked: false });

    service.adminAssignDispute('disp-1', 'staff-1', 'INTERNAL_CAPI', 'IN_REVIEW').subscribe((d) =>
      expect(d.status).toBe('IN_REVIEW')
    );
    const assignReq = httpMock.expectOne('/api/v1/admin/disputes/disp-1/assign');
    expect(assignReq.request.method).toBe('POST');
    expect(assignReq.request.body).toEqual({
      assigneeUserId: 'staff-1',
      assignedDomain: 'INTERNAL_CAPI',
      status: 'IN_REVIEW'
    });
    assignReq.flush({ id: 'disp-1', status: 'IN_REVIEW' });
  });

  it('invokes caller lookup, not-verified, verification, SMS webhook, and voice call endpoints', () => {
    service.lookupCustomerBySaId('8501015800082').subscribe((profile) => {
      expect(profile.fullName).toBe('Thabo Mokoena');
    });
    const lookupReq = httpMock.expectOne((r) => r.url === '/api/v1/omnichannel/lookup-customer');
    expect(lookupReq.request.method).toBe('GET');
    expect(lookupReq.request.params.get('saIdNumber')).toBe('8501015800082');
    lookupReq.flush({ fullName: 'Thabo Mokoena', saIdNumber: '8501015800082' });

    service.markCallerNotVerified('8501015800082', 'Failed check').subscribe((res) => {
      expect(res.verified).toBeFalse();
    });
    const notVerifiedReq = httpMock.expectOne('/api/v1/omnichannel/not-verified');
    expect(notVerifiedReq.request.method).toBe('POST');
    expect(notVerifiedReq.request.body).toEqual({ saIdNumber: '8501015800082', reason: 'Failed check' });
    notVerifiedReq.flush({ verified: false, message: 'Caller marked as Not Verified.' });

    service
      .verifyCustomerCaller({
        saIdNumber: '8501015800082',
        phoneNumber: '+27825550192',
        hasCreditCardFacility: false
      })
      .subscribe((res) => expect(res.verified).toBeTrue());
    const verifyReq = httpMock.expectOne('/api/v1/omnichannel/verify-customer');
    expect(verifyReq.request.method).toBe('POST');
    verifyReq.flush({ verified: true, questionsAnswered: 2, transactions: [] });

    service
      .simulateSmsWebhook({
        channel: 'SMS',
        fromPhoneNumber: '+27825550192',
        messageBody: 'DISPUTE TXN-2026-9005 ATM cash not dispensed'
      })
      .subscribe((res) => expect(res.channel).toBe('SMS'));
    const smsReq = httpMock.expectOne('/api/v1/omnichannel/sms-webhook');
    expect(smsReq.request.method).toBe('POST');
    smsReq.flush({ channel: 'SMS' });

    service
      .simulateVoiceCall({
        callerPhoneNumber: '+27825550192',
        transactionReference: 'TXN-2026-9001',
        scenario: 'FILLING_STATION_DOUBLE_TAP',
        category: 'FRAUDULENT_CARD_USE',
        callerStatement: 'Double tap at filling station',
        cardFreezeRequested: true
      })
      .subscribe((res) => expect(res.channel).toBe('PHONE_CALL'));
    const voiceReq = httpMock.expectOne('/api/v1/omnichannel/voice-call');
    expect(voiceReq.request.method).toBe('POST');
    voiceReq.flush({ channel: 'PHONE_CALL' });
  });
});
