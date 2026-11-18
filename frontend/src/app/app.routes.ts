import { Routes } from '@angular/router';
import { authGuard, roleGuard } from './core/guards/auth.guard';
import { LoginComponent } from './features/auth/login.component';
import { FraudReportComponent } from './features/auth/fraud-report.component';
import { ResetPasswordVerifyComponent } from './features/auth/reset-password-verify.component';
import { CustomerDashboardComponent } from './features/customer/customer-dashboard.component';
import { CustomerDisputesComponent } from './features/customer/customer-disputes.component';
import { StaffDashboardComponent } from './features/staff/staff-dashboard.component';
import { AffiliateDashboardComponent } from './features/affiliate/affiliate-dashboard.component';
import { AdminDashboardComponent } from './features/admin/admin-dashboard.component';
import { DisputeDetailComponent } from './features/disputes/dispute-detail.component';
import { OmnichannelSimulatorComponent } from './features/omnichannel/omnichannel-simulator.component';

export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'login' },
  { path: 'login', component: LoginComponent },
  { path: 'fraud-report', component: FraudReportComponent },
  { path: 'reset-password-verify', component: ResetPasswordVerifyComponent },
  {
    path: 'customer',
    component: CustomerDashboardComponent,
    canActivate: [roleGuard(['CUSTOMER'])]
  },
  {
    path: 'customer/disputes',
    component: CustomerDisputesComponent,
    canActivate: [roleGuard(['CUSTOMER'])]
  },
  {
    path: 'staff',
    component: StaffDashboardComponent,
    canActivate: [roleGuard(['STAFF', 'ADMIN'])]
  },
  {
    path: 'affiliate',
    component: AffiliateDashboardComponent,
    canActivate: [roleGuard(['AFFILIATE'])]
  },
  {
    path: 'admin',
    component: AdminDashboardComponent,
    canActivate: [roleGuard(['ADMIN'])]
  },
  {
    path: 'disputes/:id',
    component: DisputeDetailComponent,
    canActivate: [authGuard]
  },
  {
    path: 'simulator',
    component: OmnichannelSimulatorComponent,
    canActivate: [roleGuard(['STAFF', 'ADMIN'])]
  },
  { path: '**', redirectTo: 'login' }
];

