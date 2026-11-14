import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideRouter, Router } from '@angular/router';
import { AuthService } from './auth.service';
import { AuthResponse, UserProfile } from '../models/portal.models';

describe('AuthService', () => {
  let service: AuthService;
  let httpMock: HttpTestingController;
  let router: Router;

  const mockCustomer: UserProfile = {
    id: 'cust-1',
    email: 'customer@capi.test',
    fullName: 'Test Customer',
    role: 'CUSTOMER',
    userDomain: 'CUSTOMER',
    partnerInstitution: null,
    isLocked: false,
    lockedReason: null
  };

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([])
      ]
    });
    service = TestBed.inject(AuthService);
    httpMock = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
  });

  afterEach(() => {
    httpMock.verify();
    localStorage.clear();
  });

  it('stores token and user profile on login', () => {
    const authRes: AuthResponse = {
      accessToken: 'jwt-token-123',
      tokenType: 'Bearer',
      expiresInSeconds: 3600,
      user: mockCustomer
    };

    service.login('customer@capi.test', 'Secret!123').subscribe((res) => {
      expect(res.accessToken).toBe('jwt-token-123');
    });

    const req = httpMock.expectOne('/api/v1/auth/login');
    expect(req.request.method).toBe('POST');
    req.flush(authRes);

    expect(service.getToken()).toBe('jwt-token-123');
    expect(service.currentUser()).toEqual(mockCustomer);
    expect(service.isAuthenticated()).toBeTrue();
  });

  it('clears stored session and navigates to /login on logout', () => {
    localStorage.setItem('capi_portal_token', 'jwt-token-123');
    localStorage.setItem('capi_portal_user', JSON.stringify(mockCustomer));
    const navSpy = spyOn(router, 'navigate');

    service.logout();

    expect(service.getToken()).toBeNull();
    expect(service.currentUser()).toBeNull();
    expect(service.isAuthenticated()).toBeFalse();
    expect(navSpy).toHaveBeenCalledWith(['/login']);
  });

  it('clears local session before submitting reportFraud', () => {
    localStorage.setItem('capi_portal_token', 'jwt-token-123');
    localStorage.setItem('capi_portal_user', JSON.stringify(mockCustomer));

    service.reportFraud('fraud+token/1').subscribe((res) => {
      expect(res.locked).toBeTrue();
    });

    expect(service.getToken()).toBeNull();
    expect(service.currentUser()).toBeNull();

    const req = httpMock.expectOne('/api/v1/auth/report-fraud?token=fraud%2Btoken%2F1');
    expect(req.request.method).toBe('POST');
    req.flush({
      locked: true,
      email: 'customer@capi.test',
      message: 'Account locked'
    });
  });

  it('maps user roles to expected home routes', () => {
    expect(service.getHomeRouteForRole('CUSTOMER')).toBe('/customer');
    expect(service.getHomeRouteForRole('STAFF')).toBe('/staff');
    expect(service.getHomeRouteForRole('AFFILIATE')).toBe('/affiliate');
    expect(service.getHomeRouteForRole('ADMIN')).toBe('/admin');
    expect(service.getHomeRouteForRole(undefined)).toBe('/login');
  });
});

