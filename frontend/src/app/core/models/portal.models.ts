export type UserRole = 'CUSTOMER' | 'STAFF' | 'AFFILIATE' | 'ADMIN';
export type UserDomain = 'CUSTOMER' | 'INTERNAL_CAPITEC' | 'PARTNER_BANKSERV' | 'PARTNER_MERCHANT';
export type DisputeStatus =
  | 'LOGGED'
  | 'IN_REVIEW'
  | 'PENDING_CUSTOMER_ACTION'
  | 'PENDING_EXTERNAL_REVIEW'
  | 'ESCALATED'
  | 'RESOLVED'
  | 'REFUNDED'
  | 'REJECTED';

export type DisputeCategory =
  | 'UNAUTHORIZED_TRANSACTION'
  | 'FRAUDULENT_CARD_USE'
  | 'DUPLICATE_CHARGE'
  | 'GOODS_OR_SERVICES_NOT_RECEIVED'
  | 'PAYSHAP_WRONG_RECIPIENT'
  | 'ATM_DISPENSE_ERROR'
  | 'OTHER';

export interface UserProfile {
  id: string;
  email: string;
  fullName: string;
  role: UserRole;
  userDomain: UserDomain;
  partnerInstitution: string | null;
  isLocked: boolean;
  lockedReason: string | null;
}

export interface AuthResponse {
  accessToken: string;
  tokenType: string;
  expiresInSeconds: number;
  user: UserProfile;
}

export interface FraudReportResponse {
  locked: boolean;
  email: string;
  message: string;
}

export interface BankTransaction {
  id: string;
  transactionReference: string;
  merchantName: string;
  paymentRail: string;
  amount: number;
  currency: string;
  cardLastFour: string;
  location: string;
  transactedAt: string;
}

export interface CustomerSummary {
  id: string;
  fullName: string;
  email: string;
  saIdNumber: string;
  phoneNumber: string;
  accountNumber: string;
  piiMasked: boolean;
}

export interface DisputeNote {
  id: string;
  disputeId: string;
  authorId: string;
  authorName: string;
  authorRole: UserRole;
  message: string;
  internalOnly: boolean;
  requiresCustomerReply: boolean;
  createdAt: string;
}

export interface DisputeAttachment {
  id: string;
  disputeId: string;
  uploadedBy: string;
  fileName: string;
  fileSizeBytes: number;
  mimeType: string;
  checksumSha256: string;
  createdAt: string;
}

export interface LinkedDisputeLineage {
  id: string;
  disputeReference: string;
  status: DisputeStatus;
  category: DisputeCategory;
  amount: number;
  reason: string;
  resolutionSummary: string | null;
  createdAt: string;
  resolvedAt: string | null;
  previousNotes: DisputeNote[];
}

export interface DisputeSummary {
  id: string;
  disputeReference: string;
  transactionId: string | null;
  merchantName: string;
  paymentRail: string;
  status: DisputeStatus;
  terminal: boolean;
  category: DisputeCategory;
  amount: number;
  reason: string;
  resolutionSummary: string | null;
  assignedDomain: UserDomain;
  assignedUserId: string | null;
  assignedUserName: string | null;
  originalDisputeId: string | null;
  originalDisputeReference: string | null;
  customer: CustomerSummary;
  attachmentCount: number;
  createdAt: string;
  resolvedAt: string | null;
}

export interface DisputeDetail extends DisputeSummary {
  originalDisputeLineage: LinkedDisputeLineage | null;
  childLinkedReferences: string[];
  notes: DisputeNote[];
  attachments: DisputeAttachment[];
}

export interface AuditLogEntry {
  id: number;
  actorId: string | null;
  actorEmail: string | null;
  severity: string;
  eventType: string;
  entityName: string;
  entityId: string;
  ipAddress: string;
  metadata: string;
  timestamp: string;
}

export interface StaffProductivityRow {
  staffId: string;
  staffEmail: string;
  staffFullName: string;
  completedSessions: number;
  activeSessions: number;
  totalDurationSeconds: number;
  averageDurationSeconds: number;
  distinctDisputesHandled: number;
}

export interface WorkSessionDetail {
  sessionId: number;
  disputeId: string;
  disputeReference: string;
  staffId: string;
  staffEmail: string;
  startedAt: string;
  completedAt: string | null;
  totalDurationSeconds: number | null;
}

export interface ProductivityReport {
  staffSummaries: StaffProductivityRow[];
  recentSessions: WorkSessionDetail[];
}

